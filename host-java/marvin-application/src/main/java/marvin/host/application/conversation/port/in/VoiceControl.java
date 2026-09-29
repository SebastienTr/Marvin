// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.in;

import java.util.List;
import java.util.Map;

import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.conversation.VoiceSnapshot;

/**
 * The voice as the app controls it (Talk panel, voice settings): on and off, listening, muting, typed
 * questions, and the recent conversation.
 */
public interface VoiceControl {

    /** Whether the voice can be used at all here. */
    boolean available();

    /** Why not, for the app, when it is not available. */
    String unavailableReason();

    /** What to do about it. */
    String unavailableFix();

    /** The voice's state now. */
    VoiceSnapshot snapshot();

    /** The settings the app edits, with the defaults filled in. */
    Map<String, Object> appSettings();

    /**
     * Checks and saves settings, restarting the voice if it runs. Returns the new app settings.
     *
     * @throws marvin.host.domain.conversation.VoiceSettings.InvalidVoiceSettingException for a bad value
     */
    Map<String, Object> updateSettings(Map<String, ?> update);

    /** The recent conversation entries after id {@code since}, oldest first. */
    List<ConversationEntry> recent(long since);

    /** Starts the voice in the background (nothing if it runs or is starting). */
    void start();

    void stop();

    /**
     * Fills the model server's prompt cache again, in the background, as at start: after memory used the voice's model
     * (which evicts the cached prompt) or changed what the prompt holds. Nothing while the voice is off.
     */
    void rewarm();

    /**
     * Drops the conversation's history: something was forgotten, and the earlier questions' memory sections and tool
     * results may still state it.
     */
    void clearHistory();

    /**
     * A typed question.
     *
     * @throws IllegalArgumentException empty or longer than 500 characters
     * @throws VoiceOff                 the voice is not running
     */
    void ask(String text);

    /**
     * A typed question with an image (JPEG or PNG bytes; {@code null}: none): the image is attached as with
     * {@link #attachImage(byte[])}, then the question is asked.
     *
     * @throws IllegalArgumentException empty or too long a question, or not a usable image
     *                                  ({@link marvin.host.domain.conversation.ImageAttachment.InvalidImage})
     * @throws ImageRefused             the model cannot see images
     * @throws VoiceOff                 the voice is not running
     */
    void ask(String text, byte[] image);

    /**
     * An image for the next question, typed or spoken (docs/voice.md "Showing Marvin an image"): kept in memory only,
     * until that question takes it, it is removed, another replaces it, or {@code IMAGE_WAIT_S} pass. Returns what is
     * known of it (type, width, height, bytes, sha256 and the model that will look at it), never the image.
     *
     * @throws IllegalArgumentException not a usable image
     *                                  ({@link marvin.host.domain.conversation.ImageAttachment.InvalidImage})
     * @throws ImageRefused             the model cannot see images
     * @throws VoiceOff                 the voice is not running
     */
    Map<String, Object> attachImage(byte[] image);

    /** Drops the image waiting for the next question, if any. */
    void removeImage();

    /** Talk now ({@code on}), or close the listening window. @throws VoiceOff the voice is not running */
    void listenNow(boolean on);

    /** Remembered across restarts; works while the voice is off (it starts muted). */
    void mute(boolean muted);

    /** @throws VoiceOff the voice is not running */
    void stopSpeaking();

    /** What the settings panel offers: models, speech recognition, voices, tools. */
    Map<String, Object> options();

    /** An image the model cannot look at; the message says what to do, for the owner. */
    class ImageRefused extends RuntimeException {
        public ImageRefused(String message) {
            super(message);
        }
    }

    /** A command for the voice while it is not running. */
    class VoiceOff extends RuntimeException {
        public VoiceOff(String message) {
            super(message);
        }
    }
}
