// SPDX-License-Identifier: MIT
package marvin.host.adapter.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import marvin.host.application.memory.Consolidator;
import marvin.host.application.memory.port.out.MemoryModel;
import marvin.host.application.memory.testing.MemoryFixture;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactCandidate;
import marvin.host.domain.memory.FactKind;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.Operation;
import marvin.host.domain.memory.Reconciliation;
import marvin.host.domain.memory.Sensitivity;
import marvin.host.domain.shared.JsonText;

/**
 * Memory's extraction evaluation set ({@code memory-eval/cases.json}): each conversation goes through the real prompts,
 * the Ollama adapters and the consolidator, and the facts and operations are scored (precision, recall, operations,
 * dates, sensitivity). By default against the stub Ollama with scripted answers (a check of the harness); against a
 * real Ollama with {@code MARVIN_EVAL_OLLAMA=http://localhost:11434} (and {@code MARVIN_EVAL_MODEL},
 * {@code MARVIN_EVAL_EMBED}): then it reports, and fails only below {@code MARVIN_EVAL_MIN_RECALL} /
 * {@code MARVIN_EVAL_MIN_PRECISION} when they are set. The report is written to
 * {@code target/memory-eval-report.txt}.
 */
class MemoryEvaluationTest {

    record Expected(String subject, List<String> all, List<List<String>> any, String op, int target, String date,
                    String sensitivity) {
    }

    record Scores(int predicted, int expected, int matchedPredictions, int matchedExpected, int ops, int opsRight, int dates,
                  int datesRight, int sensitivities, int sensitivitiesRight) {

        double precision() {
            return predicted == 0 ? 1.0 : (double) matchedPredictions / predicted;
        }

        double recall() {
            return expected == 0 ? 1.0 : (double) matchedExpected / expected;
        }
    }

