// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import marvin.host.application.conversation.port.in.ConversationHistory;
import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.application.presence.port.in.PresenceHistory;
import marvin.host.application.robot.port.in.RobotLinkQuery;
import marvin.host.application.settings.port.in.ManageSettings;
import marvin.host.application.system.port.in.HostLog;
import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.shared.Clocks;
import tools.jackson.core.JacksonException;

/**
 * The app's JSON API, endpoint for endpoint as the Python host serves it ({@code ui/server.py}): same
 * paths, query parameters, statuses, error messages and JSON shapes.
 */
@RestController
public class ApiController {
    private final LiveState live;
    private final PresenceHistory history;
    private final ManageSettings settings;
    private final ConversationHistory conversation;
    private final VoiceControl voice;
    private final HostLog hostLog;
    private final RobotLinkQuery robot;
    private final Clocks clocks;
    private final WebProperties props;
    private final AccessKey key;

    public ApiController(LiveState live, PresenceHistory history, ManageSettings settings,
                         ConversationHistory conversation, VoiceControl voice, HostLog hostLog, RobotLinkQuery robot,
                         Clocks clocks, WebProperties props, AccessKey key) {
        this.live = live;
        this.history = history;
        this.settings = settings;
        this.conversation = conversation;
        this.voice = voice;
        this.hostLog = hostLog;
        this.robot = robot;
        this.clocks = clocks;
        this.props = props;
        this.key = key;
    }

    // ------------------------------------------------------------------ GET

    @GetMapping("/api/state")
    public ResponseEntity<byte[]> state() {
        return Responses.json(state(live, history, settings));
    }

