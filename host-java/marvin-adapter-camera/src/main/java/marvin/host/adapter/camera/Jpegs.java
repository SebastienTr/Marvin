// SPDX-License-Identifier: MIT
package marvin.host.adapter.camera;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import marvin.host.domain.vision.event.Frame;

/**
 * A camera's JPEG made the size of a picture shown in Talk: at most {@link Frame#MAX_SIDE} pixels a side, as it is when
 * it already fits, else drawn smaller and encoded at {@link Frame#QUALITY}.
 */
final class Jpegs {
    /** A JPEG and its size. */
    record Sized(byte[] jpeg, int width, int height) {
    }

    /** The JPEG is not one: what came is not a picture. */
    static final class NotAJpeg extends Exception {
        NotAJpeg(String message) {
            super(message);
        }
    }

    private Jpegs() {
    }

    /**
     * The first whole JPEG in {@code bytes}, from its start-of-image marker to its end-of-image marker, found by walking
     * its segments (so a thumbnail inside it, or what follows it, does not end it early or late); {@code null} when
     * there is none, or it is cut short.
     */
    static byte[] first(byte[] b) {
        int start = -1;
        for (int i = 0; i + 2 < b.length; i++) {
            if (at(b, i, 0xd8) && (b[i + 2] & 0xff) == 0xff) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int i = start + 2;
        while (i + 1 < b.length) {
            if ((b[i] & 0xff) != 0xff) {
                return null;                                // not a marker where one must be: not a JPEG
            }
            int m = b[i + 1] & 0xff;
            if (m == 0xff) {
                i++;                                        // fill byte
                continue;
            }
            if (m == 0xd9) {
                return java.util.Arrays.copyOfRange(b, start, i + 2);
            }
            if (m == 0x01 || m >= 0xd0 && m <= 0xd7) {
                i += 2;                                     // markers without a length
                continue;
            }
            if (i + 3 >= b.length) {
                return null;
            }
            int len = (b[i + 2] & 0xff) << 8 | b[i + 3] & 0xff;
            if (len < 2) {
                return null;
            }
            i += 2 + len;
            if (m == 0xda) {
                // the compressed picture: it runs to the next marker (an FF not followed by 00 or a restart marker)
                while (i + 1 < b.length) {
                    int n = b[i + 1] & 0xff;
                    if ((b[i] & 0xff) == 0xff && n != 0x00 && !(n >= 0xd0 && n <= 0xd7) && n != 0xff) {
                        break;
                    }
                    i++;
                }
            }
        }
        return null;
    }

    private static boolean at(byte[] b, int i, int marker) {
        return (b[i] & 0xff) == 0xff && (b[i + 1] & 0xff) == marker;
    }

    /** {@code jpeg} at most {@link Frame#MAX_SIDE} pixels a side. */
    static Sized fit(byte[] jpeg) throws NotAJpeg {
        int[] size = size(jpeg);
        int w = size[0];
        int h = size[1];
        if (w <= Frame.MAX_SIDE && h <= Frame.MAX_SIDE) {
            return new Sized(jpeg, w, h);
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (IOException e) {
            throw new NotAJpeg("the picture cannot be decoded: " + e.getMessage());
        }
        if (image == null) {
            throw new NotAJpeg("the picture cannot be decoded");
        }
        double scale = (double) Frame.MAX_SIDE / Math.max(w, h);
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage small = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return new Sized(encode(small), nw, nh);
    }

    /** Width and height from the JPEG's header, without decoding it. */
    static int[] size(byte[] jpeg) throws NotAJpeg {
        if (jpeg.length < 4 || (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8) {
            throw new NotAJpeg("not a JPEG");
        }
        try (InputStream in = new ByteArrayInputStream(jpeg); ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new NotAJpeg("not a JPEG");
            }
            ImageReader r = readers.next();
            try {
                r.setInput(iis, true, true);
                return new int[] {r.getWidth(0), r.getHeight(0)};
            } finally {
                r.dispose();
            }
        } catch (IOException e) {
            throw new NotAJpeg("the picture's header cannot be read: " + e.getMessage());
        }
    }

    static byte[] encode(BufferedImage image) {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            w.setOutput(ios);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(Frame.QUALITY);
            w.write(null, new IIOImage(image, null, null), p);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } finally {
            w.dispose();
        }
        return out.toByteArray();
    }
}
