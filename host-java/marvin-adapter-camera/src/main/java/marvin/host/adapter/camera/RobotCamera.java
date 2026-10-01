// SPDX-License-Identifier: MIT
package marvin.host.adapter.camera;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import marvin.host.application.vision.port.out.FrameSource;
import marvin.host.domain.robot.DeviceStatus;
import marvin.host.domain.vision.CameraChoice;
import marvin.host.domain.vision.event.Frame;

/**
 * The robot's camera: a robot whose {@code HELLO} has the camera flag serves MJPEG on TCP 81 (docs/protocol.md). One
 * picture is {@code GET /capture} (a fresh JPEG); a firmware without it gives the first picture of {@code /stream}.
 * The robot's pictures (VGA) need no resizing; a larger one is made smaller.
 */
public final class RobotCamera implements FrameSource {
    /** The most a picture may weigh (the camera's VGA JPEGs are 20 to 60 kB). */
    static final int MAX_BYTES = 4 * 1024 * 1024;

    private final Supplier<List<DeviceStatus>> devices;
    private final int port;
    private final HttpClient http;

    /** @param devices the devices as the robot link knows them now */
    public RobotCamera(Supplier<List<DeviceStatus>> devices, int port) {
        this.devices = devices;
        this.port = port;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .proxy(HttpClient.Builder.NO_PROXY)            // the robot is on this network, never behind a proxy
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String name() {
        return CameraChoice.ROBOT;
    }

    /** The robot to look through: one online with a camera, a real one before a simulated one. */
    Optional<DeviceStatus> robot() {
        List<DeviceStatus> all = devices.get();
        return all == null ? Optional.empty() : all.stream()
                .filter(d -> d.camera() && d.online() && d.ip() != null && !d.ip().isBlank())
                .min(Comparator.comparing(DeviceStatus::simulated).thenComparing(DeviceStatus::name));
    }

    @Override
    public CameraChoice.Source availability() {
        return robot().map(d -> new CameraChoice.Source(name(), true, "The robot's camera (" + d.name() + " at " + d.ip() + ")", ""))
                .orElseGet(() -> new CameraChoice.Source(name(), false, "No robot with a camera is connected",
                        "Turn the robot on: its camera works once it says hello."));
    }

    private URI uri(DeviceStatus d, String path) {
        String host = d.ip().contains(":") && !d.ip().startsWith("[") ? "[" + d.ip() + "]" : d.ip();
        return URI.create("http://" + host + ":" + port + path);
    }

    @Override
    public Frame capture(double timeoutS) {
        DeviceStatus d = robot().orElseThrow(() -> new CaptureFailed("No robot with a camera is connected",
                "Turn the robot on: its camera works once it says hello."));
        long t0 = System.nanoTime();
        byte[] jpeg;
        try {
            HttpResponse<InputStream> r = get(uri(d, "/capture?t=" + System.currentTimeMillis()), timeoutS);
            if (r.statusCode() == 404) {
                r.body().close();
                double left = Math.max(1.0, timeoutS - (System.nanoTime() - t0) / 1e9);
                r = get(uri(d, "/stream"), left);
                jpeg = firstPart(r);
            } else if (r.statusCode() != 200) {
                r.body().close();
                throw new CaptureFailed(d.name() + "'s camera answered HTTP " + r.statusCode(), "");
            } else {
                try (InputStream in = r.body()) {
                    jpeg = in.readNBytes(MAX_BYTES);
                }
            }
        } catch (HttpTimeoutException e) {
            throw new CaptureFailed(d.name() + "'s camera gave no picture in " + Math.round(timeoutS) + " s",
                    "Is the robot still on the Wi-Fi?");
        } catch (IOException e) {
            throw new CaptureFailed(d.name() + "'s camera cannot be reached at " + d.ip() + ":" + port + " (" + e.getMessage() + ")",
                    "Is the robot still on the Wi-Fi?");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CaptureFailed("interrupted", "");
        }
        byte[] one = Jpegs.first(jpeg);
        if (one == null) {
            throw new CaptureFailed(d.name() + "'s camera sent no picture", "");
        }
        Jpegs.Sized s;
        try {
            s = Jpegs.fit(one);
        } catch (Jpegs.NotAJpeg e) {
            throw new CaptureFailed(d.name() + "'s camera sent no picture (" + e.getMessage() + ")", "");
        }
        return new Frame(s.jpeg(), s.width(), s.height(), name(), d.name() + " at " + d.ip(), (System.nanoTime() - t0) / 1e9);
    }

    private HttpResponse<InputStream> get(URI uri, double timeoutS) throws IOException, InterruptedException {
        HttpRequest rq = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis((long) (timeoutS * 1000))).GET().build();
        return http.send(rq, HttpResponse.BodyHandlers.ofInputStream());
    }

    /** The first picture of an MJPEG stream (multipart/x-mixed-replace): its part's Content-Length, or its markers. */
    static byte[] firstPart(HttpResponse<InputStream> r) throws IOException {
        if (r.statusCode() != 200) {
            r.body().close();
            throw new CaptureFailed("the robot's camera answered HTTP " + r.statusCode(), "");
        }
        try (InputStream in = r.body()) {
            return firstPart(in);
        }
    }

    static byte[] firstPart(InputStream in) throws IOException {
        // the part's head: "--boundary", Content-Type, Content-Length, a blank line
        int length = -1;
        boolean headers = false;
        for (int lines = 0; lines < 20; lines++) {
            String line = readLine(in);
            if (line == null) {
                return new byte[0];
            }
            if (line.isEmpty()) {
                if (headers) {
                    break;                      // the picture follows
                }
                continue;
            }
            headers |= line.indexOf(':') > 0;
            String low = line.toLowerCase(Locale.ROOT);
            if (low.startsWith("content-length:")) {
                try {
                    length = Integer.parseInt(line.substring(15).strip());
                } catch (NumberFormatException e) {
                    length = -1;
                }
            }
        }
        if (length > 0 && length <= MAX_BYTES) {
            return in.readNBytes(length);
        }
        // no length: read on until a whole picture is there
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (out.size() < MAX_BYTES && (n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            byte[] sofar = out.toByteArray();
            byte[] one = Jpegs.first(sofar);
            if (one != null && endsPicture(sofar, one)) {
                return one;
            }
        }
        return out.toByteArray();
    }

    /** A picture is whole once something follows its end-of-image marker (the next part's boundary). */
    private static boolean endsPicture(byte[] all, byte[] one) {
        return all.length > one.length + 4;
    }

    private static String readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (b.size() > 1024) {
                return null;
            }
            b.write(c);
        }
        if (c < 0 && b.size() == 0) {
            return null;
        }
        String s = b.toString(StandardCharsets.ISO_8859_1);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
}
