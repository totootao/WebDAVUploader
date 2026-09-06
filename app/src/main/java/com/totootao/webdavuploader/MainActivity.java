package com.totootao.webdavuploader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主界面：两个标签页 —— 「任务」（多目录同步任务）与「配置」（WebDAV 登录 + 同步设置）。
 * <p>
 * 同步规则：本地目录 → 远程目录，单向上传（不下载、不删除远端）；
 * 仅在 WiFi 下传输；远端同名同大小文件自动跳过。
 * </p>
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_DIR = 2001;

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
    private Switch swWifiOnly;
    private Button btnTest, btnSave;

    private final List<Task> tasks = new ArrayList<>();
    private TaskAdapter adapter;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService syncExec = Executors.newSingleThreadExecutor();
    private final ExecutorService miscExec = Executors.newCachedThreadPool();

    /** 待同步任务 id 队列 */
    private final List<String> queue = new ArrayList<>();
    private final AtomicBoolean workerBusy = new AtomicBoolean(false);

    /** 目录名缓存，避免列表滚动时反复查询 */
    private final Map<String, String> dirLabelCache = new HashMap<>();

    // 目录选择对话框的临时状态
    private String[] pendingPicked;
    private TextView pendingTvDir;

    private final BroadcastReceiver netReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            updateWifiHeader();
            boolean pending;
            synchronized (queue) {
                pending = !queue.isEmpty();
            }
            if (pending && NetUtil.isWifi(c)) {
                pumpQueue();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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
        btnTest = findViewById(R.id.btnTest);
        btnSave = findViewById(R.id.btnSave);

        adapter = new TaskAdapter();
        lvTasks.setAdapter(adapter);

        tasks.addAll(Prefs.loadTasks(this));

        // ---- 标签页 ----
        tabTasks.setOnClickListener(v -> showTab(true));
        tabConfig.setOnClickListener(v -> showTab(false));
        showTab(true);

        // ---- 任务页 ----
        btnNewTask.setOnClickListener(v -> showTaskDialog(null));
        btnSyncAll.setOnClickListener(v -> {
            if (!checkReady()) return;
            int n = 0;
            for (Task t : tasks) {
                if (t.enabled && !t.isBusy()) {
                    enqueue(t);
                    n++;
                }
            }
            if (n == 0) {
                Toast.makeText(this, R.string.toast_no_enabled_task, Toast.LENGTH_SHORT).show();
            }
        });

        // ---- 配置页 ----
        etServer.setText(Prefs.server(this));
        etUser.setText(Prefs.user(this));
        etPass.setText(Prefs.pass(this));
        cbInsecure.setChecked(Prefs.insecure(this));
        swWifiOnly.setChecked(Prefs.wifiOnly(this));

        btnSave.setOnClickListener(v -> {
            Prefs.saveConfig(this,
                    etServer.getText().toString().trim(),
                    etUser.getText().toString().trim(),
                    etPass.getText().toString(),
                    cbInsecure.isChecked(),
                    swWifiOnly.isChecked());
            updateWifiHeader();
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
                ui.post(() -> {
                    btnTest.setEnabled(true);
                    btnTest.setText(R.string.btn_test);
                    if (code >= 200 && code < 400) {
                        Toast.makeText(MainActivity.this, R.string.test_ok, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this,
                                getString(R.string.test_fail, describeCode(code)),
                                Toast.LENGTH_LONG).show();
                    }
                });
            });
        });

        updateWifiHeader();
        refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(netReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        updateWifiHeader();
        boolean pending;
        synchronized (queue) {
            pending = !queue.isEmpty();
        }
        if (pending && NetUtil.isWifi(this)) pumpQueue();
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(netReceiver);
        } catch (Exception ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        syncExec.shutdownNow();
        miscExec.shutdownNow();
        super.onDestroy();
    }

    // ==================== 界面切换 ====================

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
                        tasks.add(new Task(name, picked[0], remote));
                        Toast.makeText(this, R.string.toast_task_added, Toast.LENGTH_SHORT).show();
                    } else {
                        editing.name = name;
                        editing.treeUri = picked[0];
                        editing.remotePath = remote;
                    }
                    persist();
                    refreshList();
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
                    synchronized (queue) {
                        queue.remove(t.id);
                    }
                    tasks.remove(t);
                    persist();
                    refreshList();
                })
                .show();
    }

    // ==================== 同步调度 ====================

    private boolean checkReady() {
        if (Prefs.server(this).isEmpty()) {
            Toast.makeText(this, R.string.toast_fill_server, Toast.LENGTH_SHORT).show();
            showTab(false);
            return false;
        }
        return true;
    }

    private void enqueue(Task t) {
        synchronized (queue) {
            if (!queue.contains(t.id)) queue.add(t.id);
        }
        pumpQueue();
    }

    private void pumpQueue() {
        boolean pending;
        synchronized (queue) {
            pending = !queue.isEmpty();
        }
        if (!pending) return;

        if (!Prefs.wifiOnly(this) || NetUtil.isWifi(this)) {
            if (workerBusy.compareAndSet(false, true)) {
                try {
                    syncExec.execute(this::runQueue);
                } catch (Exception e) {
                    workerBusy.set(false);
                }
            }
        } else {
            for (Task t : tasks) {
                if (t.status != Task.RUNNING && queueSnapshot().contains(t.id)) {
                    t.status = Task.WAITING_WIFI;
                }
            }
            refreshList();
            tvOverall.setText(R.string.status_waiting_wifi);
        }
    }

    private List<String> queueSnapshot() {
        synchronized (queue) {
            return new ArrayList<>(queue);
        }
    }

    private Task findTask(String id) {
        for (Task t : tasks) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }

    private void runQueue() {
        int up = 0, skip = 0;
        boolean err = false, waiting = false;

        try {
            while (true) {
                String id;
                synchronized (queue) {
                    if (queue.isEmpty()) break;
                    id = queue.get(0);
                }
                Task t = findTask(id);
                if (t == null || !t.enabled) {
                    synchronized (queue) {
                        queue.remove(id);
                    }
                    continue;
                }
                if (Prefs.wifiOnly(this) && !NetUtil.isWifi(this)) {
                    waiting = true;
                    t.status = Task.WAITING_WIFI;
                    ui.post(this::refreshList);
                    break;
                }

                Result r = syncOne(t);
                if (r == null) {
                    waiting = true;
                    break;
                }
                up += r.uploaded;
                skip += r.skipped;
                err |= r.failed;
                synchronized (queue) {
                    if (!queue.isEmpty() && id.equals(queue.get(0))) queue.remove(0);
                }
            }
        } finally {
            workerBusy.set(false);
            final int fu = up, fs = skip;
            final boolean fe = err, fw = waiting;
            ui.post(() -> {
                boolean pending;
                synchronized (queue) {
                    pending = !queue.isEmpty();
                }
                refreshList();
                if (fw || pending) {
                    tvOverall.setText(R.string.status_waiting_wifi);
                    pbOverall.setProgress(0);
                } else if (fe) {
                    tvOverall.setText(getString(R.string.status_all_done_part, fu, fs));
                    pbOverall.setProgress(100);
                } else {
                    tvOverall.setText(getString(R.string.status_all_done, fu, fs));
                    pbOverall.setProgress(100);
                }
            });
        }
    }

    /** 同步单个任务（后台线程）。返回 null 表示中途因失去 WiFi 而中止。 */
    private Result syncOne(Task t) {
        final String server = Prefs.server(this);
        final String user = Prefs.user(this);
        final String pass = Prefs.pass(this);
        final boolean insecure = Prefs.insecure(this);

        t.status = Task.RUNNING;
        t.uploaded = 0;
        t.skipped = 0;
        t.total = 0;
        t.currentPct = 0;
        t.currentFile = "";
        t.errorMessage = "";
        ui.post(() -> {
            tvOverall.setText(R.string.status_preparing);
            pbOverall.setProgress(0);
            refreshList();
        });

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
            files = DocsTree.walk(this, tree);
        } catch (Exception e) {
            return fail(t, "读取目录失败：" + e.getMessage());
        }
        t.total = files.size();
        if (files.isEmpty()) {
            t.status = Task.DONE;
            t.lastSync = System.currentTimeMillis();
            t.lastResult = getString(R.string.task_empty_dir);
            persist();
            return new Result(0, 0, false);
        }

        for (DocsTree.Entry e : files) {
            if (Prefs.wifiOnly(this) && !NetUtil.isWifi(this)) {
                t.status = Task.WAITING_WIFI;
                return null;
            }
            t.currentFile = e.relPath();
            t.currentPct = 0;
            ui.post(() -> {
                tvOverall.setText(getString(R.string.status_syncing, t.name,
                        t.uploaded + t.skipped, t.total));
                if (t.total > 0) {
                    pbOverall.setProgress(Math.min(100,
                            (t.uploaded + t.skipped) * 100 / t.total));
                }
                refreshList();
            });

            try {
                String url = WebDavClient.buildPutUrlPath(server, t.remotePath, e.relPath());

                // 单向上传的增量判断：远端已存在且大小一致则跳过
                if (e.size > 0) {
                    long remote = WebDavClient.headSize(url, user, pass, insecure);
                    if (remote == e.size) {
                        t.skipped++;
                        continue;
                    }
                }

                WebDavClient.ensureParentDirs(url, user, pass, insecure);

                try (InputStream in = DocsTree.open(this, tree, e.docId)) {
                    if (in == null) throw new IOException("无法打开文件");
                    final long[] lastPost = {0};
                    int code = WebDavClient.putFile(url, in, e.size, user, pass, insecure, sent -> {
                        if (e.size > 0) {
                            t.currentPct = (int) (sent * 100 / e.size);
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastPost[0] >= 300) {
                            lastPost[0] = now;
                            ui.post(MainActivity.this::refreshList);
                        }
                    });
                    if (code < 200 || code >= 300) {
                        throw new IOException("HTTP " + code);
                    }
                }
                t.uploaded++;
                t.currentPct = 100;
                ui.post(this::refreshList);
            } catch (Exception ex) {
                String msg = ex.getMessage();
                return fail(t, (msg == null || msg.isEmpty()) ? ex.toString() : msg);
            }
        }

        t.status = Task.DONE;
        t.lastSync = System.currentTimeMillis();
        t.lastResult = getString(R.string.task_done, t.uploaded, t.skipped);
        persist();
        return new Result(t.uploaded, t.skipped, false);
    }

    private Result fail(Task t, String msg) {
        t.status = Task.ERROR;
        t.errorMessage = msg;
        persist();
        return new Result(t.uploaded, t.skipped, true);
    }

    private boolean hasUriPermission(Uri u) {
        try {
            for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(u)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void persist() {
        Prefs.saveTasks(this, tasks);
    }

    private void refreshList() {
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(tasks.isEmpty() ? View.VISIBLE : View.GONE);
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
            return tasks.size();
        }

        @Override
        public Object getItem(int position) {
            return tasks.get(position);
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
            final Task t = tasks.get(position);

            TextView tvName = v.findViewById(R.id.tvName);
            Switch swEnabled = v.findViewById(R.id.swEnabled);
            TextView tvLocal = v.findViewById(R.id.tvLocal);
            TextView tvRemote = v.findViewById(R.id.tvRemote);
            TextView tvStatus = v.findViewById(R.id.tvStatus);
            ProgressBar pbTask = v.findViewById(R.id.pbTask);
            TextView tvTime = v.findViewById(R.id.tvTime);
            Button btnSyncOne = v.findViewById(R.id.btnSyncOne);
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
                persist();
                if (!checked) {
                    synchronized (queue) {
                        queue.remove(t.id);
                    }
                }
                refreshList();
            });

            int statusColor = R.color.text_secondary;
            switch (t.status) {
                case Task.RUNNING:
                    pbTask.setVisibility(View.VISIBLE);
                    if (t.total > 0) {
                        pbTask.setProgress(Math.min(100, (t.uploaded + t.skipped) * 100 / t.total));
                    } else {
                        pbTask.setProgress(0);
                    }
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
            tvStatus.setTextColor(getResources().getColor(statusColor));

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
                enqueue(t);
                refreshList();
            });
            btnDelete.setOnClickListener(btn -> confirmDelete(t));

            return v;
        }
    }

    private static class Result {
        final int uploaded;
        final int skipped;
        final boolean failed;

        Result(int uploaded, int skipped, boolean failed) {
            this.uploaded = uploaded;
            this.skipped = skipped;
            this.failed = failed;
        }
    }
}
