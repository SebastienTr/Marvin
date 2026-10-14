// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import marvin.host.domain.soul.SoulPart;
import marvin.host.domain.soul.SoulProposal;
import marvin.host.domain.soul.SoulText;
import marvin.host.domain.soul.SoulVersion;

/** The soul's versions and proposals on PostgreSQL (Docker needed). */
@Testcontainers(disabledWithoutDocker = true)
class SoulStoreIT {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));

    private JdbcClient jdbc;
    private JdbcSoulStore soul;

    @BeforeEach
    void setUp() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String s : ContextMigrations.SCHEMAS) {
                st.execute("DROP SCHEMA IF EXISTS " + s + " CASCADE");
            }
        }
        ContextMigrations migrations = new ContextMigrations(ds, "live");
        migrations.afterPropertiesSet();
        jdbc = JdbcClient.create(ds);
        soul = new JdbcSoulStore(jdbc, migrations);
    }

    @Test
    void versionsAreNumberedPerPartAndKept() {
        assertThat(soul.current(SoulPart.CORE)).isEmpty();
        SoulVersion c1 = soul.addVersion(SoulPart.CORE, SoulText.DEFAULT_CORE, 100.25, SoulVersion.OWNER, "The core Marvin starts with");
        SoulVersion l1 = soul.addVersion(SoulPart.LEARNED, "Prefers short answers.", 200, SoulVersion.OWNER, "Edited by the owner");
        SoulVersion l2 = soul.addVersion(SoulPart.LEARNED, "Prefers short answers.\nNo reminders during calls.", 300,
                SoulVersion.REFLECTION, "Approved by the owner");
        assertThat(List.of(c1.version(), l1.version(), l2.version())).containsExactly(1, 1, 2);
        assertThat(soul.current(SoulPart.LEARNED).orElseThrow()).isEqualTo(l2);
        assertThat(soul.current(SoulPart.CORE).orElseThrow()).isEqualTo(c1);
        assertThat(soul.version(SoulPart.LEARNED, 1).orElseThrow().text()).isEqualTo("Prefers short answers.");
        assertThat(soul.versions(2)).containsExactly(l2, l1);
        // the database itself keeps the core the owner's
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO memory.soul_version (part, version, text, created, author) "
                + "VALUES ('core', 9, 'x', now(), 'reflection')").update()).hasMessageContaining("check");
    }

    @Test
    void proposalsAndTheReflectionsLastRun() {
        SoulProposal p = soul.addProposal(SoulProposal.propose(400.5, "Does not want break reminders during calls.",
                "Asked twice.", List.of(1812L, 1840L), "Prefers short answers.", 1, SoulText.DEFAULT_CORE));
        SoulProposal bad = soul.addProposal(SoulProposal.propose(401, "Spend more time with Marvin.", "", List.of(), "", 1,
                SoulText.DEFAULT_CORE));
        assertThat(soul.proposal(p.id()).orElseThrow()).isEqualTo(p);
        assertThat(soul.awaitingOwner()).extracting(SoulProposal::id).containsExactly(bad.id(), p.id());
        soul.updateProposal(p.decide(SoulProposal.State.APPROVED, 500));
        soul.updateProposal(bad.decide(SoulProposal.State.REJECTED, 501));
        assertThat(soul.awaitingOwner()).isEmpty();
        assertThat(soul.proposal(p.id()).orElseThrow().decided()).isEqualTo(500.0);
        assertThat(soul.proposal(bad.id()).orElseThrow().guardrails()).anySatisfy(c -> {
            assertThat(c.rule()).isEqualTo("no_dependency");
            assertThat(c.ok()).isFalse();
            assertThat(c.reason()).isNotBlank();
        });
        assertThat(soul.lastReflection()).isEmpty();
        soul.reflected(600.5);
        soul.reflected(700.5);
        assertThat(soul.lastReflection()).contains(700.5);
    }
}
