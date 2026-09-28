// SPDX-License-Identifier: MIT
package marvin.host.app.contract;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads a Server-Sent Events stream for a while: {event, data, retry} per message. */
final class Sse {
    private Sse() {
    }

    static List<String[]> read(String address, int port, String path, long millis) throws IOException {
        List<String[]> out = new ArrayList<>();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(address, port), 5000);
            s.setSoTimeout(200);
            OutputStream o = s.getOutputStream();
            o.write(("GET " + path + " HTTP/1.1\r\nHost: localhost:" + port + "\r\nAccept: text/event-stream\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            o.flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            long end = System.currentTimeMillis() + millis;
            boolean body = false;
            String event = null;
            String data = null;
            String retry = null;
            while (System.currentTimeMillis() < end) {
                String line;
                try {
                    line = r.readLine();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (line == null) {
                    break;
                }
                if (!body) {
                    body = line.isEmpty();
                    continue;
                }
                if (line.startsWith("event: ")) {
                    event = line.substring(7);
                } else if (line.startsWith("data: ")) {
                    data = line.substring(6);
                } else if (line.startsWith("retry: ")) {
                    retry = line.substring(7);
                } else if (line.isEmpty() && event != null) {
                    out.add(new String[] {event, data, retry});
                    event = data = retry = null;
                }
                // anything else: chunk sizes of a chunked body
            }
        }
        return out;
    }
}
