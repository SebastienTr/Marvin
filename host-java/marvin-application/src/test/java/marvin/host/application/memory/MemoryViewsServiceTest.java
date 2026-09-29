// SPDX-License-Identifier: MIT
package marvin.host.application.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import marvin.host.application.memory.port.in.ConsolidateMemory;
import marvin.host.application.memory.port.in.ManageFacts;
import marvin.host.application.memory.port.in.VisualiseMemory;
import marvin.host.application.memory.port.out.Embedder;
import marvin.host.application.memory.testing.FakeMemoryModel;
import marvin.host.application.memory.testing.MemoryFixture;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactTimeline;
import marvin.host.domain.memory.MemoryGraph;
import marvin.host.domain.memory.Sensitivity;

/** The app's pictures of memory from the stores: what they show, what they never show, and the worker's passes. */
class MemoryViewsServiceTest {
    static final Instant T = Instant.parse("2026-09-28T12:00:00Z");
    final MemoryFixture m = new MemoryFixture(T, new FakeMemoryModel());
    final ConsolidateMemory.Report nightly = new ConsolidateMemory.Report(ConsolidateMemory.Pass.NIGHTLY, "done",
            T.getEpochSecond() - 3600, 2.5, "m", Map.of("days", 1), List.of(Map.of("step", "extract", "seconds", 1.0)), "", "");
    final ConsolidateMemory worker = new ConsolidateMemory() {
        @Override
        public CompletableFuture<Report> consolidateNow(Pass pass) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Status status() {
            return new Status("running", Pass.IDLE, "extract", 3, 0, 0, 0, nightly);
        }
    };
    final MemoryViewsService views = new MemoryViewsService(m.store.facts, m.store.log, m.store.episodes, m.store.profiles,
            worker, m.days, m.clock);

    @Test
    void forgottenFactsAppearNowhereAndSensitiveOnesAreMarked() {
        Fact sister = m.admin.remember("Claire is the owner's sister.", "person:Claire", null);
        m.clock.advance(60);
        Fact secret = m.admin.remember("The owner's favourite bird is the heron.", "owner", null);
        m.clock.advance(60);
        Fact health = m.admin.remember("Claire has asthma.", "person:Claire", Sensitivity.SENSITIVE);
        m.clock.advance(60);
        m.admin.forgetFact(secret.id());

        VisualiseMemory.GraphView g = views.graph();
        assertThat(g.facts()).extracting(Fact::id).containsExactlyInAnyOrder(sister.id(), health.id());
        MemoryGraph.Node claire = g.graph().nodes().stream().filter(n -> n.id().equals("person:claire")).findFirst().orElseThrow();
        assertThat(claire.sensitive()).isTrue();
        assertThat(claire.facts()).isEqualTo(2);
        assertThat(views.map().points()).extracting(p -> p.fact().id()).doesNotContain(secret.id()).hasSize(2);
        assertThat(views.timeline(VisualiseMemory.Range.ALL).lanes()).flatExtracting(FactTimeline.Lane::bars)
                .extracting(b -> b.fact().id()).doesNotContain(secret.id());
        assertThat(views.flow().facts().forgotten()).isEqualTo(1);
        assertThat(views.flow().facts().sensitive()).isEqualTo(1);
    }

