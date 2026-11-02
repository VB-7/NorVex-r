package com.norvex.roleplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mode background: service foreground + notifikasi tetap selama ada tugas
 * (unduhan / AI menjawab / transfer file). Sistem jadi tidak mematikan aplikasi,
 * user pun tahu aplikasi sedang apa walau di luar (walau fullscreen).
 */
public class TaskService extends Service {

    public static final String ACT_START = "nv_task_start";
    public static final String ACT_END = "nv_task_end";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_LABEL = "label";

    private static final String CH = "nv_tasks";
    private static final int NID = 41;

    private final Map<String, String> tasks = new LinkedHashMap<String, String>();

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null && intent.getAction() != null) {
                String id = intent.getStringExtra(EXTRA_ID);
                if (id == null) id = "";
                if (ACT_START.equals(intent.getAction())) {
                    String label = intent.getStringExtra(EXTRA_LABEL);
                    tasks.put(id, (label == null || label.isEmpty()) ? "Bekerja…" : label);
                } else if (ACT_END.equals(intent.getAction())) {
                    tasks.remove(id);
                }
            }
            // WAJIB DULUAN: sistem (Android 8+) menuntut startForeground() dalam
            // hitungan detik setiap startForegroundService() — BAHKAN saat tugas
            // sudah habis dan service mau berhenti. Kalau dilewati, sistem
            // melempar RemoteServiceException = force close (biang crash b23-b27:
            // taskEnd untuk tugas yang tak pernah dimulai -> stopSelf tanpa
            // foreground -> mati). Jadi: foreground dulu, baru putuskan.
            try {
                ensureChannel();
                Notification n = buildNotif();
                if (n == null) n = fallbackNotif();
                if (n != null) startForeground(NID, n);
            } catch (Throwable t) {
                try { android.util.Log.e("TaskService", "fg: " + t); } catch (Exception ignored) {}
            }
            if (tasks.isEmpty()) {
                try { stopForeground(true); } catch (Exception ignored) {}
                stopSelf();
            } else {
                try {
                    Notification n2 = buildNotif();
                    if (n2 == null) n2 = fallbackNotif();
                    if (n2 != null) {
                        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (nm != null) nm.notify(NID, n2);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return START_STICKY;
    }

    private void ensureChannel() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CH) == null) {
                    NotificationChannel c = new NotificationChannel(CH, "NorVex-R berjalan", NotificationManager.IMPORTANCE_LOW);
                    c.setDescription("Tugas aplikasi (unduhan, jawaban AI).");
                    nm.createNotificationChannel(c);
                }
            }
        } catch (Exception ignored) {}
    }

    private Notification buildNotif() {
        String text;
        if (tasks.size() == 1) {
            text = tasks.values().iterator().next();
        } else {
            text = tasks.size() + " tugas berjalan";
        }
        Intent back = new Intent(this, MainActivity.class);
        back.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = null;
        try {
            int fl = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
            pi = PendingIntent.getActivity(this, 0, back, fl);
        } catch (Exception ignored) {}
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH);
        else b = new Notification.Builder(this);
        b.setContentTitle("NorVex-R")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true);
        try { if (pi != null) b.setContentIntent(pi); } catch (Exception ignored) {}
        try { return b.build(); } catch (Exception e) { return fallbackNotif(); }
    }

    // Notifikasi darurat super-sederhana: dipakai bila buildNotif() gagal.
    // startForeground() TAK BOLEH menerima null — null = crash RemoteService.
    private Notification fallbackNotif() {
        try {
            ensureChannel();
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH);
            else b = new Notification.Builder(this);
            return b.setContentTitle("NorVex-R")
                    .setContentText("Bekerja…")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setOngoing(true)
                    .build();
        } catch (Exception ignored) { return null; }
    }
}
