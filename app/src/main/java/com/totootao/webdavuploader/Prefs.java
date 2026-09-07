package com.totootao.webdavuploader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置与任务列表的持久化（SharedPreferences）。
 */
public class Prefs {

    private static final String P = "webdav_sync";
    private static final String K_SERVER = "server";
    private static final String K_USER = "user";
    private static final String K_PASS = "pass";
    private static final String K_INSECURE = "insecure";
    private static final String K_WIFI_ONLY = "wifi_only";
    private static final String K_AUTO_REPEAT = "auto_repeat";
    private static final String K_COOLDOWN_HOURS = "cooldown_hours";
    private static final String K_TASKS = "tasks";
    private static final String K_SETUP = "setup_guide_shown";

    /** 冷却时长候选（小时） */
    public static final int[] COOLDOWN_OPTIONS = {6, 12, 24, 48};
    public static final int DEFAULT_COOLDOWN_HOURS = 24;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    // ---------- 配置 ----------
    public static String server(Context c) { return sp(c).getString(K_SERVER, ""); }
    public static String user(Context c) { return sp(c).getString(K_USER, ""); }
    public static String pass(Context c) { return sp(c).getString(K_PASS, ""); }
    public static boolean insecure(Context c) { return sp(c).getBoolean(K_INSECURE, false); }
    public static boolean wifiOnly(Context c) { return sp(c).getBoolean(K_WIFI_ONLY, true); }

    /** 全局循环同步开关（任务完成后按冷却时间自动再次同步） */
    public static boolean autoRepeat(Context c) { return sp(c).getBoolean(K_AUTO_REPEAT, true); }

    /** 冷却时长（小时），默认 24 小时 */
    public static int coolDownHours(Context c) {
        int h = sp(c).getInt(K_COOLDOWN_HOURS, DEFAULT_COOLDOWN_HOURS);
        if (h <= 0) h = DEFAULT_COOLDOWN_HOURS;
        return h;
    }

    public static long coolDownMillis(Context c) {
        return coolDownHours(c) * 60L * 60L * 1000L;
    }

    /** 首次启动的后台保活引导是否已展示过。 */
    public static boolean setupGuideShown(Context c) {
        return sp(c).getBoolean(K_SETUP, false);
    }

    public static void markSetupGuideShown(Context c) {
        sp(c).edit().putBoolean(K_SETUP, true).apply();
    }

    public static void saveConfig(Context c, String server, String user, String pass,
                                  boolean insecure, boolean wifiOnly) {
        sp(c).edit()
                .putString(K_SERVER, server)
                .putString(K_USER, user)
                .putString(K_PASS, pass)
                .putBoolean(K_INSECURE, insecure)
                .putBoolean(K_WIFI_ONLY, wifiOnly)
                .apply();
    }

    /** 保存循环同步设置 */
    public static void saveRepeat(Context c, boolean autoRepeat, int coolDownHours) {
        sp(c).edit()
                .putBoolean(K_AUTO_REPEAT, autoRepeat)
                .putInt(K_COOLDOWN_HOURS, coolDownHours)
                .apply();
    }

    // ---------- 任务 ----------
    public static List<Task> loadTasks(Context c) {
        List<Task> list = new ArrayList<>();
        String json = sp(c).getString(K_TASKS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(Task.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void saveTasks(Context c, List<Task> tasks) {
        JSONArray arr = new JSONArray();
        for (Task t : tasks) {
            try {
                arr.put(t.toJson());
            } catch (Exception ignored) {
            }
        }
        sp(c).edit().putString(K_TASKS, arr.toString()).apply();
    }
}
