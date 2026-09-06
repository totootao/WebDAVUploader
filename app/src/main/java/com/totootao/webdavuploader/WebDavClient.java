package com.totootao.webdavuploader;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 轻量级 WebDAV 客户端：仅依赖标准 HttpURLConnection，支持 Basic 认证、
 * 自动创建远程目录（MKCOL）与文件上传（PUT），并可选择忽略自签名证书。
 */
public class WebDavClient {

    private static final String TAG = "WebDavClient";

    /** 上传进度回调，bytes 为已发送字节数。 */
    public interface ProgressListener {
        void onProgress(long bytes);
    }

    /** 组合出最终 PUT 地址：server + remoteDir + fileName，并对各路径段做 URL 编码。 */
    public static String buildPutUrl(String server, String remoteDir, String fileName) {
        if (server == null) server = "";
        server = server.trim();
        if (!server.endsWith("/")) server += "/";

        remoteDir = (remoteDir == null ? "" : remoteDir.trim());
        // 去掉 remoteDir 两端多余斜杠
        while (remoteDir.startsWith("/")) remoteDir = remoteDir.substring(1);
        while (remoteDir.endsWith("/")) remoteDir = remoteDir.substring(0, remoteDir.length() - 1);

        StringBuilder sb = new StringBuilder(server);
        if (!remoteDir.isEmpty()) {
            for (String seg : remoteDir.split("/")) {
                if (!seg.isEmpty()) {
                    sb.append(encodeSegment(seg)).append("/");
                }
            }
        }
        sb.append(encodeSegment(fileName));
        return sb.toString();
    }

    private static String encodeSegment(String seg) {
        try {
            return URLEncoder.encode(seg, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (Exception e) {
            return seg;
        }
    }

    private static String basicAuth(String user, String pass) {
        if (user == null || user.isEmpty()) return null;
        String cred = user + ":" + (pass == null ? "" : pass);
        String enc = android.util.Base64.encodeToString(
                cred.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        return "Basic " + enc;
    }

    /**
     * 确保文件 URL 的所有父级目录存在（逐层 MKCOL，已存在则忽略）。
     */
    public static boolean ensureParentDirs(String fileUrl, String user, String pass, boolean insecure)
            throws IOException {
        String auth = basicAuth(user, pass);
        // 取文件 URL 的父目录
        int lastSlash = fileUrl.lastIndexOf('/');
        if (lastSlash <= 0) return true;
        String parent = fileUrl.substring(0, lastSlash + 1); // 带结尾 '/'

        // 拆分协议与路径
        int protoEnd = parent.indexOf("://");
        if (protoEnd < 0) return true;
        int hostEnd = parent.indexOf('/', protoEnd + 3);
        if (hostEnd < 0) return true;
        String base = parent.substring(0, hostEnd);
        String path = parent.substring(hostEnd); // 以 '/' 开头，结尾 '/'

        String[] segs = path.split("/");
        StringBuilder cur = new StringBuilder(base);
        // segs[0] 为空（开头斜杠），最后一段为空（结尾斜杠）
        for (int i = 1; i < segs.length - 1; i++) {
            cur.append("/").append(segs[i]);
            mkcol(cur.toString() + "/", auth, insecure);
        }
        return true;
    }

    private static void mkcol(String dirUrl, String auth, boolean insecure) throws IOException {
        HttpURLConnection conn = openConnection(dirUrl, auth, insecure);
        try {
            conn.setRequestMethod("MKCOL");
            conn.setDoOutput(false);
            conn.connect();
            int code = conn.getResponseCode();
            // 201 已创建；405/409 已存在；2xx 视为成功
            if (code != HttpURLConnection.HTTP_CREATED
                    && code != 405
                    && code != 409
                    && (code < 200 || code >= 300)) {
                Log.d(TAG, "MKCOL " + dirUrl + " -> " + code + " (忽略)");
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 上传输入流到指定 URL。返回 HTTP 响应码（2xx 视为成功）。
     */
    public static int putFile(String fileUrl, InputStream data, long length,
                              String user, String pass, boolean insecure, ProgressListener listener)
            throws IOException {
        String auth = basicAuth(user, pass);
        HttpURLConnection conn = openConnection(fileUrl, auth, insecure);
        try {
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(true);
            if (length >= 0) {
                conn.setFixedLengthStreamingMode(length);
            } else {
                conn.setChunkedStreamingMode(8192);
            }
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.connect();

            OutputStream out = conn.getOutputStream();
            byte[] buf = new byte[8192];
            long sent = 0;
            int n;
            while ((n = data.read(buf)) > 0) {
                out.write(buf, 0, n);
                sent += n;
                if (listener != null) listener.onProgress(sent);
            }
            out.flush();
            out.close();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                // 读取错误流便于排查
                try {
                    InputStream err = conn.getErrorStream();
                    if (err != null) {
                        byte[] e = new byte[512];
                        int r = err.read(e);
                        if (r > 0) Log.d(TAG, "PUT error body: " + new String(e, 0, r, StandardCharsets.UTF_8));
                        err.close();
                    }
                } catch (Exception ignored) {
                }
            }
            return code;
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String urlStr, String auth, boolean insecure)
            throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (insecure && conn instanceof HttpsURLConnection) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                }, new SecureRandom());
                SSLSocketFactory sf = ctx.getSocketFactory();
                ((HttpsURLConnection) conn).setSSLSocketFactory(sf);
                ((HttpsURLConnection) conn).setHostnameVerifier((hostname, session) -> true);
            } catch (Exception e) {
                Log.w(TAG, " insecure ssl setup failed, continue: " + e.getMessage());
            }
        }
        if (auth != null) {
            conn.setRequestProperty("Authorization", auth);
        }
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        return conn;
    }
}