    static Object resource(String name) throws IOException {
        try (InputStream in = MemoryEvaluationTest.class.getResourceAsStream("/memory-eval/" + name)) {
            return JsonText.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static String norm(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    static boolean matches(Expected e, String subject, String statement) {
        if (!"*".equals(e.subject()) && !e.subject().equalsIgnoreCase(subject)) {
            return false;
        }
        List<String> words = List.of(norm(statement).split("[^a-z0-9]+"));
        for (String w : e.all()) {
            if (!words.contains(norm(w))) {
                return false;
            }
        }
        for (List<String> group : e.any()) {
            if (group.stream().noneMatch(w -> words.contains(norm(w)))) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    static List<Expected> expected(Map<String, Object> c) {
        List<Expected> out = new ArrayList<>();
        for (Object o : (List<Object>) c.get("expected")) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.add(new Expected((String) m.get("subject"), (List<String>) m.getOrDefault("all", List.of()),
                    (List<List<String>>) m.getOrDefault("any", List.of()), (String) m.get("op"),
                    m.get("target") instanceof Number n ? n.intValue() : 0, (String) m.get("date"), (String) m.get("sensitivity")));
        }
        return out;
    }

    static final Pattern OP = Pattern.compile("^(ADD|UPDATE|INVALIDATE|NOOP) \\[([^\\]]*)\\] (.*)$", Pattern.DOTALL);

    @Test
    @SuppressWarnings("unchecked")
    void theEvaluationSet() throws Exception {
        String realHost = System.getenv("MARVIN_EVAL_OLLAMA");
        boolean real = realHost != null && !realHost.isBlank();
        String modelName = real ? System.getenv().getOrDefault("MARVIN_EVAL_MODEL", "qwen3:4b-instruct") : "qwen3:4b-instruct";
        String embedModel = real ? System.getenv().getOrDefault("MARVIN_EVAL_EMBED", "qwen3-embedding:0.6b") : "qwen3-embedding:0.6b";
        Map<String, Object> set = (Map<String, Object>) resource("cases.json");
        Map<String, Object> answers = (Map<String, Object>) resource("stub-answers.json");
        ZoneId zone = ZoneId.of((String) set.get("zone"));
        List<Map<String, Object>> cases = (List<Map<String, Object>>) set.get("cases");

        StringBuilder report = new StringBuilder("Memory evaluation, " + (real ? "Ollama at " + realHost : "stub Ollama")
                + ", model " + modelName + ", embeddings " + embedModel + ", prompts " + OllamaMemoryModel.VERSION + "\n\n");
        int[] totals = new int[10];
        try (StubOllama stub = new StubOllama()) {
            String host = real ? realHost : stub.url();
            Map<String, Object> extract = (Map<String, Object>) answers.get("extract");
            Map<String, Object> reconcile = (Map<String, Object>) answers.get("reconcile");
            for (Map<String, Object> c : cases) {
                String id = (String) c.get("id");
                stub.chat = body -> {
                    Map<String, Object> format = (Map<String, Object>) body.get("format");
                    Map<String, Object> props = (Map<String, Object>) format.get("properties");
                    if (props.containsKey("facts")) {
                        return StubOllama.json(Map.of("facts", extract.getOrDefault(id, List.of())));
                    }
                    String prompt = StubOllama.prompt(body);
                    for (var r : reconcile.entrySet()) {
                        if (prompt.contains("] " + r.getKey() + "\n") || prompt.contains("] " + r.getKey() + " (")) {
                            return StubOllama.json(r.getValue());
                        }
                    }
                    return StubOllama.json(Map.of("operation", "ADD", "target", 0, "statement", "", "valid_to", ""));
                };
                List<Map<String, Object>> lines = (List<Map<String, Object>>) c.get("lines");
                Instant first = LocalDateTime.parse((String) lines.getFirst().get("t")).atZone(zone).toInstant();
                MemoryFixture m = new MemoryFixture(first.plusSeconds(600), new OllamaMemoryModel(), new OllamaEmbedder(), host);
                m.settings.update(Map.of("embed_model", embedModel));

                List<Fact> existing = new ArrayList<>();
                for (Object o : (List<Object>) c.getOrDefault("existing", List.of())) {
                    Map<String, Object> e = (Map<String, Object>) o;
                    MemoryEvent src = m.store.log.appendOne(MemoryEvent.draft(first.minusSeconds(86_400 * 30), "conversation", "heard",
                            Sensitivity.NORMAL, "old:" + UUID.randomUUID(), "(earlier)", Map.of())).orElseThrow();
                    FactCandidate fc = new FactCandidate((String) e.get("subject"), (String) e.get("statement"), FactKind.BIOGRAPHICAL,
                            null, null, 7, Sensitivity.NORMAL, 0.9);
                    Reconciliation.Plan p = Reconciliation.plan(fc, new Operation.Add(), List.of(), List.of(src.id()), src.ts(),
                            src.ts(), UUID::randomUUID, "eval");
                    m.store.log.markConsolidated(List.of(src.id()), src.ts());
                    m.store.facts.apply(p, Map.of(p.added().getFirst().id(), m.embeddings.embed(p.added().getFirst().embeddingText())));
                    existing.add(p.added().getFirst());
                }
                List<MemoryEvent> batch = new ArrayList<>();
                for (Map<String, Object> l : lines) {
                    Instant at = LocalDateTime.parse((String) l.get("t")).atZone(zone).toInstant();
                    batch.add(m.store.log.appendOne(MemoryEvent.draft(at, "conversation", "Marvin".equals(l.get("who")) ? "reply" : "heard",
                            Sensitivity.NORMAL, "eval:" + UUID.randomUUID(), (String) l.get("text"), Map.of())).orElseThrow());
                }
                Consolidator.Result r = m.consolidator.process(batch, new MemoryModel.Target(host, modelName), () -> false);

                List<Expected> exp = expected(c);
                boolean[] used = new boolean[exp.size()];
                int matchedPred = 0;
                int ops = 0;
                int opsRight = 0;
                int dates = 0;
                int datesRight = 0;
                int sens = 0;
                int sensRight = 0;
                StringBuilder lineOut = new StringBuilder();
                for (String op : r.operations()) {
                    Matcher mo = OP.matcher(op);
                    if (!mo.matches()) {
                        continue;
                    }
                    String subject = mo.group(2);
                    String statement = mo.group(3);
                    int hit = -1;
                    for (int i = 0; i < exp.size(); i++) {
                        if (!used[i] && matches(exp.get(i), subject, statement)) {
                            hit = i;
                            break;
                        }
                    }
                    lineOut.append("    ").append(hit >= 0 ? "+ " : "x ").append(op).append('\n');
                    if (hit < 0) {
                        continue;
                    }
                    used[hit] = true;
                    matchedPred++;
                    Expected e = exp.get(hit);
                    if (e.op() != null) {
                        ops++;
                        opsRight += op.startsWith(e.op() + " ") ? 1 : 0;
                    }
                    Fact made = m.store.facts.rows.values().stream().filter(f -> f.statement().equals(statement)).findFirst()
                            .orElse(null);
                    if (e.date() != null) {
                        dates++;
                        datesRight += made != null && made.validFrom() != null
                                && made.validFrom().atZone(zone).toLocalDate().toString().equals(e.date()) ? 1 : 0;
                    }
                    if (e.sensitivity() != null) {
                        sens++;
                        sensRight += made != null && made.sensitivity().wire().equals(e.sensitivity()) ? 1 : 0;
                    }
                }
                int matchedExp = 0;
                for (int i = 0; i < exp.size(); i++) {
                    if (used[i]) {
                        matchedExp++;
                    } else {
                        lineOut.append("    - missed: ").append(exp.get(i).subject()).append(' ').append(exp.get(i).all()).append('\n');
                    }
                }
                for (int i = 0; i < exp.size(); i++) {
                    if (!used[i] && exp.get(i).op() != null) {
                        ops++;
                    }
                }
                int[] s = {r.operations().size(), exp.size(), matchedPred, matchedExp, ops, opsRight, dates, datesRight, sens, sensRight};
                for (int i = 0; i < s.length; i++) {
                    totals[i] += s[i];
                }
                report.append(String.format(Locale.ROOT, "%-18s facts %d/%d found, %d/%d kept right, dropped %d%n", id, matchedExp,
                        exp.size(), matchedPred, r.operations().size(), r.dropped())).append(lineOut);
            }
        }
        Scores t = new Scores(totals[0], totals[1], totals[2], totals[3], totals[4], totals[5], totals[6], totals[7], totals[8],
                totals[9]);
        report.append(String.format(Locale.ROOT, "%nprecision %.2f (%d/%d)  recall %.2f (%d/%d)  operations %d/%d  dates %d/%d  "
                        + "sensitivity %d/%d%n", t.precision(), t.matchedPredictions(), t.predicted(), t.recall(), t.matchedExpected(),
                t.expected(), t.opsRight(), t.ops(), t.datesRight(), t.dates(), t.sensitivitiesRight(), t.sensitivities()));
        Path out = Path.of("target", "memory-eval-report.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println(report);

        if (real) {
            double minRecall = Double.parseDouble(System.getenv().getOrDefault("MARVIN_EVAL_MIN_RECALL", "0"));
            double minPrecision = Double.parseDouble(System.getenv().getOrDefault("MARVIN_EVAL_MIN_PRECISION", "0"));
            assertThat(t.recall()).isGreaterThanOrEqualTo(minRecall);
            assertThat(t.precision()).isGreaterThanOrEqualTo(minPrecision);
        } else {
            // the scripted answers: every case right but the two wrong on purpose
            assertThat(t.expected()).isEqualTo(10);
            assertThat(t.matchedExpected()).isEqualTo(9);
            assertThat(t.predicted()).isEqualTo(10);
            assertThat(t.matchedPredictions()).isEqualTo(9);
            assertThat(t.opsRight()).isEqualTo(3).isEqualTo(t.ops());
            assertThat(t.datesRight()).isEqualTo(1).isEqualTo(t.dates());
            assertThat(t.sensitivitiesRight()).isEqualTo(1).isEqualTo(t.sensitivities());
        }
    }
}
