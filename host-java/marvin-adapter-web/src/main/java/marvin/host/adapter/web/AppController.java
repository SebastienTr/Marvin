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
 * The app itself: the page, its style, its scripts and manifest (the Java host's own app, see docs/ui.md; the
 * Python host keeps its older one), the face drawn on the host ({@code /face.png}) and the icon.
 */
@RestController
public class AppController {
    private static final Logger log = LoggerFactory.getLogger(AppController.class);
    /** The app's files: the page, its style, its scripts (ES modules) and the manifest. Nothing else is served. */
    static final Map<String, String> STATIC_TYPES = Map.ofEntries(
            Map.entry("index.html", "text/html; charset=utf-8"),
            Map.entry("style.css", "text/css; charset=utf-8"),
            Map.entry("manifest.webmanifest", "application/manifest+json"),
            script("theme.js"), script("app.js"), script("core.js"), script("face.js"), script("daycard.js"),
            script("memdata.js"), script("inspector.js"), script("convo.js"), script("home.js"), script("talk.js"),
            script("memory.js"), script("activity.js"), script("marvin.js"));

    private static Map.Entry<String, String> script(String name) {
        return Map.entry(name, "text/javascript; charset=utf-8");
    }

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
