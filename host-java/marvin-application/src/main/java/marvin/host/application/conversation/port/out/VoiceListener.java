// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.Map;

/**
 * Who shows the voice live (the app's event stream): {@code voice} (its state), {@code transcript} (one
 * conversation entry) and the live signals {@code level}, {@code utterance}, {@code partial} and
 * {@code say}. Called from the voice's threads: keep it quick.
 */
public interface VoiceListener {

    void onVoice(String kind, Map<String, Object> payload);
}
