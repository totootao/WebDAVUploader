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
        return buildPutUrlPath(server, remoteDir, fileName);
    }

    /**
     * 同 {@link #buildPutUrl}，但 relPath 允许包含子目录（如 "a/b/c.jpg"），
     * 每一段都会单独做 URL 编码，"/" 作为层级分隔符保留。
     */
    public static String buildPutUrlPath(String server, String remoteDir, String relPath) {
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
        if (relPath != null) {
            for (String seg : relPath.split("/")) {
                if (!seg.isEmpty()) {
                    sb.append(encodeSegment(seg)).append("/");
                }
            }
            // 去掉最后一个 "/"（若 relPath 非空）
            int len = sb.length();
            if (len > server.length() && sb.charAt(len - 1) == '/') {
                sb.deleteCharAt(len - 1);
            }
        }
        return sb.toString();
    }

    /**
     * 探测远程文件大小（HEAD）。返回 >=0 表示远端已存在且拿到长度；
     * -1 表示不存在、不支持 HEAD 或长度未知（调用方应重新上传）。
     */
    public static long headSize(String fileUrl, String user, String pass, boolean insecure) {
        try {
            HttpURLConnection conn = openConnection(fileUrl, basicAuth(user, pass), insecure);
            try {
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) return -1;
                String cl = conn.getHeaderField("Content-Length");
                if (cl == null) return -1;
                return Long.parseLong(cl.trim());
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 测试服务器连通性：优先 PROPFIND(Depth 0) 期望 207，失败回退 HEAD。
     * 两者都用 RawHttp（HttpURLConnection 不支持 PROPFIND）。
     * 返回 HTTP 状态码（未知异常返回 -1）。
     */
    public static int probe(String server, String user, String pass, boolean insecure) {
        String url = server == null ? "" : server.trim();
        if (url.isEmpty()) return -1;
        if (!url.endsWith("/")) url += "/";
        String auth = basicAuth(user, pass);

        // 1) PROPFIND（标准 WebDAV）
        try {
            int code = RawHttp.request("PROPFIND", url, auth, insecure,
                    "<D:propfind xmlns:D=\"DAV:\"><D:resourcetype/></D:propfind>"
                            .getBytes(StandardCharsets.UTF_8));
            if (code != 405 && code != 501) return code;
        } catch (Exception ignored) {
        }

        // 2) 回退 HEAD
        try {
            return RawHttp.request("HEAD", url, auth, insecure, null);
        } catch (Exception ignored) {
        }
        return -1;
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

    private static void mkcol(String dirUrl, String auth, boolean insecure) {
        // Android 的 HttpURLConnection 不允许 MKCOL 方法（方法白名单限制），
        // 必须走 RawHttp 原生 Socket 通道。
        try {
            int code = RawHttp.request("MKCOL", dirUrl, auth, insecure, null);
            // 201 已创建；405/409 已存在；2xx 视为成功；其余（如 301/403）忽略，
            // 目录是否真正可用由后续 PUT 决定。
            if (code < 200 || code >= 300) {
                Log.d(TAG, "MKCOL " + dirUrl + " -> " + code + " (忽略)");
            }
        } catch (Exception e) {
            // 单层 MKCOL 失败不中断任务，让 PUT 最终判定
            Log.d(TAG, "MKCOL " + dirUrl + " -> " + e.getMessage());
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
