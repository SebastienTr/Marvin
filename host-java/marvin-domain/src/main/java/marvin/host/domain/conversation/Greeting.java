// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;

/**
 * Greeting the owner by name when they sit down after an absence (v1 contract C5, docs/voice.md "The greeting").
 *
 * <p>An {@code arrived} after at least {@code absenceS} away (or the first one since the host started: how long they
 * were away is unknown, and they have not been greeted yet) arms the greeting; the next {@code sat_down} says it, at
 * most once every {@code minIntervalS}. A {@code left} disarms it. Single owner (v1): whoever sits down is assumed to be
 * the owner, so the greeting names them, unless someone else is in the room or the owner has no name yet: then it is
 * neutral. Times are monotonic seconds of the host (the brain's device clock may restart); not thread-safe.
 */
public final class Greeting {
    /** {@code arrived} counts as "back" after this long away. */
    public static final double ABSENCE_S = 30 * 60;
    /** At most one greeting per interval. */
    public static final double MIN_INTERVAL_S = 10 * 60;

    /** The greetings, by language: with the owner's name, and neutral. */
    static final Map<String, String> BY_NAME = Map.of("fr", "Bonjour {name} !", "en", "Hi {name}!",
            "de", "Hallo {name}!", "es", "¡Hola {name}!", "it", "Ciao {name}!", "nl", "Hoi {name}!", "pt", "Olá {name}!");
    static final Map<String, String> NEUTRAL = Map.of("fr", "Bonjour !", "en", "Hello!", "de", "Hallo!",
            "es", "¡Hola!", "it", "Ciao!", "nl", "Hallo!", "pt", "Olá!");

    /**
     * Who Marvin lives with, as the voice sees the app's {@code owner} setting.
     *
     * @param name      what Marvin calls them; empty: not set yet
     * @param languages the languages they speak to Marvin, most used first; empty: not set yet
     */
    public record Owner(String name, List<String> languages) {
        public static final Owner NONE = new Owner("", List.of());

        public Owner {
            name = name == null ? "" : name.strip();
            languages = languages == null ? List.of() : List.copyOf(languages);
        }
    }

    private final double absenceS;
    private final double minIntervalS;
    private Double leftAt;
    private boolean armed;
    private Double lastGreeted;

    public Greeting() {
        this(ABSENCE_S, MIN_INTERVAL_S);
    }

    public Greeting(double absenceS, double minIntervalS) {
        this.absenceS = absenceS;
        this.minIntervalS = minIntervalS;
    }

    /** Whether {@code event}, at {@code now}, is the moment to greet; arming and disarming as it goes. */
    public boolean onEvent(PresenceEvent event, double now) {
        Objects.requireNonNull(event, "event");
        if (event.kind() == EventKind.LEFT) {
            leftAt = now;
            armed = false;
        } else if (event.kind() == EventKind.ARRIVED) {
            armed = leftAt == null || now - leftAt >= absenceS;
        } else if (event.kind() == EventKind.SAT_DOWN && armed) {
            armed = false;                          // once per return, said or not
            return lastGreeted == null || now - lastGreeted >= minIntervalS;
        }
        return false;
    }

    /** It was said. */
    public void greeted(double now) {
        lastGreeted = now;
    }

    /** Whether a greeting waits for the next {@code sat_down}. */
    public boolean armed() {
        return armed;
    }

    /**
     * What to say: the owner's name in {@code language} (English for a language without a greeting), or a neutral
     * greeting when {@code name} is empty.
     */
    public static String text(String name, String language) {
        if (name == null || name.isBlank()) {
            return NEUTRAL.getOrDefault(language, NEUTRAL.get("en"));
        }
        return BY_NAME.getOrDefault(language, BY_NAME.get("en")).replace("{name}", name.strip());
    }

    /**
     * The greeting's language: the last one the owner spoke in, else the first of theirs, else the voice's own.
     *
     * @param lastHeard the language of the last question heard, {@code null} when none
     */
    public static String language(String lastHeard, Owner owner, String voiceLanguage) {
        if (lastHeard != null && !lastHeard.isBlank()) {
            return lastHeard;
        }
        if (owner != null && !owner.languages().isEmpty()) {
            return owner.languages().getFirst();
        }
        return voiceLanguage;
    }
}
