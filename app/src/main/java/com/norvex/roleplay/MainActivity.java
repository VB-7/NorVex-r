package com.norvex.roleplay;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.inputmethod.InputMethodManager;
import android.webkit.DownloadListener;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * NorVex-R — aplikasi native (bukan MIT).
 * Kontrak protokol: src/mit/protocol.md (web menjembatani via window.AppInventor).
 *
 * Prinsip: web kirim mentah (termasuk blob per potong), aplikasi yang tulis file.
 * Tanpa upload ke mana pun kecuali user tekan salin-link/backup-link (itu urusan web).
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://perchance.org/norvex-r?is-mit";

    // Halaman generator sendiri (yang boleh tampil di webview). Varian query
    // (?is-mit dsb) tetap dihitung halaman sendiri.
    private boolean isAppUrl(String u) {
        try {
            if (u == null) return false;
            android.net.Uri p = android.net.Uri.parse(u);
            if (!"perchance.org".equalsIgnoreCase(p.getHost())) return false;
            String path = p.getPath();
            return "/norvex-r".equals(path) || (path != null && path.startsWith("/norvex-r/"));
        } catch (Exception e) { return false; }
    }
    private static final String UPDATE_REPO = "VB-7/NorVex-r";
    private static final int GREET_TIMES = 5;
    private static final long GREET_EVERY_MS = 1500L;
    private static final int FILE_REQ = 1001;
    private static final int REQ_STORAGE = 8; // izin tulis (HP Android 9/10)
    private static final int CHUNK_RAW = 256 * 1024; // byte mentah per potong (disepakati dgn web)

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Bridge bridge = new Bridge();
    private ValueCallback<Uri[]> fileCb;
    private int greetLeft = 0;

    private final ArrayList<Dialog> dialogStack = new ArrayList<Dialog>();
    private final Map<String, PartState> parts = new HashMap<String, PartState>();
    private boolean notifAsked = false;
    private boolean pausedCheckpointSent = false;
    private long lastErrorDialogAt = 0;
    private long updateDownloadId = -1;
    // Splash: WebView disembunyikan sampai web kirim {app_ready:true}.
    private FrameLayout webRoot = null; // wadah WebView (buat ulang saat renderer mati)
    private boolean readyShown = false;
    private boolean pageDone = false;
    private static final String UPD_PREF_TAG = "upd_tag"; // tag APK update yg sudah terunduh

    // ---------------- Jembatan JS (nama sama dengan MIT: window.AppInventor) ----------------

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

        void greet() {
            webViewString = "{\"is_mit\":true}";
        }

        void greetNative() {
            webViewString = "{\"is_native\":true}";
        }
    }

    private static class PartState {
        String id, target, name, mime;
        boolean silent; // b51: backup (.backup) ditulis DIAM (tanpa notif/toast)
        long size;
        File partFile;
        RandomAccessFile raf;
        long received;
        int lastPct;
    }

    // ---------------- Pesan MASUK dari web ----------------

    private void onWebString(String s) {
        if (s == null || s.isEmpty()) return;
        try {
            JSONObject o;
            try { o = new JSONObject(s); } catch (Exception e) { return; } // bukan JSON: abaikan
            try {
                java.util.Iterator<String> ks = o.keys();
                StringBuilder kb = new StringBuilder();
                while (ks.hasNext()) { if (kb.length() > 0) kb.append(","); kb.append(ks.next()); }
                // file_chunk bisa ratusan pesan per file: jangan banjiri trace.
                if (!o.has("file_chunk")) trace("pesan: " + kb.toString());
            } catch (Exception ignored) {}
            if (o.has("app_ready")) { onAppReady(); return; } // web sudah tampil
            if (o.has("file_start")) { onFileStart(o.optJSONObject("file_start")); return; }
            if (o.has("file_chunk")) { onFileChunk(o.optJSONObject("file_chunk")); return; }
            if (o.has("file_end")) { onFileEnd(o.optJSONObject("file_end")); return; }
            if (o.has("preview")) { onPreview(o.optJSONObject("preview")); return; }
            if (o.has("task")) { onTask(o.optJSONObject("task")); return; }
            if (o.has("log")) { onWebLog(o.optJSONObject("log")); return; }
            if (o.has("on_download")) {
                JSONObject d = o.optJSONObject("on_download");
                if (d != null) startDownloadHttp(d.optString("url", ""), d.optString("filename", "download"), d.optString("mimetype", ""));
                return;
            }
            if (o.has("is_preview")) return; // kompat: web menyimpan status sendiri (mode MIT lama)
            if (o.has("keyboard")) {
                if (!o.optBoolean("keyboard", false)) hideKeyboard();
            }
        } catch (Throwable t) {
            logError("bridge", String.valueOf(t));
        }
    }

    // ---------------- File mentah per potong (blob/data dari web) ----------------

    private void onFileStart(JSONObject f) {
        try {
            if (f == null) return;
            String id = f.optString("id", "");
            if (id.isEmpty()) return;
            // id dobel (kirim ulang / web retry): tutup & buang sisa lama dulu
            // agar file-descriptor tak bocor dan isi tak tercampur.
            try {
                PartState old = parts.remove(id);
                if (old != null) {
                    try { if (old.raf != null) old.raf.close(); } catch (Exception ignored) {}
                    try { if (old.partFile != null) old.partFile.delete(); } catch (Exception ignored) {}
                    taskEnd("dl-" + id);
                }
            } catch (Exception ignored) {}
            PartState st = new PartState();
            st.id = id;
            st.target = sanitizeTarget(f.optString("target", "download"));
            st.name = sanitizeName(f.optString("name", "download"));
            st.mime = f.optString("mime", "");
            st.size = f.optLong("size", -1);
            try {
                File dir = new File(getCacheDir(), "parts");
                dir.mkdirs();
                st.partFile = new File(dir, "p-" + sanitizeName(id));
                st.raf = new RandomAccessFile(st.partFile, "rw");
                st.raf.setLength(0);
            } catch (Exception e) {
                logError("file_start", e.toString());
                return;
            }
            parts.put(id, st);
            // b51: backup (.backup) hukumnya DIAM — tanpa notifikasi, tanpa
            // toast; user tak mau diganggu tiap aplikasi ke background.
            st.silent = st.target != null && st.target.startsWith(".backup");
            if (!st.silent) taskStart("dl-" + id, "Menerima " + st.name);
            else trace("backup diam: " + st.name);
        } catch (Throwable t) { logError("file_start", String.valueOf(t)); }
    }

    private void onFileChunk(JSONObject f) {
        try {
            if (f == null) return;
            PartState st = parts.get(f.optString("id", ""));
            if (st == null || st.raf == null) return;
            String data = f.optString("data", "");
            if (data.isEmpty()) return;
            byte[] raw;
            try { raw = Base64.decode(data, Base64.DEFAULT); } catch (Exception e) { return; }
            long n = f.optLong("n", -1);
            try {
                if (n >= 0) st.raf.seek(n * CHUNK_RAW);
                st.raf.write(raw);
                st.received += raw.length;
            } catch (Exception e) { logError("file_chunk", e.toString()); }
            // Progres penerimaan (di-throttle per 2%) -> notifikasi ada %.
            try {
                if (st.size > 0) {
                    int pct = (int) (st.received * 100 / st.size);
                    if (pct != st.lastPct && (pct - st.lastPct >= 2 || pct >= 100)) {
                        st.lastPct = pct;
                        if (!st.silent) taskProgress("dl-" + st.id, st.received, st.size, "Menerima " + st.name);
                    }
                }
            } catch (Exception ignored) {}
        } catch (Throwable t) { logError("file_chunk", String.valueOf(t)); }
    }

    private void onFileEnd(JSONObject f) {
        try {
            if (f == null) return;
            String id = f.optString("id", "");
            final PartState st = parts.remove(id);
            if (st == null || !st.silent) taskEnd("dl-" + id);
            if (st == null) return;
            try { if (st.raf != null) st.raf.close(); } catch (Exception ignored) {}
            final File dest = resolveTarget(st.target, st.name);
            if (dest == null || st.partFile == null || !st.partFile.exists()) {
                toast("Gagal menyimpan " + st.name);
                logError("file_end", "tujuan tak bisa dipakai: " + st.target + "/" + st.name);
                return;
            }
            // Salin di latar (file bisa puluhan MB): JANGAN di thread UI (ANR).
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        copyFile(st.partFile, dest);
                        try { st.partFile.delete(); } catch (Exception ignored) {}
                        final String nm = dest.getName();
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (!st.silent) toast("Tersimpan: " + nm);
                                trace("file tersimpan: " + nm);
                            }
                        });
                    } catch (final Exception e) {
                        main.post(new Runnable() {
                            @Override public void run() { toast("Gagal menyimpan " + st.name); }
                        });
                        logError("file_end", e.toString());
                    }
                }
            }).start();
        } catch (Throwable t) { logError("file_end", String.valueOf(t)); }
    }

    private String sanitizeName(String s) {
        if (s == null) return "download";
        s = s.replaceAll("[/\\\\]+", "_").replaceAll("\\.+", ".").trim();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) return "download";
        if (s.length() > 120) s = s.substring(0, 120);
        return s;
    }

    // target: "download" | "download/image/<kartu>" | ".backup" — tiap segmen aman.
    private String sanitizeTarget(String t) {
        if (t == null || t.isEmpty()) return "download";
        String[] segs = t.split("/");
        StringBuilder sb = new StringBuilder();
        for (String g : segs) {
            if (g == null) continue;
            g = g.trim();
            if (g.isEmpty() || g.equals(".") || g.equals("..")) continue;
            if (!g.matches("[\\w][\\w.\\- ]*")) continue;
            if (sb.length() > 0) sb.append("/");
            sb.append(g);
            if (sb.length() > 120) break;
        }
        String out = sb.toString();
        if (out.isEmpty()) return "download";
        if (out.equals(".backup") || out.startsWith(".backup/")) return out;
        if (out.equals("download") || out.startsWith("download/")) return out;
        return "download/" + out;
    }

    // Basis folder: /storage/emulated/0/perchance_app/Roleplay (WAJIB user).
    // Gagal (scope storage / tanpa izin) -> folder khusus aplikasi (selalu bisa).
    private File baseDirExternal() {
        try {
            File base = new File(Environment.getExternalStorageDirectory(), "perchance_app/Roleplay");
            base.mkdirs();
            if (base.isDirectory() && base.canWrite()) return base;
        } catch (Exception ignored) {}
        return null;
    }

    private File resolveTarget(String target, String name) {
        try {
            File base = baseDirExternal();
            if (base != null) {
                File d = new File(base, target);
                d.mkdirs();
                if (target.startsWith("download")) ensureNoMedia(new File(base, "download"));
                if (d.isDirectory()) return new File(d, name);
            }
            File fb = new File(getExternalFilesDir(null), "Roleplay/" + target);
            fb.mkdirs();
            if (fb.isDirectory()) return new File(fb, name);
        } catch (Exception e) { logError("resolve", e.toString()); }
        return null;
    }

    // .nomedia: sembunyikan dari galeri/pemutar media (privasi!). Rekursif ke subfolder.
    private void ensureNoMedia(File downloadDir) {
        try {
            if (downloadDir == null) return;
            File nm = new File(downloadDir, ".nomedia");
            if (!nm.exists()) { try { nm.createNewFile(); } catch (Exception ignored) {} }
        } catch (Exception ignored) {}
    }

    private void copyFile(File src, File dst) throws Exception {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.getFD().sync();
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    // Unduhan http langsung (DownloadManager, tanpa izin apa pun).
    // Berupa ANTREAN serial di latar belakang: user tak perlu tahu/tunggu —
    // dikerjakan satu per satu, tiap selesai ada notif + toast pendek.
    private static class HttpJob { String url, name, mime; }
    private final java.util.ArrayDeque<HttpJob> httpQueue = new java.util.ArrayDeque<HttpJob>();
    private long activeHttpDmId = -1;
    private String activeHttpName = "";
    private int httpFails = 0; // pengaman: gagal beruntun -> berhenti, jangan macet antrean
    private volatile boolean destroyed = false; // true setelah onDestroy: hentikan thread latar
    private final java.util.Map<Long, String> dmJobs = new java.util.HashMap<Long, String>();
    private boolean dmPolling = false;

    private void startDownloadHttp(String url, String filename, String mime) {
        if (url == null || !url.startsWith("http")) return;
        final String safe = sanitizeName(filename == null || filename.isEmpty() ? "download" : filename);
        HttpJob j = new HttpJob();
        j.url = url; j.name = safe; j.mime = (mime == null ? "" : mime);
        httpQueue.add(j);
        trace("antre unduhan: " + safe);
        pumpHttpQueue();
    }

    private void pumpHttpQueue() {
        try {
            if (activeHttpDmId != -1) return; // masih ada yang jalan
            final HttpJob j = httpQueue.poll();
            if (j == null) return;
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) { httpFail("layanan tidak ada"); return; }
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(j.url));
            r.setTitle(j.name);
            r.setDescription("NorVex-R");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            // WAJIB: /storage/emulated/0/perchance_app/Roleplay/download/
            r.setDestinationInExternalPublicDir("perchance_app", "Roleplay/download/" + j.name);
            if (j.mime != null && !j.mime.isEmpty()) { try { r.setMimeType(j.mime); } catch (Exception ignored) {} }
            long id = dm.enqueue(r);
            activeHttpDmId = id;
            activeHttpName = j.name;
            synchronized (dmJobs) { dmJobs.put(id, "dl-http"); }
            taskStart("dl-http", "Mengunduh " + j.name);
            trace("unduh http mulai: " + j.name);
            pollDmProgress();
        } catch (Exception e) { httpFail(e.toString()); }
    }

    // Satu unduhan http gagal: catat, lanjut ke antrean berikut. Gagal 3x
    // beruntun (mis. layanan mati total) -> buang sisa antrean + beri tahu
    // user, JANGAN rekursi tanpa henti (itu stack-overflow = force close).
    private void httpFail(String why) {
        try {
            logError("download-http", why);
            taskEnd("dl-http");
            activeHttpDmId = -1;
            activeHttpName = "";
            httpFails++;
            if (httpFails > 3) {
                httpFails = 0;
                httpQueue.clear();
                toast("Unduhan gagal, coba lagi nanti");
            } else {
                pumpHttpQueue();
            }
        } catch (Throwable t) { logError("download-http", String.valueOf(t)); }
    }

    // Intip progres DownloadManager tiap detik -> notifikasi ada % + ukuran.
    private void pollDmProgress() {
        try {
            if (dmPolling) return;
            dmPolling = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                        while (true) {
                            if (destroyed) break; // activity mati: berhenti, jangan bocor
                            java.util.Map<Long, String> jobs;
                            synchronized (dmJobs) { jobs = new java.util.HashMap<Long, String>(dmJobs); }
                            if (jobs.isEmpty() || dm == null) break;
                            for (java.util.Map.Entry<Long, String> en : jobs.entrySet()) {
                                try {
                                    DownloadManager.Query q = new DownloadManager.Query().setFilterById(en.getKey());
                                    android.database.Cursor c = null;
                                    try {
                                        c = dm.query(q);
                                        if (c != null && c.moveToFirst()) {
                                            long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                                            long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                                            if (total > 0) taskProgress(en.getValue(), done, total, null);
                                        }
                                    } finally { try { if (c != null) c.close(); } catch (Exception ignored) {} }
                                } catch (Exception ignored) {}
                            }
                            try { Thread.sleep(1000); } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                    dmPolling = false;
                }
            }).start();
        } catch (Exception ignored) {}
    }

    // ---------------- Pratinjau: jendela gambar sendiri (WebView polos) ----------------

    private void onPreview(JSONObject p) {
        try {
            if (p == null) return;
            String url = p.optString("url", "");
            if (url.isEmpty()) return;
            showPreview(url);
        } catch (Throwable t) { logError("preview", String.valueOf(t)); }
    }

    private void showPreview(final String url) {
        try {
            closeDialogType(PreviewDialog.class);
            final PreviewDialog d = new PreviewDialog(this, url);
            pushDialog(d);
            d.show();
        } catch (Throwable t) { logError("preview", String.valueOf(t)); }
    }

    private static class PreviewDialog extends Dialog {
        private final String url;
        private float downY = -1;

        PreviewDialog(Context ctx, String u) {
            super(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            url = u;
        }

        @Override
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(0xFF000000);
            setContentView(root);
            WebView w = new WebView(getContext());
            root.addView(w, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            WebSettings s = w.getSettings();
            s.setJavaScriptEnabled(false);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            s.setLoadWithOverviewMode(true);
            s.setUseWideViewPort(true);
            // Tombol tutup SELALU ada: usap-bawah sering tak sampai (WebView
            // makan sentuhan di atas gambar), tombol ini jalan 100%.
            try {
                android.widget.ImageButton x = new android.widget.ImageButton(getContext());
                x.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
                x.setBackgroundColor(0x00000000);
                x.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { try { dismiss(); } catch (Exception ignored) {} }
                });
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
                int m = (int) (16 * getContext().getResources().getDisplayMetrics().density);
                lp.setMargins(m, m, m, m);
                root.addView(x, lp);
            } catch (Exception ignored) {}
            if (url.startsWith("data:")) {
                String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=5'/>" +
                        "<style>html,body{margin:0;background:#000;height:100%}img{width:100%;height:auto}</style></head>" +
                        "<body><img src='" + url.replace("'", "") + "'/></body></html>";
                w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
            } else {
                w.loadUrl(url);
            }
            // Geser-bawah = tutup (enak, ala MIT).
            root.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    try {
                        if (e.getAction() == MotionEvent.ACTION_DOWN) downY = e.getY();
                        else if (e.getAction() == MotionEvent.ACTION_MOVE && downY >= 0) {
                            float dy = e.getY() - downY;
                            if (dy > 0) v.setTranslationY(dy);
                        } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                            float dy = e.getY() - downY;
                            downY = -1;
                            if (dy > 240) { dismiss(); return true; }
                            v.setTranslationY(0);
                        }
                    } catch (Exception ignored) {}
                    return false;
                }
            });
        }
    }

    // ---------------- Siap (tanpa tirai: web langsung kelihatan) ----------------

    // Sinyal dari web bahwa halaman sudah tampil. Tak ada yang ditutup -
    // cuma catat supaya dialog galat tak menuduh lagi.
    private void onAppReady() {
        try {
            if (readyShown) return;
            readyShown = true;
            trace("web siap");
        } catch (Throwable t) { logError("ready", String.valueOf(t)); }
    }

    // ---------------- Tugas latar (service + notifikasi) ----------------

    private void onTask(JSONObject t) {
        try {
            if (t == null) return;
            String id = t.optString("id", "");
            String phase = t.optString("phase", "");
            if (id.isEmpty()) return;
            if ("done".equals(phase)) taskEnd(id);
            else taskStart(id, t.optString("label", defaultTaskLabel(id)));
        } catch (Throwable th) { logError("task", String.valueOf(th)); }
    }

    private String defaultTaskLabel(String id) {
        if ("ai".equals(id)) return "AI menjawab…";
        if (id.startsWith("dl")) return "Mengunduh…";
        return "Bekerja…";
    }

    private void taskStart(String id, String label) {
        try {
            ensureNotifPerm();
            Intent i = new Intent(this, TaskService.class);
            i.setAction(TaskService.ACT_START);
            i.putExtra(TaskService.EXTRA_ID, id);
            i.putExtra(TaskService.EXTRA_LABEL, label == null ? "" : label);
            startSvc(i);
        } catch (Throwable t) { logError("taskStart", String.valueOf(t)); }
    }

    private void taskEnd(String id) {
        try {
            Intent i = new Intent(this, TaskService.class);
            i.setAction(TaskService.ACT_END);
            i.putExtra(TaskService.EXTRA_ID, id == null ? "" : id);
            startSvc(i);
        } catch (Throwable t) { logError("taskEnd", String.valueOf(t)); }
    }

    // Progres tugas (notifikasi ada % + ukuran). Label boleh null = pakai yg ada.
    // Diam saat aplikasi di latar: service tak boleh dibangunkan dari
    // background di Android baru (melempar tiap detik = spam log + baterai).
    private volatile boolean uiVisible = true;

    private void taskProgress(String id, long done, long total, String label) {
        try {
            if (!uiVisible) return;
            Intent i = new Intent(this, TaskService.class);
            i.setAction(TaskService.ACT_PROGRESS);
            i.putExtra(TaskService.EXTRA_ID, id == null ? "" : id);
            i.putExtra(TaskService.EXTRA_DONE, done);
            i.putExtra(TaskService.EXTRA_TOTAL, total);
            i.putExtra(TaskService.EXTRA_LABEL, label == null ? "" : label);
            startSvc(i);
        } catch (Throwable t) { logError("taskProgress", String.valueOf(t)); }
    }

    private void startSvc(Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            // Android 12+: start dari background DILARANG sistem (bukan bug
            // kita) -> catat ringan saja, jangan spam file error tiap detik.
            String m = String.valueOf(e);
            if (m.contains("Background") || m.contains("NotAllowed") || m.contains("background")) trace("service bg ditolak");
            else logError("service", m);
        }
    }

    // ---------------- Error: log file + dialog (fallback berlapis) ----------------

    private void onWebLog(JSONObject l) {
        try {
            if (l == null) return;
            String level = l.optString("level", "error");
            String msg = l.optString("msg", "");
            if (msg.isEmpty()) return;
            logError("web-" + level, msg);
            if ("fatal".equals(level)) showErrorDialog(msg);
        } catch (Throwable t) { logError("weblog", String.valueOf(t)); }
    }

    private File errorDir() {
        try {
            File base = baseDirExternal();
            File d = (base != null) ? new File(base, "error") : new File(getFilesDir(), "error");
            d.mkdirs();
            if (d.isDirectory()) return d;
        } catch (Exception ignored) {}
        return getCacheDir();
    }

    private void logError(String tag, String msg) {
        trace(tag + ": " + shortMsg(msg));
        try {
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            File f = new File(errorDir(), "log-" + day + ".txt");
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(f, true);
                out.write(("[" + ts + "][" + tag + "] " + msg + "\n").getBytes("UTF-8"));
            } finally {
                try { if (out != null) out.close(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    // Jejak langkah (untuk buru force-close): ringan, selalu ditulis.
    private void trace(String msg) {
        try {
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            File f = new File(errorDir(), "trace-" + day + ".txt");
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(f, true);
                out.write(("[" + ts + "] " + msg + "\n").getBytes("UTF-8"));
            } finally {
                try { if (out != null) out.close(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void showErrorDialog(final String msg) {        try {
            long now = System.currentTimeMillis();
            if (now - lastErrorDialogAt < 5000) { // anti-spam: 1 dialog per 5 dtk
                toast("Galat: " + shortMsg(msg));
                return;
            }
            lastErrorDialogAt = now;
            AlertDialog d = new AlertDialog.Builder(this)
                    .setTitle("Terjadi galat")
                    .setMessage(shortMsg(msg))
                    .setPositiveButton("Tutup", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface di, int w) { try { di.dismiss(); } catch (Exception ignored) {} }
                    })
                    .setCancelable(true)
                    .create();
            pushDialog(d);
            d.show();
        } catch (Throwable t) {
            try { toast("Galat: " + shortMsg(msg)); } catch (Exception ignored) {}
        }
    }

    private String shortMsg(String s) {
        if (s == null) return "-";
        s = s.trim();
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }

    // ---------------- Cek update dalam aplikasi ----------------

    // Cek update SETIAP buka aplikasi, diam-diam di latar belakang: tanpa
    // throttle antar-buka manual, tanpa toast/suara. Satu-satunya yang user
    // lihat adalah dialog "pasang" kalau versi baru benar-benar sudah terunduh.
    // Pengaman internal: rotasi layar (onCreate ulang) tak memicu cek ganda.
    private static long lastUpdateCheckAt = 0;

    private void checkUpdate() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastUpdateCheckAt < 10 * 60 * 1000) return;
            lastUpdateCheckAt = now;
            new Thread(new Runnable() {
                @Override public void run() {
                    HttpURLConnection c = null;
                    InputStream in = null;
                    try {
                        URL u = new URL("https://api.github.com/repos/" + UPDATE_REPO + "/releases/latest");
                        c = (HttpURLConnection) u.openConnection();
                        c.setRequestProperty("User-Agent", "NorVex-R");
                        c.setRequestProperty("Accept", "application/vnd.github+json");
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(15000);
                        if (c.getResponseCode() != 200) return;
                        in = c.getInputStream();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
                        JSONObject rel = new JSONObject(bos.toString("UTF-8"));
                        String tag = rel.optString("tag_name", "");
                        int remote = parseTagCode(tag);
                        if (remote <= 0) return;
                        int mine = myVersionCode();
                        if (remote <= mine) { trace("cek update: sudah terbaru"); return; }
                        String apkUrl = null;
                        try { apkUrl = rel.getJSONArray("assets").getJSONObject(0).getString("browser_download_url"); } catch (Exception ignored) {}
                        if (apkUrl == null) return;
                        final String fUrl = apkUrl, fTag = tag;
                        trace("update tersedia: " + fTag);
                        main.post(new Runnable() {
                            @Override public void run() { onUpdateFound(fUrl, fTag); }
                        });
                    } catch (Exception e) { logError("update-check", e.toString()); }
                    finally {
                        // Tutup SELALU (bocor koneksi = baterai + soket habis).
                        try { if (in != null) in.close(); } catch (Exception ignored) {}
                        try { if (c != null) c.disconnect(); } catch (Exception ignored) {}
                    }
                }
            }).start();
        } catch (Throwable t) { logError("update-check", String.valueOf(t)); }
    }

    private int parseTagCode(String tag) {
        try {
            tag = tag.trim();
            if (tag.startsWith("b") || tag.startsWith("v")) tag = tag.substring(1);
            return Integer.parseInt(tag.replaceAll("[^0-9]", ""));
        } catch (Exception e) { return -1; }
    }

    private int myVersionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) pi.getLongVersionCode();
            return pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    // Update OTOMATIS: ketemu versi baru -> langsung unduh di dalam aplikasi
    // (tanpa buka GitHub, tanpa ditanya). Kalau file tag ini sudah ada -> ajak
    // pasang. Kalau sedang mengunduh -> biarkan jalan.
    private void onUpdateFound(String apkUrl, String tag) {
        try {
            File apk = new File(new File(getExternalFilesDir(null), "update"), "norvex-r.apk");
            SharedPreferences sp = getSharedPreferences("nv", MODE_PRIVATE);
            String have = sp.getString(UPD_PREF_TAG, "");
            if (tag.equals(have) && apk.exists() && apk.length() > 0) {
                showUpdateReadyDialog(apk, tag);
                return;
            }
            if (updateDownloadId != -1) { trace("update masih mengunduh: " + tag); return; }
            // Hapus APK lama dulu: kalau unduhan BARU gagal, file basi bertag
            // lama JANGAN dikira "siap dipasang" oleh penerima selesai.
            try {
                File stale = new File(new File(getExternalFilesDir(null), "update"), "norvex-r.apk");
                if (stale.exists()) stale.delete();
            } catch (Exception ignored) {}
            sp.edit().putString(UPD_PREF_TAG, tag).apply();
            trace("update mengunduh: " + tag);
            startUpdateDownload(apkUrl);
        } catch (Throwable t) { logError("update-found", String.valueOf(t)); }
    }

    private void showUpdateReadyDialog(final File apk, String tag) {
        try {
            AlertDialog d = new AlertDialog.Builder(this)
                    .setTitle("Update " + tag + " siap dipasang")
                    .setMessage("Versi " + tag + " sudah terunduh. Pasang sekarang?")
                    .setPositiveButton("Pasang", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface di, int w) { promptInstall(apk); }
                    })
                    .setNegativeButton("Nanti", null)
                    .setCancelable(true)
                    .create();
            pushDialog(d);
            d.show();
            trace("update siap: " + tag);
        } catch (Throwable t) { logError("update-dlg", String.valueOf(t)); }
    }

    private void startUpdateDownload(String apkUrl) {
        try {
            taskStart("update", "Mengunduh update…");
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) { taskEnd("update"); return; }
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(apkUrl));
            r.setTitle("NorVex-R update");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            File dir = new File(getExternalFilesDir(null), "update");
            dir.mkdirs();
            r.setDestinationInExternalFilesDir(this, null, "update/norvex-r.apk");
            updateDownloadId = dm.enqueue(r);
            synchronized (dmJobs) { dmJobs.put(updateDownloadId, "update"); }
            pollDmProgress();
        } catch (Throwable t) {
            taskEnd("update");
            logError("update-dl", String.valueOf(t));
        }
    }

    private final BroadcastReceiver dlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            try {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                synchronized (dmJobs) { dmJobs.remove(id); }
                if (id == updateDownloadId && id != -1) {
                    updateDownloadId = -1;
                    taskEnd("update");
                    File apk = new File(getExternalFilesDir(null), "update/norvex-r.apk");
                    String tag = "";
                    try { tag = getSharedPreferences("nv", MODE_PRIVATE).getString(UPD_PREF_TAG, ""); } catch (Exception ignored) {}
                    // SUKSES beneran (bukan gagal/batal): status DownloadManager +
                    // file ada + berisi. Tanpa ini, APK setengah/basi dikira siap.
                    if (dmSuccess(ctx, id) && apk.exists() && apk.length() > 0) showUpdateReadyDialog(apk, tag);
                    else { toast("Unduhan update gagal, coba lagi nanti"); logError("update-dl", "file hilang/gagal"); }
                } else if (id == activeHttpDmId && id != -1) {
                    // Satu antrean selesai -> cek STATUS beneran (gagal/batal
                    // tetap memicu event selesai!) -> toast jujur -> berikutnya.
                    activeHttpDmId = -1;
                    taskEnd("dl-http");
                    boolean ok = dmSuccess(ctx, id);
                    String nm = activeHttpName;
                    activeHttpName = "";
                    if (ok) {
                        httpFails = 0;
                        try { if (!nm.isEmpty()) toast("Tersimpan: " + nm); } catch (Exception ignored) {}
                    } else {
                        logError("download-http", "gagal/batal id=" + id);
                        try { toast("Gagal mengunduh " + (nm.isEmpty() ? "file" : nm)); } catch (Exception ignored) {}
                    }
                    pumpHttpQueue();
                }
            } catch (Throwable t) { logError("receiver", String.valueOf(t)); }
        }
    };

    // Status beneran unduhan DownloadManager (jangan percaya event selesai saja).
    private boolean dmSuccess(Context ctx, long id) {
        android.database.Cursor c = null;
        try {
            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return false;
            c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c != null && c.moveToFirst()) {
                int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                return st == DownloadManager.STATUS_SUCCESSFUL;
            }
        } catch (Exception ignored) {}
        finally { try { if (c != null) c.close(); } catch (Exception ignored) {} }
        return false;
    }

    private void promptInstall(File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    if (!getPackageManager().canRequestPackageInstalls()) {
                        Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        try { startActivity(s); } catch (Exception ignored) {}
                        toast("Izinkan aplikasi ini memasang update, lalu tekan unduh lagi");
                        return;
                    }
                } catch (Exception ignored) {}
            }
            Uri uri = MiniFileProvider.uriFor(this, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(i); }
            catch (ActivityNotFoundException e) { toast("Tak ada pemasang paket"); logError("install", e.toString()); }
        } catch (Throwable t) { logError("install", String.valueOf(t)); }
    }

    // Dialog galat jaringan DITUNDA 5 dtk (debounce): di jaringan flaky,
    // error sesaat lalu halaman selesai normal — jangan tuduh user dengan
    // dialog padahal 3 dtk kemudian sembuh sendiri. Batal bila halaman selesai.
    private final Runnable errorDlgDelayed = new Runnable() {
        @Override public void run() {
            try {
                if (pageDone || readyShown) return;
                showErrorDialog("Gagal membuka halaman. Periksa koneksi lalu buka lagi.");
                onWebLoadError();
            } catch (Exception ignored) {}
        }
    };

    private void scheduleErrorDialog() {
        try {
            main.removeCallbacks(errorDlgDelayed);
            if (!pageDone && !readyShown) main.postDelayed(errorDlgDelayed, 5000);
        } catch (Exception ignored) {}
    }

    // Halaman GAGAL dimuat (sinyal hilang, kuota habis): tanpa splash penutup,
    // langsung dialog jujur + tombol coba lagi.
    private void onWebLoadError() {
        try {
            main.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (pageDone || readyShown) return;
                        showRetryDialog("Gagal membuka halaman. Periksa koneksi lalu coba lagi.");
                    } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
    }

    // Dialog galat + tombol Coba lagi (pengganti splash-ketuk-dulu).
    private void showRetryDialog(String msg) {
        try {
            AlertDialog d = new AlertDialog.Builder(this)
                    .setTitle("Gagal memuat")
                    .setMessage(msg)
                    .setPositiveButton("Coba lagi", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface di, int w) { try { retryLoad(); } catch (Exception ignored) {} }
                    })
                    .setNegativeButton("Tutup", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface di, int w) { try { di.dismiss(); } catch (Exception ignored) {} }
                    })
                    .setCancelable(true)
                    .create();
            pushDialog(d);
            d.show();
        } catch (Throwable t) { try { toast("Gagal: " + shortMsg(msg)); } catch (Exception ignored) {} }
    }

    private void retryLoad() {
        try {
            pageDone = false;
            readyShown = false;
            if (web != null) {
                try { web.reload(); } catch (Exception ignored) {}
            }
            trace("muat ulang");
        } catch (Throwable t) { logError("retry", String.valueOf(t)); }
    }

    // Bangkit dari renderer mati: WebView yang mati TAK BISA dipakai lagi
    // (aturan resmi Android) -> cabut + hancurkan + buat BARU yang segar,
    // langsung buka halaman (tanpa tirai). Dipakai juga saat pulih dari jeda.
    private void recoverRenderer(boolean crash) {
        try {
            pageDone = false;
            readyShown = false;
            try {
                if (web != null) {
                    try { ((ViewGroup) web.getParent()).removeView(web); } catch (Exception ignored) {}
                    try { web.destroy(); } catch (Exception ignored) {}
                    web = null;
                }
            } catch (Exception ignored) {}
            setupFreshWebView();
            if (crash) { try { toast("Web dimuat ulang (memori penuh)"); } catch (Exception ignored) {} }
            trace("pulih renderer (WebView baru)");
        } catch (Throwable t) { logError("recover", String.valueOf(t)); }
    }

    // ---------------- Back bertingkat ----------------

    @Override
    public void onBackPressed() {
        // 1. Dialog native teratas (pratinjau/error/update/keluar): tutup.
        try {
            for (int i = dialogStack.size() - 1; i >= 0; i--) {
                Dialog d = dialogStack.get(i);
                if (d != null && d.isShowing()) {
                    try { d.dismiss(); } catch (Exception ignored) {}
                    return;
                }
            }
        } catch (Exception ignored) {}
        // 2. WebView tak dipakai untuk riwayat (satu halaman saja) -> langsung
        // 3. Tanya web: tutup pop-up teratas / minta-save / boleh keluar?
        try {
            web.evaluateJavascript("try{window.__norvexBack?window.__norvexBack():\"{\\\"exit\\\":true}\"}catch(e){\"{\\\"exit\\\":true}\"}",
                    new ValueCallback<String>() {
                        @Override public void onReceiveValue(String v) { onBackAnswer(v); }
                    });
        } catch (Throwable t) {
            showExitConfirm();
        }
    }

    private void onBackAnswer(String v) {
        try {
            boolean exit = true;
            try {
                String s = (v == null) ? "" : v.trim();
                // evaluateJavascript membungkus balasan dalam string JSON
                // ("{\"exit\":true}") -> kupas dulu jadi {"exit":true}.
                if (s.length() >= 2 && s.startsWith("\"")) {
                    try { s = new JSONObject("{\"x\":" + s + "}").optString("x", s); }
                    catch (Exception ignored) {}
                }
                if (s.startsWith("{")) {
                    JSONObject o = new JSONObject(s);
                    if (o.has("handled")) exit = !o.optBoolean("handled", false);
                    else exit = o.optBoolean("exit", true);
                }
            } catch (Exception e) { exit = true; }
            if (exit) showExitConfirm();
            // handled=true -> web sudah menutup sesuatu, tak perlu apa-apa.
        } catch (Throwable t) { showExitConfirm(); }
    }

    private void showExitConfirm() {
        try {
            AlertDialog d = new AlertDialog.Builder(this)
                    .setTitle("Keluar aplikasi?")
                    .setMessage("State tersimpan otomatis — lanjutkan lagi nanti dari titik yang sama.")
                    .setPositiveButton("Keluar", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface di, int w) { finish(); }
                    })
                    .setNegativeButton("Tetap", null)
                    .setCancelable(true)
                    .create();
            pushDialog(d);
            d.show();
        } catch (Throwable t) { try { finish(); } catch (Exception ignored) {} }
    }

    private void pushDialog(Dialog d) {
        try {
            dialogStack.add(d);
            d.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface di) {
                    try { dialogStack.remove(di); } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
    }

    private void closeDialogType(Class<?> cls) {
        try {
            for (int i = dialogStack.size() - 1; i >= 0; i--) {
                Dialog d = dialogStack.get(i);
                if (d != null && cls.isInstance(d)) { try { d.dismiss(); } catch (Exception ignored) {} }
            }
        } catch (Exception ignored) {}
    }

    // Bikin WebView segar + langsung buka halaman. Dipakai saat aplikasi dibuka
    // dan saat bangkit dari renderer mati. Tanpa splash/tirai: user melihat
    // proses muat web apa adanya (lebih ringan + jujur).
    private void setupFreshWebView() {
        try {
            web = new WebView(this);
            if (webRoot != null) webRoot.addView(web, new FrameLayout.LayoutParams(
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

        // WebView adalah isi utama app: jangan matikan proses tampilannya selagi dipakai.
        if (Build.VERSION.SDK_INT >= 26) {
            try { web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true); } catch (Exception ignored) {}
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                injectCosmetic(); // suntik awal (spinner luar cepat muncul)
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                trace("halaman selesai");
                pageDone = true;
                startGreeting();
                injectCosmetic();
                // Halaman selesai -> tuduhan galat tadi batal (flaky sesaat).
                try { main.removeCallbacks(errorDlgDelayed); } catch (Exception ignored) {}
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String u = (req != null && req.getUrl() != null) ? req.getUrl().toString() : "";
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    // b51 (user: web lain jangan masuk webview): hanya halaman
                    // generator sendiri yang boleh pindah di bingkai utama.
                    // Tautan perchance lain -> buka di browser luar (riwayat
                    // webview tetap bersih, tombol back tak pernah nyasar).
                    boolean main = true;
                    try { main = req == null || req.isForMainFrame(); } catch (Exception ignored) {}
                    if (main && !isAppUrl(u)) {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } catch (Exception ignored) {}
                        return true;
                    }
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                } catch (ActivityNotFoundException ignored) {}
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, android.webkit.WebResourceError err) {
                try {
                    boolean main = (req == null) || req.isForMainFrame();
                    String host = "";
                    try { if (req != null && req.getUrl() != null) host = String.valueOf(req.getUrl().getHost()); } catch (Exception ignored) {}
                    String msg = "web-" + (main ? "utama" : "sub") + (host.isEmpty() ? "" : "(" + host + ")")
                            + ": " + (err == null ? "?" : String.valueOf(err.getDescription()));
                    logError("webview", msg);
                    if (main) scheduleErrorDialog();
                } catch (Throwable t) { logError("webview", String.valueOf(t)); }
            }

            // HP lama (API<23): panggil versi lawas (versi baru tak dipanggil sistem).
            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int code, String desc, String url) {
                try {
                    logError("webview", "web-utama(HP lama): " + desc);
                    scheduleErrorDialog();
                } catch (Throwable t) { logError("webview", String.valueOf(t)); }
            }

            // Proses tampil MATI (tanpa dialog/crash). Pulihkan: WebView baru +
            // muat ulang, JANGAN diam hitam.
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                try {
                    boolean crash = false;
                    try { crash = detail != null && detail.didCrash(); } catch (Exception ignored) {}
                    final boolean fCrash = crash;
                    logError("renderer", "proses tampil mati" + (fCrash ? " (crash)" : " (dibunuh sistem, biasanya RAM)"));
                    main.post(new Runnable() {
                        @Override public void run() { recoverRenderer(fCrash); }
                    });
                } catch (Throwable t) { logError("renderer", String.valueOf(t)); }
                return true; // kita yang tangani: jangan biarkan hitam
            }

        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                try {
                    if (m != null) {
                        // Error: seperti dulu. Info "load log:": breadcrumb boot
                        // web (sementara b46, untuk tahu langkah boot yang macet).
                        boolean isErr = m.messageLevel() == ConsoleMessage.MessageLevel.ERROR;
                        boolean isBoot = false;
                        try { isBoot = String.valueOf(m.message()).startsWith("load log:"); } catch (Exception ignored) {}
                        if (isErr || isBoot) logError("web-console", String.valueOf(m.message()));
                    }
                } catch (Exception ignored) {}
                return super.onConsoleMessage(m);
            }

            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
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

        // Jaring pengaman: unduhan http yang lolos dari intersepsi web.
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mime, long len) {
                if (url == null || !url.startsWith("http")) return; // blob: ditangani chunked
                String name = "download";
                try {
                    String d = android.webkit.URLUtil.guessFileName(url, contentDisposition, mime);
                    if (d != null && !d.isEmpty()) name = d;
                } catch (Exception ignored) {}
                startDownloadHttp(url, name, mime == null ? "" : mime);
            }
        });
            web.loadUrl(START_URL);
            trace("web dibuka");
        } catch (Throwable t) { logError("setupweb", String.valueOf(t)); }
    }

    // ---------------- Activity ----------------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        hookCrashLogger(); // catat biang force-close ke folder error/
        pruneOldLogs(); // buang trace/log/crash >14 hari (hemat storage mepet)
        trace("boot mulai");
        hideNav();
        // b51: kunci potret di kode juga (manifest sudah, sabuk pengaman ganda —
        // sensor/konten landscape tak bisa memutar aplikasi).
        try { setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); } catch (Exception ignored) {}
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0xFF000000); // hitam: selaras takik + tema gelap
        setContentView(root);
        // b50: alis abu = latar window/tema yg bocor lewat status bar translusen
        // (atau kanvas html). Kunci semua lapis jadi hitam.
        try { getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000)); } catch (Exception ignored) {}
        try { getWindow().getDecorView().setBackgroundColor(0xFF000000); } catch (Exception ignored) {}
        // Keyboard: cukup adjustResize sistem (cara aplikasi MIT: tanpa ganjal
        // manual — ganjal manual + resize sistem = jarak hitam ganda).
        try { getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); } catch (Exception ignored) {}
        ensureStoragePerm(); // minta izin tulis SEKALI (HP Android 9/10)

        webRoot = root;
        setupFreshWebView(); // buat WebView + langsung buka halaman (tanpa tirai)

        try {
            IntentFilter dlFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(dlReceiver, dlFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(dlReceiver, dlFilter);
            }
        } catch (Exception ignored) {}

        // Privasi: pastikan .nomedia ada (sejak awal, bukan saat unduh pertama).
        try {
            File base = baseDirExternal();
            if (base != null) ensureNoMedia(new File(base, "download"));
        } catch (Exception ignored) {}

        checkUpdate();
    }

    // Perekam force-close: tulis stack trace ke error/crash-*.txt,
    // lalu biarkan dialog sistem seperti biasa.
    private void hookCrashLogger() {
        try {
            final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                        StringWriter sw = new StringWriter();
                        try { e.printStackTrace(new PrintWriter(sw)); } catch (Exception ignored) {}
                        File f = new File(errorDir(), "crash-" + day + ".txt");
                        FileOutputStream out = null;
                        try {
                            out = new FileOutputStream(f, true);
                            out.write(("[" + ts + "] CRASH: " + sw.toString() + "\n").getBytes("UTF-8"));
                        } finally {
                            try { if (out != null) out.close(); } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                    try { if (def != null) def.uncaughtException(t, e); } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {}
    }

    // File trace/log/crash dibatasi 14 hari terakhir: folder error/ tak boleh
    // membengkak tanpa henti di HP storage mepet. Jalan di latar, 1x per buka.
    private static boolean prunedOnce = false;

    private void pruneOldLogs() {
        try {
            if (prunedOnce) return;
            prunedOnce = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        long cut = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000;
                        java.util.ArrayList<File> dirs = new java.util.ArrayList<File>();
                        try {
                            File base = baseDirExternal();
                            if (base != null) dirs.add(new File(base, "error"));
                        } catch (Exception ignored) {}
                        try { dirs.add(new File(getFilesDir(), "error")); } catch (Exception ignored) {}
                        try { dirs.add(getCacheDir()); } catch (Exception ignored) {}
                        for (File d : dirs) {
                            File[] fs = null;
                            try { fs = d.listFiles(); } catch (Exception ignored) {}
                            if (fs == null) continue;
                            for (File f : fs) {
                                try {
                                    String n = f.getName();
                                    if ((n.startsWith("trace-") || n.startsWith("log-") || n.startsWith("crash-"))
                                            && f.lastModified() < cut) f.delete();
                                } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }).start();
        } catch (Exception ignored) {}
    }

    // b51 (user: JANGAN fullscreen, biar aman): bar sistem TAMPIL normal
    // seperti aplikasi biasa. Hanya warnanya dikunci hitam agar selaras tema.
    // Tanpa IMMERSIVE/LAYOUT_*: tak ada bar bocor, tak ada takik tertutup,
    // keyboard resize bawaan berjalan apa adanya.
    private void hideNav() {
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                try { getWindow().setStatusBarColor(0xFF000000); } catch (Exception ignored) {}
                try { getWindow().setNavigationBarColor(0xFF000000); } catch (Exception ignored) {}
            }
            try { getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN); } catch (Exception ignored) {}
            try { getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideNav(); // jaga fullscreen (mis. setelah dialog sistem)
    }

    /** Dorong sapaan native (superset is_mit) beberapa kali, lalu berhenti total. */
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
            try { bridge.greetNative(); trace("salam native"); } catch (Exception ignored) {}
            if (greetLeft > 0) main.postDelayed(this, GREET_EVERY_MS);
        }
    };

    // ---------------- Cosmetic (rules elemen ala uBlock: domain###id) ----------------
    // Elemen milik halaman sendiri (bukan request iklan) tak bisa diblokir di
    // level jaringan — caranya disembunyikan via CSS, persis seperti
    // injectCosmeticHelper-nya Sunny Gupta. b49 (copas AIA 1:1 + sisa abu):
    // HANYA 6 rules user + #menuBarEl (saudara aman di luar output; Perchance
    // sendiri sembunyikan dia di minimal mode) + body gelap (biar sisa kulit
    // luar yang bocor tidak abu-abu). Buktinya:
    // b46 (tanpa suntik) tampil, b45+b47 (suntik+tambahan) hitam.
    // TEMUAN PENTING dari bedah HTML halaman publik: #editorEl adalah INDUK
    // dari #main/#b/#output/#outputIframeEl — menyembunyikan editorEl =
    // menyembunyikan SELURUH generator = BLACK SCREEN (itu biang b45/b47!).
    // editorEl/main/b/e/output JANGAN PERNAH disembunyikan.
    // header/footer/nav tak bersalah (b47 tanpa mereka tetap hitam).
    private static final String COSMETIC_CSS =
            "#minimalModeMenuBtn,#backToMinimalModeBtn,#adCtn," +
            "#adsSandboxIframeEl,#adShieldEl,#whyAdsButton1El," +
            "#menuBarEl{display:none!important}" +
            "html,body{background:#0b0e14!important}"; // b50: html ikut digelapkan
            // (kanvas paling luar; aman — bukan editorEl).

    private void injectCosmetic() {
        // b49: 6 rules + menuBarEl + body gelap (abu hilang, editorEl JANGAN disentuh).
        try {
            // documentElement bisa null bila disuntik di onPageStarted
            // (terlalu awal) -> lewati saja, onPageFinished menyuntik lagi.
            String js = "(function(){if(!document.documentElement){return;}" +
                    "var s=document.getElementById('norvex-hide');" +
                    "if(!s){s=document.createElement('style');s.id='norvex-hide';" +
                    "document.documentElement.appendChild(s);}" +
                    "s.textContent='" + COSMETIC_CSS + "';" +
                    "})();";
            web.evaluateJavascript(js, null);
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

    // b49: ganjal manual DIHAPUS total (copas cara aplikasi MIT: andalkan
    // adjustResize sistem saja). Ganjal + resize = kolom ketik mental jauh +
    // strip hitam (padding root dengan background hitam). Takik aman tanpa
    // ganjal: isi generator (iframe) mulai di bawah kulit luar Perchance.
    private void toast(final String msg) {
        try { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
    }

    private void ensureNotifPerm() {
        if (notifAsked) return;
        notifAsked = true;
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
            }
        } catch (Exception ignored) {}
    }

    // Izin tulis (HP Android 9/10, API<=29): tanpa ini folder perchance_app tak bisa
    // ditulis, semua diam-diam jatuh ke folder aplikasi (user tak menemukan file
    // di file manager + tak ada izin di Setting aplikasi). Minta SEKALI saja
    // (dicatat): kalau user menolak permanen, jangan nagih tiap buka.
    private void ensureStoragePerm() {
        try {
            if (Build.VERSION.SDK_INT <= 29
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                SharedPreferences sp = getSharedPreferences("nv", MODE_PRIVATE);
                if (sp.getBoolean("storage_asked", false)) return;
                try { sp.edit().putBoolean("storage_asked", true).apply(); } catch (Exception ignored) {}
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        try {
            if (req == REQ_STORAGE) {
                boolean ok = grants != null && grants.length > 0
                        && grants[0] == PackageManager.PERMISSION_GRANTED;
                if (ok) {
                    try {
                        File base = baseDirExternal();
                        if (base != null) ensureNoMedia(new File(base, "download"));
                    } catch (Exception ignored) {}
                    toast("Izin penyimpanan OK — file tersimpan di perchance_app/Roleplay");
                    trace("izin tulis diberikan");
                } else {
                    toast("Tanpa izin, file tersimpan di folder aplikasi (bukan perchance_app)");
                    trace("izin tulis ditolak");
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiVisible = false;
        trace("jeda");
        // Checkpoint: minta web simpan state/backup SEKARANG (baterai aman, cepat).
        if (!pausedCheckpointSent) {
            pausedCheckpointSent = true;
            try { web.evaluateJavascript("try{window.__norvexCheckpoint&&window.__norvexCheckpoint()}catch(e){}", null); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiVisible = true;
        pausedCheckpointSent = false;
        hideNav();
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
        // b51 (user: webview jangan simpan state): riwayat webview SENGAJA
        // tak disimpan — tiap buka selalu halaman generator yang segar.
    }

    @Override
    protected void onDestroy() {
        destroyed = true; // hentikan thread intip-progres
        try { unregisterReceiver(dlReceiver); } catch (Exception ignored) {}
        try {
            main.removeCallbacks(greetRun);
            main.removeCallbacks(errorDlgDelayed);
            if (web != null) {
                ((ViewGroup) web.getParent()).removeView(web);
                web.destroy();
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
