// SPDX-License-Identifier: MIT
package marvin.host.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.in.VoiceControl;
import marvin.host.application.conversation.port.out.ConversationStore;
import marvin.host.application.conversation.port.out.VoiceSettingsStore;
import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.application.presence.port.in.PresenceQuery;
import marvin.host.domain.conversation.ConversationEntry;
import marvin.host.domain.conversation.AudioDeviceProfile;
import marvin.host.domain.conversation.VoiceSettings;
import marvin.host.domain.presence.event.EventKind;
import marvin.host.domain.presence.event.PresenceEvent;
import marvin.host.domain.presence.event.PresenceState;
import marvin.host.domain.robot.event.RoomState;
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

        /** As the real voice does: opened again with the same settings, it keeps running and says nothing. */
        boolean quietWhenUnchanged;

        @Override
        public void open(Settings settings, Signals s) {
            boolean same = open && !opened.isEmpty() && opened.getLast().equals(settings);
            opened.add(settings);
            signals = s;
            if (quietWhenUnchanged && same) {
                return;
            }
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
                            new Voice("Thomas", "say", "fr", "fr_FR", true), new Voice("marvin", "qwen", "", "", false))));
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
        return service(model, java.util.Optional::empty);
    }

    VoiceService service(FakeModel model, java.util.function.Supplier<java.util.Optional<String>> robotAudio) {
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
                .containsExactly("La capitale de la France,", "c'est Paris.", "Elle est grande.");
        assertThat(sidecar.sent(VoiceSidecar.Text.class)).extracting(VoiceSidecar.Text::language).containsOnly("fr");
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
    void theRoomSensorsAreInTheContextWhileTheyAreFresh() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w", "Vingt-trois degrés.", "Je ne sais pas.");
        VoiceService v = service(model);
        RoomState[] room = {new RoomState(4.0, "marvin-112233", false, true, false, false, 41.0, true, 23.4, 48.0,
                false, null)};
        v.setRoom(() -> room[0]);
        v.start();
        waitFor(() -> model.calls.size() == 2);
        sidecar.signals.signal(new VoiceSidecar.Heard(1, "Il fait combien ?", "", "fr", "typed", Map.of(), 0));
        waitFor(() -> kinds("reply").size() == 1);
        assertThat((String) kinds("reply").get(0).get("context"))
                .endsWith("- The room, from your own sensors: 23.4 °C, 48 % humidity, last movement 41 seconds ago.");
        room[0] = new RoomState(RoomState.FRESH_S, "marvin-112233", false, true, false, false, 41.0, true, 23.4, 48.0,
                false, null);
        sidecar.signals.signal(new VoiceSidecar.Heard(2, "Et maintenant ?", "", "fr", "typed", Map.of(), 0));
        waitFor(() -> kinds("reply").size() == 2);
        assertThat((String) kinds("reply").get(1).get("context")).doesNotContain("The room");
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
        // the voice has no words of its own: the sentence that says why is the persona's, sent like the answer
        assertThat(sidecar.sent(VoiceSidecar.Text.class)).extracting(VoiceSidecar.Text::text)
                .containsExactly(marvin.host.domain.conversation.Persona.phrase("llm_down", "fr"));
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
    void anAnswerThatChangesLanguageIsSentInClausesTaggedWithTheirLanguage() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w", "C'est une bonne idée, let me know when it's done. À plus tard !");
        started(model);
        waitFor(() -> model.calls.size() == 2);
        sidecar.signals.signal(new VoiceSidecar.Heard(1, "Je lance le build ?", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> kinds("reply").size() == 1);
        assertThat(sidecar.sent(VoiceSidecar.Text.class)).extracting(t -> t.language() + ":" + t.text())
                .containsExactly("fr:C'est une bonne idée,", "en:let me know when it's done.", "fr:À plus tard !");
        assertThat(kinds("reply").get(0).get("text")).isEqualTo(
                "C'est une bonne idée, let me know when it's done. À plus tard !");
    }

    @Test
    void talkNowWhileItThinksLeavesNoEmptyReplyAndTheNextQuestionReplacesIt() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w", "Il est midi.", "À Tokyo, il est dix-neuf heures.");
        started(model);
        waitFor(() -> model.calls.size() == 2);
        sidecar.autoSpeak = false;
        sidecar.signals.signal(new VoiceSidecar.Heard(1, "Quelle heure est-il ?", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> !sidecar.sent(VoiceSidecar.ReplyEnd.class).isEmpty());
        long rid = sidecar.sent(VoiceSidecar.ReplyStart.class).get(0).replyId();
        // Talk now before the first word: the voice cuts it (barge-in) having said nothing
        sidecar.signals.signal(new VoiceSidecar.Interrupted(rid, "barge-in", 1));
        sidecar.signals.signal(new VoiceSidecar.ReplySpoken(rid, 1, "", true, Map.of("total", 0.4)));
        sidecar.autoSpeak = true;
        sidecar.signals.signal(new VoiceSidecar.Heard(2, "Quelle heure est-il ? À Tokyo.", "", "fr", "voice", Map.of(), 0,
                List.of(1L)));
        waitFor(() -> kinds("reply").size() == 1);
        assertThat(kinds("reply")).extracting(e -> e.get("text")).containsExactly("À Tokyo, il est dix-neuf heures.");
        long first = ((Number) kinds("heard").get(0).get("id")).longValue();
        assertThat(kinds("heard").get(1)).containsEntry("replaces", List.of(first)).containsEntry("continues", List.of());
    }

    @Test
    void earconsPlayOnTheVoicesDeviceAndTypedQuestionsSayTheirLanguage() throws InterruptedException {
        VoiceService v = started(FakeModel.of("w", "w"));
        waitFor(() -> v.snapshot().state().equals("on"));
        assertThat(v.snapshot().earcons()).isEqualTo("device");
        assertThat(v.snapshot().device()).isEmpty();                  // the fake voice does not say
        assertThat(v.earcon("wake")).isTrue();
        assertThat(sidecar.sent(VoiceSidecar.Earcon.class)).extracting(VoiceSidecar.Earcon::sound).containsExactly("wake");
        sidecar.signals.signal(new VoiceSidecar.Status("listening", false, "", "", "fake", "fake", 6.0, false, true,
                "computer"));
        assertThat(v.snapshot().asked()).isTrue();
        assertThat(v.snapshot().toMap()).containsEntry("device", "computer").containsEntry("earcons", "device");
        v.ask("What time is it in Tokyo?");
        assertThat(sidecar.sent(VoiceSidecar.Ask.class).getLast().language()).isEqualTo("en");
        settings.values.put("chime", false);
        assertThat(v.snapshot().earcons()).isEqualTo("none");
        assertThat(v.earcon("done")).isFalse();
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

    // ------------------------------------------------------------------ one thought, one question

    @Test
    void aQuestionThatGoesOnBeforeItsAnswerIsHeardIsAnsweredOnceWhole() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w", "C'est noté.", "Développeur et marin, c'est noté.");
        started(model);
        waitFor(() -> model.calls.size() == 2);
        sidecar.autoSpeak = false;
        sidecar.signals.signal(new VoiceSidecar.Heard(1, "Je suis développeur.", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> !sidecar.sent(VoiceSidecar.ReplyEnd.class).isEmpty());
        long rid = sidecar.sent(VoiceSidecar.ReplyStart.class).get(0).replyId();
        // the owner went on talking before the answer was heard: the voice cancels it and asks both as one
        sidecar.signals.signal(new VoiceSidecar.Interrupted(rid, "merged", 1));
        sidecar.autoSpeak = true;
        sidecar.signals.signal(new VoiceSidecar.Heard(2, "Je suis développeur. Et j'aime la voile.", "", "fr", "voice",
                Map.of(), 0, List.of(1L)));
        sidecar.signals.signal(new VoiceSidecar.ReplySpoken(rid, 1, "", true, Map.of("total", 0.3)));
        waitFor(() -> kinds("reply").size() == 1);
        Thread.sleep(100);
        assertThat(kinds("reply")).hasSize(1);
        Map<String, Object> reply = kinds("reply").get(0);
        assertThat(reply).containsEntry("text", "Développeur et marin, c'est noté.").containsEntry("joined", 2)
                .containsEntry("interrupted", false);
        List<Map<String, Object>> heard = kinds("heard");
        assertThat(heard).hasSize(2);
        assertThat(heard.get(1)).containsEntry("joined", 2).containsEntry("replaces", List.of(heard.get(0).get("id")))
                .containsEntry("continues", List.of());
        // the model sees the whole thought once, and nothing of the cancelled question
        var last = model.calls.get(model.calls.size() - 1);
        assertThat(last).hasSize(2);
        assertThat(last.get(1).content()).contains("The person says: Je suis développeur. Et j'aime la voile.");
        // ...and so does the next question
        sidecar.signals.signal(new VoiceSidecar.Heard(3, "Et toi ?", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> kinds("reply").size() == 2);
        var next = model.calls.get(model.calls.size() - 1);
        assertThat(next).hasSize(4);
        assertThat(next.get(1).content()).contains("Je suis développeur. Et j'aime la voile.");
    }

    @Test
    void aQuestionThatCutsAnAnswerToGoOnTakesItsPlaceInTheHistory() throws InterruptedException {
        FakeModel model = FakeModel.of("w", "w", "Il était une fois un phare. Son gardien parlait peu.",
                "Il était une fois un bateau.");
        started(model);
        waitFor(() -> model.calls.size() == 2);
        sidecar.autoSpeak = false;
        sidecar.signals.signal(new VoiceSidecar.Heard(1, "Raconte une histoire.", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> !sidecar.sent(VoiceSidecar.ReplyEnd.class).isEmpty());
        long rid = sidecar.sent(VoiceSidecar.ReplyStart.class).get(0).replyId();
        sidecar.signals.signal(new VoiceSidecar.Interrupted(rid, "barge-in", 1));
        sidecar.signals.signal(new VoiceSidecar.Heard(2, "Raconte une histoire. Avec des bateaux.", "", "fr", "voice",
                Map.of(), 0, List.of(1L)));
        sidecar.autoSpeak = true;
        sidecar.signals.signal(new VoiceSidecar.ReplySpoken(rid, 1, "Il était une fois un phare.", true, Map.of("total", 0.9)));
        waitFor(() -> kinds("reply").size() == 2);
        // what was said stays in the conversation, before the question that goes on
        assertThat(transcript.stream().map(e -> (String) e.get("kind")).filter(k -> !"note".equals(k)).toList())
                .containsExactly("heard", "reply", "heard", "reply");
        assertThat(kinds("reply").get(0)).containsEntry("interrupted", true);
        List<Map<String, Object>> heard = kinds("heard");
        assertThat(heard.get(1)).containsEntry("replaces", List.of())
                .containsEntry("continues", List.of(heard.get(0).get("id")));
        // the cut turn left the history: the joined question says it all
        var last = model.calls.get(model.calls.size() - 1);
        assertThat(last).hasSize(2);
        assertThat(last.get(1).content()).contains("Raconte une histoire. Avec des bateaux.");
    }

    @Test
    void theListeningWindowWaitsWhileSomeoneTalks() throws InterruptedException {
        VoiceService v = started(FakeModel.of("w", "w"));
        sidecar.signals.signal(new VoiceSidecar.Status("listening", false, "", "", "fake", "fake", 2.0));
        assertThat(v.snapshot().hearing()).isFalse();
        sidecar.signals.signal(new VoiceSidecar.Status("listening", false, "", "", "fake", "fake", null, true));
        clock.mono += 10.0;
        assertThat(v.snapshot().listenS()).isNull();
        assertThat(v.snapshot().hearing()).isTrue();
        assertThat(v.snapshot().toMap()).containsEntry("hearing", true).containsEntry("listen_s", null);
        sidecar.signals.signal(new VoiceSidecar.Status("listening", false, "", "", "fake", "fake", 1.5));
        assertThat(v.snapshot().listenS()).isCloseTo(1.5, org.assertj.core.data.Offset.offset(0.01));
        assertThat(v.snapshot().hearing()).isFalse();
    }

    @Test
    void theSettingsForAQuestionThatGoesOnReachTheVoice() throws InterruptedException {
        started(FakeModel.of("w", "w"));
        // every value is sent, defaults included: the voice has none of its own
        assertThat(sidecar.opened.getLast().continueGraceS()).isEqualTo(VoiceSettings.CONTINUE_GRACE_S);
        assertThat(sidecar.opened.getLast().endSilenceLongMs()).isEqualTo(VoiceSettings.END_SILENCE_LONG_MS);
        assertThat(sidecar.opened.getLast().continueJoinS()).isEqualTo(VoiceSettings.CONTINUE_JOIN_S);
        VoiceSettings.VoiceConfig c = VoiceSettings.config(Map.of("continue_grace_s", 1.2, "end_silence_long_ms", 0,
                "continue_join_s", 0, "end_silence_ms", 0));
        assertThat(c.continueGraceS()).isEqualTo(1.2);
        assertThat(c.endSilenceLongMs()).isEqualTo(0.0);
        assertThat(c.continueJoinS()).isEqualTo(0.0);
        assertThat(c.endSilenceMs()).as("0 was the default before").isEqualTo(VoiceSettings.END_SILENCE_MS);
        assertThat(VoiceSettings.FILE_KEYS).contains("continue_grace_s", "end_silence_long_ms", "continue_join_s");
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
        String[] robot = {"marvin-a1b2c3"};
        settings.values.put("audio_route", "auto");
        VoiceService v = service(FakeModel.of("w", "w"), () -> java.util.Optional.ofNullable(robot[0]));
        v.start();
        waitFor(() -> v.snapshot().state().equals("on"));
        assertThat(sidecar.opened.get(0).robot()).isTrue();
        assertThat(sidecar.opened.get(0).device()).isEqualTo(AudioDeviceProfile.ROBOT_DEFAULT.forRobot("marvin-a1b2c3"));
        robot[0] = null;
        v.robotAudioChanged();
        waitFor(() -> sidecar.opened.size() == 2);
        assertThat(sidecar.opened.get(1).robot()).isFalse();
        assertThat(sidecar.opened.get(1).device()).isEqualTo(AudioDeviceProfile.COMPUTER_DEFAULT);
        // only the device changed: the same settings otherwise
        assertThat(sidecar.opened.get(1).on(sidecar.opened.get(0).device())).isEqualTo(sidecar.opened.get(0));
        robot[0] = null;
        v.robotAudioChanged();                                  // nothing changed: nothing sent
        Thread.sleep(100);
        assertThat(sidecar.opened).hasSize(2);
    }

    @Test
    void optionsCombineTheModelsTheVoicesAndTheTools() {
        VoiceService v = service(FakeModel.of());
        Map<String, Object> o = v.options();
        assertThat(new ArrayList<>(o.keySet())).containsExactly("llm_models", "ollama", "stt_backends", "stt_models",
                "tts_backends", "voices", "languages", "platform", "tools", "vision");
        assertThat(o.get("vision")).isEqualTo(Map.of("model", "qwen3:4b-instruct", "own_model", false, "sees", false,
                "error", "This model cannot see images; choose a vision model in Marvin > Voice"));
        assertThat(o.get("llm_models")).isEqualTo(List.of("qwen3:4b-instruct"));
        assertThat(o.get("platform")).isEqualTo("linux");
        assertThat(o.get("voices")).isEqualTo(Map.of("piper", List.of(Map.of("name", "fr_FR-siwis-medium", "installed", true)),
                "say", List.of(Map.of("name", "Thomas", "locale", "fr_FR")),
                "qwen", List.of(Map.of("name", "marvin", "installed", false))));
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

    // ------------------------------------------------------------------ an image with a question

    /** A JPEG's structure (the pixels are not real) with EXIF saying where it was taken. */
    static byte[] jpeg(int width, int height) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.writeBytes(new byte[] {(byte) 0xff, (byte) 0xd8});
        segment(o, 0xe1, "Exif\0\0GPS 43.70N 7.26E".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        segment(o, 0xc0, new byte[] {8, (byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width, 1, 1, 0x11, 0});
        segment(o, 0xda, new byte[] {1, 1, 0, 0, 0x3f, 0});
        o.writeBytes(new byte[] {0x12, 0x34, (byte) 0xff, (byte) 0xd9});
        return o.toByteArray();
    }

    static void segment(java.io.ByteArrayOutputStream o, int marker, byte[] data) {
        int len = data.length + 2;
        o.writeBytes(new byte[] {(byte) 0xff, (byte) marker, (byte) (len >> 8), (byte) len});
        o.writeBytes(data);
    }

    /** A model that answers everything the same way, and sees images when {@code vision} names it. */
    static FakeModel answering(String answer, String... vision) {
        FakeModel m = new FakeModel(msgs -> new ArrayList<>(List.of(answer)));
        Map<String, java.util.Set<String>> caps = new LinkedHashMap<>();
        for (String name : vision) {
            caps.put(name, java.util.Set.of("completion", "vision", "tools"));
        }
        m.capabilities = caps;
        return m;
    }

    static boolean anyImage(List<marvin.host.domain.conversation.ChatMessage> messages) {
        return messages.stream().anyMatch(x -> !x.images().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anImageGoesWithTheNextQuestionOnlyAndTheHistoryKeepsANote() throws InterruptedException {
        FakeModel model = answering("A red square on a white card.", "qwen3:4b-instruct");
        VoiceService v = started(model);
        waitFor(() -> model.calls.size() == 2);
        Map<String, Object> info = v.attachImage(jpeg(1280, 960));
        assertThat(info).containsEntry("type", "image/jpeg").containsEntry("width", 1280).containsEntry("height", 960)
                .containsEntry("model", "qwen3:4b-instruct").containsKeys("bytes", "sha256");
        assertThat(v.snapshot().toMap()).containsEntry("image", info);
        assertThat(published).contains("voice");

        sidecar.signals.signal(new VoiceSidecar.Heard(1, "C'est quoi ?", "", "fr", "typed", Map.of(), 0));
        waitFor(() -> kinds("reply").size() == 1);
        List<marvin.host.domain.conversation.ChatMessage> asked = model.calls.get(2);
        marvin.host.domain.conversation.ChatMessage last = asked.getLast();
        assertThat(last.images()).hasSize(1);
        String sent = new String(java.util.Base64.getDecoder().decode(last.images().get(0)), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(sent).doesNotContain("GPS");                     // the metadata never reaches the model
        assertThat(last.content()).contains(marvin.host.domain.conversation.Persona.IMAGE_NOTE + "\nThe person says: C'est quoi ?");
        assertThat(asked.subList(0, asked.size() - 1)).noneMatch(x -> !x.images().isEmpty());
        assertThat(asked.get(0)).as("the system prompt does not change with an image").isEqualTo(model.calls.get(0).get(0));
        assertThat(v.snapshot().toMap()).doesNotContainKey("image");

        Map<String, Object> heard = kinds("heard").get(0);
        assertThat((Map<String, Object>) heard.get("image")).containsOnlyKeys("type", "width", "height", "bytes", "sha256")
                .containsEntry("sha256", info.get("sha256"));
        Map<String, Object> reply = kinds("reply").get(0);
        assertThat(reply).containsEntry("model", "qwen3:4b-instruct").containsEntry("image", info);
        assertThat((String) reply.get("prompt")).contains(marvin.host.domain.conversation.Persona.IMAGE_NOTE);
        // what is stored holds no image
        String b64 = last.images().get(0);
        assertThat(store.entries).allSatisfy(e -> assertThat(e.data().toString()).doesNotContain(b64.substring(0, 16)));

        sidecar.signals.signal(new VoiceSidecar.Heard(2, "Et sa couleur ?", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> kinds("reply").size() == 2);
        List<marvin.host.domain.conversation.ChatMessage> next = model.calls.get(3);
        assertThat(anyImage(next)).as("the image is not sent again").isFalse();
        assertThat(next.get(1).content()).contains(marvin.host.domain.conversation.Persona.IMAGE_SHOWN_NOTE
                + "\nThe person says: C'est quoi ?").doesNotContain(marvin.host.domain.conversation.Persona.IMAGE_NOTE);
        assertThat(next.get(2).content()).isEqualTo("A red square on a white card.");
        assertThat(kinds("heard").get(1)).doesNotContainKey("image");
        assertThat(kinds("reply").get(1)).doesNotContainKey("image");
    }

    @Test
    void aModelThatCannotSeeIsRefusedAndTheVisionModelLooksInstead() throws InterruptedException {
        FakeModel model = answering("Réponse.", "qwen2.5vl:7b");
        VoiceService v = started(model);
        assertThatThrownBy(() -> v.attachImage(jpeg(640, 480))).isInstanceOf(VoiceControl.ImageRefused.class)
                .hasMessage("This model cannot see images; choose a vision model in Marvin > Voice");
        assertThatThrownBy(() -> v.ask("What is it?", jpeg(640, 480))).isInstanceOf(VoiceControl.ImageRefused.class);
        assertThat(model.capabilityCalls).as("asked once, then remembered").containsExactly("qwen3:4b-instruct");
        assertThat(sidecar.sent(VoiceSidecar.Ask.class)).as("a refused image does not ask").isEmpty();

        v.updateSettings(Map.of("vision_model", "llama3.2:3b"));
        waitFor(() -> sidecar.opened.size() == 2);
        waitFor(() -> v.snapshot().state().equals("on"));
        assertThatThrownBy(() -> v.attachImage(jpeg(640, 480)))
                .hasMessage("llama3.2:3b cannot see images; choose a vision model in Marvin > Voice");

        v.updateSettings(Map.of("vision_model", "qwen2.5vl:7b"));
        waitFor(() -> sidecar.opened.size() == 3);
        waitFor(() -> v.snapshot().state().equals("on"));
        assertThat(v.appSettings()).containsEntry("vision_model", "qwen2.5vl:7b");
        v.ask("Qu'est-ce que c'est ?", jpeg(640, 480));
        assertThat(sidecar.sent(VoiceSidecar.Ask.class)).extracting(VoiceSidecar.Ask::text).containsExactly("Qu'est-ce que c'est ?");
        int before = model.calls.size();
        sidecar.signals.signal(new VoiceSidecar.Heard(1, "Qu'est-ce que c'est ?", "", "fr", "typed", Map.of(), 0));
        waitFor(() -> kinds("reply").size() == 1);
        assertThat(model.modelNames.get(before)).isEqualTo("qwen2.5vl:7b");
        assertThat(model.calls.get(before).getLast().images()).hasSize(1);
        assertThat(kinds("reply").get(0)).containsEntry("model", "qwen2.5vl:7b");

        // the questions without an image stay with the voice's model
        sidecar.signals.signal(new VoiceSidecar.Heard(2, "Merci.", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> kinds("reply").size() == 2);
        assertThat(model.modelNames.getLast()).isEqualTo("qwen3:4b-instruct");
        assertThat(kinds("reply").get(1)).containsEntry("model", "qwen3:4b-instruct");
        @SuppressWarnings("unchecked")
        Map<String, Object> vision = (Map<String, Object>) v.options().get("vision");
        assertThat(vision).containsEntry("model", "qwen2.5vl:7b").containsEntry("own_model", true).containsEntry("sees", true);
    }

    @Test
    void anImageWaitsForASpokenQuestionCanBeRemovedAndExpires() throws InterruptedException {
        FakeModel model = answering("Oui.", "qwen3:4b-instruct");
        VoiceService v = service(model);
        assertThatThrownBy(() -> v.attachImage(jpeg(10, 10))).isInstanceOf(VoiceControl.VoiceOff.class);
        v.start();
        waitFor(() -> v.snapshot().state().equals("on"));
        assertThatThrownBy(() -> v.attachImage("not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(marvin.host.domain.conversation.ImageAttachment.InvalidImage.class)
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> v.ask("  ", jpeg(10, 10))).hasMessage("nothing to ask");
        assertThat(v.snapshot().toMap()).doesNotContainKey("image");

        v.attachImage(jpeg(10, 10));
        v.removeImage();
        assertThat(v.snapshot().toMap()).doesNotContainKey("image");

        v.attachImage(jpeg(20, 10));
        clock.mono += VoiceService.IMAGE_WAIT_S + 1;
        assertThat(v.snapshot().toMap()).as("dropped after waiting too long").doesNotContainKey("image");
        int before = model.calls.size();
        sidecar.signals.signal(new VoiceSidecar.Heard(1, "Tu vois ?", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> kinds("reply").size() == 1);
        assertThat(anyImage(model.calls.get(before))).isFalse();

        // a spoken question takes it as a typed one does
        v.attachImage(jpeg(30, 20));
        sidecar.signals.signal(new VoiceSidecar.Heard(2, "Et maintenant ?", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> kinds("reply").size() == 2);
        assertThat(model.calls.getLast().getLast().images()).hasSize(1);
        assertThat(kinds("heard").get(1)).containsKey("image");
        v.stop();
        waitFor(() -> v.snapshot().state().equals("off"));
    }

    @Test
    void aSettingTheVoiceDoesNotUseRestartsWithoutWaitingForIt() throws InterruptedException {
        sidecar.quietWhenUnchanged = true;
        VoiceService v = started(answering("Oui.", "qwen2.5vl:7b"));
        v.updateSettings(Map.of("vision_model", "qwen2.5vl:7b"));
        waitFor(() -> sidecar.opened.size() == 2);
        waitFor(() -> v.snapshot().state().equals("on"));
        v.updateSettings(Map.of("llm_model", "qwen3:4b-instruct", "home_place", "Nice"));
        waitFor(() -> sidecar.opened.size() == 3);
        waitFor(() -> v.snapshot().state().equals("on"));
        v.updateSettings(Map.of("wake", false));                // one it uses: it reports again
        waitFor(() -> sidecar.opened.size() == 4);
        waitFor(() -> v.snapshot().state().equals("on"));
        assertThat(sidecar.opened.get(3).wake()).isFalse();
    }

    // ------------------------------------------------------------------ the look tool

    /** A model that calls look for "je tiens quoi", describes pictures, and answers from the description. */
    static FakeModel looking(String... vision) {
        FakeModel m = new FakeModel(msgs -> {
            marvin.host.domain.conversation.ChatMessage last = msgs.getLast();
            if (msgs.get(0).content().equals(marvin.host.domain.conversation.tool.Look.SYSTEM_PROMPT)) {
                return new ArrayList<>(List.of("A blue mug, held in a right hand."));
            }
            if ("tool".equals(last.role())) {
                return new ArrayList<>(List.of("Tu tiens une tasse bleue."));
            }
            if (last.content().contains("je tiens quoi")) {
                return new ArrayList<>(List.of(new marvin.host.domain.conversation.ToolCall("look",
                        Map.of("question", "je tiens quoi ?"), "")));
            }
            return new ArrayList<>(List.of("Bonjour."));
        });
        Map<String, java.util.Set<String>> caps = new LinkedHashMap<>();
        for (String name : vision) {
            caps.put(name, java.util.Set.of("completion", "vision", "tools"));
        }
        m.capabilities = caps;
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void withACameraMarvinLooksWhenAskedAndThePictureNeverReachesTheConversation() throws InterruptedException {
        LookToolTest.FakeCamera camera = new LookToolTest.FakeCamera();
        FakeModel model = looking("qwen3:4b-instruct");
        VoiceService v = service(model);
        v.setCamera(camera);
        v.start();
        waitFor(() -> v.snapshot().state().equals("on"));
        waitFor(() -> model.calls.size() == 2);
        assertThat(model.calls.get(0).get(0).content()).contains("call look");
        assertThat(model.tools.get(0)).anySatisfy(t -> assertThat(t.toString()).contains("name=look"));
        assertThat((List<Map<String, Object>>) v.options().get("tools")).extracting(t -> t.get("name")).contains("look");

        sidecar.signals.signal(new VoiceSidecar.Heard(1, "Marvin, je tiens quoi ?", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> kinds("reply").size() == 1);
        assertThat(camera.purposes).containsExactly("look");
        // the round that called look, the picture described, the answer from the description
        assertThat(model.calls).hasSize(5);
        List<marvin.host.domain.conversation.ChatMessage> described = model.calls.get(3);
        assertThat(described).hasSize(2);
        assertThat(described.get(1).images()).hasSize(1);
        assertThat(described.get(1).content()).isEqualTo("The person asks: je tiens quoi ?");
        List<marvin.host.domain.conversation.ChatMessage> answer = model.calls.get(4);
        assertThat(anyImage(answer)).isFalse();
        assertThat(answer.getLast().content()).contains("A blue mug, held in a right hand.");
        assertThat(answer.get(0)).as("the system prompt stays cached").isEqualTo(model.calls.get(2).get(0));
        Map<String, Object> reply = kinds("reply").get(0);
        assertThat((String) reply.get("text")).endsWith("Tu tiens une tasse bleue.");
        assertThat((List<Map<String, Object>>) reply.get("tools")).singleElement()
                .satisfies(t -> assertThat(t).containsEntry("name", "look").containsEntry("ok", true));
        assertThat(sidecar.sent(VoiceSidecar.Filler.class)).extracting(VoiceSidecar.Filler::text).containsExactly("Je regarde…");
        String b64 = described.get(1).images().get(0);
        assertThat(store.entries).allSatisfy(e -> assertThat(e.data().toString()).doesNotContain(b64.substring(0, 16)));

        // the camera goes (the robot left, the setting is off): look is no longer offered, the persona is blind again
        camera.refuse = new marvin.host.application.vision.port.in.Camera.NoPicture("The camera is off", "", true);
        sidecar.signals.signal(new VoiceSidecar.Heard(2, "Bonjour", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> kinds("reply").size() == 2);
        List<marvin.host.domain.conversation.ChatMessage> blind = model.calls.getLast();
        assertThat(blind.get(0).content()).doesNotContain("call look").contains("which you still cannot look at");
        assertThat(model.tools.getLast()).noneSatisfy(t -> assertThat(t.toString()).contains("name=look"));
    }

    @Test
    void withoutACameraOrWithAModelThatCannotSeeMarvinSaysSo() throws InterruptedException {
        // no camera at all: neither the tool nor the persona's camera sentence
        FakeModel plain = looking();
        VoiceService v0 = started(plain);
        waitFor(() -> plain.calls.size() == 2);
        assertThat(plain.calls.get(0).get(0).content()).doesNotContain("call look");
        assertThat(plain.tools.get(0)).noneSatisfy(t -> assertThat(t.toString()).contains("name=look"));
        v0.close();

        LookToolTest.FakeCamera camera = new LookToolTest.FakeCamera();
        FakeModel model = looking();                       // nothing can see
        VoiceService v = service(model);
        v.setCamera(camera);
        v.start();
        waitFor(() -> v.snapshot().state().equals("on"));
        sidecar.signals.signal(new VoiceSidecar.Heard(1, "Marvin, je tiens quoi ?", "", "fr", "voice", Map.of(), 0));
        waitFor(() -> kinds("reply").size() == 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> call = ((List<Map<String, Object>>) kinds("reply").get(0).get("tools")).get(0);
        assertThat(call).containsEntry("ok", false);
        assertThat((String) call.get("error")).isEqualTo("qwen3:4b-instruct cannot see pictures: the owner can choose a vision "
                + "model in Marvin > Voice & models");
        assertThat(model.calls.getLast().getLast().content()).contains("cannot see pictures");
    }
}
