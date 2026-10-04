// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.application.conversation.port.in.VoiceEnrollment;

/**
 * The voice calibration and the voices Marvin knows (docs/voice.md, "Your voice"; Marvin > Voice & models):
 *
 * <ul>
 * <li>{@code GET /api/voice/enroll}: where the calibration is, the sentences to read, what was heard, the result, the
 * people Marvin knows (never their voiceprints), each device's last calibration, {@code first_run};</li>
 * <li>{@code POST /api/voice/enroll} {@code {"name"}}: starts it (409 while the voice is off);</li>
 * <li>{@code POST /api/voice/enroll/cancel};</li>
 * <li>{@code POST /api/voice/people/forget} {@code {"name"}}: deletes a voiceprint.</li>
 * </ul>
 */
@RestController
public class VoiceEnrollmentController {
    static final String START = "/api/voice/enroll";
    static final String CANCEL = "/api/voice/enroll/cancel";
    static final String FORGET = "/api/voice/people/forget";

    private final VoiceEnrollment enrollment;

    public VoiceEnrollmentController(VoiceEnrollment enrollment) {
        this.enrollment = enrollment;
    }

    @GetMapping(START)
    public ResponseEntity<byte[]> state() {
        return Responses.json(enrollment.state());
    }

    @PostMapping({START, CANCEL, FORGET})
    public ResponseEntity<byte[]> post(HttpServletRequest rq) {
        if (!(ApiController.body(rq) instanceof Map<?, ?> b)) {
            throw new ApiController.BadRequest("send a JSON object");
        }
        try {
            return Responses.json(switch (rq.getRequestURI()) {
                case START -> enrollment.start(b.get("name"));
                case CANCEL -> enrollment.cancel();
                default -> enrollment.forget(b.get("name"));
            });
        } catch (VoiceControl.VoiceOff e) {
            return Responses.error(409, e.getMessage());
        }
    }
}
