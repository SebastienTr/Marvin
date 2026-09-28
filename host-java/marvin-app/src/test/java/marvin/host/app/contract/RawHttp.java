// SPDX-License-Identifier: MIT
package marvin.host.app.contract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A tiny HTTP/1.1 client over a socket, so a test can send any {@code Host} header (the JDK client
 * cannot) and see the response exactly as sent.
 */
final class RawHttp {
    record Response(int status, Map<String, String> headers, byte[] body) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private RawHttp() {
    }

    static Response call(String address, int port, String method, String path, Map<String, String> headers, byte[] body)
            throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(address, port), 5000);
            s.setSoTimeout(15000);
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Host", "localhost:" + port);
            h.put("Connection", "close");
            if (headers != null) {
                h.putAll(headers);
            }
            if (body != null) {
                h.put("Content-Length", Integer.toString(body.length));
            }
            StringBuilder req = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
            h.forEach((k, v) -> req.append(k).append(": ").append(v).append("\r\n"));
            req.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) {
                out.write(body);
            }
            out.flush();
            return read(s.getInputStream());
        }
    }

    private static Response read(InputStream in) throws IOException {
        byte[] all = in.readAllBytes();
        int split = indexOf(all, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        String head = new String(all, 0, split, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            headers.merge(lines[i].substring(0, c).strip().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).strip(),
                    (a, b) -> a + ", " + b);
        }
        byte[] body = java.util.Arrays.copyOfRange(all, split + 4, all.length);
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            body = dechunk(body);
        }
        return new Response(status, headers, body);
    }

    private static byte[] dechunk(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < b.length) {
            int eol = indexOf(java.util.Arrays.copyOfRange(b, i, b.length), "\r\n".getBytes(StandardCharsets.ISO_8859_1));
            int n = Integer.parseInt(new String(b, i, eol, StandardCharsets.ISO_8859_1).split(";")[0].strip(), 16);
            i += eol + 2;
            if (n == 0) {
                break;
            }
            out.write(b, i, n);
            i += n + 2;
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] a, byte[] p) {
        outer:
        for (int i = 0; i + p.length <= a.length; i++) {
            for (int j = 0; j < p.length; j++) {
                if (a[i + j] != p[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
