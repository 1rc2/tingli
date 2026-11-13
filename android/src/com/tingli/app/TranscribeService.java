package com.tingli.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 转写前台服务：App 退到后台或锁屏后仍保持进程与 CPU 活跃，
 * 转写进度在通知栏展示，点击通知可回到 App。
 */
public class TranscribeService extends Service {

    private static final String CHANNEL_ID = "tingli_transcribe";
    private static final int NOTIFY_ID = 1001;

    private static final String ACTION_START  = "com.tingli.app.TRANSCRIBE_START";
    private static final String ACTION_UPDATE = "com.tingli.app.TRANSCRIBE_UPDATE";
    private static final String EXTRA_TEXT = "text";

    private PowerManager.WakeLock wakeLock;

    public static void start(Context ctx, String text) {
        Intent i = new Intent(ctx, TranscribeService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_TEXT, text);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) {}
    }

    public static void update(Context ctx, String text) {
        Intent i = new Intent(ctx, TranscribeService.class);
        i.setAction(ACTION_UPDATE);
        i.putExtra(EXTRA_TEXT, text);
        try { ctx.startService(i); } catch (Exception ignored) {}
    }

    public static void stop(Context ctx) {
        try { ctx.stopService(new Intent(ctx, TranscribeService.class)); } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        String text = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);

        if (ACTION_UPDATE.equals(action)) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTIFY_ID, build(text));
            } catch (Exception ignored) {}
            return START_NOT_STICKY;
        }

        // 首次启动：创建通知渠道 + 前台通知 + 持有 CPU 唤醒锁
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel ch = new NotificationChannel(
                            CHANNEL_ID, "转写进度", NotificationManager.IMPORTANCE_LOW);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            } catch (Exception ignored) {}
        }
        try {
            startForeground(NOTIFY_ID, build(text));
        } catch (Exception ignored) {}

        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tingli:transcribe");
                    wakeLock.setReferenceCounted(false);
                }
            }
            // 最多持有 2 小时，防止异常情况下永久持锁
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(2 * 60 * 60 * 1000L);
        } catch (Exception ignored) {}

        return START_NOT_STICKY;
    }

    private Notification build(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flag);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("听力训练 · 转写中")
                .setContentText(text == null ? "正在转写…" : text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);
        return b.build();
    }

    private void releaseWake() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        releaseWake();
        try { stopForeground(true); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
