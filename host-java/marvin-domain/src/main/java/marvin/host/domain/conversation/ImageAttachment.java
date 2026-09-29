// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An image the owner shows Marvin with a question (docs/voice.md "Showing Marvin an image"): a JPEG or a PNG, checked
 * from its own bytes (never from what the client says it is), its size read from its header, and its metadata removed
 * (a JPEG's EXIF, XMP, comments and other application segments; a PNG's text, time and EXIF chunks), so where and when
 * a photo was taken never reaches the model. It lives in memory for one question: nothing here stores it.
 *
 * <p>The app downscales before it sends (longest side 1280 px, JPEG): the limits here are for other clients.
 */
public final class ImageAttachment {
    /** At most this many bytes, as received. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    /** At most this many pixels a side. */
    public static final int MAX_SIDE = 4096;
    /** What is accepted. */
    public static final List<String> TYPES = List.of("image/jpeg", "image/png");

    private final String type;
    private final int width;
    private final int height;
    private final byte[] data;
    private final String sha256;

    private ImageAttachment(String type, int width, int height, byte[] data) {
        this.type = type;
        this.width = width;
        this.height = height;
        this.data = data;
        this.sha256 = sha256(data);
    }

    /** An image that cannot be used; the message says why, for the owner. */
    public static final class InvalidImage extends IllegalArgumentException {
        private final boolean tooLarge;

        public InvalidImage(String message, boolean tooLarge) {
            super(message);
            this.tooLarge = tooLarge;
        }

        /** Too many bytes (the API answers 413), rather than not an image or too many pixels (400). */
        public boolean tooLarge() {
            return tooLarge;
        }
    }

