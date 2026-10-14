// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import marvin.host.application.soul.port.out.SoulStore;
import marvin.host.domain.soul.Guardrails;
import marvin.host.domain.soul.SoulDiff;
import marvin.host.domain.soul.SoulPart;
import marvin.host.domain.soul.SoulProposal;
import marvin.host.domain.soul.SoulVersion;

/** The soul's versions and proposals in memory's schema ({@code soul_version}, {@code soul_proposal}, {@code soul_state}). */
@Component
public class JdbcSoulStore implements SoulStore {
    static final String LAST_REFLECTION = "last_reflection";
    private static final String VERSION_COLUMNS = "part, version, text, extract(epoch from created) AS created, author, note";
    private static final String PROPOSAL_COLUMNS = "id, extract(epoch from created) AS created, state, text, rationale, "
            + "diff::text AS diff, events::text AS events, guardrails::text AS guardrails, base_version, "
            + "extract(epoch from decided) AS decided";

    private final JdbcClient jdbc;

    public JdbcSoulStore(JdbcClient jdbc, ContextMigrations migrated) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SoulVersion> current(SoulPart part) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM memory.soul_version WHERE part = ? ORDER BY version DESC LIMIT 1")
                .params(part.id()).query(JdbcSoulStore::version).optional();
    }

    @Override
    public SoulVersion addVersion(SoulPart part, String text, double created, String author, String note) {
        // the next number in the same statement (the soul service writes one version at a time; a race would fail on
        // the primary key rather than share a number)
        int version = jdbc.sql("INSERT INTO memory.soul_version (part, version, text, created, author, note) VALUES (?, "
                        + "(SELECT COALESCE(MAX(version), 0) + 1 FROM memory.soul_version WHERE part = ?), ?, to_timestamp(?), "
                        + "?, ?) RETURNING version")
                .params(part.id(), part.id(), text, created, author, note).query(Integer.class).single();
        return new SoulVersion(part, version, text, created, author, note);
    }

    @Override
    public Optional<SoulVersion> version(SoulPart part, int version) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM memory.soul_version WHERE part = ? AND version = ?")
                .params(part.id(), version).query(JdbcSoulStore::version).optional();
    }

    @Override
    public List<SoulVersion> versions(int limit) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM memory.soul_version ORDER BY created DESC, version DESC LIMIT ?")
                .params(limit).query(JdbcSoulStore::version).list();
    }

    @Override
    public SoulProposal addProposal(SoulProposal p) {
        long id = jdbc.sql("INSERT INTO memory.soul_proposal (created, state, text, rationale, diff, events, guardrails, "
                        + "base_version, decided) VALUES (to_timestamp(?), ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, "
                        + "to_timestamp(?)) RETURNING id")
                .params(p.created(), p.state().id(), p.text(), p.rationale(), diff(p), JsonValues.write(p.events()),
                        guardrails(p), p.baseVersion(), p.decided())
                .query(Long.class).single();
        return p.withId(id);
    }

    @Override
    public void updateProposal(SoulProposal p) {
        jdbc.sql("UPDATE memory.soul_proposal SET state = ?, decided = to_timestamp(?) WHERE id = ?")
                .params(p.state().id(), p.decided(), p.id()).update();
    }

    @Override
    public Optional<SoulProposal> proposal(long id) {
        return jdbc.sql("SELECT " + PROPOSAL_COLUMNS + " FROM memory.soul_proposal WHERE id = ?").params(id)
                .query(JdbcSoulStore::proposal).optional();
    }

    @Override
    public List<SoulProposal> awaitingOwner() {
        return jdbc.sql("SELECT " + PROPOSAL_COLUMNS + " FROM memory.soul_proposal WHERE decided IS NULL "
                        + "AND state IN ('pending', 'rejected') ORDER BY id DESC")
                .query(JdbcSoulStore::proposal).list();
    }

    @Override
    public Optional<Double> lastReflection() {
        return jdbc.sql("SELECT value FROM memory.soul_state WHERE key = ?").params(LAST_REFLECTION).query(String.class)
                .optional().map(Double::valueOf);
    }

    @Override
    public void reflected(double at) {
        jdbc.sql("INSERT INTO memory.soul_state (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value")
                .params(LAST_REFLECTION, Double.toString(at)).update();
    }

    private static String diff(SoulProposal p) {
        return JsonValues.write(p.diff().stream().map(SoulDiff.Op::toMap).toList());
    }

    private static String guardrails(SoulProposal p) {
        return JsonValues.write(p.guardrails().stream().map(Guardrails.Check::toMap).toList());
    }

    private static List<?> list(String json) {
        return json == null || json.isBlank() ? List.of() : JsonValues.read(json) instanceof List<?> l ? l : List.of();
    }

    private static SoulVersion version(ResultSet rs, int n) throws SQLException {
        return new SoulVersion(SoulPart.of(rs.getString("part")), rs.getInt("version"), rs.getString("text"),
                rs.getDouble("created"), rs.getString("author"), rs.getString("note"));
    }

    private static SoulProposal proposal(ResultSet rs, int n) throws SQLException {
        List<SoulDiff.Op> diff = new ArrayList<>();
        for (Object o : list(rs.getString("diff"))) {
            if (o instanceof Map<?, ?> m) {
                diff.add(new SoulDiff.Op(String.valueOf(m.get("op")), String.valueOf(m.get("text"))));
            }
        }
        List<Long> events = new ArrayList<>();
        for (Object o : list(rs.getString("events"))) {
            if (o instanceof Number x) {
                events.add(x.longValue());
            }
        }
        List<Guardrails.Check> checks = new ArrayList<>();
        for (Object o : list(rs.getString("guardrails"))) {
            if (o instanceof Map<?, ?> m) {
                checks.add(new Guardrails.Check(String.valueOf(m.get("rule")), Boolean.TRUE.equals(m.get("ok")),
                        m.get("reason") instanceof String r ? r : null));
            }
        }
        double d = rs.getDouble("decided");
        Double decided = rs.wasNull() ? null : d;
        return new SoulProposal(rs.getLong("id"), rs.getDouble("created"), SoulProposal.State.of(rs.getString("state")),
                rs.getString("text"), rs.getString("rationale"), diff, events, checks, rs.getInt("base_version"), decided);
    }
}
