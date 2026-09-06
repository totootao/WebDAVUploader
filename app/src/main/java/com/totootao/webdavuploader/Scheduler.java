package com.totootao.webdavuploader;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.util.List;

/**
 * 循环同步的排程：用系统 JobScheduler 安排下一次自动同步。
 * <p>
 * 每次一轮同步结束 / 配置变更后调用 {@link #reschedule(Context)}，
 * 计算最近一个「冷却到期」的时间点，安排一个一次性 Job；
 * Job 触发后再由 SyncJobService 执行并重新排下一次，从而形成循环。
 * </p>
 */
public class Scheduler {

    private static final String TAG = "Scheduler";
    private static final int JOB_ID = 9101;

    /**
     * 计算距离最近一次「冷却到期」的毫秒数。
     *
     * @return >=0 的延迟；-1 表示没有需要循环的任务
     */
    public static long nextDelay(Context c) {
        if (!Prefs.autoRepeat(c)) return -1;
        long cd = Prefs.coolDownMillis(c);
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        List<Task> tasks = Prefs.loadTasks(c);
        for (Task t : tasks) {
            if (!t.enabled || !t.repeat || t.paused) continue;
            long at = t.nextSyncAt(cd);
            if (at <= now) return 0;
            next = Math.min(next, at);
        }
        return next == Long.MAX_VALUE ? -1 : (next - now);
    }

    /** 最近一次自动同步的时间戳；0 表示立即或无需同步。 */
    public static long nextSyncAt(Context c) {
        long delay = nextDelay(c);
        if (delay < 0) return 0;
        return System.currentTimeMillis() + delay;
    }

    public static void reschedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        try {
            long delay = nextDelay(c);
            if (delay < 0) {
                js.cancel(JOB_ID);
                Log.d(TAG, "循环同步已取消（无到期任务或已关闭）");
                return;
            }
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                    new ComponentName(c, SyncJobService.class))
                    .setMinimumLatency(delay)
                    .setOverrideDeadline(delay + 30 * 60 * 1000L)
                    .setPersisted(true);   // 重启后仍然保留
            if (Prefs.wifiOnly(c)) {
                b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
            } else {
                b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
            }
            int r = js.schedule(b.build());
            Log.d(TAG, "已排程下次循环同步：" + (delay / 1000) + "s 后，结果=" + r);
        } catch (Exception e) {
            Log.w(TAG, "排程失败: " + e.getMessage());
        }
    }

    public static void cancel(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) {
            try {
                js.cancel(JOB_ID);
            } catch (Exception ignored) {
            }
        }
    }
}
