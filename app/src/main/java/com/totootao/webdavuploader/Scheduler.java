package com.totootao.webdavuploader;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.List;

/**
 * 循环同步的排程：用系统 JobScheduler + AlarmManager 双重保险安排下一次自动同步。
 * <p>
 * - JobScheduler：带 WiFi 网络约束，在 WiFi 恢复时自动唤醒，最省电；
 * - AlarmManager（精确闹钟）：绕过 Doze / 神隐模式对 Job 的延迟，保证「到点」准时触发。
 * 两者任一触发都会唤起 {@link SyncForegroundService} 执行同步；重复触发幂等（已在同步则不重复）。
 * </p>
 * <p>
 * 每次一轮同步结束 / 配置变更后调用 {@link #reschedule(Context)} 计算最近一个「冷却到期」的
 * 时间点，同时排程 Job 与 Alarm，从而形成循环。
 * </p>
 */
public class Scheduler {

    private static final String TAG = "Scheduler";
    private static final int JOB_ID = 9101;
    private static final String ALARM_ACTION = "com.totootao.webdavuploader.action.SYNC_ALARM";

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
        long delay = nextDelay(c);
        if (delay < 0) {
            cancel(c);
            Log.d(TAG, "循环同步已取消（无到期任务或已关闭）");
            return;
        }
        scheduleJob(c, delay);
        scheduleAlarm(c, delay);
    }

    private static void scheduleJob(Context c, long delay) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        try {
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                    new ComponentName(c, SyncJobService.class))
                    .setMinimumLatency(delay)
                    .setOverrideDeadline(delay + 30 * 60 * 1000L)
                    .setPersisted(true);   // 重启后仍然保留（仍建议配合自启动白名单）
            if (Prefs.wifiOnly(c)) {
                b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
            } else {
                b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
            }
            int r = js.schedule(b.build());
            Log.d(TAG, "已排程下次循环同步(Job)：" + (delay / 1000) + "s 后，结果=" + r);
        } catch (Exception e) {
            Log.w(TAG, "Job 排程失败: " + e.getMessage());
        }
    }

    private static void scheduleAlarm(Context c, long delay) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = alarmPi(c);
            am.cancel(pi);
            long at = System.currentTimeMillis() + Math.max(delay, 0);
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
            Log.d(TAG, "已排程下次循环同步(Alarm)：" + (delay / 1000) + "s 后");
        } catch (SecurityException e) {
            Log.w(TAG, "精确闹钟不可用: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Alarm 排程失败: " + e.getMessage());
        }
    }

    /** 能否使用精确闹钟（Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限且未被撤销）。 */
    public static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    private static PendingIntent alarmPi(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(ALARM_ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, JOB_ID, i, flags);
    }

    public static void cancel(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) {
            try {
                js.cancel(JOB_ID);
            } catch (Exception ignored) {
            }
        }
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(alarmPi(c));
        } catch (Exception ignored) {
        }
    }
}
