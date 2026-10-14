// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import marvin.host.application.system.port.in.RunSetup;

/**
 * The first-run setup (docs/ui.md "Setting Marvin up", contract C2, {@code golden/api/get/setup.json}):
 *
 * <ul>
 * <li>{@code GET /api/setup}: {@code {steps: [{id, state, detail, fix}], done}}, every check run again;</li>
 * <li>{@code POST /api/setup/{step}}: {@code {}} checks it again, {@code {"skip": true|false}}, {@code {"play": true}}
 * and {@code {"heard": true|false}} for the speaker, {@code {"name", "languages"}} for the owner, {@code {"enabled",
 * "start", "end"}} for the quiet hours; answers the same object as the GET. 404 for a step that does not exist, 400 for
 * a body it does not take.</li>
 * </ul>
 */
@RestController
public class SetupController {
    static final String PATH = "/api/setup";

    private final RunSetup setup;

    public SetupController(RunSetup setup) {
        this.setup = setup;
    }

    @GetMapping(PATH)
    public ResponseEntity<byte[]> status() {
        return Responses.json(view(setup.status()));
    }

    @PostMapping(PATH + "/{step}")
    public ResponseEntity<byte[]> act(@PathVariable String step, HttpServletRequest rq) {
        if (!(ApiController.body(rq) instanceof Map<?, ?> b)) {
            throw new ApiController.BadRequest("send a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, ?> body = (Map<String, ?>) b;
        try {
            return Responses.json(view(setup.act(step, body)));
        } catch (RunSetup.UnknownStep e) {
            return Responses.error(404, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiController.BadRequest(e.getMessage());
        }
    }

    /** A POST to one of the setup's steps (checked by the access filter like every POST). */
    static boolean isStepPath(String path) {
        return path.startsWith(PATH + "/") && RunSetup.STEPS.contains(path.substring(PATH.length() + 1));
    }

    static Map<String, Object> view(RunSetup.Status s) {
        List<Map<String, Object>> steps = s.steps().stream().map(st -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", st.id());
            m.put("state", st.state());
            m.put("detail", st.detail());
            m.put("fix", st.fix());
            return m;
        }).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("steps", steps);
        m.put("done", s.done());
        return m;
    }
}
