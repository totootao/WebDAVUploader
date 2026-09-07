package com.totootao.webdavuploader;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 循环同步的触发器：由 JobScheduler 在冷却到期（且 WiFi 满足约束）时唤起，
 * 仅负责启动 {@link SyncForegroundService} 接管长任务，自身立即结束，
 * 避免 JobService 的执行时限把上传中途掐断。
 */
public class SyncJobService extends JobService {

    private static final String TAG = "SyncJobService";

    @Override
    public boolean onStartJob(JobParameters p) {
        Log.d(TAG, "循环同步触发，唤起前台服务");
        startSyncService(this);
        return false; // Job 立即结束，长任务由前台服务承载
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        // 系统要回收 Job：确保前台服务已启动，避免丢失这次触发
        Log.d(TAG, "系统回收 Job，确保前台服务已启动");
        startSyncService(this);
        return false;
    }

    private static void startSyncService(JobService s) {
        try {
            Intent i = new Intent(s, SyncForegroundService.class);
            if (Build.VERSION.SDK_INT >= 26) s.startForegroundService(i);
            else s.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "启动前台服务失败: " + e.getMessage());
        }
    }
}
