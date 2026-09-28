// SPDX-License-Identifier: MIT
package marvin.host.application.face.port.in;

/** The robot's face, as pixels for the app. */
public interface FaceImage {

    /** Frame width and height, pixels. */
    int width();

    int height();

    /** The face now, RGB888 row-major; {@code null} when it cannot be drawn. */
    byte[] frame();

    /** The app icon (the content face, square), RGB888 row-major, {@link #width()} square. */
    byte[] icon();

    /** The expression the face is heading to; {@code null} before the face was first drawn. */
    String expression();
}
