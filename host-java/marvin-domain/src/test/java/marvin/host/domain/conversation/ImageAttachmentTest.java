// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

/** An image shown with a question: what is accepted, its size read from its header, its metadata left out. */
class ImageAttachmentTest {

    /** A JPEG's structure (the pixels are not real): JFIF, EXIF with a place, a comment, a frame header, a scan. */
    static byte[] jpeg(int width, int height) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[] {(byte) 0xff, (byte) 0xd8});
        segment(o, 0xe0, bytes("JFIF\0\1\1\0\0\1\0\1\0\0"));
        segment(o, 0xe1, bytes("Exif\0\0GPS 43.70N 7.26E 2026:09:29 08:52"));
        segment(o, 0xfe, bytes("taken on a phone"));
        segment(o, 0xdb, new byte[65]);
        segment(o, 0xc0, new byte[] {8, (byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width, 1, 1, 0x11, 0});
        segment(o, 0xda, new byte[] {1, 1, 0, 0, 0x3f, 0});
        o.writeBytes(new byte[] {0x12, 0x34, (byte) 0xff, 0x00, 0x56, (byte) 0xff, (byte) 0xd9});
        return o.toByteArray();
    }

    static void segment(ByteArrayOutputStream o, int marker, byte[] data) {
        int len = data.length + 2;
        o.writeBytes(new byte[] {(byte) 0xff, (byte) marker, (byte) (len >> 8), (byte) len});
        o.writeBytes(data);
    }

    static byte[] png(int width, int height) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        chunk(o, "IHDR", new byte[] {0, 0, (byte) (width >> 8), (byte) width, 0, 0, (byte) (height >> 8), (byte) height, 8, 2, 0, 0, 0});
        chunk(o, "tEXt", bytes("Comment\0my desk at home"));
        chunk(o, "IDAT", new byte[] {1, 2, 3});
        chunk(o, "eXIf", bytes("GPS"));
        chunk(o, "IEND", new byte[0]);
        return o.toByteArray();
    }

    static void chunk(ByteArrayOutputStream o, String type, byte[] data) {
        o.writeBytes(new byte[] {0, 0, (byte) (data.length >> 8), (byte) data.length});
        o.writeBytes(bytes(type));
        o.writeBytes(data);
        o.writeBytes(new byte[4]);          // the CRC is not checked
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    static String text(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test
    void aJpegKeepsItsPixelsAndLosesWhereAndWhenItWasTaken() throws Exception {
        byte[] in = jpeg(1280, 960);
        ImageAttachment img = ImageAttachment.of(in);
        assertThat(img.type()).isEqualTo("image/jpeg");
        assertThat(img.width()).isEqualTo(1280);
        assertThat(img.height()).isEqualTo(960);
        byte[] sent = Base64.getDecoder().decode(img.base64());
        assertThat(text(sent)).contains("JFIF").doesNotContain("Exif").doesNotContain("GPS").doesNotContain("phone");
        assertThat(sent).endsWith(0x12, 0x34, (byte) 0xff, 0x00, 0x56, (byte) 0xff, (byte) 0xd9);
        assertThat(img.bytes()).isEqualTo(sent.length).isLessThan(in.length);
        assertThat(img.sha256()).isEqualTo(sha256(sent));
        assertThat(img.describe()).containsOnlyKeys("type", "width", "height", "bytes", "sha256")
                .containsEntry("width", 1280).containsEntry("height", 960);
    }

    @Test
    void aPngKeepsItsPixelsAndLosesItsTextAndExif() {
        ImageAttachment img = ImageAttachment.of(png(300, 200));
        assertThat(img.type()).isEqualTo("image/png");
        assertThat(List.of(img.width(), img.height())).containsExactly(300, 200);
        String sent = text(Base64.getDecoder().decode(img.base64()));
        assertThat(sent).contains("IHDR", "IDAT", "IEND").doesNotContain("tEXt", "desk", "eXIf");
    }

    @Test
    void onlyAJpegOrAPngOfAReasonableSizeIsAccepted() {
        assertThatThrownBy(() -> ImageAttachment.of(bytes("GIF89a....")))
                .isInstanceOf(ImageAttachment.InvalidImage.class).hasMessage("the image must be a JPEG or a PNG");
        assertThatThrownBy(() -> ImageAttachment.of(bytes("<svg xmlns='http://www.w3.org/2000/svg'/>")))
                .hasMessage("the image must be a JPEG or a PNG");
        assertThatThrownBy(() -> ImageAttachment.of(new byte[0])).hasMessage("send an image (JPEG or PNG)");
        byte[] full = jpeg(640, 480);
        assertThatThrownBy(() -> ImageAttachment.of(java.util.Arrays.copyOf(full, 40)))
                .hasMessage("the image is incomplete or damaged");
        assertThatThrownBy(() -> ImageAttachment.of(jpeg(5000, 100)))
                .hasMessage("the image is too large: at most 4096 pixels a side")
                .satisfies(e -> assertThat(((ImageAttachment.InvalidImage) e).tooLarge()).isFalse());
        byte[] huge = java.util.Arrays.copyOf(full, ImageAttachment.MAX_BYTES + 1);
        assertThatThrownBy(() -> ImageAttachment.of(huge)).hasMessage("the image is too large: at most 2 MB")
                .satisfies(e -> assertThat(((ImageAttachment.InvalidImage) e).tooLarge()).isTrue());
        byte[] noEnd = java.util.Arrays.copyOf(png(10, 10), 60);
        assertThatThrownBy(() -> ImageAttachment.of(noEnd)).hasMessage("the image is incomplete or damaged");
    }

    @Test
    void base64AndDataUrlsAreAccepted() {
        byte[] in = jpeg(64, 48);
        String b64 = Base64.getEncoder().encodeToString(in);
        assertThat(ImageAttachment.ofBase64(b64).width()).isEqualTo(64);
        assertThat(ImageAttachment.ofBase64("data:image/jpeg;base64," + b64).height()).isEqualTo(48);
        assertThatThrownBy(() -> ImageAttachment.ofBase64("not base64 !!")).hasMessage("image must be base64");
        assertThatThrownBy(() -> ImageAttachment.ofBase64("data:image/jpeg," + b64)).hasMessage("image must be base64");
        assertThatThrownBy(() -> ImageAttachment.ofBase64("A".repeat(ImageAttachment.MAX_BYTES / 3 * 4 + 16)))
                .hasMessage("the image is too large: at most 2 MB");
    }

    @Test
    void theHistoryKeepsTheTextNeverTheImage() {
        ChatMessage m = ChatMessage.user("look", List.of("aGVsbG8="));
        assertThat(m.images()).containsExactly("aGVsbG8=");
        assertThat(m.withoutImages()).isEqualTo(ChatMessage.user("look"));
        ConversationMemory memory = new ConversationMemory(8, 180);
        memory.remember(Persona.userMessage("What is this?", "Context:", "en", Persona.IMAGE_SHOWN_NOTE), "A cat.", List.of(), 1);
        assertThat(memory.messages(2)).allSatisfy(x -> assertThat(x.images()).isEmpty());
        assertThat(memory.messages(2).get(0).content()).contains(Persona.IMAGE_SHOWN_NOTE + "\nThe person says: What is this?");
    }
}
