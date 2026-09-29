// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.conversation.ImageAttachment;
import marvin.host.domain.conversation.VoiceSnapshot;
import marvin.host.domain.shared.JsonText;

/**
 * The API of an image shown with a question ({@code POST /api/voice/image}, {@code /api/voice/image/remove}, and
 * {@code /api/voice/ask} with {@code image}) behind the real access filter, on a stand-in voice: types, sizes, the
 * statuses of a voice that is off or a model that cannot see.
 */
class VoiceImageApiTest {

    /** The voice as the API sees it; checks images as the real one does. */
    static final class Voice implements VoiceControl {
        boolean on = true;
        boolean sees = true;
        final List<String> asked = new ArrayList<>();
        Map<String, Object> waiting;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String unavailableReason() {
            return "";
        }

        @Override
        public String unavailableFix() {
            return "";
        }

        @Override
        public VoiceSnapshot snapshot() {
            return new VoiceSnapshot(on ? "on" : "off", on ? "idle" : "off", false, "", "", "qwen3:4b-instruct", true, true,
                    null, false, waiting);
        }

        @Override
        public Map<String, Object> appSettings() {
            return Map.of("llm_model", "qwen3:4b-instruct", "vision_model", "");
        }

        @Override
        public Map<String, Object> updateSettings(Map<String, ?> update) {
            return appSettings();
        }

        @Override
        public List<ConversationEntry> recent(long since) {
            return List.of();
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void rewarm() {
        }

        @Override
        public void clearHistory() {
        }

        @Override
        public void ask(String text) {
            ask(text, null);
        }

        @Override
        public void ask(String text, byte[] image) {
            if (!on) {
                throw new VoiceOff("Marvin's voice is off");
            }
            if (image != null) {
                attachImage(image);
            }
            asked.add(text);
        }

        @Override
        public Map<String, Object> attachImage(byte[] image) {
            if (!on) {
                throw new VoiceOff("Marvin's voice is off");
            }
            ImageAttachment img = ImageAttachment.of(image);
            if (!sees) {
                throw new ImageRefused("This model cannot see images; choose a vision model in Marvin > Voice");
            }
            waiting = new LinkedHashMap<>(img.describe());
            waiting.put("model", "qwen3:4b-instruct");
            return waiting;
        }

        @Override
        public void removeImage() {
            waiting = null;
        }

        @Override
        public void listenNow(boolean on) {
        }

        @Override
        public void mute(boolean muted) {
        }

        @Override
        public void stopSpeaking() {
        }

        @Override
        public Map<String, Object> options() {
            return Map.of();
        }
    }

    final Voice voice = new Voice();
    final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ApiController(null, null, null, null, voice, null, null, null, null, AccessKey.fixed("k3y")))
            .setControllerAdvice(new ApiErrors())
            .addFilters(new AccessFilter(AccessKey.fixed("k3y"), "marvin-desk")).build();

