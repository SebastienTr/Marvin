// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import marvin.host.application.vision.port.in.Camera;
import marvin.host.domain.vision.CameraChoice;
import marvin.host.domain.vision.event.Frame;

/**
 * Marvin's cameras for the app (docs/vision.md; the Java host only): which camera the {@code camera} voice setting
 * picks and why, and the camera test, which takes one picture and sends it back to the page that asked (as a
 * {@code data:} URL, which the page's policy allows). The picture is not kept.
 *
 * <ul>
 * <li>{@code GET /api/camera}: {@code setting}, {@code source} ({@code computer}, {@code robot} or {@code null}),
 * {@code detail} (which camera, or why none), {@code fix}, every camera's {@code sources}, and the {@code last}
 * picture taken (when, which camera, why, its size);</li>
 * <li>{@code POST /api/camera/snapshot}: {@code image}, {@code picture} (its size, camera, how long it took) and the
 * {@code camera} as above; 409 when no camera can be used, 502 when the camera failed, both with {@code fix}.</li>
 * </ul>
 */
@RestController
public class CameraController {
    static final String SNAPSHOT_PATH = "/api/camera/snapshot";

    private final Camera camera;

    public CameraController(Camera camera) {
        this.camera = camera;
    }

    @GetMapping("/api/camera")
    public ResponseEntity<byte[]> status() {
        return Responses.json(payload(camera.status()));
    }

    @PostMapping(SNAPSHOT_PATH)
    public ResponseEntity<byte[]> snapshot(HttpServletRequest rq) {
        ApiController.body(rq);
        Frame frame;
        try {
            frame = camera.take(Camera.TEST);
        } catch (Camera.NoPicture e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", e.getMessage());
            m.put("fix", e.fix());
            m.put("camera", payload(camera.status()));
            return Responses.json(e.unavailable() ? 409 : 502, m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("image", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(frame.jpeg()));
        m.put("picture", frame.describe());
        m.put("camera", payload(camera.status()));
        return Responses.json(m);
    }

    static Map<String, Object> payload(Camera.Status s) {
        CameraChoice.Choice c = s.choice();
        List<Map<String, Object>> sources = new ArrayList<>();
        for (CameraChoice.Source src : s.sources()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", src.name());
            m.put("available", src.available());
            m.put("detail", src.detail());
            m.put("fix", src.fix());
            sources.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("setting", c.setting());
        m.put("settings", CameraChoice.SETTINGS);
        m.put("source", c.source());
        m.put("detail", c.reason());
        m.put("fix", c.fix());
        m.put("sources", sources);
        m.put("last", s.last());
        return m;
    }
}
