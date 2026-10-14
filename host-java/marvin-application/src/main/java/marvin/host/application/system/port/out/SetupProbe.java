// SPDX-License-Identifier: MIT
package marvin.host.application.system.port.out;

import java.util.List;
import java.util.Map;

/**
 * What the first-run setup checks and changes outside the system context: this computer, the model, the voice, the
 * devices, the voiceprints, the speaker, and the owner's settings. Each check must return quickly and never throw.
 */
public interface SetupProbe {

    /**
     * One check's answer.
     *
     * @param ok     found and usable
     * @param detail one line for the owner on what was found (or not)
     * @param fix    a command or an action that fixes it, or {@code null}
     */
    record Check(boolean ok, String detail, String fix) {
        public static Check ok(String detail) {
            return new Check(true, detail, null);
        }

        public static Check missing(String detail, String fix) {
            return new Check(false, detail, fix);
        }
    }

    /** Java, the database, the disk. */
    Check host();

    /** Ollama and the voice's language model. */
    Check model();

    /** The voice sidecar and the ears' models. */
    Check voice();

    /** A robot board (or the demo's simulated robot) linked now. */
    Check robot();

    /** The MR60BHA2 kit (or simulated vital signs in the demo) linked now. */
    Check mr60();

    /** At least one voice Marvin knows. */
    Check voiceprint();

    /** Plays the speaker test on the voice's device; {@code ok} when something played, with what and where. */
    Check playSpeakerTest();

    /** The owner as the settings keep them: name (may be empty), languages (ISO 639-1, most used first). */
    Owner owner();

    /** The quiet hours as the settings keep them. */
    QuietHours quietHours();

    /** Saves the owner ({@code name}, {@code languages}). @throws IllegalArgumentException a value the settings refuse */
    void saveOwner(Map<String, ?> owner);

    /** Saves the quiet hours ({@code enabled}, {@code start}, {@code end}). @throws IllegalArgumentException refused */
    void saveQuietHours(Map<String, ?> quietHours);

    /** The owner, as the settings keep it. */
    record Owner(String name, List<String> languages) {
    }

    /** The quiet hours, as the settings keep them ({@code HH:MM}). */
    record QuietHours(boolean enabled, String start, String end) {
    }
}
