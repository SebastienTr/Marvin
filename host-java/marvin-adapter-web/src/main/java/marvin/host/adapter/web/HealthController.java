// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import marvin.host.application.system.port.in.ReportHealth;
import marvin.host.domain.system.ComponentHealth;
import marvin.host.domain.system.HostStatus;

/**
 * {@code GET /api/health}: whether the host and its parts are up. 200 when healthy, 503 when a
 * component is down. Used by {@code ./marvin up} and {@code ./marvin status}; it is not part of the
 * Python host's API.
 */
@RestController
public class HealthController {
    private final ReportHealth reportHealth;

    public HealthController(ReportHealth reportHealth) {
        this.reportHealth = reportHealth;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        HostStatus s = reportHealth.health();
        Map<String, Object> components = new LinkedHashMap<>();
        s.components().forEach((name, c) -> components.put(name, component(c)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", s.healthy() ? "ok" : "degraded");
        body.put("version", s.version());
        body.put("mode", s.mode().name().toLowerCase(Locale.ROOT));
        body.put("components", components);
        return ResponseEntity.status(s.healthy() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private static Map<String, Object> component(ComponentHealth c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", c.state().name().toLowerCase(Locale.ROOT));
        m.put("detail", c.detail());
        return m;
    }
}
