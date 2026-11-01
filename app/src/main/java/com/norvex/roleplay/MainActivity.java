package com.norvex.roleplay;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.inputmethod.InputMethodManager;
import android.webkit.DownloadListener;
import android.widget.FrameLayout;

import org.json.JSONObject;

/**
 * Pembungkus native untuk web Roleplay AI (pengganti MIT App Inventor).
 * Kontrak protokol: sama persis dengan src/mit/protocol.md —
 * web yang sama jalan tanpa perubahan karena nama jembatan JS-nya identik:
 * window.AppInventor.setWebViewString / getWebViewString.
 *
 * - Timer: setelah halaman selesai, dorong {"is_mit":true} 5x @1500ms.
 * - Masuk (JS -> native): on_download -> DownloadManager; is_preview -> flag;
 *   keyboard=false -> sembunyikan keyboard.
 * - Back: pratinjau? kirim {"is_preview":false} : (bisa kembali? kembali : keluar).
 * - Upload file (<input type=file>): pemilih file sistem.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://perchance.org/norvex-r?is-mit";
    private static final int GREET_TIMES = 5;
    private static final long GREET_EVERY_MS = 1500L;
    private static final int FILE_REQ = 1001;

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Bridge bridge = new Bridge();
    private ValueCallback<Uri[]> fileCb;
    private int greetLeft = 0;

    /** Jembatan JS — nama & perilaku sama dengan CustomWebView MIT. */
    public class Bridge {
        private volatile String webViewString = "";

        @JavascriptInterface
        public void setWebViewString(String s) {
            if (s == null) s = "";
            webViewString = s;
            final String copy = s;
            main.post(() -> onWebString(copy));
        }

        @JavascriptInterface
        public String getWebViewString() {
            return webViewString;
        }

        /** Dorongan sapaan (native -> web). BUKAN pesan masuk: jangan diproses. */
        void greet() {
            webViewString = "{\"is_mit\":true}";
        }
    }

    /** Pesan MASUK dari web (hanya yang lewat setWebViewString JS). */
    private void onWebString(String s) {
        if (s == null || s.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(s);
            if (o.has("on_download")) {
                JSONObject d = o.getJSONObject("on_download");
                startDownload(
                        d.optString("url", ""),
                        d.optString("filename", "download"),
                        d.optString("mimetype", ""));
                return;
            }
            if (o.has("is_preview")) {
                previewActive = o.optBoolean("is_preview", false);
                return;
            }
            if (o.has("keyboard")) {
                keyboardActive = o.optBoolean("keyboard", false);
                if (!keyboardActive) hideKeyboard();
            }
        } catch (Exception ignored) {
            // Bukan JSON / bukan kunci dikenal: abaikan diam-diam.
        }
    }

    private boolean previewActive = false;
    private boolean keyboardActive = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(web, true);
        } catch (Exception ignored) {}

        web.addJavascriptInterface(bridge, "AppInventor");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                startGreeting();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String u = (req != null && req.getUrl() != null) ? req.getUrl().toString() : "";
                // http(s) tetap di dalam; skema lain (tel:, intent:, dsb) lempar ke sistem.
                if (u.startsWith("http://") || u.startsWith("https://")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                } catch (ActivityNotFoundException ignored) {}
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileCb != null) { try { fileCb.onReceiveValue(null); } catch (Exception ignored) {} }
                fileCb = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, "Pilih file"), FILE_REQ);
                } catch (ActivityNotFoundException e) {
                    fileCb = null;
                    try { cb.onReceiveValue(null); } catch (Exception ignored) {}
                }
                return true;
            }
        });

        // Jaring pengaman: unduhan yang lolos dari intersepsi web.
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mime, long len) {
                if (url == null || !url.startsWith("http")) return; // blob: tak tertangani
                String name = "download";
                try {
                    String d = android.webkit.URLUtil.guessFileName(url, contentDisposition, mime);
                    if (d != null && !d.isEmpty()) name = d;
                } catch (Exception ignored) {}
                startDownload(url, name, mime == null ? "" : mime);
            }
        });

        if (b == null) {
            web.loadUrl(START_URL);
        } else {
            web.restoreState(b);
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                try { requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7); } catch (Exception ignored) {}
            }
        }
    }

    /** Dorong sapaan beberapa kali (tahan HP lambat), lalu berhenti total. */
    private void startGreeting() {
        greetLeft = GREET_TIMES;
        main.removeCallbacks(greetRun);
        main.post(greetRun);
    }

    private final Runnable greetRun = new Runnable() {
        @Override
        public void run() {
            if (greetLeft <= 0) return;
            greetLeft--;
            try { bridge.greet(); } catch (Exception ignored) {}
            if (greetLeft > 0) main.postDelayed(this, GREET_EVERY_MS);
        }
    };

    private void startDownload(String url, String filename, String mime) {
        if (url == null || !url.startsWith("http")) return;
        if (filename == null || filename.isEmpty()) filename = "download";
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle(filename);
            r.setDescription("Roleplay AI");
            r.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            try { r.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "perchance_app/Roleplay/" + filename); } catch (Exception ignored) {}
            if (mime != null && !mime.isEmpty()) { try { r.setMimeType(mime); } catch (Exception ignored) {} }
            try { r.allowScanningByMediaScanner(); } catch (Exception ignored) {}
            dm.enqueue(r);
        } catch (Exception ignored) {}
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && web != null) {
                imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onBackPressed() {
        // 1. Pratinjau web: minta tutup (web yang menutup layarnya sendiri).
        if (previewActive) {
            try { bridge.webViewString = "{\"is_preview\":false}"; } catch (Exception ignored) {}
            return;
        }
        // 2. WebView masih bisa kembali: kembali.
        try {
            if (web != null && web.canGoBack()) { web.goBack(); return; }
        } catch (Exception ignored) {}
        // 3. Mentok: keluar aplikasi (perilaku Back Android normal).
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == FILE_REQ) {
            ValueCallback<Uri[]> cb = fileCb;
            fileCb = null;
            if (cb == null) return;
            try {
                if (res == RESULT_OK && data != null && data.getData() != null) {
                    cb.onReceiveValue(new Uri[]{data.getData()});
                } else {
                    cb.onReceiveValue(null);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        try { if (web != null) web.saveState(out); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        try {
            main.removeCallbacks(greetRun);
            if (web != null) {
                ((ViewGroup) web.getParent()).removeView(web);
                web.destroy();
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}