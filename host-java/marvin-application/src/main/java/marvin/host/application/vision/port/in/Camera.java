// SPDX-License-Identifier: MIT
package marvin.host.application.vision.port.in;

import java.util.List;
import java.util.Map;

import marvin.host.domain.vision.CameraChoice;
import marvin.host.domain.vision.event.Frame;

/**
 * Marvin's eyes: which camera is used (the {@code camera} setting and what each camera can do now), and one picture
 * taken with it, on demand. Nothing is filmed in between, and no picture is kept.
 */
public interface Camera {

    /** Why a picture was taken: the {@code look} tool, or the app's test. */
    String LOOK = "look";
    String TEST = "test";

    /**
     * What the cameras can do now.
     *
     * @param choice  the setting and the camera it picks (or why none)
     * @param sources every camera this host knows, available or not
     * @param last    the last picture taken (when, which camera, why, its size; never the picture), or {@code null}
     */
    record Status(CameraChoice.Choice choice, List<CameraChoice.Source> sources, Map<String, Object> last) {
    }

    Status status();

    /** A camera can take a picture now (the {@code look} tool is offered then, and only then). */
    boolean available();

    /**
     * Takes one picture now with the chosen camera ({@code purpose}: {@link #LOOK} or {@link #TEST}).
     *
     * @throws NoPicture no camera can be used, or it failed; the message says why, {@link NoPicture#fix()} what to do
     */
    Frame take(String purpose);

    /** No picture: no camera is chosen ({@code unavailable}), or it could not take one. */
    class NoPicture extends RuntimeException {
        private final String fix;
        private final boolean unavailable;

        public NoPicture(String message, String fix, boolean unavailable) {
            super(message);
            this.fix = fix == null ? "" : fix;
            this.unavailable = unavailable;
        }

        public String fix() {
            return fix;
        }

        public boolean unavailable() {
            return unavailable;
        }
    }
}
