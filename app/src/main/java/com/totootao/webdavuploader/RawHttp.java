package com.totootao.webdavuploader;

import java.io.BufferedReader;
import java.io.IOException;
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
