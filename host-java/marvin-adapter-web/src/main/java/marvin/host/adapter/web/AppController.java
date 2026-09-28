// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import marvin.host.application.face.port.in.FaceImage;

/**
 * The app itself: the page, its script, style and manifest (the Python host's {@code ui/static}, served
 * unchanged), the face drawn on the host ({@code /face.png}) and the icon.
 */
@RestController
public class AppController {
    private static final Logger log = LoggerFactory.getLogger(AppController.class);
    static final Map<String, String> STATIC_TYPES = Map.of(
            "index.html", "text/html; charset=utf-8",
            "app.js", "text/javascript; charset=utf-8",
            "style.css", "text/css; charset=utf-8",
            "manifest.webmanifest", "application/manifest+json");

    private final FaceImage face;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private volatile boolean faceBroken;
    private byte[] lastFrame;
    private byte[] lastPng;
    private byte[] icon;

    public AppController(FaceImage face) {
        this.face = face;
    }

    @GetMapping({"/", "/index.html"})
    public ResponseEntity<byte[]> index() {
        return file("index.html");
    }

    @GetMapping("/manifest.webmanifest")
    public ResponseEntity<byte[]> manifest() {
        return file("manifest.webmanifest");
    }

    @GetMapping("/static/{name}")
    public ResponseEntity<byte[]> staticFile(@PathVariable String name) {
        return file(name);
    }

    @GetMapping({"/face.png", "/api/face"})
    public ResponseEntity<byte[]> facePng() {
        byte[] png = facePngBytes();
        if (png == null) {
            return Responses.error(503, "face rendering unavailable");
        }
        return Responses.send(200, png, "image/png");
    }

    @GetMapping({"/icon.png", "/favicon.ico"})
    public ResponseEntity<byte[]> iconPng() {
        byte[] png;
        synchronized (this) {
            if (icon == null) {
                icon = Png.encode(face.icon(), face.width(), face.width());
            }
            png = icon;
        }
        return Responses.send(200, png, "image/png", "max-age=86400");
    }

    private synchronized byte[] facePngBytes() {
        if (faceBroken) {
            return null;
        }
        try {
            byte[] frame = face.frame();
            if (frame == null) {
                return null;
            }
            if (frame != lastFrame) {
                lastPng = Png.encode(frame, face.width(), face.height());
                lastFrame = frame;
            }
            return lastPng;
        } catch (RuntimeException e) {
            log.error("face rendering failed", e);
            faceBroken = true;
            return null;
        }
    }

    private ResponseEntity<byte[]> file(String name) {
        String type = STATIC_TYPES.get(name);
        if (type == null) {
            return Responses.error(404, "not found");
        }
        byte[] body = files.computeIfAbsent(name, AppController::load);
        return Responses.send(200, body, type);
    }

    private static byte[] load(String name) {
        try (InputStream in = AppController.class.getResourceAsStream("/app/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing app file " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