    static Map<String, Object> state(LiveState live, PresenceHistory history, ManageSettings settings) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", live.snapshot());
        m.put("today", Views.day(history.today()));
        m.put("settings", settings.current().toMap());
        return m;
    }

    @GetMapping("/api/day")
    public ResponseEntity<byte[]> day(HttpServletRequest rq) {
        LocalDate d = queryDate(rq, "date");
        return Responses.json(Views.day(history.day(d)));
    }

    @GetMapping("/api/history")
    public ResponseEntity<byte[]> history(HttpServletRequest rq) {
        int days = (int) queryInt(rq, "days", 7, 1, 62);
        LocalDate last = queryDate(rq, "date");
        return Responses.json(Map.of("days", history.history(last, days).stream().map(Views::summary).toList()));
    }

    @GetMapping("/api/events")
    public ResponseEntity<byte[]> events(HttpServletRequest rq) {
        long since = queryInt(rq, "since", 0, 0, 1L << 62);
        int limit = (int) queryInt(rq, "limit", 50, 1, 500);
        String quiet = first(rq, "quiet");
        boolean q = "1".equals(quiet) || "true".equals(quiet);
        return Responses.json(Map.of("events", history.recent(limit, since, q).stream().map(Views::event).toList()));
    }

    @GetMapping("/api/settings")
    public ResponseEntity<byte[]> settings() {
        return Responses.json(settingsPayload());
    }

    @GetMapping("/api/robot")
    public ResponseEntity<byte[]> robot() {
        return Responses.json(robotPayload(robot, true));
    }

    static Map<String, Object> robotPayload(RobotLinkQuery robot, boolean history) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("devices", Views.devices(robot.devices()));
        m.put("scene", Views.scene(robot.scene(history)));
        return m;
    }

    @GetMapping("/api/log")
    public ResponseEntity<byte[]> log(HttpServletRequest rq) {
        int limit = (int) queryInt(rq, "limit", 200, 1, HostLog.CAPACITY);
        long since = queryInt(rq, "since", 0, 0, 1L << 62);
        Set<String> sources = new LinkedHashSet<>();
        String s = first(rq, "source");
        if (s != null) {
            for (String x : s.split(",")) {
                if (!x.isEmpty()) {
                    sources.add(x);
                }
            }
        }
        return Responses.json(Map.of("entries", hostLog.entries(limit, sources, since).stream().map(Views::log).toList()));
    }

    @GetMapping("/api/conversation")
    public ResponseEntity<byte[]> conversation(HttpServletRequest rq) {
        Map<String, List<String>> query = QueryString.parse(rq.getQueryString());
        if (query.containsKey("q")) {
            String q = QueryString.first(query, "q").strip();
            q = q.length() > 200 ? q.substring(0, 200) : q;
            int limit = (int) queryInt(rq, "limit", 100, 1, 100);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("q", q);
            m.put("results", conversation.search(q, limit).stream().map(Views::conversation).toList());
            return Responses.json(m);
        }
        LocalDate d = queryDate(rq, "day");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", d.toString());
        m.put("entries", conversation.day(d).stream().map(Views::conversation).toList());
        return Responses.json(m);
    }

    @GetMapping("/api/voice")
    public ResponseEntity<byte[]> voice() {
        return Responses.json(voicePayload(voice));
    }

    /** The voice, its settings and the recent conversation; without a voice, as the Python host answers when it has none. */
    static Map<String, Object> voicePayload(VoiceControl voice) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!voice.available()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("state", "unavailable");
            v.put("status", "off");
            v.put("muted", false);
            v.put("error", voice.unavailableReason());
            v.put("fix", voice.unavailableFix());
            m.put("voice", v);
            m.put("settings", null);
            m.put("transcript", List.of());
            return m;
        }
        m.put("voice", voice.snapshot().toMap());
        m.put("settings", voice.appSettings());
        m.put("transcript", voice.recent(0).stream().map(ConversationEntry::toLiveMap).toList());
        return m;
    }

    @GetMapping("/api/voice/options")
    public ResponseEntity<byte[]> voiceOptions() {
        if (!voice.available()) {
            return Responses.error(404, "voice control is not available");
        }
        return Responses.json(voice.options());
    }

    /** Anything else. */
    @RequestMapping("/**")
    public ResponseEntity<byte[]> notFound() {
        return Responses.error(404, "not found");
    }

    // ------------------------------------------------------------------ POST

    @PostMapping("/api/settings")
    public ResponseEntity<byte[]> updateSettings(HttpServletRequest rq) {
        Object body = body(rq);
        if (!(body instanceof Map<?, ?> m)) {
            throw new BadRequest("settings must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, ?> update = (Map<String, ?>) m;
        settings.update(update);
        return Responses.json(settingsPayload());
    }

    @PostMapping({"/api/voice/settings", "/api/voice/on", "/api/voice/off", "/api/voice/ask", "/api/voice/listen",
            "/api/voice/mute", "/api/voice/stop-speaking"})
    public ResponseEntity<byte[]> voicePost(HttpServletRequest rq) {
        Object body = body(rq);                                 // bad JSON is a 400 even without a voice
        if (!voice.available()) {
            return Responses.error(404, "voice control is not available");
        }
        if (!(body instanceof Map<?, ?> b)) {
            throw new BadRequest("send a JSON object");
        }
        try {
            switch (rq.getRequestURI()) {
                case "/api/voice/settings" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, ?> update = (Map<String, ?>) b;
                    voice.updateSettings(update);
                }
                case "/api/voice/on" -> {
                    voice.start();
                    if (!settings.current().voice()) {
                        settings.update(Map.of("voice", true));       // and at the next start of the host
                    }
                }
                case "/api/voice/off" -> {
                    voice.stop();
                    if (settings.current().voice()) {
                        settings.update(Map.of("voice", false));
                    }
                }
                case "/api/voice/ask" -> {
                    if (!(b.get("text") instanceof String text)) {
                        throw new BadRequest("text must be a string");
                    }
                    voice.ask(text);
                }
                case "/api/voice/listen" -> {
                    Object on = b.containsKey("on") ? b.get("on") : Boolean.TRUE;
                    if (!(on instanceof Boolean o)) {
                        throw new BadRequest("on must be true or false");
                    }
                    voice.listenNow(o);
                }
                case "/api/voice/mute" -> {
                    if (!(b.get("muted") instanceof Boolean muted)) {
                        throw new BadRequest("muted must be true or false");
                    }
                    voice.mute(muted);
                }
                default -> voice.stopSpeaking();
            }
        } catch (VoiceControl.VoiceOff e) {
            return Responses.error(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new BadRequest(e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("voice", voice.snapshot().toMap());
        m.put("settings", voice.appSettings());
        return Responses.json(m);
    }

    private Map<String, Object> settingsPayload() {
        Map<String, Object> about = new LinkedHashMap<>();
        about.put("data_dir", props.dataDir() == null ? null : props.dataDir().toString());
        about.put("access_key", key.enabled());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settings", settings.current().toMap());
        m.put("defaults", settings.defaults().toMap());
        m.put("about", about);
        return m;
    }

    // ------------------------------------------------------------------ errors

    /** A request the app should not have sent: 400 with the reason. */
    static final class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ helpers

    static Object body(HttpServletRequest rq) {
        byte[] raw = (byte[]) rq.getAttribute(AccessFilter.BODY);
        if (raw == null || raw.length == 0) {
            return new LinkedHashMap<>();
        }
        try {
            return PyJson.MAPPER.readValue(raw, Object.class);
        } catch (JacksonException e) {
            String msg = e.getOriginalMessage();
            throw new BadRequest("invalid JSON: " + (msg == null ? "cannot parse" : msg.lines().findFirst().orElse(msg)));
        }
    }

    private static String first(HttpServletRequest rq, String key) {
        return QueryString.first(QueryString.parse(rq.getQueryString()), key);
    }

    private LocalDate queryDate(HttpServletRequest rq, String key) {
        String v = first(rq, key);
        if (v == null) {
            return history.dayOf(clocks.wallSeconds());
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new BadRequest(key + " must be YYYY-MM-DD");
        }
    }

    /** Python's {@code int(value)}, clamped to [lo, hi]. */
    static long queryInt(HttpServletRequest rq, String key, long dflt, long lo, long hi) {
        String v = first(rq, key);
        if (v == null) {
            return dflt;
        }
        String t = v.strip();
        if (!t.matches("[+-]?\\d+(_\\d+)*")) {
            throw new BadRequest(key + " must be an integer");
        }
        long n;
        try {
            n = Long.parseLong(t.replace("_", ""));
        } catch (NumberFormatException e) {
            n = t.startsWith("-") ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return Math.max(lo, Math.min(hi, n));
    }
}
