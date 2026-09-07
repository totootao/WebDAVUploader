package com.totootao.webdavuploader;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 极简 HTTP 客户端：直接用 Socket 发送请求。
 * <p>
 * 为什么不用 HttpURLConnection？Android 的实现（OkHttp）在
 * {@code setRequestMethod} 处只允许固定白名单
 * [OPTIONS, GET, HEAD, POST, PUT, DELETE, TRACE, PATCH]，
 * WebDAV 的 MKCOL / PROPFIND 会直接抛 ProtocolException：
 * "Expected one of [...] but was MKCOL"。
 * 这里绕开该限制，支持任意方法。
 * </p>
 * 只返回状态码，读取到响应头即停止（够 MKCOL / PROPFIND 使用）。
 */
public class RawHttp {

    /**
     * 发送自定义方法的请求。
     *
     * @param method   如 "MKCOL"、"PROPFIND"、"HEAD"
     * @param urlStr   绝对 URL（路径段需已做 URL 编码）
     * @param auth     Authorization 头的值（如 "Basic xxx"），可为 null
     * @param insecure true 时信任所有证书（自签名服务器）
     * @param body     请求体，可为 null
     * @return HTTP 状态码
     */
    public static int request(String method, String urlStr, String auth,
                              boolean insecure, byte[] body) throws IOException {
        URI uri = URI.create(urlStr.trim());
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new IOException("URL 缺少主机名: " + urlStr);
        int port = uri.getPort();
        if (port < 0) port = tls ? 443 : 80;
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            path = path + "?" + uri.getRawQuery();
        }

