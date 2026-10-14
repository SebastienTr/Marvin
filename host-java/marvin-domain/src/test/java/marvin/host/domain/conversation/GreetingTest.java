// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;

/** Greeting the owner by name when they sit down after an absence (C5). */
class GreetingTest {
    private static PresenceEvent ev(EventKind kind) {
        return new PresenceEvent(kind, 0, "", Map.of());
    }

    @Test
    void theFirstSitDownSinceTheHostStartedIsGreeted() {
        Greeting g = new Greeting();
        assertThat(g.onEvent(ev(EventKind.SAT_DOWN), 10)).as("not armed before an arrival").isFalse();
        assertThat(g.onEvent(ev(EventKind.ARRIVED), 20)).isFalse();
        assertThat(g.armed()).isTrue();
        assertThat(g.onEvent(ev(EventKind.SAT_DOWN), 25)).isTrue();
        g.greeted(25);
        assertThat(g.onEvent(ev(EventKind.STOOD_UP), 30)).isFalse();
        assertThat(g.onEvent(ev(EventKind.SAT_DOWN), 40)).as("once per return").isFalse();
    }

    @Test
    void aShortAbsenceIsNotGreetedALongOneIs() {
        Greeting g = new Greeting();
        g.onEvent(ev(EventKind.ARRIVED), 0);
        g.onEvent(ev(EventKind.SAT_DOWN), 5);
        g.greeted(5);
        g.onEvent(ev(EventKind.LEFT), 100);
        g.onEvent(ev(EventKind.ARRIVED), 100 + 29 * 60);         // back after 29 minutes
        assertThat(g.onEvent(ev(EventKind.SAT_DOWN), 100 + 29 * 60 + 4)).isFalse();
        g.onEvent(ev(EventKind.LEFT), 2000);
        g.onEvent(ev(EventKind.ARRIVED), 2000 + Greeting.ABSENCE_S);
        assertThat(g.onEvent(ev(EventKind.SAT_DOWN), 2000 + Greeting.ABSENCE_S + 4)).isTrue();
    }

    @Test
    void atMostOnceEveryTenMinutes() {
        Greeting g = new Greeting(60, Greeting.MIN_INTERVAL_S);
        g.onEvent(ev(EventKind.ARRIVED), 0);
        assertThat(g.onEvent(ev(EventKind.SAT_DOWN), 3)).isTrue();
        g.greeted(3);
        g.onEvent(ev(EventKind.LEFT), 10);
        g.onEvent(ev(EventKind.ARRIVED), 80);                     // away long enough, but greeted 77 s ago
        assertThat(g.onEvent(ev(EventKind.SAT_DOWN), 82)).isFalse();
        g.onEvent(ev(EventKind.LEFT), 90);
        g.onEvent(ev(EventKind.ARRIVED), 700);
        assertThat(g.onEvent(ev(EventKind.SAT_DOWN), 703)).isTrue();
    }

    @Test
    void leavingDisarms() {
        Greeting g = new Greeting();
        g.onEvent(ev(EventKind.ARRIVED), 0);
        g.onEvent(ev(EventKind.LEFT), 2);
        assertThat(g.onEvent(ev(EventKind.SAT_DOWN), 3)).isFalse();
    }

    @Test
    void theWordsByLanguage() {
        assertThat(Greeting.text("Sébastien", "fr")).isEqualTo("Bonjour Sébastien !");
        assertThat(Greeting.text("Sébastien", "en")).isEqualTo("Hi Sébastien!");
        assertThat(Greeting.text(" Ana ", "es")).isEqualTo("¡Hola Ana!");
        assertThat(Greeting.text("Sébastien", "ja")).as("English without a greeting of its own").isEqualTo("Hi Sébastien!");
        assertThat(Greeting.text("", "fr")).isEqualTo("Bonjour !");
        assertThat(Greeting.text(null, "en")).isEqualTo("Hello!");
    }

    @Test
    void theLanguageIsTheLastSpokenThenTheOwnersThenTheVoices() {
        Greeting.Owner owner = new Greeting.Owner("Sébastien", List.of("en", "fr"));
        assertThat(Greeting.language("fr", owner, "de")).isEqualTo("fr");
        assertThat(Greeting.language(null, owner, "de")).isEqualTo("en");
        assertThat(Greeting.language("", Greeting.Owner.NONE, "de")).isEqualTo("de");
    }
}
