package com.totootao.webdavuploader;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 同步引擎：不依赖任何 Activity，前台界面与后台 JobService 共用同一套逻辑。
 * <p>
 * 同步规则：本地目录 → 远程目录，单向上传；仅在 WiFi 下传输（可配置）；
 * 远端已存在且大小一致的文件跳过；任务完成后按冷却时间循环同步。
 * </p>
 */
public class SyncEngine {

    private static final String TAG = "SyncEngine";

    /** 引擎状态变化回调（主线程）。 */
    public interface Listener {
        void onChanged();
    }

    private static volatile SyncEngine instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private final List<Task> tasks = new ArrayList<>();
    private final List<String> queue = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile boolean cancelRequested = false;

    private SyncEngine(Context c) {
        app = c.getApplicationContext();
        reload();
    }

    public static SyncEngine get(Context c) {
        if (instance == null) {
            synchronized (SyncEngine.class) {
                if (instance == null) instance = new SyncEngine(c);
            }
        }
        return instance;
    }

    // ==================== 任务列表 ====================

    public synchronized void reload() {
        tasks.clear();
        tasks.addAll(Prefs.loadTasks(app));
    }

    public synchronized List<Task> tasks() {
        return tasks;
    }

    public synchronized Task find(String id) {
        for (Task t : tasks) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }

    public synchronized void add(Task t) {
        tasks.add(t);
        persist();
    }

    public synchronized void remove(Task t) {
        tasks.remove(t);
        persist();
    }

    public synchronized void persist() {
        Prefs.saveTasks(app, tasks);
    }

