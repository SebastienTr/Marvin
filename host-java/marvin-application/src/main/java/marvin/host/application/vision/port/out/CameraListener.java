// SPDX-License-Identifier: MIT
package marvin.host.application.vision.port.out;

import java.util.Map;

/**
 * Told each time a camera takes a picture (the app shows it, so a picture is never taken unseen): which camera, why,
 * its size and when; never the picture.
 */
@FunctionalInterface
public interface CameraListener {
    void pictureTaken(Map<String, Object> picture);
}
