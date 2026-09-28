// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** A minimal PNG encoder (the Python host's {@code png.py}): 8-bit RGB, no filtering, zlib level 6. */
final class Png {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private Png() {
    }

    /** RGB888 pixels, row-major, {@code width x height} → PNG bytes. */
    static byte[] encode(byte[] rgb, int width, int height) {
        if (rgb.length != width * height * 3) {
            throw new IllegalArgumentException("expected " + width + "x" + height + " RGB pixels");
        }
        byte[] raw = new byte[height * (width * 3 + 1)];                // each row starts with filter type 0
        for (int y = 0; y < height; y++) {
            System.arraycopy(rgb, y * width * 3, raw, y * (width * 3 + 1) + 1, width * 3);
        }
        Deflater d = new Deflater(6);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream(raw.length / 4 + 64);
        byte[] buf = new byte[8192];
        while (!d.finished()) {
            z.write(buf, 0, d.deflate(buf));
        }
        d.end();
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2)
                .put((byte) 0).put((byte) 0).put((byte) 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream(z.size() + 64);
        out.writeBytes(SIGNATURE);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String tag, byte[] body) {
        byte[] t = tag.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(body.length).array());
        out.writeBytes(t);
        out.writeBytes(body);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(body);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