    // ==================== 监听 ====================

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        if (l != null) listeners.remove(l);
    }

    private void notifyChanged() {
        main.post(() -> {
            for (Listener l : listeners) {
                try {
                    l.onChanged();
                } catch (Exception ignored) {
                }
            }
        });
    }

    public boolean isBusy() {
        return busy.get();
    }

    /** 队列中是否还有待执行的任务。 */
    public boolean pending() {
        synchronized (queue) {
            return !queue.isEmpty();
        }
    }

    /** 队列中待执行的任务数量。 */
    public int pendingCount() {
        synchronized (queue) {
            return queue.size();
        }
    }

    /** 网络等条件恢复后，尝试继续推进队列。 */
    public void pumpNow() {
        pump();
    }

    // ==================== 调度 ====================

    /** 手动同步：把一个任务加入队列（忽略冷却）。 */
    public void enqueue(Task t) {
        synchronized (queue) {
            if (!queue.contains(t.id)) queue.add(t.id);
        }
        pump();
    }

    /**
     * 手动触发：同步所有已开启且未暂停的任务（忽略冷却）。
     * 已暂停的任务会被跳过。
     */
    public int syncAll() {
        int n = 0;
        List<Task> snapshot = new ArrayList<>(tasks);
        for (Task t : snapshot) {
            if (t.enabled && !t.paused && !t.isBusy()) {
                synchronized (queue) {
                    if (!queue.contains(t.id)) queue.add(t.id);
                }
                n++;
            }
        }
        if (n > 0) pump();
        return n;
    }

    /**
     * 自动同步（启动软件时执行 / 循环同步到点时执行）：
     * 只同步「已开启、未暂停、冷却已结束」的任务，仍在冷却期内的任务跳过。
     */
    public int syncAuto() {
        long cd = Prefs.coolDownMillis(app);
        long now = System.currentTimeMillis();
        int n = 0;
        List<Task> snapshot = new ArrayList<>(tasks);
        for (Task t : snapshot) {
            if (t.enabled && !t.paused && t.repeat && t.isDue(cd, now)) {
                synchronized (queue) {
                    if (!queue.contains(t.id)) queue.add(t.id);
                }
                n++;
            }
        }
        if (n > 0) pump();
        return n;
    }

    /** {@link #syncAuto()} 的别名。 */
    public int syncDue() {
        return syncAuto();
    }

    /** 是否存在「已过冷却时间、可被自动同步」的任务。 */
    public boolean hasDue() {
        long cd = Prefs.coolDownMillis(app);
        long now = System.currentTimeMillis();
        for (Task t : new ArrayList<>(tasks)) {
            if (t.enabled && !t.paused && t.repeat && t.isDue(cd, now)) return true;
        }
        return false;
    }

    /** 设置任务暂停状态；暂停时从队列中移除，自动/批量同步都会跳过它。 */
    public void setPaused(Task t, boolean paused) {
        t.paused = paused;
        if (paused) {
            synchronized (queue) {
                queue.remove(t.id);
            }
        }
        onTaskMutated();
    }

    /** 取消当前与待执行的同步。 */
    public void cancel() {
        cancelRequested = true;
        synchronized (queue) {
            queue.clear();
        }
    }

    private void pump() {
        boolean pending;
        synchronized (queue) {
            pending = !queue.isEmpty();
        }
        if (!pending) return;

        if (Prefs.wifiOnly(app) && !NetUtil.isWifi(app)) {
            List<String> q;
            synchronized (queue) {
                q = new ArrayList<>(queue);
            }
            for (Task t : new ArrayList<>(tasks)) {
                if (q.contains(t.id) && t.status != Task.RUNNING) t.status = Task.WAITING_WIFI;
            }
            notifyChanged();
            return;
        }

        if (busy.compareAndSet(false, true)) {
            try {
                exec.execute(this::runQueue);
            } catch (Exception e) {
                busy.set(false);
            }
        }
    }

    private void runQueue() {
        int up = 0, skip = 0, fc = 0;
        boolean err = false, waiting = false, aborted = false;
        cancelRequested = false;
        Notify.progress(app, "正在同步…");

        try {
            while (true) {
                if (cancelRequested) {
                    aborted = true;
                    break;
                }
                String id;
                synchronized (queue) {
                    if (queue.isEmpty()) break;
                    id = queue.get(0);
                }
                Task t = find(id);
                if (t == null || !t.enabled || t.paused) {
                    synchronized (queue) {
                        queue.remove(id);
                    }
                    continue;
                }
                if (Prefs.wifiOnly(app) && !NetUtil.isWifi(app)) {
                    waiting = true;
                    t.status = Task.WAITING_WIFI;
                    notifyChanged();
                    break;
                }

                Result r = syncOne(t);
                if (r == null) {
                    waiting = true;
                    break;
                }
                if (r.aborted) {
                    aborted = true;
                    break;
                }
                up += r.uploaded;
                skip += r.skipped;
                fc += r.failedCount;
                err |= r.failed;
                synchronized (queue) {
                    if (!queue.isEmpty() && id.equals(queue.get(0))) queue.remove(0);
                }
            }
        } finally {
            busy.set(false);
            final int fu = up, fs = skip;
            final boolean fe = err, fw = waiting, fa = aborted;
            boolean pending;
            synchronized (queue) {
                pending = !queue.isEmpty();
            }
            if (fa) {
                Notify.done(app, "同步已取消");
            } else if (fw || pending) {
                Notify.done(app, app.getString(R.string.status_waiting_wifi));
            } else if (fe) {
                Notify.done(app, app.getString(R.string.status_all_done_part, fu, fs, fc));
            } else {
                Notify.done(app, app.getString(R.string.status_all_done, fu, fs));
            }
            // 一轮结束后重排下一次循环同步
            Scheduler.reschedule(app);
            notifyChanged();
        }
    }

    /** 同步单个任务（后台线程）。返回 null 表示因失去 WiFi 中止；Result.aborted 表示被取消。 */
    private Result syncOne(Task t) {
        final String server = Prefs.server(app);
        final String user = Prefs.user(app);
        final String pass = Prefs.pass(app);
        final boolean insecure = Prefs.insecure(app);

        t.status = Task.RUNNING;
        t.uploaded = 0;
        t.skipped = 0;
        t.total = 0;
        t.failed = 0;
        t.currentPct = 0;
        t.currentFile = "";
        t.errorMessage = "";
        notifyChanged();

        Uri tree;
        try {
            tree = Uri.parse(t.treeUri);
        } catch (Exception e) {
            return fail(t, "本地目录地址无效");
        }
        if (!hasUriPermission(tree)) {
            return fail(t, "目录授权已失效，请编辑任务重新选择目录");
        }

        List<DocsTree.Entry> files;
        try {
            files = DocsTree.walk(app, tree);
        } catch (Exception e) {
            return fail(t, "读取目录失败：" + e.getMessage());
        }
        t.total = files.size();
        if (files.isEmpty()) {
            t.status = Task.DONE;
            t.lastSync = System.currentTimeMillis();
            t.lastResult = app.getString(R.string.task_empty_dir);
            persist();
            return new Result(0, 0, false);
        }

        // 先拉取远程目录列表，与本地比较，只同步「远端不存在或大小变化」的文件。
        // 这样每个远程目录只需一次 PROPFIND，远少于逐个文件 HEAD 探测。
        Notify.progress(app, app.getString(R.string.status_comparing));
        Map<String, Long> remote = new HashMap<>();
        // 本轮已尝试创建过的远程目录，避免每个文件都逐层 MKCOL 一遍
        Set<String> createdDirs = new HashSet<>();
        LinkedHashSet<String> parents = new LinkedHashSet<>();
        for (DocsTree.Entry e : files) parents.add(e.relDir);
        for (String parent : parents) {
            if (cancelRequested) {
                t.status = Task.IDLE;
                return new Result(t.uploaded, t.skipped, false, true);
            }
            if (Prefs.wifiOnly(app) && !NetUtil.isWifi(app)) {
                t.status = Task.WAITING_WIFI;
                return null;
            }
            Map<String, Long> m = WebDavClient.listDir(server, t.remotePath, parent, user, pass, insecure);
            if (m == null) {
                // 无法确认远端真实状态：必须中止本轮。
                // 若在此处当作「远端为空」继续，就会把全部文件重传一遍（重复上传）。
                String where = parent.isEmpty() ? (t.remotePath.isEmpty() ? "/" : t.remotePath)
                        : parent;
                return fail(t, "无法获取远程目录列表（" + where + "），已跳过本次同步以避免重复上传");
            }
            for (Map.Entry<String, Long> it : m.entrySet()) {
                String name = it.getKey();
                String rel = parent.isEmpty() ? name : parent + "/" + name;
                remote.put(rel, it.getValue());
            }
        }

        // 比对完成：统计本地 / 远端 / 待上传文件数，立即反馈给用户
        // （开始同步或恢复同步时，递交完目录即可看到这三项数字）
        int localCount = files.size();
        int remoteCount = remote.size();
        int toUpload = 0;
        for (DocsTree.Entry e : files) {
            if (needUpload(remote.get(e.relPath()), e.size)) toUpload++;
        }
        t.localCount = localCount;
        t.remoteCount = remoteCount;
        t.toUpload = toUpload;
        notifyChanged();
        Notify.progress(app, app.getString(R.string.status_compare_done, localCount, remoteCount, toUpload));

        for (DocsTree.Entry e : files) {
            if (cancelRequested) {
                t.status = Task.IDLE;
                return new Result(t.uploaded, t.skipped, false, true);
            }
            if (Prefs.wifiOnly(app) && !NetUtil.isWifi(app)) {
                t.status = Task.WAITING_WIFI;
                return null;
            }
            t.currentFile = e.relPath();
            t.currentPct = 0;
            notifyChanged();

            try {
                String url = WebDavClient.buildPutUrlPath(server, t.remotePath, e.relPath());

                // 比较远程列表：远端已存在且无需更新则跳过（不再逐个 HEAD 探测）
                Long rsize = remote.get(e.relPath());
                if (!needUpload(rsize, e.size)) {
                    t.skipped++;
                    continue;
                }

                WebDavClient.ensureParentDirs(url, createdDirs, user, pass, insecure);

                try (InputStream in = DocsTree.open(app, tree, e.docId)) {
                    if (in == null) throw new IOException("无法打开文件");
                    final long[] lastPost = {0};
                    // 本地大小未知（SAF 未给出）时传 -1 走 chunked；
                    // 若仍传 0 会让 PUT 声明 Content-Length: 0，把远端文件截断成空文件。
                    int code = WebDavClient.putFile(url, in, e.size > 0 ? e.size : -1,
                            user, pass, insecure, sent -> {
                        if (e.size > 0) {
                            t.currentPct = (int) (sent * 100 / e.size);
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastPost[0] >= 400) {
                            lastPost[0] = now;
                            notifyChanged();
                        }
                    });
                    if (code < 200 || code >= 300) {
                        throw new IOException("HTTP " + code);
                    }
                }
                t.uploaded++;
                t.currentPct = 100;
                notifyChanged();
            } catch (Exception ex) {
                // 单个文件上传失败：跳过该文件、继续传下一个，不中断整个任务；
                // 失败文件因远端没有完整副本，下一轮循环同步时会重新比对并自动重试
                String msg = (ex.getMessage() == null || ex.getMessage().isEmpty())
                        ? ex.toString() : ex.getMessage();
                t.failed++;
                t.errorMessage = (t.errorMessage.isEmpty() ? "" : t.errorMessage + "\n")
                        + app.getString(R.string.sync_fail_file, e.relPath(), msg);
                Log.w(TAG, "文件上传失败，已跳过并继续：" + e.relPath() + " -> " + msg);
                notifyChanged();
                continue;
            }
        }

        t.status = Task.DONE;
        t.lastSync = System.currentTimeMillis();
        if (t.failed > 0) {
            t.lastResult = app.getString(R.string.task_done_part, t.uploaded, t.skipped, t.failed);
            persist();
            // 任务整体仍标记 DONE（不中断）；失败文件下一轮循环同步自动重试
            return new Result(t.uploaded, t.skipped, true, false, t.failed);
        }
        t.lastResult = app.getString(R.string.task_done, t.uploaded, t.skipped);
        persist();
        return new Result(t.uploaded, t.skipped, false);
    }

    /**
     * 判断某个本地文件是否需要上传。
     * <p>
     * 只有「远端不存在」才一定要传。若任一侧大小未知——本地 SAF 未给出大小（{@code <=0}），
     * 或服务器未返回 Content-Length（{@code <0}）——只要远端已存在同名文件就跳过。
     * 否则这类文件会永远「匹配不上」，导致每一轮都全量重传。
     */
    private static boolean needUpload(Long remoteSize, long localSize) {
        if (remoteSize == null) return true;   // 远端不存在 → 新增
        if (localSize <= 0) return false;      // 本地大小未知：远端已有，跳过
        if (remoteSize < 0) return false;      // 远端大小未知：视为已存在，跳过
        return remoteSize != localSize;        // 两边都已知且不一致 → 内容有变更
    }

    private Result fail(Task t, String msg) {
        t.status = Task.ERROR;
        t.errorMessage = msg;
        persist();
        return new Result(t.uploaded, t.skipped, true);
    }

    public boolean hasUriPermission(Uri u) {
        try {
            for (UriPermission p : app.getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(u)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 单个任务结束后的收尾：更新最近同步记录并（按需）重排任务。 */
    public void onTaskMutated() {
        persist();
        Scheduler.reschedule(app);
        notifyChanged();
    }

    private static class Result {
        final int uploaded;
        final int skipped;
        final boolean failed;
        final boolean aborted;
        final int failedCount;

        Result(int uploaded, int skipped, boolean failed) {
            this(uploaded, skipped, failed, false, 0);
        }

        Result(int uploaded, int skipped, boolean failed, boolean aborted) {
            this(uploaded, skipped, failed, aborted, 0);
        }

        Result(int uploaded, int skipped, boolean failed, boolean aborted, int failedCount) {
            this.uploaded = uploaded;
            this.skipped = skipped;
            this.failed = failed;
            this.aborted = aborted;
            this.failedCount = failedCount;
        }
    }
}
