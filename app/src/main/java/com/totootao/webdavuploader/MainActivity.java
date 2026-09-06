package com.totootao.webdavuploader;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;
    private static final String PREFS = "webdav_cfg";

    private EditText etServer, etUser, etPass, etRemote;
    private CheckBox cbInsecure;
    private Button btnSave, btnPick;
    private ProgressBar pb;
    private TextView tvStatus;
    private ListView lvFiles;

    private final List<UploadItem> items = new ArrayList<>();
    private UploadAdapter adapter;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etServer = findViewById(R.id.etServer);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        etRemote = findViewById(R.id.etRemote);
        cbInsecure = findViewById(R.id.cbInsecure);
        btnSave = findViewById(R.id.btnSave);
        btnPick = findViewById(R.id.btnPick);
        pb = findViewById(R.id.pb);
        tvStatus = findViewById(R.id.tvStatus);
        lvFiles = findViewById(R.id.lvFiles);

        adapter = new UploadAdapter(this, items);
        lvFiles.setAdapter(adapter);

        loadConfig();

        btnSave.setOnClickListener(v -> {
            saveConfig();
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
        });

        btnPick.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQ_PICK);
        });
    }

    private void loadConfig() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        etServer.setText(sp.getString("server", ""));
        etUser.setText(sp.getString("user", ""));
        etPass.setText(sp.getString("pass", ""));
        etRemote.setText(sp.getString("remote", ""));
        cbInsecure.setChecked(sp.getBoolean("insecure", false));
    }

    private void saveConfig() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        sp.edit()
                .putString("server", etServer.getText().toString().trim())
                .putString("user", etUser.getText().toString().trim())
                .putString("pass", etPass.getText().toString())
                .putString("remote", etRemote.getText().toString().trim())
                .putBoolean("insecure", cbInsecure.isChecked())
                .apply();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                Uri u = data.getClipData().getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_file, Toast.LENGTH_SHORT).show();
            return;
        }

        for (Uri uri : uris) {
            String name = queryName(uri);
            long size = querySize(uri);
            UploadItem item = new UploadItem(name, uri, size);
            items.add(item);
        }
        adapter.notifyDataSetChanged();
        startQueue();
    }

    private String queryName(Uri uri) {
        String name = "file";
        ContentResolver cr = getContentResolver();
        Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (c != null) {
            if (c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) name = n;
            }
            c.close();
        }
        return name;
    }

    private long querySize(Uri uri) {
        long size = -1;
        ContentResolver cr = getContentResolver();
        Cursor c = cr.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
        if (c != null) {
            if (c.moveToFirst()) size = c.getLong(0);
            c.close();
        }
        if (size < 0) {
            try {
                ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
                if (pfd != null) {
                    size = pfd.getStatSize();
                    pfd.close();
                }
            } catch (Exception ignored) {
            }
        }
        return size;
    }

    private void startQueue() {
        if (running) return;
        running = true;
        executor.execute(this::processQueue);
    }

    private void processQueue() {
        String server = etServer.getText().toString().trim();
        String user = etUser.getText().toString().trim();
        String pass = etPass.getText().toString();
        String remote = etRemote.getText().toString().trim();
        boolean insecure = cbInsecure.isChecked();

        if (server.isEmpty()) {
            ui.post(() -> {
                Toast.makeText(MainActivity.this, R.string.toast_fill, Toast.LENGTH_SHORT).show();
                running = false;
            });
            return;
        }

        for (UploadItem item : items) {
            if (item.status == UploadItem.STATUS_DONE || item.status == UploadItem.STATUS_ERROR) {
                // 已完成/失败的不再重复（重新上传请清除列表）
                continue;
            }
            uploadOne(item, server, user, pass, remote, insecure);
        }

        ui.post(() -> {
            running = false;
            tvStatus.setText(R.string.status_idle);
            pb.setProgress(0);
        });
    }

    private void uploadOne(UploadItem item, String server, String user, String pass,
                           String remote, boolean insecure) {
        ui.post(() -> {
            item.status = UploadItem.STATUS_UPLOADING;
            item.uploaded = 0;
            item.message = "";
            adapter.notifyDataSetChanged();
            tvStatus.setText(getString(R.string.status_prepare));
        });

        try {
            Uri uri = item.uri;
            if (uri == null) throw new Exception("找不到文件 URI");

            String url = WebDavClient.buildPutUrl(server, remote, item.name);
            WebDavClient.ensureParentDirs(url, user, pass, insecure);

            ContentResolver cr = getContentResolver();
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) throw new Exception("无法打开输入流");
                final long[] lastUi = {0};
                int code = WebDavClient.putFile(url, in, item.total, user, pass, insecure,
                        sent -> {
                            item.uploaded = sent;
                            long step = Math.max(65536, (item.total > 0 ? item.total / 100 : 65536));
                            if (sent - lastUi[0] >= step || (item.total > 0 && sent >= item.total)) {
                                lastUi[0] = sent;
                                postProgress(item);
                            }
                        });

                if (code >= 200 && code < 300) {
                    item.status = UploadItem.STATUS_DONE;
                    item.message = "HTTP " + code;
                } else {
                    item.status = UploadItem.STATUS_ERROR;
                    item.message = "HTTP " + code;
                }
            }
        } catch (Exception e) {
            item.status = UploadItem.STATUS_ERROR;
            item.message = e.getMessage() == null ? "error" : e.getMessage();
        }

        ui.post(() -> {
            adapter.notifyDataSetChanged();
            if (item.status == UploadItem.STATUS_DONE) {
                tvStatus.setText(getString(R.string.status_done, item.name));
            } else {
                tvStatus.setText(getString(R.string.status_error, item.name, item.message));
            }
        });
    }

    private void postProgress(UploadItem item) {
        ui.post(() -> {
            adapter.notifyDataSetChanged();
            if (item.total > 0) {
                int pct = (int) (item.uploaded * 100 / item.total);
                pb.setProgress(Math.min(100, pct));
                tvStatus.setText(getString(R.string.status_uploading, item.name, item.uploaded, item.total));
            } else {
                tvStatus.setText(getString(R.string.status_uploading, item.name, item.uploaded, 0));
            }
        });
    }

    // ---- 列表适配器 ----
    private static class UploadAdapter extends ArrayAdapter<UploadItem> {
        private final LayoutInflater inflater;

        UploadAdapter(Context ctx, List<UploadItem> list) {
            super(ctx, 0, list);
            inflater = LayoutInflater.from(ctx);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            UploadItem it = getItem(position);
            TextView tv1 = convertView.findViewById(android.R.id.text1);
            TextView tv2 = convertView.findViewById(android.R.id.text2);
            if (it == null) return convertView;

            tv1.setText(it.name);
            String statusText;
            switch (it.status) {
                case UploadItem.STATUS_PENDING:
                    statusText = "等待中";
                    break;
                case UploadItem.STATUS_UPLOADING:
                    if (it.total > 0) {
                        int pct = (int) (it.uploaded * 100 / it.total);
                        statusText = "上传中 " + pct + "%  (" + human(it.uploaded) + "/" + human(it.total) + ")";
                    } else {
                        statusText = "上传中 " + human(it.uploaded);
                    }
                    break;
                case UploadItem.STATUS_DONE:
                    statusText = "✅ 完成 " + it.message;
                    break;
                case UploadItem.STATUS_ERROR:
                    statusText = "❌ 失败 " + it.message;
                    break;
                default:
                    statusText = "";
            }
            tv2.setText(statusText);
            return convertView;
        }

        private static String human(long b) {
            if (b < 1024) return b + " B";
            if (b < 1024 * 1024) return (b / 1024) + " KB";
            return (b / 1024 / 1024) + " MB";
        }
    }
}
