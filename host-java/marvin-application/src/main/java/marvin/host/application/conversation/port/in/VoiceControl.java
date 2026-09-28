// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.in;

/**
 * The voice as the app controls it (Talk panel): on and off, listening, muting, questions. This version
 * of the Java host has no voice yet; {@link #available()} says so, and the app shows why.
 */
public interface VoiceControl {

    /** Whether the voice can be used at all here. */
    boolean available();

    /** Why not, for the app, when it is not available. */
    String unavailableReason();

    /** What to do about it. */
    String unavailableFix();
}
