// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The voice's state as the app shows it.
 *
 * @param state   {@code off}, {@code starting}, {@code on}, {@code stopping}, {@code error} (or
 *                {@code unavailable} when this host has no voice)
 * @param status  what the running voice does: {@code idle}, {@code listening}, {@code thinking},
 *                {@code speaking}; {@code off} when it does not run
 * @param listenS seconds left to talk without the name, in a listening window; else {@code null}
 */
public record VoiceSnapshot(String state, String status, boolean muted, String error, String fix, String model,
                            boolean wake, boolean chime, Double listenS) {

    /** In the Python host's key order. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("status", status);
        m.put("muted", muted);
        m.put("error", error);
        m.put("fix", fix);
        m.put("model", model);
        m.put("wake", wake);
        m.put("chime", chime);
        m.put("listen_s", listenS);
        return m;
    }
}
