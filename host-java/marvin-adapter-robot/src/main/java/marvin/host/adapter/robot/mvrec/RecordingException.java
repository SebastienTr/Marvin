// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot.mvrec;

/** Not a recording, an unsupported version, or a corrupt record. */
public class RecordingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RecordingException(String message) {
        super(message);
    }

    public RecordingException(String message, Throwable cause) {
        super(message, cause);
    }
}
