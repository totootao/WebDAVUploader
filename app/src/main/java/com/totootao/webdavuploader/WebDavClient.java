package com.totootao.webdavuploader;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * <p>
     * 注意：不能依赖 {@code String.split("/")} 末尾空串来做边界判断——Java 的 split
     * 会丢弃末尾空字符串（"/a/b/".split("/") 得到 ["", "a", "b"]），此前按
     * {@code i < segs.length - 1} 遍历会导致：单层目录一次 MKCOL 都不发、
     * 多层目录漏建最深一层，最终 PUT 全部 409（远端永远为空 → 每轮重复上传）。
     * 这里改为遍历所有非空路径段。
     *
     * @param created 已确认创建过的目录 URL 集合（可为 null）。传入同一个集合可让
     *                同一轮同步内每个目录只 MKCOL 一次，显著减少请求数。
     */
    public static boolean ensureParentDirs(String fileUrl, Set<String> created,
                                           String user, String pass, boolean insecure)
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
        for (String seg : segs) {
            if (seg == null || seg.isEmpty()) continue;
            cur.append("/").append(seg);
            String dirUrl = cur.toString() + "/";
            if (created != null) {
                if (!created.add(dirUrl)) continue; // 本轮已创建过，跳过
            }
            mkcol(dirUrl, auth, insecure);
        }
        return true;
    }

    /** 兼容旧签名：不复用已创建目录缓存。 */
    public static boolean ensureParentDirs(String fileUrl, String user, String pass, boolean insecure)
            throws IOException {
        return ensureParentDirs(fileUrl, null, user, pass, insecure);
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

    /**
     * 列出某远程目录下的直接子项（PROPFIND Depth:1），返回 文件名 → 大小 的映射。
     * 文件名已做 URL 解码，可与本地 DocumentsContract 名称直接比较；目录（collection）不计入。
     * <p>
     * 返回值三种语义，调用方务必区分：
     * <ul>
     *   <li>非空/空 Map：请求成功。空 Map 表示该目录还不存在或为空，其下文件都是新增。</li>
     *   <li><b>null</b>：请求失败（网络异常、5xx、403 等）。此时<b>不能</b>当作「远端为空」，
     *       否则会把全部文件重新上传一遍——必须中止本轮同步。</li>
     * </ul>
     */
    public static Map<String, Long> listDir(String server, String remotePath, String relDir,
                                            String user, String pass, boolean insecure) {
        String auth = basicAuth(user, pass);
        String dirUrl = buildDirUrl(server, remotePath, relDir);
        try {
            String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<D:propfind xmlns:D=\"DAV:\"><D:prop>"
                    + "<D:resourcetype/><D:getcontentlength/></D:prop></D:propfind>";
            RawHttp.Response resp = RawHttp.requestBody("PROPFIND", dirUrl, auth, insecure,
                    body.getBytes(StandardCharsets.UTF_8));
            if (resp.code == 404 || resp.code == 410) {
                // 目录确实不存在：其下文件都是新增，返回空 Map 是正确的
                return new HashMap<>();
            }
            if (resp.code < 200 || resp.code >= 300) {
                // 其它错误（403/405/500/网络层异常）：无法确认远端状态 → 通知调用方中止
                Log.w(TAG, "PROPFIND 失败 " + dirUrl + " -> HTTP " + resp.code);
                return null;
            }
            if (resp.body == null || resp.body.isEmpty()) {
                Log.w(TAG, "PROPFIND 响应体为空 " + dirUrl);
                return null;
            }
            return parsePropfind(dirUrl, resp.body);
        } catch (Exception e) {
            Log.w(TAG, "PROPFIND 异常 " + dirUrl + " -> " + e.getMessage());
            return null;
        }
    }

    /**
     * 解析 PROPFIND Depth:1 的响应体，返回该目录下直接子项的 文件名 → 大小 映射。
     * 跳过自身（href 与 dirUrl 一致的那个 response）。
     */
    private static Map<String, Long> parsePropfind(String dirUrl, String xml) {
        Map<String, Long> map = new HashMap<>();
        if (xml == null) return map;
        String normSelf = stripTrailingSlash(pathOf(decode(dirUrl)));
        Pattern respP = Pattern.compile("(?is)<(?:[A-Za-z0-9]+:)?response>(.*?)</(?:[A-Za-z0-9]+:)?response>");
        Pattern hrefP = Pattern.compile("(?i)<(?:[A-Za-z0-9]+:)?href>([^<]*)</(?:[A-Za-z0-9]+:)?href>");
        Pattern lenP = Pattern.compile("(?i)<(?:[A-Za-z0-9]+:)?getcontentlength>([^<]*)</(?:[A-Za-z0-9]+:)?getcontentlength>");
        // <D:collection/> 表示这是目录，不应作为文件参与比对
        Pattern colP = Pattern.compile("(?i)<(?:[A-Za-z0-9]+:)?collection[\\s/>]");
        Matcher rm = respP.matcher(xml);
        while (rm.find()) {
            String block = rm.group(1);
            if (colP.matcher(block).find()) continue; // 跳过子目录
            Matcher hm = hrefP.matcher(block);
            if (!hm.find()) continue;
            String href = hm.group(1).trim();
            String norm = stripTrailingSlash(pathOf(decode(href)));
            if (norm.isEmpty() || norm.equals(normSelf)) continue; // 跳过自身
            String name = norm;
            int s = norm.lastIndexOf('/');
            if (s >= 0) name = norm.substring(s + 1);
            if (name.isEmpty()) continue;
            long size = -1; // -1 = 服务器未返回长度（大小未知）
            Matcher lm = lenP.matcher(block);
            if (lm.find()) {
                try {
                    size = Long.parseLong(lm.group(1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            map.put(name, size);
        }
        return map;
    }

    /** 组合远程目录 URL（结尾带 '/'），供 PROPFIND 使用。 */
    private static String buildDirUrl(String server, String remoteDir, String relDir) {
        String u = buildPutUrlPath(server, remoteDir, relDir == null ? "" : relDir);
        if (!u.endsWith("/")) u += "/";
        return u;
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 取 URL 的路径部分（去掉协议与 host），如 "http://h/dir/x" → "/dir/x"。 */
    private static String pathOf(String url) {
        if (url == null) return "/";
        int i = url.indexOf("://");
        String rest = (i >= 0) ? url.substring(i + 3) : url;
        int s = rest.indexOf('/');
        return s >= 0 ? rest.substring(s) : "/";
    }

    /**
     * 仅做 percent 解码（%XX → 字节，再按 UTF-8 还原）。
     * <p>
     * 不用 {@link URLDecoder}：它会把字面的 '+' 也转成空格（那是
     * application/x-www-form-urlencoded 的规则，不适用于 URL 路径），
     * 会让文件名里含 '+' 的文件永远匹配不上 → 每轮重复上传。
     */
    private static String decode(String s) {
        if (s == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
            for (int i = 0; i < b.length; i++) {
                if (b[i] == '%' && i + 2 < b.length) {
                    int hi = Character.digit((char) b[i + 1], 16);
                    int lo = Character.digit((char) b[i + 2], 16);
                    if (hi >= 0 && lo >= 0) {
                        bos.write((hi << 4) | lo);
                        i += 2;
                        continue;
                    }
                }
                bos.write(b[i]);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