        Socket socket;
        if (tls) {
            SSLSocket s = (SSLSocket) (insecure ? trustAllFactory() : defaultFactory())
                    .createSocket();
            s.connect(new InetSocketAddress(host, port), 15000);
            try {
                SSLParameters p = s.getSSLParameters();
                p.setServerNames(Collections.singletonList(new SNIHostName(host)));
                s.setSSLParameters(p);
            } catch (Exception ignored) {
                // 个别平台不支持手动 SNI，忽略（多数服务器可按默认完成握手）
            }
            s.setSoTimeout(20000);
            s.startHandshake();
            socket = s;
        } else {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 15000);
            s.setSoTimeout(20000);
            socket = s;
        }

        try {
            OutputStream out = socket.getOutputStream();

            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host);
            if (port != (tls ? 443 : 80)) req.append(':').append(port);
            req.append("\r\n");
            if (auth != null && !auth.isEmpty()) {
                req.append("Authorization: ").append(auth).append("\r\n");
            }
            if ("PROPFIND".equals(method)) {
                req.append("Depth: 0\r\n");
                req.append("Content-Type: application/xml\r\n");
            }
            req.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
            req.append("Connection: close\r\n");
            req.append("\r\n");

            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            if (body != null && body.length > 0) out.write(body);
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = reader.readLine();
            if (statusLine == null || statusLine.isEmpty()) {
                throw new IOException("服务器无响应");
            }
            String[] parts = statusLine.split("\\s+");
            if (parts.length < 2) throw new IOException("异常响应行: " + statusLine);
            int code;
            try {
                code = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IOException("异常状态码: " + statusLine);
            }

            // 1xx（如 100 Continue）继续读下一行
            while (code >= 100 && code < 200) {
                String cont = reader.readLine();
                if (cont == null) break;
                String[] pp = cont.split("\\s+");
                if (pp.length >= 2) {
                    try {
                        code = Integer.parseInt(pp[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            // 读完响应头（到空行为止），不读 body
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // 丢弃
            }
            return code;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** PROPFIND 等需要解析响应体的请求的返回结果。 */
    public static class Response {
        public final int code;
        public final String body;
        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /**
     * 与 {@link #request} 相同，但会读取完整响应体（支持 Content-Length 与
     * chunked 编码，连接以 Connection: close 结束）。用于 PROPFIND 解析目录列表。
     */
    public static Response requestBody(String method, String urlStr, String auth,
                                        boolean insecure, byte[] body) throws IOException {
        URI uri = URI.create(urlStr.trim());
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new IOException("URL 缺少主机名: " + urlStr);
        int port = uri.getPort();
        if (port < 0) port = tls ? 443 : 80;
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            path = path + "?" + uri.getRawQuery();
        }

        Socket socket;
        if (tls) {
            SSLSocket s = (SSLSocket) (insecure ? trustAllFactory() : defaultFactory())
                    .createSocket();
            s.connect(new InetSocketAddress(host, port), 15000);
            try {
                SSLParameters p = s.getSSLParameters();
                p.setServerNames(Collections.singletonList(new SNIHostName(host)));
                s.setSSLParameters(p);
            } catch (Exception ignored) {
            }
            s.setSoTimeout(30000);
            s.startHandshake();
            socket = s;
        } else {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 15000);
            s.setSoTimeout(30000);
            socket = s;
        }

        try {
            OutputStream out = socket.getOutputStream();
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host);
            if (port != (tls ? 443 : 80)) req.append(':').append(port);
            req.append("\r\n");
            if (auth != null && !auth.isEmpty()) {
                req.append("Authorization: ").append(auth).append("\r\n");
            }
            if ("PROPFIND".equals(method)) {
                req.append("Depth: 1\r\n");
                req.append("Content-Type: application/xml; charset=\"utf-8\"\r\n");
            }
            req.append("Accept-Encoding: identity\r\n");
            req.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
            req.append("Connection: close\r\n");
            req.append("\r\n");
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            if (body != null && body.length > 0) out.write(body);
            out.flush();

            InputStream in = socket.getInputStream();
            int code = readStatus(in);
            boolean chunked = false;
            int contentLength = -1;
            String headerLine;
            while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
                String low = headerLine.toLowerCase();
                if (low.startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(low.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (low.startsWith("transfer-encoding:")) {
                    chunked = low.contains("chunked");
                }
            }
            String bodyStr;
            if (chunked) bodyStr = readChunked(in);
            else if (contentLength >= 0) bodyStr = readFixed(in, contentLength);
            else bodyStr = readToEnd(in);
            return new Response(code, bodyStr);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 读取状态行（处理 1xx 续行），返回状态码。 */
    private static int readStatus(InputStream in) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null || statusLine.isEmpty()) throw new IOException("服务器无响应");
        String[] parts = statusLine.split("\\s+");
        if (parts.length < 2) throw new IOException("异常响应行: " + statusLine);
        int code;
        try {
            code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("异常状态码: " + statusLine);
        }
        while (code >= 100 && code < 200) {
            String cont = readLine(in);
            if (cont == null) break;
            String[] pp = cont.split("\\s+");
            if (pp.length >= 2) {
                try {
                    code = Integer.parseInt(pp[1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return code;
    }

    /** 从原始字节流按行读取（到 \n 结束，去掉 \r）；EOF 且无可读字节返回 null。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') continue;
            if (c == '\n') break;
            bos.write(c);
        }
        if (c == -1 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String readFixed(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) break;
            off += r;
        }
        return new String(buf, 0, off, StandardCharsets.UTF_8);
    }

    private static String readToEnd(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) break;
            int idx = sizeLine.indexOf(';');
            String hex = (idx >= 0 ? sizeLine.substring(0, idx) : sizeLine).trim();
            int len;
            try {
                len = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0) break;
            byte[] chunk = new byte[len];
            int off = 0;
            while (off < len) {
                int r = in.read(chunk, off, len - off);
                if (r < 0) break;
                off += r;
            }
            bos.write(chunk, 0, off);
            readLine(in); // 吃掉 chunk 后的 CRLF
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static SSLSocketFactory defaultFactory() {
        return (SSLSocketFactory) SSLSocketFactory.getDefault();
    }

    private static SSLSocketFactory trustAllFactory() throws IOException {
        try {
            TrustManager[] tms = new TrustManager[]{new X509TrustManager() {
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
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tms, new SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            throw new IOException("SSL 初始化失败: " + e.getMessage(), e);
        }
    }
}
