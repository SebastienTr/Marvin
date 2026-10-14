// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import marvin.host.application.vitals.port.in.VitalsQuery;
import marvin.host.domain.vitals.VitalsCurrent;
import marvin.host.domain.vitals.VitalsMinute;

/**
 * The vital signs for the app (contract C3, golden/api/get/vitals.json). Measured, not diagnosed: what the
 * MR60BHA2 radar reports about a still person, with how far to trust it.
 *
 * <ul>
 * <li>{@code GET /api/vitals?hours=24} (1 to 24, default 24): {@code current} ({@code hr}, {@code br} per minute,
 * {@code null} when the quality is none; {@code quality} good | unstable | none; {@code distance_m}; {@code t}
 * wall-clock seconds), {@code null} when there is no vitals sensor; {@code minutes}, one row per minute with
 * readings, oldest first ({@code t}, the medians {@code hr} and {@code br}, {@code n} the seconds that passed the
 * gate);</li>
 * <li>on {@code /api/stream}: {@code vitals}, the {@code current} object, once a second while there is a sensor and
 * someone has the app open.</li>
 * </ul>
 */
@RestController
public class VitalsController implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(VitalsController.class);
    static final String PATH = "/api/vitals";
    static final String EVENT = "vitals";
    static final long PERIOD_MS = 1000;

    private final VitalsQuery vitals;
    private final EventHub hub;
    private ScheduledExecutorService timer;

    public VitalsController(VitalsQuery vitals, EventHub hub) {
        this.vitals = vitals;
        this.hub = hub;
    }

    @GetMapping(PATH)
    public ResponseEntity<byte[]> get(HttpServletRequest rq) {
        int hours = (int) ApiController.queryInt(rq, "hours", 24, 1, 24);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current", vitals.current().map(VitalsController::current).orElse(null));
        m.put("minutes", minutes(vitals.minutes(hours)));
        return Responses.json(m);
    }

    static Map<String, Object> current(VitalsCurrent c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hr", c.hr());
        m.put("br", c.br());
        m.put("quality", c.quality().wire());
        m.put("distance_m", c.distanceM());
        m.put("t", c.t());
        return m;
    }

    static List<Map<String, Object>> minutes(List<VitalsMinute> rows) {
        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", r.t());
            m.put("hr", r.hr());
            m.put("br", r.br());
            m.put("n", r.n());
            return m;
        }).toList();
    }

    /** The {@code vitals} event, when someone listens and there is a sensor. */
    void publish() {
        try {
            if (hub.size() > 0) {
                vitals.current().ifPresent(c -> hub.publish(EVENT, current(c)));
            }
        } catch (RuntimeException e) {
            log.warn("vitals event failed", e);
        }
    }

    @Override
    public synchronized void start() {
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("vitals-stream").daemon().factory());
        timer.scheduleAtFixedRate(this::publish, PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return timer != null;
    }
}