    @Test
    void theMapPlacesEmbeddedFactsAndListsTheOthersApartWithACachedProjection() {
        assertThat(views.map().points()).isEmpty();
        Fact tea = m.admin.remember("The owner drinks green tea every morning.", "owner", null);
        VisualiseMemory.MapView one = views.map();
        assertThat(one.points()).singleElement().satisfies(p -> assertThat(p.x()).isZero());
        m.admin.remember("The owner drinks black coffee after lunch.", "owner", null);
        m.admin.remember("The owner plays the cello in an orchestra.", "owner", null);
        m.embedder.failure = new Embedder.Unavailable("model missing", "ollama pull x");
        Fact late = m.admin.remember("The owner walks the dog at noon.", "owner", null);
        VisualiseMemory.MapView map = views.map();
        assertThat(map.points()).hasSize(3);
        assertThat(map.unplaced()).extracting(Fact::id).containsExactly(late.id());
        assertThat(map.explained()[0]).isGreaterThan(0);
        // the same fact set: the same projection, from the cache, with the facts as they are now
        m.admin.pin(tea.id(), true);
        VisualiseMemory.MapView again = views.map();
        assertThat(again.version()).isEqualTo(map.version());
        assertThat(again.points()).filteredOn(p -> p.fact().id().equals(tea.id())).singleElement()
                .satisfies(p -> assertThat(p.fact().pinned()).isTrue());
        assertThat(again.points()).extracting(VisualiseMemory.Point::x).containsExactlyElementsOf(
                map.points().stream().map(VisualiseMemory.Point::x).toList());
        // embedded later: placed, and a new version
        m.embedder.failure = null;
        m.nightly.embedMissing(() -> false);
        VisualiseMemory.MapView later = views.map();
        assertThat(later.unplaced()).isEmpty();
        assertThat(later.version()).isNotEqualTo(map.version());
        // archived facts are not on the map
        m.admin.archive(tea.id(), true);
        assertThat(views.map().points()).extracting(p -> p.fact().id()).doesNotContain(tea.id());
    }

    @Test
    void theTimelineShowsAnEditAsTheNextWordingInTheSameLane() {
        Fact v1 = m.admin.remember("Ana works at a hospital.", "person:Ana", null);
        m.clock.advance(86_400 * 3);
        Fact v2 = m.admin.edit(v1.id(), new ManageFacts.Edit("Ana works at the hospital in Lille.", null, null, null, null));
        m.clock.advance(86_400);
        VisualiseMemory.TimelineView week = views.timeline(VisualiseMemory.Range.WEEK);
        assertThat(week.lanes()).singleElement().satisfies(l -> {
            assertThat(l.bars()).extracting(b -> b.fact().id()).containsExactly(v1.id(), v2.id());
            assertThat(l.bars().getFirst().endKind()).isEqualTo("replaced");
        });
        assertThat(week.to()).isEqualTo(T.plusSeconds(86_400 * 4));
        assertThat(week.from()).isEqualTo(week.to().minusSeconds(86_400 * 7));
        // a lane entirely before the range is left out
        m.clock.advance(86_400 * 40);
        assertThat(views.timeline(VisualiseMemory.Range.WEEK).lanes()).singleElement();      // v2 still holds: open
        assertThat(views.timeline(VisualiseMemory.Range.ALL).from()).isEqualTo(T);
    }

    @Test
    void theFlowCountsTheLogPerSourceTheFactsAndTheWorkersPasses() {
        m.say(T, "heard", "I have a cat called Pixel");
        m.say(T.plusSeconds(1), "reply", "Nice!");
        Fact f = m.admin.remember("The owner likes rain.", "owner", null);
        m.admin.edit(f.id(), new ManageFacts.Edit("The owner likes summer rain.", null, null, null, null));
        VisualiseMemory.FlowView flow = views.flow();
        assertThat(flow.sources()).extracting(VisualiseMemory.Source::source).startsWith("conversation", "brain", "owner");
        VisualiseMemory.Source conv = flow.sources().getFirst();
        assertThat(conv.events()).isEqualTo(2);
        assertThat(conv.waiting()).isEqualTo(2);
        assertThat(flow.sources().get(1).events()).isZero();
        assertThat(flow.facts().stored()).isEqualTo(2);
        assertThat(flow.facts().current()).isEqualTo(1);
        assertThat(flow.facts().updated()).isEqualTo(1);
        assertThat(flow.facts().invalidated()).isZero();
        assertThat(flow.worker().step()).isEqualTo("extract");
        assertThat(flow.recent()).containsExactly(nightly);
        assertThat(flow.written().profileVersions()).isZero();
    }
}
