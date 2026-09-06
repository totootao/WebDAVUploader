package com.totootao.webdavuploader;

import android.net.Uri;

/**
 * 单个上传任务的状态模型。
 */
public class UploadItem {
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_UPLOADING = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_ERROR = 3;

    public final String name;
    public final Uri uri;
    public long total;
    public long uploaded;
    public int status = STATUS_PENDING;
    public String message = "";

    public UploadItem(String name, Uri uri, long total) {
        this.name = name;
        this.uri = uri;
        this.total = total;
    }
}
