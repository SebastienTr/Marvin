// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.tools.LookTool;
import marvin.host.application.conversation.tools.ToolRegistry;
import marvin.host.application.vision.port.in.Camera;
import marvin.host.domain.conversation.ImageAttachment;
import marvin.host.domain.conversation.tool.ToolError;
import marvin.host.domain.conversation.tool.ToolResult;
import marvin.host.domain.vision.CameraChoice;
import marvin.host.domain.vision.event.Frame;

class LookToolTest {

    /** A camera that gives a JPEG with EXIF, or no picture. */
    static final class FakeCamera implements Camera {
        volatile Camera.NoPicture refuse;
        final List<String> purposes = new CopyOnWriteArrayList<>();

        @Override
        public Status status() {
            return new Status(new CameraChoice.Choice("auto", "computer", "This computer's camera", ""), List.of(), null);
        }

        @Override
        public boolean available() {
            return refuse == null;
        }

        @Override
        public Frame take(String purpose) {
            purposes.add(purpose);
            if (refuse != null) {
                throw refuse;
            }
            return new Frame(VoiceServiceTest.jpeg(1280, 720), 1280, 720, "computer", "This computer's camera", 0.8);
        }
    }

    final FakeCamera camera = new FakeCamera();
    final List<Object[]> described = new CopyOnWriteArrayList<>();
    final ToolRegistry reg = new ToolRegistry(List.of(LookTool.tool(camera, (img, q) -> {
        described.add(new Object[] {img, q});
        return "A blue mug, held in a right hand.";
    })), true, false, () -> System.nanoTime() / 1e9);

    @Test
    void aLookTakesOnePictureWithoutItsMetadataAndReturnsWhatWasSeen() {
        ToolResult r = reg.call("look", Map.of("question", " je tiens quoi ? "), Map.of());
        assertThat(r.ok()).isTrue();
        assertThat(r.content()).isEqualTo("{\"camera\": \"the computer's camera, in front of the person\", "
                + "\"picture\": \"1280x720, taken just now\", \"seen\": \"A blue mug, held in a right hand.\"}");
        assertThat(camera.purposes).containsExactly("look");
        ImageAttachment img = (ImageAttachment) described.get(0)[0];
        assertThat(new String(java.util.Base64.getDecoder().decode(img.base64()), java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("the EXIF is gone").doesNotContain("GPS");
        assertThat(described.get(0)[1]).isEqualTo("je tiens quoi ?");
        assertThat(reg.call("look", Map.of(), Map.of()).ok()).isTrue();
        assertThat(described.get(1)[1]).isEqualTo("");
        assertThat(reg.get("look")).as("offline: offered without the internet").isNotNull();
    }

    @Test
    void noPictureOrNoSightIsAnErrorTheModelCanSay() {
        camera.refuse = new Camera.NoPicture("macOS does not let Marvin use the camera", "allow it", false);
        ToolResult r = reg.call("look", Map.of(), Map.of());
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).isEqualTo("the camera took no picture: macOS does not let Marvin use the camera");
        camera.refuse = null;
        ToolRegistry blind = new ToolRegistry(List.of(LookTool.tool(camera, (img, q) -> {
            throw new ToolError("qwen3:4b-instruct cannot see pictures");
        })), true, true, () -> 0);
        assertThat(blind.call("look", Map.of(), Map.of()).error()).isEqualTo("qwen3:4b-instruct cannot see pictures");
        assertThat(reg.call("look", Map.of("question", 3), Map.of()).ok()).as("a bad argument").isFalse();
    }
}
