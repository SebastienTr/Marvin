// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import marvin.host.application.soul.port.in.SoulControl;
import marvin.host.domain.soul.Guardrails;
import marvin.host.domain.soul.SoulPart;
import marvin.host.domain.soul.SoulText;

/**
 * Marvin's soul (docs/design.md 8; golden/api/get/soul.json). Every POST answers the soul as {@code GET} does.
 *
 * <ul>
 * <li>{@code GET /api/soul}: the core, the learned part, the limits, the proposal awaiting the owner (or null), the
 * versions;</li>
 * <li>{@code POST /api/soul/core} {@code {"text": "..."}}: the owner's core (400 when empty or too long);</li>
 * <li>{@code POST /api/soul/learned} {@code {"text": "..."}}: the owner's learned part, checked by the guardrails (422
 * with {@code guardrails} when one fails);</li>
 * <li>{@code POST /api/soul/proposal/{id}/approve}, {@code .../reject}: the owner's decision (404, 409 when it was
 * decided or the learned part changed since, 422 when it fails the guardrails now);</li>
 * <li>{@code POST /api/soul/revert} {@code {"part": "core" | "learned", "version": 2}}: a new version with an earlier
 * one's text;</li>
 * <li>{@code POST /api/soul/reflect}: runs the weekly reflection now.</li>
 * </ul>
 */
@RestController
public class SoulController {
    private final SoulControl soul;

    public SoulController(SoulControl soul) {
        this.soul = soul;
    }

    @GetMapping("/api/soul")
    public ResponseEntity<byte[]> get() {
        return Responses.json(soul.view());
    }

    @PostMapping("/api/soul/core")
    public ResponseEntity<byte[]> core(HttpServletRequest rq) {
        String text = text(rq);
        return answer(() -> {
            try {
                soul.setCore(text);
            } catch (IllegalArgumentException e) {
                throw new ApiController.BadRequest(e.getMessage());
            }
        });
    }

    @PostMapping("/api/soul/learned")
    public ResponseEntity<byte[]> learned(HttpServletRequest rq) {
        String text = text(rq);
        return answer(() -> soul.setLearned(text));
    }

    @PostMapping("/api/soul/proposal/{id}/approve")
    public ResponseEntity<byte[]> approve(@PathVariable long id) {
        return answer(() -> soul.approve(id));
    }

    @PostMapping("/api/soul/proposal/{id}/reject")
    public ResponseEntity<byte[]> reject(@PathVariable long id) {
        return answer(() -> soul.reject(id));
    }

    @PostMapping("/api/soul/revert")
    public ResponseEntity<byte[]> revert(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        SoulPart part;
        try {
            part = SoulPart.of(b.get("part") instanceof String s ? s : null);
        } catch (IllegalArgumentException e) {
            throw new ApiController.BadRequest(e.getMessage());
        }
        if (!(b.get("version") instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 1) {
            throw new ApiController.BadRequest("version must be a whole number from 1");
        }
        int version = n.intValue();
        return answer(() -> soul.revert(part, version));
    }

    @PostMapping("/api/soul/reflect")
    public ResponseEntity<byte[]> reflect() {
        return answer(() -> soul.reflect());
    }

    /** Runs a change, then answers the soul; the port's refusals as their statuses. */
    private ResponseEntity<byte[]> answer(Runnable change) {
        try {
            change.run();
        } catch (SoulControl.NotFound e) {
            return Responses.error(404, e.getMessage());
        } catch (SoulControl.Conflict e) {
            return Responses.error(409, e.getMessage());
        } catch (SoulControl.Refused e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", e.getMessage());
            m.put("guardrails", e.checks().stream().map(Guardrails.Check::toMap).toList());
            return Responses.json(422, m);
        }
        return Responses.json(soul.view());
    }

    private static String text(HttpServletRequest rq) {
        Map<?, ?> b = object(rq);
        if (!(b.get("text") instanceof String s)) {
            throw new ApiController.BadRequest("text must be a string");
        }
        if (s.length() > SoulText.MAX_CHARS) {
            throw new ApiController.BadRequest("text is at most " + SoulText.MAX_CHARS + " characters");
        }
        return s;
    }

    private static Map<?, ?> object(HttpServletRequest rq) {
        if (!(ApiController.body(rq) instanceof Map<?, ?> m)) {
            throw new ApiController.BadRequest("send a JSON object");
        }
        return m;
    }
}
