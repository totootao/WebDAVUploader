package com.totootao.webdavuploader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 同步通知：后台 JobService 同步时给用户可见的进度与结果。
 * 未授予通知权限（Android 13+）时静默跳过，不影响同步本身。
 */
public class Notify {

    static final String CHANNEL = "webdav_sync";
    static final int ID = 2001;

    private static volatile String lastText = "";
    private static volatile long lastAt = 0;

    private static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager m = nm(c);
            if (m == null) return;
            if (m.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "目录同步",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("WebDAV 目录同步的进度与结果");
                m.createNotificationChannel(ch);
            }
        }
    }

    private static Notification.Builder builder(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(c, CHANNEL);
        }
        return new Notification.Builder(c);
    }

    private static PendingIntent contentIntent(Context c) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 1, i, flags);
    }

    /** 更新进行中的同步通知（内部做节流，最多约 1.5 秒一次）。 */
    public static void progress(Context c, String text) {
        long now = System.currentTimeMillis();
        if (text.equals(lastText) && now - lastAt < 1500) return;
        lastText = text;
        lastAt = now;
        try {
            ensureChannel(c);
            Notification n = builder(c)
                    .setSmallIcon(R.drawable.ic_stat_sync)
                    .setContentTitle(c.getString(R.string.notify_title))
                    .setContentText(text)
                    .setContentIntent(contentIntent(c))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(0, 0, true)
                    .build();
            NotificationManager m = nm(c);
            if (m != null) m.notify(ID, n);
        } catch (SecurityException ignored) {
            // 未授予通知权限，忽略
        } catch (Exception ignored) {
        }
    }

    /** 同步结束（或取消）：把通知改为可清除的完成态。 */
    public static void done(Context c, String text) {
        lastText = text;
        lastAt = System.currentTimeMillis();
        try {
            ensureChannel(c);
            Notification n = builder(c)
                    .setSmallIcon(R.drawable.ic_stat_sync)
                    .setContentTitle(c.getString(R.string.notify_title))
                    .setContentText(text)
                    .setContentIntent(contentIntent(c))
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build();
            NotificationManager m = nm(c);
            if (m != null) m.notify(ID, n);
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
    }

    /** 前台服务常驻通知（与同步进度通知共用同一 ID，复用同一条通知）。 */
    public static Notification foreground(Context c) {
        ensureChannel(c);
        return builder(c)
                .setSmallIcon(R.drawable.ic_stat_sync)
                .setContentTitle(c.getString(R.string.notify_title))
                .setContentText(c.getString(R.string.fg_running))
                .setContentIntent(contentIntent(c))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    public static void cancel(Context c) {
        try {
            NotificationManager m = nm(c);
            if (m != null) m.cancel(ID);
        } catch (Exception ignored) {
        }
    }
}
