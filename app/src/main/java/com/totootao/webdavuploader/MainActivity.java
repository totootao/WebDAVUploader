package com.totootao.webdavuploader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：两个标签页 ——「任务」（多目录同步任务）与「配置」（登录 + 同步设置）。
 * <p>
 * 同步逻辑全部在 {@link SyncEngine} 中，界面只负责展示与触发；
 * 后台循环同步由 {@link SyncJobService} 通过 {@link Scheduler} 排程。
 * </p>
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_DIR = 2001;
    private static final int REQ_NOTIFY = 3001;

        /** 每次进程启动只自动同步一次（旋转屏幕 / 切后台回来不会重复触发） */
    private static boolean autoLaunched = false;
    // 顶部与标签
    private TextView tvWifi;
    private Button tabTasks, tabConfig;
    private View screenTasks, screenConfig;

    // 任务页
    private TextView tvOverall, tvEmpty;
    private ProgressBar pbOverall;
    private Button btnSyncAll, btnNewTask;
    private ListView lvTasks;

    // 配置页
    private EditText etServer, etUser, etPass;
    private CheckBox cbInsecure;
    private Switch swWifiOnly, swAutoRepeat;
    private Spinner spCooldown;
    private TextView tvNextSync;
    private Button btnTest, btnSave;

    private SyncEngine engine;
    private TaskAdapter adapter;
    private final ExecutorService miscExec = Executors.newCachedThreadPool();

    /** 目录名缓存，避免列表滚动时反复查询 */
    private final Map<String, String> dirLabelCache = new HashMap<>();

    // 目录选择对话框的临时状态
    private String[] pendingPicked;
    private TextView pendingTvDir;

    private final SyncEngine.Listener engineListener = this::onEngineChanged;

    private final BroadcastReceiver netReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            updateWifiHeader();
            if (engine.pending() && NetUtil.isWifi(c)) {
                engine.pumpNow();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        engine = SyncEngine.get(this);

        tvWifi = findViewById(R.id.tvWifi);
        tabTasks = findViewById(R.id.tabTasks);
        tabConfig = findViewById(R.id.tabConfig);
        screenTasks = findViewById(R.id.screenTasks);
        screenConfig = findViewById(R.id.screenConfig);

        tvOverall = findViewById(R.id.tvOverall);
        tvEmpty = findViewById(R.id.tvEmpty);
        pbOverall = findViewById(R.id.pbOverall);
        btnSyncAll = findViewById(R.id.btnSyncAll);
        btnNewTask = findViewById(R.id.btnNewTask);
        lvTasks = findViewById(R.id.lvTasks);

        etServer = findViewById(R.id.etServer);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        cbInsecure = findViewById(R.id.cbInsecure);
        swWifiOnly = findViewById(R.id.swWifiOnly);
        swAutoRepeat = findViewById(R.id.swAutoRepeat);
        spCooldown = findViewById(R.id.spCooldown);
        tvNextSync = findViewById(R.id.tvNextSync);
        btnTest = findViewById(R.id.btnTest);
        btnSave = findViewById(R.id.btnSave);

        adapter = new TaskAdapter();
        lvTasks.setAdapter(adapter);

        // ---- 标签页 ----
        tabTasks.setOnClickListener(v -> showTab(true));
        tabConfig.setOnClickListener(v -> showTab(false));
        showTab(true);

        // ---- 任务页 ----
        btnNewTask.setOnClickListener(v -> showTaskDialog(null));
        btnSyncAll.setOnClickListener(v -> {
            if (!checkReady()) return;
            int n = engine.syncAll();
            if (n == 0) {
                boolean anyPaused = false;
                for (Task t : engine.tasks()) {
                    if (t.enabled && t.paused) {
                        anyPaused = true;
                        break;
                    }
                }
                Toast.makeText(this, anyPaused ? R.string.toast_all_paused
                        : R.string.toast_no_enabled_task, Toast.LENGTH_SHORT).show();
            }
        });

        // ---- 配置页 ----
        etServer.setText(Prefs.server(this));
        etUser.setText(Prefs.user(this));
        etPass.setText(Prefs.pass(this));
        cbInsecure.setChecked(Prefs.insecure(this));
        swWifiOnly.setChecked(Prefs.wifiOnly(this));
        swAutoRepeat.setChecked(Prefs.autoRepeat(this));
        setupCooldownSpinner();

        btnSave.setOnClickListener(v -> {
            Prefs.saveConfig(this,
                    etServer.getText().toString().trim(),
                    etUser.getText().toString().trim(),
                    etPass.getText().toString(),
                    cbInsecure.isChecked(),
                    swWifiOnly.isChecked());
            Prefs.saveRepeat(this, swAutoRepeat.isChecked(), selectedCooldown());
            Scheduler.reschedule(this);
            updateWifiHeader();
            refreshAll();
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
        });

        btnTest.setOnClickListener(v -> {
            final String server = etServer.getText().toString().trim();
            if (server.isEmpty()) {
                Toast.makeText(this, R.string.test_no_config, Toast.LENGTH_SHORT).show();
                return;
            }
            final String user = etUser.getText().toString().trim();
            final String pass = etPass.getText().toString();
            final boolean insecure = cbInsecure.isChecked();
            btnTest.setEnabled(false);
            btnTest.setText(R.string.btn_testing);
            miscExec.execute(() -> {
                int code = WebDavClient.probe(server, user, pass, insecure);
                runOnUiThread(() -> {
                    btnTest.setEnabled(true);
                    btnTest.setText(R.string.btn_test);
                    if (code >= 200 && code < 400) {
                        Toast.makeText(this, R.string.test_ok, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this,
                                getString(R.string.test_fail, describeCode(code)),
                                Toast.LENGTH_LONG).show();
                    }
                });
            });
        });

        askNotificationPermission();
        updateWifiHeader();
        refreshAll();
        // 重新排程循环同步（覆盖升级、清数据、系统取消 Job 等场景）
        Scheduler.reschedule(this);
        maybeAutoSyncOnLaunch();
    }

    /** 启动软件时自动同步：执行全部「未暂停且已过冷却」的任务，串行执行。 */
    private void maybeAutoSyncOnLaunch() {
        if (autoLaunched) return;
        autoLaunched = true;
        if (Prefs.server(this).isEmpty()) return;
        if (engine.tasks().isEmpty()) return;

        int n = engine.syncAuto();
        if (n > 0) {
            Toast.makeText(this, getString(R.string.toast_launch_sync, n), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.toast_launch_sync_none, Toast.LENGTH_SHORT).show();
        }
        refreshAll();
        maybeShowSetupGuide();
        checkExactAlarm();
    }

    /** 首次启动引导用户到厂商后台保活设置（自启动 + 电池无限制）。 */
    private void maybeShowSetupGuide() {
        if (Prefs.setupGuideShown(this)) return;
        Prefs.markSetupGuideShown(this);
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_guide_title)
                .setMessage(R.string.setup_guide_msg)
                .setNegativeButton(R.string.setup_guide_later, null)
                .setNeutralButton(R.string.setup_guide_miui, (d, w) -> openMiuiAutostart())
                .setPositiveButton(R.string.setup_guide_detail, (d, w) -> openAppDetails())
                .show();
    }

    private void openMiuiAutostart() {
        try {
            Intent i = new Intent();
            i.setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.autosettings.AutoSettingsActivity");
            i.putExtra("packageName", getPackageName());
            startActivity(i);
        } catch (Exception e) {
            openAppDetails();
        }
    }

    private void openAppDetails() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    /** 精确闹钟权限检查（Android 12+）：被撤销时定时同步可能延迟。 */
    private void checkExactAlarm() {
        if (Build.VERSION.SDK_INT >= 33 && !Scheduler.canExact(this)) {
            Toast.makeText(this, R.string.exact_alarm_denied, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        engine.addListener(engineListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(netReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        updateWifiHeader();
        refreshAll();

        // 回到前台时，若队列里还有未完成的任务（例如之前非 WiFi 被挂起），继续推进
        if (engine.pending() && NetUtil.isWifi(this)) {
            engine.pumpNow();
        }
    }

    @Override
    protected void onStop() {
        engine.removeListener(engineListener);
        try {
            unregisterReceiver(netReceiver);
        } catch (Exception ignored) {
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        miscExec.shutdownNow();
        super.onDestroy();
    }

    // ==================== 界面 ====================

    private void showTab(boolean tasksTab) {
        tabTasks.setBackgroundResource(tasksTab ? R.drawable.tab_active : R.drawable.tab_inactive);
        tabConfig.setBackgroundResource(tasksTab ? R.drawable.tab_inactive : R.drawable.tab_active);
        tabTasks.setTextColor(getResources().getColor(
                tasksTab ? android.R.color.white : R.color.text_secondary));
        tabConfig.setTextColor(getResources().getColor(
                tasksTab ? R.color.text_secondary : android.R.color.white));
        screenTasks.setVisibility(tasksTab ? View.VISIBLE : View.GONE);
        screenConfig.setVisibility(tasksTab ? View.GONE : View.VISIBLE);
    }

    private void updateWifiHeader() {
        boolean wifi = NetUtil.isWifi(this);
        boolean only = swWifiOnly != null && swWifiOnly.isChecked();
        if (!only) {
            tvWifi.setText(R.string.wifi_any);
        } else {
            tvWifi.setText(wifi ? R.string.wifi_on : R.string.wifi_off);
        }
    }

    private void setupCooldownSpinner() {
        List<String> labels = new ArrayList<>();
        for (int h : Prefs.COOLDOWN_OPTIONS) {
            labels.add(getString(R.string.cooldown_unit, h));
        }
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCooldown.setAdapter(ad);

        int cur = Prefs.coolDownHours(this);
        int idx = 0;
        for (int i = 0; i < Prefs.COOLDOWN_OPTIONS.length; i++) {
            if (Prefs.COOLDOWN_OPTIONS[i] == cur) {
                idx = i;
                break;
            }
        }
        spCooldown.setSelection(idx);
    }

    private int selectedCooldown() {
        int i = spCooldown.getSelectedItemPosition();
        if (i < 0 || i >= Prefs.COOLDOWN_OPTIONS.length) return Prefs.DEFAULT_COOLDOWN_HOURS;
        return Prefs.COOLDOWN_OPTIONS[i];
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    // ==================== 刷新 ====================

    private void onEngineChanged() {
        refreshAll();
    }

    private void refreshAll() {
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(engine.tasks().isEmpty() ? View.VISIBLE : View.GONE);

        // 整体状态
        Task running = null;
        boolean waiting = false;
        for (Task t : engine.tasks()) {
            if (t.status == Task.RUNNING && running == null) running = t;
            if (t.status == Task.WAITING_WIFI) waiting = true;
        }
        if (running != null) {
            String txt = getString(R.string.status_syncing, running.name,
                    running.uploaded + running.skipped, running.total);
            int queued = engine.pendingCount();
            if (queued > 0) txt += getString(R.string.status_queue, queued);
            tvOverall.setText(txt);
            if (running.total > 0) {
                pbOverall.setProgress(Math.min(100,
                        (running.uploaded + running.skipped) * 100 / running.total));
            }
        } else if (waiting || engine.pending()) {
            tvOverall.setText(R.string.status_waiting_wifi);
            pbOverall.setProgress(0);
        } else {
            tvOverall.setText(R.string.status_idle);
            pbOverall.setProgress(0);
        }

        // 配置页「下次自动同步」
        if (tvNextSync != null) {
            if (!Prefs.autoRepeat(this)) {
                tvNextSync.setText(R.string.next_sync_off);
            } else {
                long at = Scheduler.nextSyncAt(this);
                if (at <= 0) {
                    tvNextSync.setText(R.string.next_sync_none);
                } else if (at <= System.currentTimeMillis() + 60000L) {
                    tvNextSync.setText(R.string.next_sync_soon);
                } else {
                    tvNextSync.setText(getString(R.string.next_sync, formatTime(at)));
                }
            }
        }
    }

    // ==================== 任务对话框 ====================

    private void showTaskDialog(final Task editing) {
        View v = getLayoutInflater().inflate(R.layout.dialog_task, null);
        final EditText etName = v.findViewById(R.id.etTaskName);
        final TextView tvDir = v.findViewById(R.id.tvDirPath);
        final EditText etRemote = v.findViewById(R.id.etRemotePath);
        Button btnPick = v.findViewById(R.id.btnPickDir);

        final String[] picked = new String[]{editing == null ? null : editing.treeUri};

        if (editing != null) {
            etName.setText(editing.name);
            etRemote.setText(editing.remotePath);
        }
        tvDir.setText(picked[0] == null
                ? getString(R.string.no_dir_selected)
                : dirLabel(Uri.parse(picked[0])));

        btnPick.setOnClickListener(x -> {
            pendingPicked = picked;
            pendingTvDir = tvDir;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                startActivityForResult(Intent.createChooser(i, getString(R.string.btn_pick_dir)),
                        REQ_PICK_DIR);
            } catch (Exception e) {
                Toast.makeText(this, R.string.toast_no_picker, Toast.LENGTH_SHORT).show();
            }
        });

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editing == null ? R.string.dialog_new_task : R.string.dialog_edit_task)
                .setView(v)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.btn_ok, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(btn -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, R.string.toast_need_name, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (picked[0] == null) {
                        Toast.makeText(this, R.string.toast_need_dir, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String remote = etRemote.getText().toString().trim();
                    if (editing == null) {
                        engine.add(new Task(name, picked[0], remote));
                        Toast.makeText(this, R.string.toast_task_added, Toast.LENGTH_SHORT).show();
                    } else {
                        editing.name = name;
                        editing.treeUri = picked[0];
                        editing.remotePath = remote;
                    }
                    engine.onTaskMutated();
                    refreshAll();
                    dialog.dismiss();
                }));

        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_DIR && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            if (pendingPicked != null) pendingPicked[0] = uri.toString();
            if (pendingTvDir != null) pendingTvDir.setText(dirLabel(uri));
            pendingPicked = null;
            pendingTvDir = null;
        }
    }

    private void confirmDelete(final Task t) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.confirm_delete, t.name))
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.btn_delete, (d, w) -> {
                    engine.remove(t);
                    Scheduler.reschedule(this);
                    refreshAll();
                })
                .show();
    }

    private boolean checkReady() {
        if (Prefs.server(this).isEmpty()) {
            Toast.makeText(this, R.string.toast_fill_server, Toast.LENGTH_SHORT).show();
            showTab(false);
            return false;
        }
        return true;
    }

    // ==================== 工具 ====================

    private String dirLabel(Uri treeUri) {
        String key = treeUri.toString();
        String cached = dirLabelCache.get(key);
        if (cached != null) return cached;
        String label = key;
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
            Cursor c = getContentResolver().query(docUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        String n = c.getString(0);
                        if (n != null && !n.isEmpty()) label = n;
                    }
                } finally {
                    c.close();
                }
            } else {
                label = docId;
            }
        } catch (Exception ignored) {
        }
        dirLabelCache.put(key, label);
        return label;
    }

    private String describeCode(int code) {
        if (code < 0) return "无法连接服务器";
        if (code == 401 || code == 403) return "认证失败（HTTP " + code + "）";
        if (code == 404) return "路径不存在（HTTP 404）";
        return "HTTP " + code;
    }

    private static String formatTime(long millis) {
        if (millis <= 0) return "";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    // ==================== 列表适配器 ====================

    private class TaskAdapter extends BaseAdapter {

        private final LayoutInflater inflater = LayoutInflater.from(MainActivity.this);

        @Override
        public int getCount() {
            return engine.tasks().size();
        }

        @Override
        public Object getItem(int position) {
            return engine.tasks().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = inflater.inflate(R.layout.item_task, parent, false);
            }
            final Task t = engine.tasks().get(position);

            TextView tvName = v.findViewById(R.id.tvName);
            Switch swEnabled = v.findViewById(R.id.swEnabled);
            TextView tvLocal = v.findViewById(R.id.tvLocal);
            TextView tvRemote = v.findViewById(R.id.tvRemote);
            TextView tvStatus = v.findViewById(R.id.tvStatus);
            ProgressBar pbTask = v.findViewById(R.id.pbTask);
            TextView tvNext = v.findViewById(R.id.tvNext);
            TextView tvTime = v.findViewById(R.id.tvTime);
            CheckBox cbRepeat = v.findViewById(R.id.cbRepeat);
            Button btnSyncOne = v.findViewById(R.id.btnSyncOne);
            Button btnPause = v.findViewById(R.id.btnPause);
            Button btnDelete = v.findViewById(R.id.btnDelete);

            tvName.setText(t.name);
            tvName.setTextColor(getResources().getColor(
                    t.enabled ? R.color.text_primary : R.color.text_hint));

            String local = t.treeUri;
            try {
                local = dirLabel(Uri.parse(t.treeUri));
            } catch (Exception ignored) {
            }
            tvLocal.setText(getString(R.string.task_local, local));
            tvRemote.setText(getString(R.string.task_remote,
                    t.remotePath.isEmpty() ? "/" : t.remotePath));

            swEnabled.setOnCheckedChangeListener(null);
            swEnabled.setChecked(t.enabled);
            swEnabled.setOnCheckedChangeListener((btn, checked) -> {
                t.enabled = checked;
                engine.onTaskMutated();
                refreshAll();
            });

            cbRepeat.setOnCheckedChangeListener(null);
            cbRepeat.setChecked(t.repeat);
            cbRepeat.setOnCheckedChangeListener((btn, checked) -> {
                t.repeat = checked;
                engine.onTaskMutated();
                refreshAll();
            });

            int statusColor = R.color.text_secondary;
            switch (t.status) {
                case Task.RUNNING:
                    pbTask.setVisibility(View.VISIBLE);
                    pbTask.setProgress(t.total > 0
                            ? Math.min(100, (t.uploaded + t.skipped) * 100 / t.total) : 0);
                    tvStatus.setText(t.currentFile.isEmpty()
                            ? getString(R.string.status_preparing)
                            : getString(R.string.task_uploading, t.currentFile));
                    statusColor = R.color.primary;
                    break;
                case Task.DONE:
                    pbTask.setVisibility(View.VISIBLE);
                    pbTask.setProgress(100);
                    tvStatus.setText(t.lastResult.isEmpty()
                            ? getString(R.string.task_done, t.uploaded, t.skipped)
                            : t.lastResult);
                    statusColor = R.color.success;
                    break;
                case Task.ERROR:
                    pbTask.setVisibility(View.GONE);
                    tvStatus.setText(getString(R.string.task_error, t.errorMessage));
                    statusColor = R.color.error;
                    break;
                case Task.WAITING_WIFI:
                    pbTask.setVisibility(View.GONE);
                    tvStatus.setText(R.string.task_waiting_wifi);
                    statusColor = R.color.warning;
                    break;
                default:
                    pbTask.setVisibility(View.GONE);
                    tvStatus.setText(R.string.task_idle);
                    statusColor = R.color.text_secondary;
                    break;
            }
            if (t.paused && t.status != Task.RUNNING) {
                tvStatus.setText(R.string.task_paused);
                statusColor = R.color.warning;
                pbTask.setVisibility(View.GONE);
            }
            tvStatus.setTextColor(getResources().getColor(statusColor));

            // 下次自动同步时间
            if (t.paused || !Prefs.autoRepeat(MainActivity.this) || !t.repeat || !t.enabled) {
                tvNext.setText(R.string.next_sync_off);
            } else {
                long at = t.nextSyncAt(Prefs.coolDownMillis(MainActivity.this));
                if (at <= System.currentTimeMillis()) {
                    tvNext.setText(R.string.next_sync_soon);
                } else {
                    tvNext.setText(getString(R.string.next_sync, formatTime(at)));
                }
            }

            String time = formatTime(t.lastSync);
            tvTime.setText(time.isEmpty()
                    ? getString(R.string.task_never)
                    : getString(R.string.task_last_sync, time));

            btnSyncOne.setOnClickListener(btn -> {
                if (!checkReady()) return;
                if (!t.enabled) {
                    Toast.makeText(MainActivity.this, R.string.toast_task_disabled,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                engine.enqueue(t);
                refreshAll();
            });

            btnPause.setText(t.paused ? R.string.btn_resume : R.string.btn_pause);
            btnPause.setTextColor(getResources().getColor(
                    t.paused ? R.color.primary : R.color.text_secondary));
            btnPause.setOnClickListener(btn -> {
                engine.setPaused(t, !t.paused);
                Toast.makeText(MainActivity.this,
                        t.paused ? getString(R.string.toast_paused, t.name)
                                 : getString(R.string.toast_resumed, t.name),
                        Toast.LENGTH_SHORT).show();
                refreshAll();
            });

            btnDelete.setOnClickListener(btn -> confirmDelete(t));

            return v;
        }
    }
}
