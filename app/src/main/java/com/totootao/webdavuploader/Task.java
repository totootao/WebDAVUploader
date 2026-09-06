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

    public Task(String name, String treeUri, String remotePath) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.treeUri = treeUri;
        this.remotePath = remotePath == null ? "" : remotePath;
    }

    private Task(String id, String name, String treeUri, String remotePath,
                 boolean enabled, long lastSync, String lastResult) {
        this.id = id;
        this.name = name;
        this.treeUri = treeUri;
        this.remotePath = remotePath;
        this.enabled = enabled;
        this.lastSync = lastSync;
        this.lastResult = lastResult;
    }

    /** 任务是否处于不可中断的忙碌状态。 */
    public boolean isBusy() {
        return status == RUNNING;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("treeUri", treeUri);
        o.put("remotePath", remotePath);
        o.put("enabled", enabled);
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
                o.optLong("lastSync", 0),
                o.optString("lastResult", ""));
    }
}
