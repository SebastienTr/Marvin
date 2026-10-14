// SPDX-License-Identifier: MIT
package marvin.host.application.system.setup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import marvin.host.application.system.port.in.RunSetup;
import marvin.host.application.system.port.out.SetupProbe;
import marvin.host.application.system.port.out.SetupStore;

/**
 * The first-run setup (contract C2): the checks come from the {@link SetupProbe} each time, what only the owner can
 * say (a step skipped, the speaker heard, the quiet hours answered) from the {@link SetupStore}. A step that is ok is
 * ok, skipped or not: plugging the MR60BHA2 kit in later turns its skipped step green by itself.
 */
public final class SetupService implements RunSetup {
    /** This computer must work: everything else may wait. */
    static final Set<String> NOT_SKIPPABLE = Set.of("host");
    static final Map<String, String> LANGUAGE_NAMES = Map.of("fr", "French", "en", "English", "de", "German",
            "es", "Spanish", "it", "Italian", "nl", "Dutch", "pt", "Portuguese");
    /** What a skipped step means, in one line. */
    static final Map<String, String> SKIPPED = Map.of(
            "model", "No language model yet: Marvin cannot answer",
            "voice", "No voice yet: Marvin can neither hear nor speak",
            "robot", "No robot yet: the app works without one",
            "mr60", "No MR60BHA2 kit: heart rate and breathing are off",
            "speaker", "The robot's speaker was not tested",
            "voiceprint", "Marvin does not know your voice yet",
            "owner", "No name: Marvin greets you without one",
            "quiet_hours", "No quiet hours");

    private final SetupProbe probe;
    private final SetupStore store;
    /** The last speaker test, until the owner says whether they heard it (kept in memory only). */
    private volatile SetupProbe.Check lastPlay;

    public SetupService(SetupProbe probe, SetupStore store) {
        this.probe = Objects.requireNonNull(probe, "probe");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Status status() {
        Map<String, Map<String, Object>> saved = store.load();
        List<Step> steps = new ArrayList<>(STEPS.size());
        boolean done = true;
        for (String id : STEPS) {
            Map<String, Object> s = saved.getOrDefault(id, Map.of());
            Step step = step(id, s);
            if (!"ok".equals(step.state()) && Boolean.TRUE.equals(s.get("skipped")) && !NOT_SKIPPABLE.contains(id)) {
                step = new Step(id, "skipped", SKIPPED.get(id), null);
            }
            done &= !"missing".equals(step.state());
            steps.add(step);
        }
        return new Status(List.copyOf(steps), done);
    }

    @Override
    public synchronized Status act(String id, Map<String, ?> body) {
        if (!STEPS.contains(id)) {
            throw new UnknownStep(id);
        }
        Map<String, Object> state = new LinkedHashMap<>(store.load().getOrDefault(id, Map.of()));
        if (body.containsKey("skip")) {
            if (body.size() != 1 || !(body.get("skip") instanceof Boolean skip)) {
                throw new IllegalArgumentException("skip must be true or false, on its own");
            }
            if (skip && NOT_SKIPPABLE.contains(id)) {
                throw new IllegalArgumentException("Marvin needs this computer's host: this step cannot be skipped");
            }
            state.put("skipped", skip);
            store.save(id, state);
            return status();
        }
        switch (id) {
            case "speaker" -> speaker(state, body);
            case "owner" -> {
                if (!body.isEmpty()) {
                    only(body, Set.of("name", "languages"), "the owner takes name and languages");
                    probe.saveOwner(body);
                    state.put("skipped", false);
                    store.save(id, state);
                }
            }
            case "quiet_hours" -> {
                if (!body.isEmpty()) {
                    only(body, Set.of("enabled", "start", "end"), "the quiet hours take enabled, start and end");
                    probe.saveQuietHours(body);
                    state.put("answered", true);
                    state.put("skipped", false);
                    store.save(id, state);
                }
            }
            default -> {
                if (!body.isEmpty()) {
                    throw new IllegalArgumentException("send {} to check this step again, or {\"skip\": true}");
                }
            }
        }
        return status();
    }

    private void speaker(Map<String, Object> state, Map<String, ?> body) {
        if (body.isEmpty()) {
            return;
        }
        if (body.size() == 1 && Boolean.TRUE.equals(body.get("play"))) {
            lastPlay = probe.playSpeakerTest();
            if (lastPlay.ok()) {
                state.remove("heard");          // a new test: the answer comes after it
                store.save("speaker", state);
            }
            return;
        }
        if (body.size() == 1 && body.get("heard") instanceof Boolean heard) {
            state.put("heard", heard);
            if (heard) {
                state.put("skipped", false);
            }
            store.save("speaker", state);
            return;
        }
        throw new IllegalArgumentException("the speaker takes {\"play\": true} or {\"heard\": true|false}");
    }

    private static void only(Map<String, ?> body, Set<String> keys, String message) {
        if (!keys.containsAll(body.keySet())) {
            throw new IllegalArgumentException(message);
        }
    }

    private Step step(String id, Map<String, Object> s) {
        return switch (id) {
            case "host" -> of(id, probe.host());
            case "model" -> of(id, probe.model());
            case "voice" -> of(id, probe.voice());
            case "robot" -> of(id, probe.robot());
            case "mr60" -> of(id, probe.mr60());
            case "voiceprint" -> of(id, probe.voiceprint());
            case "speaker" -> speakerStep(s);
            case "owner" -> ownerStep();
            case "quiet_hours" -> quietStep(s);
            default -> throw new UnknownStep(id);
        };
    }

    private static Step of(String id, SetupProbe.Check c) {
        return new Step(id, c.ok() ? "ok" : "missing", c.detail(), c.ok() ? null : c.fix());
    }

    private Step speakerStep(Map<String, Object> s) {
        Object heard = s.get("heard");
        if (Boolean.TRUE.equals(heard)) {
            return new Step("speaker", "ok", "You heard Marvin's speaker", null);
        }
        if (Boolean.FALSE.equals(heard)) {
            return new Step("speaker", "missing", "You did not hear the test",
                    "Check the speaker's two wires on the amplifier and turn the volume up, then play it again");
        }
        SetupProbe.Check play = lastPlay;
        if (play != null && !play.ok()) {
            return new Step("speaker", "missing", play.detail(), play.fix());
        }
        if (play != null) {
            return new Step("speaker", "missing", play.detail() + ". Did you hear it?", null);
        }
        return new Step("speaker", "missing", "Play a test sound on the robot and say whether you heard it", null);
    }

    private Step ownerStep() {
        SetupProbe.Owner o = probe.owner();
        if (o.name().isBlank() || o.languages().isEmpty()) {
            return new Step("owner", "missing", "Your name and the languages you speak to Marvin", null);
        }
        List<String> names = o.languages().stream().map(c -> LANGUAGE_NAMES.getOrDefault(c, c)).toList();
        return new Step("owner", "ok", o.name() + " · " + String.join(", ", names), null);
    }

    private Step quietStep(Map<String, Object> s) {
        SetupProbe.QuietHours q = probe.quietHours();
        if (q.enabled()) {
            return new Step("quiet_hours", "ok", "From " + q.start() + " to " + q.end() + ": no reminders, Marvin stays quiet",
                    null);
        }
        if (Boolean.TRUE.equals(s.get("answered"))) {
            return new Step("quiet_hours", "skipped", SKIPPED.get("quiet_hours"), null);
        }
        return new Step("quiet_hours", "missing", "When Marvin should stay quiet: no reminders, nothing said on its own",
                null);
    }
}
