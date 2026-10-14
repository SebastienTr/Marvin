// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.domain.conversation.Greeting;
import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;

/**
 * The voice greets the owner by name when they sit down after an absence (C5): in the last language they spoke, once
 * per return, neutral with a guest in the room, never in quiet hours or during a conversation, off with
 * {@code greet_on_sit}.
 */
class VoiceGreetingTest {
    final VoiceServiceTest t = new VoiceServiceTest();

    @AfterEach
    void close() {
        t.close();
    }

    private static PresenceEvent ev(EventKind kind) {
        return new PresenceEvent(kind, 0, "", Map.of());
    }

    private VoiceService started() throws InterruptedException {
        VoiceService v = t.service(FakeModel.of("w", "w"));
        v.setOwner(() -> new Greeting.Owner("Sébastien", List.of("fr", "en")));
        v.start();
        VoiceServiceTest.waitFor(() -> v.snapshot().state().equals("on"));
        return v;
    }

    private List<VoiceSidecar.Say> said() {
        return t.sidecar.sent(VoiceSidecar.Say.class);
    }

    @Test
    void theOwnerIsGreetedByNameOnSittingDownOncePerReturn() throws InterruptedException {
        VoiceService v = started();
        v.onPresenceEvent(ev(EventKind.ARRIVED));
        assertThat(said()).as("not on arriving: on sitting down").isEmpty();
        v.onPresenceEvent(ev(EventKind.SAT_DOWN));
        VoiceServiceTest.waitFor(() -> t.kinds("reply").size() == 1);
        assertThat(said()).extracting(VoiceSidecar.Say::text).containsExactly("Bonjour Sébastien !");
        assertThat(said().getFirst().proactive()).isTrue();
        assertThat(t.kinds("reply").getFirst()).containsEntry("greeting", true).containsEntry("proactive", true)
                .containsEntry("text", "Bonjour Sébastien !");
        v.onPresenceEvent(ev(EventKind.STOOD_UP));
        v.onPresenceEvent(ev(EventKind.SAT_DOWN));
        assertThat(said()).as("once per return").hasSize(1);
        // a short absence: no new greeting
        v.onPresenceEvent(ev(EventKind.LEFT));
        t.clock.mono += 60;
        v.onPresenceEvent(ev(EventKind.ARRIVED));
        v.onPresenceEvent(ev(EventKind.SAT_DOWN));
        assertThat(said()).hasSize(1);
        // back after half an hour: greeted again
        v.onPresenceEvent(ev(EventKind.LEFT));
        t.clock.mono += Greeting.ABSENCE_S;
        v.onPresenceEvent(ev(EventKind.ARRIVED));
        v.onPresenceEvent(ev(EventKind.SAT_DOWN));
        assertThat(said()).hasSize(2);
    }

    @Test
    void inTheLastLanguageSpoken() throws InterruptedException {
        VoiceService v = started();
        t.sidecar.signals.signal(new VoiceSidecar.Heard(1, "What time is it?", "", "en", "voice", Map.of(), 0));
        VoiceServiceTest.waitFor(() -> t.kinds("reply").size() == 1);
        v.onPresenceEvent(ev(EventKind.ARRIVED));
        v.onPresenceEvent(ev(EventKind.SAT_DOWN));
        VoiceServiceTest.waitFor(() -> !said().isEmpty());
        assertThat(said().getFirst().text()).isEqualTo("Hi Sébastien!");
        assertThat(said().getFirst().language()).isEqualTo("en");
    }

    @Test
    void aGuestInTheRoomGetsANeutralHello() throws InterruptedException {
        t.state = new PresenceState(3_600_000_000L, true, true, null, null, 0.85, 0, 0, 720, 14.0, 66.0, true, false, 2);
        VoiceService v = started();
        v.onPresenceEvent(ev(EventKind.ARRIVED));
        v.onPresenceEvent(ev(EventKind.SAT_DOWN));
        VoiceServiceTest.waitFor(() -> !said().isEmpty());
        assertThat(said().getFirst().text()).isEqualTo("Bonjour !");
    }

    @Test
    void notInQuietHoursNorDuringAConversation() throws InterruptedException {
        VoiceService v = started();
        boolean[] quiet = {true};
        v.setQuiet(() -> quiet[0]);
        v.onPresenceEvent(ev(EventKind.ARRIVED));
        v.onPresenceEvent(ev(EventKind.SAT_DOWN));
        v.onPresenceEvent(new PresenceEvent(EventKind.STILL_LONG, 5, "", Map.of("seated_s", 3000.0)));
        assertThat(said()).as("quiet hours: no greeting, no break reminder").isEmpty();
        quiet[0] = false;
        v.onPresenceEvent(ev(EventKind.LEFT));
        t.clock.mono += Greeting.ABSENCE_S;
        v.onPresenceEvent(ev(EventKind.ARRIVED));
        t.sidecar.signals.signal(new VoiceSidecar.Status("speaking", false, "", "", "", "", null));
        v.onPresenceEvent(ev(EventKind.SAT_DOWN));
        assertThat(said()).as("Marvin is speaking").isEmpty();
    }

    @Test
    void offWithTheSetting() throws InterruptedException {
        t.settings.values.put("greet_on_sit", false);
        t.settings.values.put("welcome_back", true);
        VoiceService v = started();
        v.onPresenceEvent(ev(EventKind.ARRIVED));
        v.onPresenceEvent(ev(EventKind.SAT_DOWN));
        assertThat(said()).as("welcome back needs an absence it saw").isEmpty();
        v.onPresenceEvent(new PresenceEvent(EventKind.LEFT, 1_000_000L, "", Map.of()));
        v.onPresenceEvent(new PresenceEvent(EventKind.ARRIVED, 1_000_000L + 1_900_000_000L, "", Map.of()));
        v.onPresenceEvent(ev(EventKind.SAT_DOWN));
        VoiceServiceTest.waitFor(() -> !said().isEmpty());
        assertThat(said()).extracting(VoiceSidecar.Say::text).containsExactly("Re-bonjour.");
    }
}
