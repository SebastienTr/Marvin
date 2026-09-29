// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.application.conversation.port.out.ConversationStore;
import marvin.host.application.conversation.port.out.VoiceSettingsStore;
import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.shared.LocalDays;

/**
 * The voice service with a fake voice sidecar and a scripted model: life (Ollama checks, warm-up, on and
 * off), turns (the answer streamed to the voice, the transcript with its latency breakdown and what the model
 * was given), interruptions, proactive speech, settings and commands (the Python host's
 * test_voice_control.py and test_ui_conversation.py, for the Java host).
 */
class VoiceServiceTest {
    final List<VoiceService> services = new ArrayList<>();

    @AfterEach
    void close() {
        services.forEach(VoiceService::close);
    }

    // ------------------------------------------------------------------ fakes

    static final class Sidecar implements VoiceSidecar {
        final List<Command> sent = new CopyOnWriteArrayList<>();
        final List<Settings> opened = new CopyOnWriteArrayList<>();
        volatile Signals signals;
        volatile boolean open;
        volatile Status onOpen = new Status("idle", false, "", "", "fake", "fake", null);
        boolean autoSpeak = true;

        @Override
        public void open(Settings settings, Signals s) {
            opened.add(settings);
            signals = s;
            open = true;
            s.signal(new Status("starting", false, "", "", "", "", null));
            s.signal(onOpen);
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public void send(Command c) {
            sent.add(c);
            if (autoSpeak && c instanceof ReplyEnd e) {
                // the voice said everything it was sent
                StringBuilder said = new StringBuilder();
                long uid = 0;
                for (Command x : sent) {
                    if (x instanceof ReplyStart r && r.replyId() == e.replyId()) {
                        uid = r.utteranceUid();
                    } else if (x instanceof Text t && t.replyId() == e.replyId()) {
                        said.append(said.isEmpty() ? "" : " ").append(t.text().strip());
                    } else if (x instanceof Filler f && f.replyId() == e.replyId()) {
                        said.append(said.isEmpty() ? "" : " ").append(f.text());
                    }
                }
                Map<String, Double> lat = new LinkedHashMap<>();
                lat.put("reply_start", 0.01);
                lat.put("first_chunk", 0.2);
                lat.put("tts", 0.1);
                lat.put("audio_start", 0.4);
                lat.put("total", 1.5);
                signals.signal(new ReplySpoken(e.replyId(), uid, said.toString(), false, lat));
            }
            if (c instanceof Say s) {
                signals.signal(new ReplySpoken(s.replyId(), 0, s.text(), false, Map.of("total", 1.0)));
            }
        }

        @Override
        public Optional<Options> options() {
            return Optional.of(new Options(List.of(new Backend("auto", true, "")), List.of("auto"),
                    List.of(new Backend("auto", true, ""), new Backend("say", false, "macOS only"),
                            new Backend("piper", true, ""), new Backend("espeak", false, "install espeak-ng")),
                    List.of(new Voice("fr_FR-siwis-medium", "piper", "fr", "", true),
                            new Voice("Thomas", "say", "fr", "fr_FR", true))));
        }

        <T extends Command> List<T> sent(Class<T> type) {
            return sent.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    static final class Store implements ConversationStore {
        final List<ConversationEntry> entries = new CopyOnWriteArrayList<>();

        @Override
        public void add(ConversationEntry e) {
            entries.removeIf(x -> x.id() == e.id());
            entries.add(e);
        }

        @Override
        public List<ConversationEntry> after(long afterId, int limit) {
            return entries.stream().filter(e -> e.id() > afterId).sorted(Comparator.comparingLong(ConversationEntry::id))
                    .limit(limit).toList();
        }

        @Override
        public List<ConversationEntry> between(double start, double end, int limit) {
            return entries.stream().filter(e -> e.t() >= start && e.t() < end)
                    .sorted(Comparator.comparingDouble(ConversationEntry::t)).toList();
        }

        @Override
        public List<ConversationEntry> search(String query, int limit) {
            return List.of();
        }

        @Override
        public long maxId() {
            return entries.stream().mapToLong(ConversationEntry::id).max().orElse(0);
        }
    }

    static final class Settings implements VoiceSettingsStore {
        final Map<String, Object> values = new LinkedHashMap<>();

        @Override
        public Map<String, Object> load() {
            return new LinkedHashMap<>(values);
        }

        @Override
        public void save(Map<String, Object> v) {
            values.putAll(v);
        }
    }

    static final class Clock implements Clocks {
        volatile double wall = 1_790_000_000.0;
        volatile double mono = 100.0;

        @Override
        public double wallSeconds() {
            return wall;
        }

        @Override
        public double monotonicSeconds() {
            mono += 0.001;
            return mono;
        }
    }

    final Sidecar sidecar = new Sidecar();
    final Store store = new Store();
    final Settings settings = new Settings();
    final Clock clock = new Clock();
    final List<String> published = new CopyOnWriteArrayList<>();
    final List<Map<String, Object>> transcript = new CopyOnWriteArrayList<>();
    PresenceState state = new PresenceState(3_600_000_000L, true, true, null, null, 0.85, 0, 0, 720, 14.0, 66.0, true,
            false, 1);

    VoiceService service(FakeModel model) {
        return service(model, () -> false);
    }

    VoiceService service(FakeModel model, BooleanSupplier robotAudio) {
        PresenceQuery presence = new PresenceQuery() {
            @Override
            public PresenceState state() {
                return state;
            }

            @Override
            public List<PresenceEvent> recentEvents() {
                return List.of(new PresenceEvent(EventKind.SAT_DOWN, 3_600_000_000L - 720_000_000L, "", Map.of()));
            }
        };
        VoiceService v = new VoiceService(sidecar, model, settings, (url, params, t) -> Map.of(), store, presence, clock,
                new LocalDays(ZoneId.of("Europe/Paris")), "linux", robotAudio);
        v.addListener((kind, payload) -> {
            published.add(kind);
            if (kind.equals("transcript")) {
                transcript.add(payload);
            }
        });
        services.add(v);
        return v;
    }

    static void waitFor(BooleanSupplier cond) throws InterruptedException {
        for (int i = 0; i < 400 && !cond.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertThat(cond.getAsBoolean()).as("condition").isTrue();
    }

    VoiceService started(FakeModel model) throws InterruptedException {
        VoiceService v = service(model);
        v.start();
        waitFor(() -> v.snapshot().state().equals("on"));
        return v;
    }

    List<Map<String, Object>> kinds(String kind) {
        return transcript.stream().filter(e -> kind.equals(e.get("kind"))).toList();
    }

    // ------------------------------------------------------------------ life

    @Test
    void ollamaDownOrWithoutTheModelIsAnErrorWithAFix() throws InterruptedException {
        FakeModel model = FakeModel.of();
        model.down = true;
        VoiceService v = service(model);
        v.start();
        waitFor(() -> v.snapshot().state().equals("error"));
        assertThat(v.snapshot().error()).startsWith("Ollama is not running at http://localhost:11434");
        assertThat(v.snapshot().fix()).contains("ollama serve");
        assertThat(sidecar.open).as("the session is closed again").isFalse();
        v.stop();                                           // stop clears the error
        assertThat(v.snapshot().state()).isEqualTo("off");
        assertThat(v.snapshot().error()).isEmpty();

        model.down = false;
        model.models = List.of("llama3.2:3b");
        v.start();
        waitFor(() -> v.snapshot().state().equals("error"));
        assertThat(v.snapshot().error()).isEqualTo("Ollama has no model qwen3:4b-instruct");
        assertThat(v.snapshot().fix()).isEqualTo("ollama pull qwen3:4b-instruct");
        model.models = List.of("qwen3:4b-instruct:latest");
        v.stop();
        v.start();
        waitFor(() -> v.snapshot().state().equals("on"));
    }

    @Test
    void startingRehearsesTheFirstQuestionAndOpensTheVoice() throws InterruptedException {
        FakeModel model = FakeModel.of("Bonjour.", "Bonjour.");
        VoiceService v = started(model);
        assertThat(v.snapshot().status()).isEqualTo("idle");
        waitFor(() -> model.calls.size() == 2);
        assertThat(model.calls.get(0)).isEqualTo(model.calls.get(1));
        assertThat(model.calls.get(0).get(0).content()).contains("Tools:");
        assertThat(model.tools.get(0)).isNotNull().isSameAs(model.tools.get(1));
        assertThat(sidecar.opened).hasSize(1);
        VoiceSidecar.Settings s = sidecar.opened.get(0);
        assertThat(s.wake()).isTrue();
        assertThat(s.robot()).isFalse();
        assertThat(kinds("note")).extracting(e -> e.get("text")).containsExactly("Voice on: say “Marvin, …”");
        assertThat(published).contains("voice");
        v.stop();
        waitFor(() -> v.snapshot().state().equals("off"));
        assertThat(sidecar.open).isFalse();
        waitFor(() -> kinds("note").size() == 2);
        assertThat(kinds("note")).extracting(e -> e.get("text")).containsExactly("Voice on: say “Marvin, …”", "Voice off");
    }

    @Test
    void theVoiceSaysWhyItCannotStart() throws InterruptedException {
        sidecar.onOpen = new VoiceSidecar.Status("error", false, "No microphone (none)", "Plug in a microphone.", "", "", null);
        VoiceService v = service(FakeModel.of());
        v.start();
        waitFor(() -> v.snapshot().state().equals("error"));
        assertThat(v.snapshot().error()).isEqualTo("No microphone (none)");
        assertThat(v.snapshot().fix()).isEqualTo("Plug in a microphone.");
    }

    @Test
    void theAudioProblemComesBeforeTheModelServer() throws InterruptedException {
        sidecar.onOpen = new VoiceSidecar.Status("error", false, "No microphone (PortAudio library not found)",
                "sudo apt install libportaudio2", "", "", null);
        FakeModel model = FakeModel.of();
        model.down = true;
        VoiceService v = service(model);
        v.start();
        waitFor(() -> v.snapshot().state().equals("error"));
        assertThat(v.snapshot().error()).isEqualTo("No microphone (PortAudio library not found)");
        assertThat(v.snapshot().fix()).isEqualTo("sudo apt install libportaudio2");
        assertThat(sidecar.open).isFalse();
    }

    @Test
    void listenSecondsCountDownFromTheLastStatus() throws InterruptedException {
        VoiceService v = started(FakeModel.of("w", "w"));
        assertThat(v.snapshot().listenS()).isNull();
        sidecar.signals.signal(new VoiceSidecar.Status("listening", false, "", "", "fake", "fake", 8.0));
        clock.mono += 3.0;
        assertThat(v.snapshot().listenS()).isCloseTo(5.0, org.assertj.core.data.Offset.offset(0.01));
        clock.mono += 10.0;
        assertThat(v.snapshot().listenS()).isEqualTo(0.0);
        sidecar.signals.signal(new VoiceSidecar.Status("idle", false, "", "", "fake", "fake", null));
        assertThat(v.snapshot().listenS()).isNull();
    }

    // ------------------------------------------------------------------ turns

    @Test
    void aHeardQuestionIsAnsweredThroughTheVoiceAndKept() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w", "La capitale de la France, c'est Paris. Elle est grande.");
        VoiceService v = started(model);
        waitFor(() -> model.calls.size() == 2);
        Map<String, Double> heardLat = new LinkedHashMap<>();
        heardLat.put("endpoint", 0.55);
        heardLat.put("stt", 0.18);
        clock.wall = 1_790_000_100.0;
        sidecar.signals.signal(new VoiceSidecar.Heard(7, "Quelle est la capitale ?", "Marvin, quelle est la capitale ?",
                "fr", "voice", heardLat, 1_790_000_099.5));
        waitFor(() -> kinds("reply").size() == 1);
        VoiceSidecar.ReplyStart start = sidecar.sent(VoiceSidecar.ReplyStart.class).get(0);
        assertThat(start.utteranceUid()).isEqualTo(7);
        assertThat(start.language()).isEqualTo("fr");
        assertThat(start.proactive()).isFalse();
        assertThat(sidecar.sent(VoiceSidecar.Text.class)).extracting(VoiceSidecar.Text::text)
                .containsExactly("La capitale de la France,\n", "c'est Paris.\n", "Elle est grande.\n");
        assertThat(sidecar.sent(VoiceSidecar.ReplyEnd.class).get(0).error()).isEmpty();

        Map<String, Object> heard = kinds("heard").get(0);
        assertThat(heard).containsEntry("text", "Quelle est la capitale ?").containsEntry("language", "fr")
                .containsEntry("source", "voice").containsEntry("raw", "Marvin, quelle est la capitale ?")
                .containsEntry("t", 1_790_000_099.5);
        assertThat(new ArrayList<>(heard.keySet())).containsExactly("id", "t", "kind", "text", "language", "source", "raw");
        Map<String, Object> reply = kinds("reply").get(0);
        assertThat(reply.get("text")).isEqualTo("La capitale de la France, c'est Paris. Elle est grande.");
        assertThat(new ArrayList<>(reply.keySet())).containsExactly("id", "t", "kind", "text", "language", "latency",
                "first_word_s", "interrupted", "proactive", "error", "hint", "context", "prompt", "model");
        @SuppressWarnings("unchecked")
        Map<String, Double> lat = (Map<String, Double>) reply.get("latency");
        assertThat(new ArrayList<>(lat.keySet())).startsWith("endpoint", "stt", "llm_first_token", "first_chunk")
                .contains("tts", "audio_start", "total").doesNotContain("reply_start");
        assertThat(reply.get("first_word_s")).isEqualTo(0.95);
        assertThat((String) reply.get("context")).startsWith("Context:\n- It is ")
                .contains("Someone is in front of you, about 0.8 metres from you.",
                        "They have been seated for 12 minutes.", "breathing at 14 per minute");
        assertThat((String) reply.get("prompt")).endsWith("The person says: Quelle est la capitale ?\n\n(Answer in French.)");
        assertThat(reply).containsEntry("model", "qwen3:4b-instruct").containsEntry("interrupted", false)
                .containsEntry("proactive", false).containsEntry("error", null).containsEntry("hint", "");
        assertThat((long) heard.get("id")).isLessThan((long) reply.get("id"));
        assertThat(store.entries).extracting(ConversationEntry::kind).containsExactly("note", "heard", "reply");

        // the next question carries the conversation, and the same system prompt
        sidecar.signals.signal(new VoiceSidecar.Heard(8, "Et de l'Italie ?", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> kinds("reply").size() == 2);
        var third = model.calls.get(3);
        assertThat(third).hasSize(4);
        assertThat(third.get(0)).isEqualTo(model.calls.get(2).get(0));
        assertThat(third.get(2).content()).isEqualTo("La capitale de la France, c'est Paris. Elle est grande.");
        assertThat(v.recent(0)).hasSize(5);
    }

    @Test
    void aFailingModelSaysSoAndIsNotRemembered() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w");
        started(model);
        waitFor(() -> model.calls.size() == 2);
        model.down = true;
        sidecar.signals.signal(new VoiceSidecar.Heard(1, "Bonjour ?", "", "fr", "typed", Map.of(), 0));
        waitFor(() -> kinds("reply").size() == 1);
        assertThat(sidecar.sent(VoiceSidecar.ReplyEnd.class).get(0).error()).isEqualTo("llm_down");
        Map<String, Object> reply = kinds("reply").get(0);
        assertThat(reply).containsEntry("error", "llm_down");
        assertThat((String) reply.get("hint")).contains("Connection refused");
        assertThat(kinds("heard").get(0)).containsEntry("source", "typed").doesNotContainKey("raw");
    }

    @Test
    void anInterruptedAnswerIsMarkedAndADroppedQuestionLeavesNoReply() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w", "Une réponse. Encore une phrase.", "Suite.");
        started(model);
        waitFor(() -> model.calls.size() == 2);
        sidecar.autoSpeak = false;
        sidecar.signals.signal(new VoiceSidecar.Heard(1, "Raconte ?", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> !sidecar.sent(VoiceSidecar.ReplyEnd.class).isEmpty());
        long rid = sidecar.sent(VoiceSidecar.ReplyStart.class).get(0).replyId();
        sidecar.signals.signal(new VoiceSidecar.Interrupted(rid, "barge-in", 1));
        sidecar.signals.signal(new VoiceSidecar.ReplySpoken(rid, 1, "Une réponse.", true, Map.of("total", 0.9)));
        waitFor(() -> kinds("reply").size() == 1);
        assertThat(kinds("reply").get(0)).containsEntry("interrupted", true).containsEntry("text", "Une réponse.");

        // dropped before it was said: the voice only says it was interrupted
        sidecar.signals.signal(new VoiceSidecar.Heard(2, "Et alors ?", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> sidecar.sent(VoiceSidecar.ReplyEnd.class).size() == 2);
        long rid2 = sidecar.sent(VoiceSidecar.ReplyStart.class).get(1).replyId();
        sidecar.signals.signal(new VoiceSidecar.Interrupted(rid2, "stop", 2));
        Thread.sleep((long) (VoiceService.SPOKEN_GRACE_S * 1000) + 600);
        assertThat(kinds("reply")).hasSize(1);
        assertThat(kinds("heard")).hasSize(2);
    }

    @Test
    void aQuestionThatInterruptsAnAnswerIsAskedWithThatAnswerInItsHistory() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w", "Il fait beau à Paris. Vingt degrés.", "À Londres, il pleut.");
        started(model);
        waitFor(() -> model.calls.size() == 2);
        sidecar.autoSpeak = false;
        sidecar.signals.signal(new VoiceSidecar.Heard(1, "Quel temps à Paris ?", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> !sidecar.sent(VoiceSidecar.ReplyEnd.class).isEmpty());
        long rid = sidecar.sent(VoiceSidecar.ReplyStart.class).get(0).replyId();
        // as the sidecar does: the new question is heard before the answer it cuts short has ended
        sidecar.signals.signal(new VoiceSidecar.Heard(2, "Et à Londres ?", "", "fr", "voice", Map.of(), 0));
        Thread.sleep(200);
        assertThat(model.calls).as("the second question waits for the first turn").hasSize(3);
        sidecar.signals.signal(new VoiceSidecar.Interrupted(rid, "barge-in", 1));
        sidecar.signals.signal(new VoiceSidecar.ReplySpoken(rid, 1, "Il fait beau à Paris.", true, Map.of("total", 0.9)));
        waitFor(() -> model.calls.size() == 4);
        List<marvin.host.domain.conversation.ChatMessage> second = model.calls.get(3);
        assertThat(second).hasSize(4);
        assertThat(second.get(1).content()).contains("Quel temps à Paris ?");
        assertThat(second.get(2).content()).startsWith("Il fait beau à Paris.").endsWith(" …");
        assertThat(second.get(3).content()).contains("Et à Londres ?");
        assertThat(sidecar.sent(VoiceSidecar.ReplyStart.class)).hasSize(2);
    }

    @Test
    void aQuestionDroppedWhileWaitingIsNotAnswered() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w", "Première.", "Troisième.");
        started(model);
        waitFor(() -> model.calls.size() == 2);
        sidecar.autoSpeak = false;
        sidecar.signals.signal(new VoiceSidecar.Heard(1, "Un ?", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> !sidecar.sent(VoiceSidecar.ReplyEnd.class).isEmpty());
        long rid = sidecar.sent(VoiceSidecar.ReplyStart.class).get(0).replyId();
        sidecar.signals.signal(new VoiceSidecar.Heard(2, "Deux ?", "", "fr", "voice", Map.of(), 0));
        sidecar.signals.signal(new VoiceSidecar.Heard(3, "Trois ?", "", "fr", "voice", Map.of(), 0));
        sidecar.signals.signal(new VoiceSidecar.Interrupted(0, "barge-in", 2));      // the voice dropped question 2
        sidecar.autoSpeak = true;
        sidecar.signals.signal(new VoiceSidecar.ReplySpoken(rid, 1, "Première.", false, Map.of("total", 0.9)));
        waitFor(() -> kinds("reply").size() == 2);
        assertThat(sidecar.sent(VoiceSidecar.ReplyStart.class)).extracting(VoiceSidecar.ReplyStart::utteranceUid)
                .containsExactly(1L, 3L);
        assertThat(model.calls.get(3).get(model.calls.get(3).size() - 1).content()).contains("Trois ?");
    }

    @Test
    void breakRemindersAreSpokenButNeverDuringAConversation() throws InterruptedException {
        VoiceService v = started(FakeModel.of("w", "w"));
        v.onPresenceEvent(new PresenceEvent(EventKind.STILL_LONG, 5, "", Map.of("seated_s", 3000.0)));
        waitFor(() -> kinds("reply").size() == 1);
        VoiceSidecar.Say say = sidecar.sent(VoiceSidecar.Say.class).get(0);
        assertThat(say.text()).isEqualTo("Tu es assis depuis 50 minutes. Et si tu faisais une pause ?");
        assertThat(say.proactive()).isTrue();
        assertThat(kinds("reply").get(0)).containsEntry("proactive", true).containsEntry("latency", Map.of())
                .containsEntry("first_word_s", null).doesNotContainKey("context");
        // at most one every ten minutes
        v.onPresenceEvent(new PresenceEvent(EventKind.STILL_LONG, 6, "", Map.of("seated_s", 3600.0)));
        assertThat(sidecar.sent(VoiceSidecar.Say.class)).hasSize(1);
        // while Marvin speaks: skipped (and it may be said at the next one)
        clock.mono += 700;
        sidecar.signals.signal(new VoiceSidecar.Status("speaking", false, "", "", "", "", null));
        v.onPresenceEvent(new PresenceEvent(EventKind.STILL_LONG, 7, "", Map.of("seated_s", 3629.0)));
        assertThat(sidecar.sent(VoiceSidecar.Say.class)).hasSize(1);
        sidecar.signals.signal(new VoiceSidecar.Status("idle", false, "", "", "", "", null));
        v.onPresenceEvent(new PresenceEvent(EventKind.STILL_LONG, 8, "", Map.of("seated_s", 3629.0)));
        assertThat(sidecar.sent(VoiceSidecar.Say.class)).hasSize(2);
        assertThat(sidecar.sent(VoiceSidecar.Say.class).get(1).text()).isEqualTo("Ça fait une heure que tu es assis. Une petite pause ?");
    }

    @Test
    void liveSignalsArePassedOnWithTheirTime() throws InterruptedException {
        started(FakeModel.of("w", "w"));
        List<Map<String, Object>> live = new CopyOnWriteArrayList<>();
        services.get(0).addListener((kind, payload) -> {
            if (VoiceService.LIVE_KINDS.contains(kind)) {
                live.add(Map.of("kind", kind, "payload", payload));
            }
        });
        sidecar.signals.signal(new VoiceSidecar.Level(0.047, false, false));
        sidecar.signals.signal(new VoiceSidecar.Utterance("start", 1));
        sidecar.signals.signal(new VoiceSidecar.Partial(1, "What time"));
        sidecar.signals.signal(new VoiceSidecar.SayProgress(9, "It's 09:32.", 1.01, List.of(0.25, 0.39)));
        assertThat(live).hasSize(4);
        @SuppressWarnings("unchecked")
        Map<String, Object> say = (Map<String, Object>) live.get(3).get("payload");
        assertThat(new ArrayList<>(say.keySet())).containsExactly("text", "seconds", "envelope", "t");
        @SuppressWarnings("unchecked")
        Map<String, Object> level = (Map<String, Object>) live.get(0).get("payload");
        assertThat(new ArrayList<>(level.keySet())).containsExactly("mic", "speech", "gated", "t");
        sidecar.signals.signal(new VoiceSidecar.Ignored("Thanks for watching!", "known hallucination", -41.5));
        assertThat(kinds("ignored").get(0)).containsEntry("reason", "known hallucination").containsEntry("dbfs", -41.5);
    }

    // ------------------------------------------------------------------ commands and settings

    @Test
    void commandsNeedTheVoiceOn() throws InterruptedException {
        VoiceService v = service(FakeModel.of("w", "w"));
        assertThatThrownBy(() -> v.ask("Bonjour")).isInstanceOf(VoiceControl.VoiceOff.class).hasMessage("Marvin's voice is off");
        assertThatThrownBy(() -> v.listenNow(true)).isInstanceOf(VoiceControl.VoiceOff.class);
        assertThatThrownBy(() -> v.stopSpeaking()).isInstanceOf(VoiceControl.VoiceOff.class);
        assertThatThrownBy(() -> v.ask("  ")).isInstanceOf(IllegalArgumentException.class).hasMessage("nothing to ask");
        assertThatThrownBy(() -> v.ask("x".repeat(501))).hasMessage("a question is at most 500 characters");
        v.mute(true);                                   // remembered while off
        assertThat(v.snapshot().muted()).isTrue();
        sidecar.onOpen = new VoiceSidecar.Status("idle", true, "", "", "", "", null);
        v.start();
        waitFor(() -> v.snapshot().state().equals("on"));
        assertThat(sidecar.sent(VoiceSidecar.Mute.class)).extracting(VoiceSidecar.Mute::muted).containsExactly(true);
        v.ask("Quelle heure est-il ?");
        assertThat(sidecar.sent(VoiceSidecar.Ask.class).get(0).text()).isEqualTo("Quelle heure est-il ?");
        v.listenNow(false);
        assertThat(sidecar.sent(VoiceSidecar.ListenNow.class).get(0).on()).isFalse();
    }

    @Test
    void settingsAreCheckedSavedAndRestartTheVoice() throws InterruptedException {
        VoiceService v = service(FakeModel.of("w", "w", "w", "w"));
        assertThatThrownBy(() -> v.updateSettings(Map.of("follow_up_s", "soon")))
                .isInstanceOf(VoiceSettings.InvalidVoiceSettingException.class)
                .hasMessage("follow_up_s must be a number of seconds between 0 and 30");
        Map<String, Object> out = v.updateSettings(Map.of("language", "auto", "home_place", "  Nice  "));
        assertThat(out).containsEntry("language", null).containsEntry("home_place", "Nice");
        assertThat(settings.values).containsEntry("home_place", "Nice");
        assertThat(kinds("note")).extracting(e -> e.get("text")).containsExactly("Voice settings saved");
        v.start();
        waitFor(() -> v.snapshot().state().equals("on"));
        v.updateSettings(Map.of("wake", false));
        waitFor(() -> sidecar.opened.size() == 2);
        waitFor(() -> v.snapshot().state().equals("on"));
        assertThat(sidecar.opened.get(1).wake()).isFalse();
        assertThat(kinds("note")).extracting(e -> e.get("text")).contains("Voice settings saved: restarting", "Voice on: just talk");
        assertThat(v.snapshot().wake()).isFalse();
    }

    @Test
    void theRobotRouteFollowsTheSettingAndTheRobot() throws InterruptedException {
        boolean[] robot = {true};
        settings.values.put("audio_route", "auto");
        VoiceService v = service(FakeModel.of("w", "w"), () -> robot[0]);
        v.start();
        waitFor(() -> v.snapshot().state().equals("on"));
        assertThat(sidecar.opened.get(0).robot()).isTrue();
        robot[0] = false;
        v.robotAudioChanged();
        waitFor(() -> sidecar.opened.size() == 2);
        assertThat(sidecar.opened.get(1).robot()).isFalse();
    }

    @Test
    void optionsCombineTheModelsTheVoicesAndTheTools() {
        VoiceService v = service(FakeModel.of());
        Map<String, Object> o = v.options();
        assertThat(new ArrayList<>(o.keySet())).containsExactly("llm_models", "ollama", "stt_backends", "stt_models",
                "tts_backends", "voices", "languages", "platform", "tools");
        assertThat(o.get("llm_models")).isEqualTo(List.of("qwen3:4b-instruct"));
        assertThat(o.get("platform")).isEqualTo("linux");
        assertThat(o.get("voices")).isEqualTo(Map.of("piper", List.of(Map.of("name", "fr_FR-siwis-medium", "installed", true)),
                "say", List.of(Map.of("name", "Thomas", "locale", "fr_FR"))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) o.get("tools");
        assertThat(tools).extracting(t -> t.get("name")).containsExactly("get_weather");
    }

    @Test
    void todaysConversationComesBackAfterARestart() {
        store.add(new ConversationEntry(5, clock.wall - 60, "heard", "Bonjour", Map.of("language", "fr")));
        store.add(new ConversationEntry(6, clock.wall - 86400 * 2, "heard", "Avant-hier", Map.of()));
        VoiceService v = service(FakeModel.of());
        assertThat(v.recent(0)).extracting(ConversationEntry::text).containsExactly("Bonjour");
        assertThat(v.recent(5)).isEmpty();
    }
}
