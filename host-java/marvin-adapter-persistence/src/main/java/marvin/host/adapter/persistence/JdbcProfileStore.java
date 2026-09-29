// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import marvin.host.application.memory.port.out.ProfileStore;
import marvin.host.domain.memory.Block;
import marvin.host.domain.memory.BlockVersion;

/** Block versions (the profile, later the soul) in {@code memory.block_version}. */
@Component
public class JdbcProfileStore implements ProfileStore {
    static final String COLUMNS = "id, block, content, tokens, status, rationale, evidence, author, created_at, decided_at, kept_lines";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcProfileStore(JdbcClient jdbc, TransactionTemplate tx, ContextMigrations migrated) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public Optional<BlockVersion> active(Block block) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.block_version WHERE block = ? AND status = 'active'")
                .param(block.wire()).query(JdbcProfileStore::version).optional();
    }

    @Override
    public BlockVersion add(BlockVersion v) {
        return tx.execute(status -> {
            if (v.status() == BlockVersion.Status.ACTIVE) {
                jdbc.sql("UPDATE memory.block_version SET status = 'superseded', decided_at = ? WHERE block = ? AND status = 'active'")
                        .params(MemoryRows.at(v.createdAt()), v.block().wire()).update();
            }
            return jdbc.sql("INSERT INTO memory.block_version (block, content, tokens, status, rationale, evidence, author, created_at, "
                            + "decided_at, kept_lines) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS)
                    .params(v.block().wire(), v.content(), v.tokens(), v.status().wire(), v.rationale(),
                            v.evidence().toArray(Long[]::new), v.author().wire(), MemoryRows.at(v.createdAt()),
                            MemoryRows.at(v.decidedAt()), v.keptLines().toArray(String[]::new))
                    .query(JdbcProfileStore::version).single();
        });
    }

    @Override
    public Optional<BlockVersion> get(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.block_version WHERE id = ?").param(id)
                .query(JdbcProfileStore::version).optional();
    }

    @Override
    public List<BlockVersion> versions(Block block, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM memory.block_version WHERE block = ? ORDER BY id DESC LIMIT ?")
                .params(block.wire(), limit).query(JdbcProfileStore::version).list();
    }

    @Override
    public void redact(long id, String content, int tokens, List<String> keptLines) {
        jdbc.sql("UPDATE memory.block_version SET content = ?, tokens = ?, kept_lines = ? WHERE id = ?")
                .params(content, tokens, keptLines.toArray(String[]::new), id).update();
    }

    @Override
    public void deleteAll() {
        jdbc.sql("DELETE FROM memory.block_version").update();
    }

    static BlockVersion version(ResultSet rs, int n) throws SQLException {
        return new BlockVersion(rs.getLong("id"), Block.parse(rs.getString("block")), rs.getString("content"), rs.getInt("tokens"),
                BlockVersion.Status.parse(rs.getString("status")), rs.getString("rationale"), MemoryRows.longs(rs, "evidence"),
                BlockVersion.Author.parse(rs.getString("author")), MemoryRows.instant(rs, "created_at"),
                MemoryRows.instant(rs, "decided_at"), MemoryRows.strings(rs, "kept_lines"));
    }
}
