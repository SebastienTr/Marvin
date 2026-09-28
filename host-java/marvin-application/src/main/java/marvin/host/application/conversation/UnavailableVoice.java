// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import marvin.host.application.conversation.port.in.VoiceControl;

/** No voice in this host yet: the app shows the Talk panel as unavailable, as the Python host does without it. */
public final class UnavailableVoice implements VoiceControl {
    private final String reason;
    private final String fix;

    public UnavailableVoice(String reason, String fix) {
        this.reason = reason;
        this.fix = fix;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String unavailableReason() {
        return reason;
    }

    @Override
    public String unavailableFix() {
        return fix;
    }
}
