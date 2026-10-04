// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.in;

import java.util.Map;

/**
 * The voice calibration as the app runs it (Marvin > Voice & models, "Your voice"; docs/voice.md): Marvin measures the
 * room and the microphone, hears a person read a few sentences, shows what it understood, and keeps their voiceprint.
 */
public interface VoiceEnrollment {

    /**
     * Where it is: {@code state} ({@code idle}, {@code noise}, {@code sentence}, {@code heard}, {@code retry},
     * {@code done}, {@code failed}, {@code cancelled}), {@code index}, the {@code sentences}, what was {@code heard} of
     * each, {@code message}, the {@code result} (thresholds and warnings, never the voiceprint), the {@code people}
     * Marvin knows, each device's last {@code calibration}, and {@code first_run} (nobody enrolled, nothing calibrated).
     */
    Map<String, Object> state();

    /**
     * Starts a calibration for {@code name} on the voice's device.
     *
     * @throws marvin.host.domain.conversation.VoiceSettings.InvalidVoiceSettingException not a name, or too many people
     * @throws VoiceControl.VoiceOff the voice is not running
     */
    Map<String, Object> start(Object name);

    /** Stops a calibration going on. */
    Map<String, Object> cancel();

    /** Deletes {@code name}'s voiceprint (Marvin no longer recognises that voice). */
    Map<String, Object> forget(Object name);
}
