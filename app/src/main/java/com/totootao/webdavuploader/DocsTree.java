package com.totootao.webdavuploader;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 通过系统 SAF（Storage Access Framework）递归遍历用户授权的目录树。
 * 直接使用框架 DocumentsContract，不依赖 AndroidX。
 */
public class DocsTree {

    /** 目录下的一个文件条目。 */
    public static class Entry {
        public final String docId;
        public final String name;
        /** 相对目录，如 "sub/dir"，根目录为 "" */
        public final String relDir;
        public final long size;

        Entry(String docId, String name, String relDir, long size) {
            this.docId = docId;
            this.name = name;
            this.relDir = relDir;
            this.size = size;
        }

        /** 相对路径（含文件名） */
        public String relPath() {
            return relDir.isEmpty() ? name : relDir + "/" + name;
        }
    }

    private static final int MAX_DEPTH = 24;

    public static List<Entry> walk(Context ctx, Uri treeUri) throws Exception {
        List<Entry> out = new ArrayList<>();
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        walkDir(ctx, treeUri, rootId, "", out, 0);
        return out;
    }

    private static void walkDir(Context ctx, Uri treeUri, String docId, String relDir,
                                List<Entry> out, int depth) {
        if (depth > MAX_DEPTH) return;
        ContentResolver cr = ctx.getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        Cursor c = null;
        try {
            c = cr.query(children, cols, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.getLong(3);
                if (id == null || name == null) continue;

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    String sub = relDir.isEmpty() ? name : relDir + "/" + name;
                    walkDir(ctx, treeUri, id, sub, out, depth + 1);
                } else {
                    out.add(new Entry(id, name, relDir, size));
                }
            }
        } finally {
            if (c != null) c.close();
        }
    }

    public static InputStream open(Context ctx, Uri treeUri, String docId) throws Exception {
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        return ctx.getContentResolver().openInputStream(uri);
    }
}
