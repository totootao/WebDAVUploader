package com.totootao.webdavuploader;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * 一个目录同步任务：本地目录（SAF 树 URI）→ 远程 WebDAV 目录，单向上传。
 */
public class Task {

    public static final int IDLE = 0;
    public static final int RUNNING = 1;
    public static final int DONE = 2;
    public static final int ERROR = 3;
    public static final int WAITING_WIFI = 4;

    public final String id;
    public String name;
    public String treeUri;
    public String remotePath;
    public boolean enabled = true;
    /** 是否参与循环同步（冷却结束后自动再次同步） */
    public boolean repeat = true;
    /** 暂停：自动/批量同步跳过本任务；可单独手动同步一次 */
    public boolean paused = false;
    public long lastSync = 0;
    public String lastResult = "";

    /** 运行时状态（不持久化） */
    public transient int status = IDLE;
    public transient int uploaded = 0;
    public transient int skipped = 0;
    public transient int total = 0;
    public transient int currentPct = 0;
    public transient String currentFile = "";
    public transient String errorMessage = "";

    /** 比对统计（不持久化）：本地文件数、远端文件数、待上传文件数 */
    public transient int localCount = 0;
    public transient int remoteCount = 0;
    public transient int toUpload = 0;
    public transient int failed = 0;

    public Task(String name, String treeUri, String remotePath) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.treeUri = treeUri;
        this.remotePath = remotePath == null ? "" : remotePath;
    }

    private Task(String id, String name, String treeUri, String remotePath,
                 boolean enabled, boolean repeat, boolean paused,
                 long lastSync, String lastResult) {
        this.id = id;
        this.name = name;
        this.treeUri = treeUri;
        this.remotePath = remotePath;
        this.enabled = enabled;
        this.repeat = repeat;
        this.paused = paused;
        this.lastSync = lastSync;
        this.lastResult = lastResult;
    }

    /** 任务是否处于不可中断的忙碌状态。 */
    public boolean isBusy() {
        return status == RUNNING;
    }

    /** 下次自动同步的时间戳；0 表示「从未同步，立即可同步」。 */
    public long nextSyncAt(long coolDownMillis) {
        if (lastSync <= 0) return 0;
        return lastSync + coolDownMillis;
    }

    /** 冷却是否已结束（可以自动同步）。 */
    public boolean isDue(long coolDownMillis, long now) {
        long at = nextSyncAt(coolDownMillis);
        return at <= now;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("treeUri", treeUri);
        o.put("remotePath", remotePath);
        o.put("enabled", enabled);
        o.put("repeat", repeat);
        o.put("paused", paused);
        o.put("lastSync", lastSync);
        o.put("lastResult", lastResult == null ? "" : lastResult);
        return o;
    }

    public static Task fromJson(JSONObject o) throws JSONException {
        return new Task(
                o.optString("id", UUID.randomUUID().toString()),
                o.optString("name", "未命名任务"),
                o.optString("treeUri", ""),
                o.optString("remotePath", ""),
                o.optBoolean("enabled", true),
                o.optBoolean("repeat", true),
                o.optBoolean("paused", false),
                o.optLong("lastSync", 0),
                o.optString("lastResult", ""));
    }
}
