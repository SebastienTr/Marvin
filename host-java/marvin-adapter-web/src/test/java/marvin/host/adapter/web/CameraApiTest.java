// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import marvin.host.application.vision.port.in.Camera;
import marvin.host.domain.shared.JsonText;
import marvin.host.domain.vision.CameraChoice;
import marvin.host.domain.vision.event.Frame;

/** {@code GET /api/camera} and the camera test behind the real access filter, on a stand-in camera. */
class CameraApiTest {

    static final class FakeCamera implements Camera {
        volatile NoPicture refuse;
        volatile Map<String, Object> last;
        int taken;

        @Override
        public Status status() {
            CameraChoice.Source mac = new CameraChoice.Source("computer", true, "This computer's camera (the built-in one)", "");
            CameraChoice.Source robot = new CameraChoice.Source("robot", false, "No robot with a camera is connected", "Turn it on.");
            return new Status(refuse != null && refuse.unavailable() ? new CameraChoice.Choice("off", null, refuse.getMessage(), refuse.fix())
                    : CameraChoice.pick("auto", mac, robot), List.of(mac, robot), last);
        }

        @Override
        public boolean available() {
            return status().choice().available();
        }

        @Override
        public Frame take(String purpose) {
            if (refuse != null) {
                throw refuse;
            }
            taken++;
            last = Map.of("source", "computer", "purpose", purpose);
            return new Frame(new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9}, 1280, 720, "computer",
                    "This computer's camera (the built-in one)", 0.9);
        }
    }

    final FakeCamera camera = new FakeCamera();
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CameraController(camera)).setControllerAdvice(new ApiErrors())
            .addFilters(new AccessFilter(AccessKey.fixed("k3y"), "marvin-desk")).build();

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return (Map<String, Object>) JsonText.parse(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCameraSaysWhichOneIsUsedAndWhy() throws Exception {
        Map<String, Object> s = json(mvc.perform(get("/api/camera").header("Host", "localhost:8765")).andExpect(status().isOk()));
        assertThat(s).containsEntry("setting", "auto").containsEntry("source", "computer")
                .containsEntry("detail", "This computer's camera (the built-in one)").containsEntry("last", null)
                .containsEntry("settings", List.of("auto", "computer", "robot", "off"));
        assertThat((List<Map<String, Object>>) s.get("sources")).extracting(m -> m.get("name")).containsExactly("computer", "robot");
        // another device needs the key, as for the whole API
        mvc.perform(get("/api/camera").header("Host", "192.168.1.10:8765").with(r -> {
            r.setRemoteAddr("192.168.1.20");
            return r;
        })).andExpect(status().isUnauthorized());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theTestTakesOnePictureAndSendsItBackToThePageOnly() throws Exception {
        Map<String, Object> r = json(mvc.perform(post("/api/camera/snapshot").header("Host", "localhost:8765")
                .contentType("application/json").content("{}")).andExpect(status().isOk()));
        assertThat((String) r.get("image")).startsWith("data:image/jpeg;base64,");
        assertThat(Base64.getDecoder().decode(((String) r.get("image")).substring(23))).hasSize(6);
        assertThat((Map<String, Object>) r.get("picture")).containsEntry("width", 1280L).containsEntry("source", "computer");
        assertThat((Map<String, Object>) ((Map<String, Object>) r.get("camera")).get("last")).containsEntry("purpose", "test");
        assertThat(camera.taken).isEqualTo(1);
        // JSON only, from this origin
        mvc.perform(post("/api/camera/snapshot").header("Host", "localhost:8765").contentType("text/plain").content("x"))
                .andExpect(status().is(415));
        mvc.perform(post("/api/camera/snapshot").header("Host", "localhost:8765").header("Origin", "http://evil.example")
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        assertThat(camera.taken).isEqualTo(1);
    }

    @Test
    void noCameraIsAConflictAFailedOneABadGatewayBothWithWhatToDo() throws Exception {
        camera.refuse = new Camera.NoPicture("The camera is off", "Choose a camera in Marvin > Voice & models > Camera.", true);
        Map<String, Object> off = json(mvc.perform(post("/api/camera/snapshot").header("Host", "localhost:8765")
                .contentType("application/json").content("{}")).andExpect(status().isConflict()));
        assertThat(off).containsEntry("error", "The camera is off").containsKey("camera");
        assertThat((String) off.get("fix")).contains("Marvin > Voice & models > Camera");
        camera.refuse = new Camera.NoPicture("macOS does not let Marvin use the camera", "allow it", false);
        mvc.perform(post("/api/camera/snapshot").header("Host", "localhost:8765").contentType("application/json").content("{}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.fix").value("allow it"));
    }
}
