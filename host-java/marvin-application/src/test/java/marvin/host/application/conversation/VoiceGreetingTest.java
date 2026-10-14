// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.domain.conversation.Greeting;
import marvin.host.domain.presence.Brain;
import marvin.host.domain.presence.BrainConfig;
import marvin.host.domain.presence.TargetSighting;
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
        return started(FakeModel.of("w", "w"));
    }

    private VoiceService started(FakeModel model) throws InterruptedException {
        VoiceService v = t.service(model);
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

    /**
     * The replay (criterion 7): the brain with the host's defaults on a seated session as the LD2450 reports it at
     * 0.6 m (jitter, the point jumping to another part of the body now and then), after the owner was away; the voice
     * greets once, within 5 s of the person sitting down, and not again while they stay.
     */
    @Test
    void replaySitDownThenGreetOnceWithinFiveSeconds() throws InterruptedException {
        VoiceService v = started();
        Brain brain = new Brain(BrainConfig.DEFAULT);
        java.util.Random r = new java.util.Random(7);
        double sitAt = 8.0;
        Double greetedAt = null;
        double jumpUntil = -1;
        double jumpDy = 0;
        for (double ft = 0; ft < sitAt + 60; ft += 0.1) {
            List<TargetSighting> seen;
            if (ft < 3) {
                seen = List.of();
            } else if (ft < sitAt) {
                double k = (ft - 3) / (sitAt - 3);
                seen = List.of(new TargetSighting(40 + r.nextGaussian() * 30, -3000 + k * 2400 + r.nextGaussian() * 30, 160,
                        -48 + r.nextGaussian() * 8));
            } else {
                if (ft > jumpUntil && r.nextDouble() < 0.04) {
                    jumpUntil = ft + 0.2 + r.nextDouble() * 0.8;
                    jumpDy = (r.nextBoolean() ? 1 : -1) * (250 + r.nextDouble() * 130);
                }
                boolean jump = ft <= jumpUntil;
                seen = List.of(new TargetSighting(40 + r.nextGaussian() * 20, -600 + (jump ? jumpDy : 0) + r.nextGaussian() * 20,
                        160, jump ? 40 : r.nextGaussian() * 6));
            }
            t.clock.mono = 1000 + ft;
            for (PresenceEvent e : brain.onTargets(Math.round(ft * 1e6), false, seen)) {
                v.onPresenceEvent(e);
            }
            if (greetedAt == null && !said().isEmpty()) {
                greetedAt = ft;
            }
        }
        assertThat(said()).extracting(VoiceSidecar.Say::text).containsExactly("Bonjour Sébastien !");
        assertThat(greetedAt - sitAt).as("greeted within 5 s of sitting down").isBetween(0.0, 5.0);
    }

    @Test
    void inTheLastLanguageSpoken() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w", "It is noon.");
        VoiceService v = started(model);
        VoiceServiceTest.waitFor(() -> model.calls.size() == 2);          // the warm-up is over
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
