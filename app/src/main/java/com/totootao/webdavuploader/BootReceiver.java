package com.totootao.webdavuploader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机 / 升级 / 解锁后重新排程循环同步。
 * <p>
 * 红米等厂商默认禁止应用自启动，需用户在「授权管理 → 自启动」中允许本应用，
 * 否则收不到 {@code BOOT_COMPLETED}，循环同步将在重启后失效。
 * </p>
 * <p>
 * 这里只重新排程（Job + Alarm），不直接启动同步服务：锁屏状态下系统限制
 * 前台服务的启动，真正干活交给 JobScheduler 的网络约束 / AlarmManager 在合适时机唤起。
 * </p>
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || c == null) return;
        Log.d(TAG, "收到广播：" + i.getAction());
        try {
            Scheduler.reschedule(c);
        } catch (Exception e) {
            Log.w(TAG, "重启/升级后排程失败: " + e.getMessage());
        }
    }
}
