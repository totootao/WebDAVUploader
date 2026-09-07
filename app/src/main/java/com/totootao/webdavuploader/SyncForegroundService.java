package com.totootao.webdavuploader;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 前台同步服务：真正承载上传长任务。
 * <p>
 * 由 JobScheduler / AlarmManager 唤起后转前台（常驻一条低优先级通知，防 MIUI 神隐模式
 * 在上传中途回收进程），同步结束（或等待 WiFi / 被取消）后自动停止前台并退出。
 * </p>
 * <p>
 * 同步逻辑完全复用 {@link SyncEngine}，与界面、JobService 共用同一套。
 * </p>
 */
public class SyncForegroundService extends Service {

    private static final String TAG = "SyncFgService";

    private SyncEngine engine;
    private final SyncEngine.Listener listener = this::onChanged;

    @Override
    public void onCreate() {
        super.onCreate();
        engine = SyncEngine.get(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 必须在 5 秒内转前台，否则系统会崩溃（Notify.foreground 内部已确保通知渠道存在）
        startForeground(Notify.ID, Notify.foreground(this));
        engine.addListener(listener);
        // 已在同步则不重复触发（Job 与 Alarm 可能几乎同时唤起，幂等保护）
        if (!engine.isBusy()) {
            engine.syncDue();
        }
        // 本次没有可同步任务时立即收尾，避免空转前台服务
        checkFinish();
        return START_NOT_STICKY;
    }

    private void onChanged() {
        checkFinish();
    }

    /** 一旦不再忙碌（完成 / 等待 WiFi / 被取消）即退出前台服务。 */
    private void checkFinish() {
        if (engine == null) return;
        if (!engine.isBusy()) {
            engine.removeListener(listener);
            try {
                if (Build.VERSION.SDK_INT >= 24) {
                    // 保留「完成」通知，仅退出前台状态
                    stopForeground(Service.STOP_FOREGROUND_DETACH);
                } else {
                    stopForeground(true);
                }
            } catch (Exception ignored) {
            }
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (engine != null) engine.removeListener(listener);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
