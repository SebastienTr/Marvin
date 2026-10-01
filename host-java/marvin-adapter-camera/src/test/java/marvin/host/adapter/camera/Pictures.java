// SPDX-License-Identifier: MIT
package marvin.host.adapter.camera;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Real JPEGs for the tests. */
final class Pictures {
    private Pictures() {
    }

    static byte[] jpeg(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        return Jpegs.encode(img);
    }
}
