package com.totootao.webdavuploader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 精确闹钟的接收器：由 {@link Scheduler} 通过 AlarmManager 在冷却到期时唤醒，
 * 启动前台同步服务。作为 JobScheduler 的补充触发，绕过 Doze / 神隐模式对 Job 的延迟，
 * 让「到点同步」更准时。
 */
public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context c, Intent i) {
        Log.d(TAG, "精确闹钟触发，启动前台同步服务");
        try {
            Intent s = new Intent(c, SyncForegroundService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
        } catch (Exception e) {
            Log.w(TAG, "启动前台服务失败: " + e.getMessage());
        }
    }
}
