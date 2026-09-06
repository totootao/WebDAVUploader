package com.totootao.webdavuploader;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/**
 * 后台循环同步：由 JobScheduler 在冷却结束时唤起。
 * 只同步「冷却已结束且开启了循环」的任务，完成后自动排下一次。
 */
public class SyncJobService extends JobService {

    private static final String TAG = "SyncJobService";

    private JobParameters params;
    private boolean finished = false;

    private final SyncEngine.Listener listener = new SyncEngine.Listener() {
        @Override
        public void onChanged() {
            if (finished) return;
            if (!SyncEngine.get(SyncJobService.this).isBusy()) {
                finishJob(false);
            }
        }
    };

    @Override
    public boolean onStartJob(JobParameters p) {
        params = p;
        finished = false;
        Log.d(TAG, "循环同步触发");

        SyncEngine engine = SyncEngine.get(this);
        engine.addListener(listener);
        int n = engine.syncDue();

        // 没有到期任务、或当前非 WiFi（任务被挂起）时无需异步等待
        if (n <= 0 || !engine.isBusy()) {
            engine.removeListener(listener);
            Scheduler.reschedule(this);
            return false;
        }
        return true;   // 异步执行中，由 listener 收尾
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        Log.d(TAG, "系统中断同步");
        SyncEngine.get(this).cancel();
        SyncEngine.get(this).removeListener(listener);
        finished = true;
        return false;  // 不需要系统重排，同步收尾时会自行 reschedule
    }

    private void finishJob(boolean reschedule) {
        if (finished) return;
        finished = true;
        SyncEngine.get(this).removeListener(listener);
        Scheduler.reschedule(this);
        try {
            jobFinished(params, reschedule);
        } catch (Exception ignored) {
        }
    }
}
