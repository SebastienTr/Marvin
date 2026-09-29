// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import marvin.host.application.memory.port.in.ExportMemory;
import marvin.host.application.memory.port.in.ForgetMemory;
import marvin.host.application.memory.port.in.ManageFacts;
import marvin.host.application.memory.port.out.FactStore;
import marvin.host.application.memory.port.out.MemoryModel;
import marvin.host.application.memory.testing.FakeMemoryModel;
import marvin.host.application.memory.testing.MemoryFixture;
import marvin.host.domain.memory.BlockVersion;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactOrigin;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.MemorySources;
import marvin.host.domain.memory.Sensitivity;

class MemoryAdminServiceTest {
    static final Instant T = Instant.parse("2026-09-28T12:00:00Z");
    final FakeMemoryModel model = new FakeMemoryModel();
    final MemoryFixture m = new MemoryFixture(T, model);

    FactStore.Query all(String validity) {
        return new FactStore.Query("", "", "", validity, null, null, null, Instant.parse("2026-12-01T00:00:00Z"), 100, 0);
    }

    @Test
    void theOwnerStatesAFactWithAnOwnerEventAsItsSource() {
        Fact f = m.admin.remember("Call me Sam.", "", null);
        assertThat(f.origin()).isEqualTo(FactOrigin.OWNER);
        assertThat(f.confidence()).isEqualTo(1.0);
        ManageFacts.Detail d = m.admin.get(f.id()).orElseThrow();
        assertThat(d.sources()).extracting(MemoryEvent::source, MemoryEvent::kind)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(MemorySources.OWNER, MemorySources.REMEMBER));
        assertThat(m.store.facts.vectors.get(f.id())).isNotNull();
        assertThatThrownBy(() -> m.admin.remember("My password is hunter22", "", null))
                .hasMessageContaining("memory never keeps those");
        // the embedding model missing does not stop the owner: the night embeds it
        m.embedder.failure = new marvin.host.application.memory.port.out.Embedder.Unavailable("no model", "pull");
        Fact g = m.admin.remember("The owner has a cat named Tofu.", "", Sensitivity.PERSONAL);
        assertThat(m.store.facts.vectors.get(g.id())).isNull();
        m.embedder.failure = null;
        assertThat(m.nightly.embedMissing(() -> false)).isEqualTo(1);
    }

    @Test
    void anEditIsANewVersionTheWorkerCannotUndo() {
        m.clock.advance(1);
        m.say(T, "heard", "J'habite à Lyon");
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner lives in Lyon.", 9));
        m.consolidator.process(m.store.log.unconsolidated(10), new MemoryModel.Target("h", "m"), () -> false);
        Fact lyon = m.store.facts.rows.values().iterator().next();
        m.clock.advance(60);
        Fact edited = m.admin.edit(lyon.id(), new ManageFacts.Edit("The owner lives in Lyon, in the Croix-Rousse.", null, null, 9, null));
        assertThat(edited.origin()).isEqualTo(FactOrigin.OWNER);
        assertThat(edited.sources()).hasSize(2).contains(lyon.sources().getFirst());
        assertThat(m.store.facts.rows.get(lyon.id()).supersededBy()).isEqualTo(edited.id());
        assertThat(m.admin.get(edited.id()).orElseThrow().versions()).extracting(Fact::id).containsExactly(lyon.id(), edited.id());
        assertThatThrownBy(() -> m.admin.edit(lyon.id(), new ManageFacts.Edit("x", null, null, null, null)))
                .hasMessageContaining("current version");
        m.admin.pin(edited.id(), true);
        assertThat(m.store.facts.rows.get(edited.id()).pinned()).isTrue();
        assertThat(m.admin.list(all("current")).total()).isEqualTo(1);
    }

    @Test
    void forgettingAFactTakesAllItsVersionsAndItsProfileLine() {
        Fact f = m.admin.remember("Julie is the owner's sister and lives in Nantes.", "person:Julie", null);
        Fact v2 = m.admin.edit(f.id(), new ManageFacts.Edit("Julie is the owner's sister and lives in Nantes with Tom.", null, null, null, null));
        m.admin.editProfile("The owner lives in Lille.\nThe owner's sister Julie lives in Nantes with Tom.", List.of());
        ForgetMemory.Forgotten out = m.admin.forgetFact(f.id());
        assertThat(out.facts()).isEqualTo(2);
        assertThat(m.store.facts.rows).isEmpty();
        assertThat(m.admin.profile().orElseThrow().content()).isEqualTo("The owner lives in Lille.");
        assertThat(m.store.log.rows.values()).filteredOn(e -> e.kind().equals(MemorySources.FORGET))
                .singleElement().satisfies(e -> assertThat(e.body()).isEmpty());
        assertThat(m.admin.get(v2.id())).isEmpty();
    }

    @Test
    void forgettingEventsCascadesToFactsThatOnlyCameFromThem() {
        MemoryEvent a = m.say(T, "heard", "J'habite à Lyon");
        m.say(T.plusSeconds(3600), "heard", "J'ai un chat");
        model.extract = r -> List.of(FakeMemoryModel.raw("owner", "The owner " + r.lines().getFirst().text(), 5));
        m.consolidator.process(List.of(a), new MemoryModel.Target("h", "m"), () -> false);
        m.consolidator.process(m.store.log.unconsolidated(10), new MemoryModel.Target("h", "m"), () -> false);
        ForgetMemory.Forgotten out = m.admin.forgetEvents(T, T.plusSeconds(60));
        assertThat(out.events()).isEqualTo(1);
        assertThat(out.facts()).isEqualTo(1);
        assertThat(m.store.facts.currentStatements(T.plusSeconds(7200))).containsExactly("The owner J'ai un chat");
        ForgetMemory.Forgotten all = m.admin.forgetEverything();
        assertThat(all.facts()).isEqualTo(1);
        assertThat(m.store.log.count()).isZero();
        assertThat(m.store.facts.rows).isEmpty();
    }

    @Test
    void theProfileIsTheOwnersToWriteAndRestore() {
        BlockVersion v1 = m.admin.editProfile("The owner lives in Lyon.", List.of());
        BlockVersion v2 = m.admin.editProfile("The owner lives in Lille.\nCall me Sam.", List.of("The owner lives in Lille."));
        assertThat(v2.keptLines()).containsExactly("The owner lives in Lille.", "Call me Sam.");
        assertThat(m.admin.profileVersions(5)).hasSize(2).first().satisfies(p -> assertThat(p.diff()).extracting(d -> d.op() + d.text())
                .containsExactly("-The owner lives in Lyon.", "+The owner lives in Lille.", "+Call me Sam."));
        BlockVersion v3 = m.admin.restoreProfile(v1.id());
        assertThat(v3.content()).isEqualTo("The owner lives in Lyon.");
        assertThat(m.admin.profile().orElseThrow().id()).isEqualTo(v3.id());
        assertThatThrownBy(() -> m.admin.editProfile("word ".repeat(600), List.of())).hasMessageContaining("limited to 500 tokens");
    }

    @Test
    void theExportHasEverythingReadableAndComplete() {
        m.admin.remember("The owner has a cat named Tofu.", "", null);
        m.admin.editProfile("Call me Sam.", List.of());
        ExportMemory.Export e = new MemoryExportService(m.store.log, m.store.facts, m.store.episodes, m.store.profiles, m.settings,
                m.clock).export();
        assertThat(e.json()).containsKeys("event_log", "fact", "episode", "block_version", "settings");
        assertThat(e.json().get("fact")).singleElement().satisfies(f -> assertThat(f).containsEntry("origin", "owner"));
        assertThat(e.json().get("event_log")).hasSize(2);
        assertThat(e.markdown()).contains("## Profile\n\nCall me Sam.").contains("- The owner has a cat named Tofu. (learned 2026-09-28, stated by you)");
        assertThat(m.admin.health().facts()).isEqualTo(1);
        assertThat(m.admin.health().embedder()).isEqualTo("ready");
        assertThat(m.admin.log("tofu", 0, 10)).hasSize(1);
        assertThat(m.settings.settings().toMap()).isEqualTo(Map.copyOf(e.json().get("settings").getFirst()));
    }
}
