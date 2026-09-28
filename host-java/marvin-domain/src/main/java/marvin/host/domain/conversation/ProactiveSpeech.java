// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.Map;
import java.util.Optional;

import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.shared.PyNumbers;

/**
 * Marvin speaking first, from the brain's events (the Python host's {@code voice/proactive.py}): on
 * {@code still_long}, a break reminder (on by default); on {@code arrived} after a long absence,
 * "Welcome back" (off by default). At most one sentence every {@code minIntervalS}. Times are monotonic
 * seconds; not thread-safe.
 */
public final class ProactiveSpeech {
    /** {@code arrived} counts as "back" after this long away. */
    public static final double ABSENCE_S = 30 * 60;
    /** At most one proactive sentence per interval. */
    public static final double MIN_INTERVAL_S = 10 * 60;

    private final boolean stillLong;
    private final boolean welcomeBack;
    private Double lastSpoken;
    private Long leftTUs;

    public ProactiveSpeech(boolean stillLong, boolean welcomeBack) {
        this.stillLong = stillLong;
        this.welcomeBack = welcomeBack;
    }

    /** What to say for {@code event}, in {@code language}, if anything and if not said too recently. */
    public Optional<String> onEvent(PresenceEvent event, String language, double now) {
        String text = null;
        if (event.kind() == EventKind.LEFT) {
            leftTUs = event.tUs();
        } else if (event.kind() == EventKind.STILL_LONG && stillLong) {
            long minutes = PyNumbers.roundToLong(event.data().getOrDefault("seated_s", 0.0) / 60);
            text = minutes >= 55 && minutes <= 65 ? Persona.phrase("still_long_hour", language)
                    : Persona.phrase("still_long", language, Map.of("minutes", minutes));
        } else if (event.kind() == EventKind.ARRIVED && welcomeBack && leftTUs != null
                && (event.tUs() - leftTUs) / 1e6 >= ABSENCE_S) {
            text = Persona.phrase("welcome_back", language);
        }
        if (text == null || (lastSpoken != null && now - lastSpoken < MIN_INTERVAL_S)) {
            return Optional.empty();
        }
        return Optional.of(text);
    }

    /** It was said (not skipped because a conversation was going on). */
    public void spoken(double now) {
        lastSpoken = now;
    }
}