    /**
     * Checks {@code bytes} and removes the metadata.
     *
     * @throws InvalidImage not a JPEG or PNG, truncated, too many bytes or too many pixels
     */
    public static ImageAttachment of(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new InvalidImage("send an image (JPEG or PNG)", false);
        }
        if (bytes.length > MAX_BYTES) {
            throw new InvalidImage("the image is too large: at most " + MAX_BYTES / (1024 * 1024) + " MB", true);
        }
        ImageAttachment img;
        if (isJpeg(bytes)) {
            img = jpeg(bytes);
        } else if (isPng(bytes)) {
            img = png(bytes);
        } else {
            throw new InvalidImage("the image must be a JPEG or a PNG", false);
        }
        if (img.width <= 0 || img.height <= 0) {
            throw new InvalidImage("the image has no size", false);
        }
        if (img.width > MAX_SIDE || img.height > MAX_SIDE) {
            throw new InvalidImage("the image is too large: at most " + MAX_SIDE + " pixels a side", false);
        }
        return img;
    }

    /** Decodes base64 (a {@code data:image/...;base64,} URL too), then {@link #of(byte[])}. */
    public static ImageAttachment ofBase64(String text) {
        return of(decodeBase64(text));
    }

    /**
     * The bytes of base64 text, or of a {@code data:image/...;base64,} URL.
     *
     * @throws InvalidImage empty, not base64, or more than {@link #MAX_BYTES} once decoded
     */
    public static byte[] decodeBase64(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidImage("send an image (JPEG or PNG)", false);
        }
        String t = text.strip();
        if (t.startsWith("data:")) {
            int comma = t.indexOf(',');
            if (comma < 0 || !t.substring(0, comma).endsWith(";base64")) {
                throw new InvalidImage("image must be base64", false);
            }
            t = t.substring(comma + 1);
        }
        if ((long) t.length() * 3 / 4 > MAX_BYTES + 3L) {
            throw new InvalidImage("the image is too large: at most " + MAX_BYTES / (1024 * 1024) + " MB", true);
        }
        try {
            return Base64.getDecoder().decode(t.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new InvalidImage("image must be base64", false);
        }
    }

    public String type() {
        return type;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The bytes the model gets (the metadata removed). */
    public int bytes() {
        return data.length;
    }

    public String sha256() {
        return sha256;
    }

    /** What the model server takes (Ollama's {@code images}). */
    public String base64() {
        return Base64.getEncoder().encodeToString(data);
    }

    /** What is recorded about it (never the image): type, size in pixels and bytes, SHA-256 of what was sent. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("width", width);
        m.put("height", height);
        m.put("bytes", data.length);
        m.put("sha256", sha256);
        return m;
    }

    // ------------------------------------------------------------------ JPEG

    static boolean isJpeg(byte[] b) {
        return b.length >= 4 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff;
    }

    /**
     * Walks the segments up to the image data: the size from the frame header, and every application segment but
     * APP0 (JFIF) and every comment left out; the entropy-coded data after the scan header is copied as it is.
     */
    private static ImageAttachment jpeg(byte[] b) {
        Bytes out = new Bytes(b.length);
        out.write(b, 0, 2);
        int i = 2;
        int width = -1;
        int height = -1;
        while (true) {
            if (i + 4 > b.length) {
                throw truncated();
            }
            if ((b[i] & 0xff) != 0xff) {
                throw new InvalidImage("the image is not a valid JPEG", false);
            }
            int marker = b[i + 1] & 0xff;
            if (marker == 0xff) {                       // fill byte
                i++;
                continue;
            }
            if (marker == 0xd9 || marker >= 0xd0 && marker <= 0xd7 || marker == 0x01) {
                throw new InvalidImage("the image is not a valid JPEG", false);
            }
            int len = u16(b, i + 2);
            if (len < 2 || i + 2 + len > b.length) {
                throw truncated();
            }
            boolean sof = marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
            if (sof) {
                if (len < 7) {
                    throw truncated();
                }
                height = u16(b, i + 5);
                width = u16(b, i + 7);
            }
            boolean metadata = marker >= 0xe1 && marker <= 0xef || marker == 0xfe;
            if (!metadata) {
                out.write(b, i, 2 + len);
            }
            i += 2 + len;
            if (marker == 0xda) {                       // start of scan: the rest is the image
                out.write(b, i, b.length - i);
                break;
            }
        }
        if (width < 0) {
            throw truncated();
        }
        return new ImageAttachment("image/jpeg", width, height, out.toByteArray());
    }

    // ------------------------------------------------------------------ PNG

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    /** Chunks that carry words, dates or EXIF, not pixels. */
    private static final List<String> PNG_METADATA = List.of("tEXt", "zTXt", "iTXt", "tIME", "eXIf");

    static boolean isPng(byte[] b) {
        if (b.length < PNG.length) {
            return false;
        }
        for (int i = 0; i < PNG.length; i++) {
            if (b[i] != PNG[i]) {
                return false;
            }
        }
        return true;
    }

    private static ImageAttachment png(byte[] b) {
        if (b.length < 33 || !"IHDR".equals(chunkType(b, 12))) {
            throw truncated();
        }
        long w = u32(b, 16);
        long h = u32(b, 20);
        Bytes out = new Bytes(b.length);
        out.write(b, 0, PNG.length);
        int i = PNG.length;
        boolean end = false;
        while (i + 12 <= b.length) {
            long len = u32(b, i);
            if (len > b.length - i - 12L) {
                throw truncated();
            }
            String type = chunkType(b, i + 4);
            int size = (int) len + 12;
            if (!PNG_METADATA.contains(type)) {
                out.write(b, i, size);
            }
            i += size;
            if ("IEND".equals(type)) {
                end = true;
                break;
            }
        }
        if (!end) {
            throw truncated();
        }
        return new ImageAttachment("image/png", (int) Math.min(Integer.MAX_VALUE, w), (int) Math.min(Integer.MAX_VALUE, h),
                out.toByteArray());
    }

    private static String chunkType(byte[] b, int at) {
        return new String(b, at, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------------ helpers

    /** What is kept of the input, in order (never longer than the input: only metadata is left out). */
    private static final class Bytes {
        private final byte[] buf;
        private int n;

        Bytes(int capacity) {
            buf = new byte[capacity];
        }

        void write(byte[] b, int at, int len) {
            System.arraycopy(b, at, buf, n, len);
            n += len;
        }

        byte[] toByteArray() {
            return java.util.Arrays.copyOf(buf, n);
        }
    }

    private static InvalidImage truncated() {
        return new InvalidImage("the image is incomplete or damaged", false);
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xff) << 8 | b[at + 1] & 0xff;
    }

    private static long u32(byte[] b, int at) {
        return (long) (b[at] & 0xff) << 24 | (b[at + 1] & 0xff) << 16 | (b[at + 2] & 0xff) << 8 | b[at + 3] & 0xff;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
