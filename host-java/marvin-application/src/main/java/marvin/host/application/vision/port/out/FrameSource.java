// SPDX-License-Identifier: MIT
package marvin.host.application.vision.port.out;

import marvin.host.domain.vision.CameraChoice;
import marvin.host.domain.vision.event.Frame;

/**
 * One camera (the computer's, the robot's): whether it can take a picture now, and one picture taken when asked. The
 * camera is opened for that picture only and closed again (the computer's camera light says so).
 */
public interface FrameSource {

    /** {@link CameraChoice#COMPUTER} or {@link CameraChoice#ROBOT}. */
    String name();

    /** Whether it can take a picture now, as far as can be known without taking one. Cheap: asked often. */
    CameraChoice.Source availability();

    /**
     * One picture, now: a JPEG of at most {@link Frame#MAX_SIDE} pixels a side.
     *
     * @throws CaptureFailed it could not, with what to do
     */
    Frame capture(double timeoutS);

    /** The camera did not give a picture; the message says why, {@code fix} what to do. */
    class CaptureFailed extends RuntimeException {
        private final String fix;

        public CaptureFailed(String message, String fix) {
            super(message);
            this.fix = fix == null ? "" : fix;
        }

        public String fix() {
            return fix;
        }
    }
}
