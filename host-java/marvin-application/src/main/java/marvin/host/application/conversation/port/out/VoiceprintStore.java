// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.List;

import marvin.host.domain.conversation.Voiceprint;

/**
 * The voiceprints of the people Marvin knows ({@code voiceprints.json} beside {@code voice.json}, on this computer
 * only).
 */
public interface VoiceprintStore {

    /** Every voiceprint, in the order they were made; empty when there are none. */
    List<Voiceprint> load();

    /** Replaces them all. */
    void save(List<Voiceprint> voiceprints);
}