    static byte[] jpeg(int width, int height, int padding) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[] {(byte) 0xff, (byte) 0xd8});
        segment(o, 0xe1, "Exif\0\0GPS".getBytes(StandardCharsets.ISO_8859_1));
        segment(o, 0xc0, new byte[] {8, (byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width, 1, 1, 0x11, 0});
        segment(o, 0xda, new byte[] {1, 1, 0, 0, 0x3f, 0});
        o.writeBytes(new byte[padding]);
        o.writeBytes(new byte[] {(byte) 0xff, (byte) 0xd9});
        return o.toByteArray();
    }

    static void segment(ByteArrayOutputStream o, int marker, byte[] data) {
        int len = data.length + 2;
        o.writeBytes(new byte[] {(byte) 0xff, (byte) marker, (byte) (len >> 8), (byte) len});
        o.writeBytes(data);
    }

    static MockHttpServletRequestBuilder image(byte[] body, String type) {
        return post("/api/voice/image").header("Host", "localhost:8765").contentType(type).content(body);
    }

    static MockHttpServletRequestBuilder ask(Map<String, Object> body) {
        return post("/api/voice/ask").header("Host", "localhost:8765").contentType("application/json")
                .content(JsonText.write(body));
    }

    static String error(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        String body = r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return (String) ((Map<?, ?>) JsonText.parse(body)).get("error");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anImageWaitsForTheNextQuestionAndCanBeRemoved() throws Exception {
        String body = mvc.perform(image(jpeg(1280, 960, 100), "image/jpeg")).andExpect(status().isOk())
                .andExpect(jsonPath("$.image.width").value(1280)).andExpect(jsonPath("$.image.height").value(960))
                .andExpect(jsonPath("$.voice.image.sha256").exists())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> image = (Map<String, Object>) ((Map<String, Object>) JsonText.parse(body)).get("image");
        assertThat(image).containsOnlyKeys("type", "width", "height", "bytes", "sha256", "model");
        mvc.perform(post("/api/voice/image/remove").header("Host", "localhost:8765").contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.voice.image").doesNotExist());
        assertThat(voice.waiting).isNull();
    }

    @Test
    void onlyAJpegOrAPngOfAReasonableSize() throws Exception {
        assertThat(error(mvc.perform(image(jpeg(64, 64, 10), "image/gif")).andExpect(status().is(415))))
                .isEqualTo("send a JPEG or PNG image");
        assertThat(error(mvc.perform(image("{}".getBytes(StandardCharsets.UTF_8), "application/json"))
                .andExpect(status().is(415)))).isEqualTo("send a JPEG or PNG image");
        // what it says it is does not matter: its bytes do
        assertThat(error(mvc.perform(image("GIF89a".getBytes(StandardCharsets.UTF_8), "image/jpeg")).andExpect(status().isBadRequest())))
                .isEqualTo("the image must be a JPEG or a PNG");
        assertThat(error(mvc.perform(image(jpeg(5000, 10, 10), "image/jpeg")).andExpect(status().isBadRequest())))
                .isEqualTo("the image is too large: at most 4096 pixels a side");
        assertThat(error(mvc.perform(image(jpeg(64, 64, ImageAttachment.MAX_BYTES), "image/jpeg"))
                .andExpect(status().is(413)))).isEqualTo("the image is too large: at most 2 MB");
        // a JSON route still takes 16 KiB at most
        assertThat(error(mvc.perform(post("/api/voice/listen").header("Host", "localhost:8765").contentType("application/json")
                .content("{\"on\": true, \"x\": \"" + "a".repeat(AccessFilter.MAX_BODY) + "\"}"))
                .andExpect(status().is(413)))).isEqualTo("too large");
        assertThat(voice.waiting).isNull();
    }

    @Test
    void aModelThatCannotSeeAndAVoiceThatIsOffSaySo() throws Exception {
        voice.sees = false;
        assertThat(error(mvc.perform(image(jpeg(64, 64, 10), "image/jpeg")).andExpect(status().is(422))))
                .isEqualTo("This model cannot see images; choose a vision model in Marvin > Voice");
        voice.on = false;
        assertThat(error(mvc.perform(image(jpeg(64, 64, 10), "image/jpeg")).andExpect(status().isConflict())))
                .isEqualTo("Marvin's voice is off");
    }

    @Test
    void aTypedQuestionCanCarryItsImage() throws Exception {
        String b64 = Base64.getEncoder().encodeToString(jpeg(800, 600, 1000));
        mvc.perform(ask(Map.of("text", "What is this?", "image", "data:image/jpeg;base64," + b64))).andExpect(status().isOk())
                .andExpect(jsonPath("$.voice.image.width").value(800));
        assertThat(voice.asked).containsExactly("What is this?");
        assertThat(error(mvc.perform(ask(Map.of("text", "And this?", "image", 42))).andExpect(status().isBadRequest())))
                .isEqualTo("image must be a JPEG or PNG in base64");
        assertThat(error(mvc.perform(ask(Map.of("text", "And this?", "image", "%%%"))).andExpect(status().isBadRequest())))
                .isEqualTo("image must be base64");
        // a large image fits in a question's body; one over the limit does not
        String big = Base64.getEncoder().encodeToString(jpeg(800, 600, ImageAttachment.MAX_BYTES - 200));
        mvc.perform(ask(Map.of("text", "Big?", "image", big))).andExpect(status().isOk());
        String over = Base64.getEncoder().encodeToString(jpeg(800, 600, ImageAttachment.MAX_BYTES + 10));
        assertThat(error(mvc.perform(ask(Map.of("text", "Too big?", "image", over))).andExpect(status().is(413))))
                .isEqualTo("the image is too large: at most 2 MB");
        String tooBig = Base64.getEncoder().encodeToString(jpeg(800, 600, ImageAttachment.MAX_BYTES + 20_000));
        assertThat(error(mvc.perform(ask(Map.of("text", "Too big?", "image", tooBig))).andExpect(status().is(413))))
                .isEqualTo("too large");
        voice.sees = false;
        assertThat(error(mvc.perform(ask(Map.of("text", "Now?", "image", b64))).andExpect(status().is(422))))
                .startsWith("This model cannot see images");
        assertThat(voice.asked).containsExactly("What is this?", "Big?");
    }
}
