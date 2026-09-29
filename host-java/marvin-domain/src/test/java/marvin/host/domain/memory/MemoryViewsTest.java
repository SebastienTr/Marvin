// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The rules behind the app's pictures of memory: the graph, the projection of the meaning map, the timeline's bars. */
class MemoryViewsTest {
    static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    static Fact fact(String subject, String statement, Instant learned) {
        return new Fact(UUID.randomUUID(), subject, statement, FactKind.STATE, 5, 0.8, Sensitivity.NORMAL, null, null,
                learned, null, null, null, 0, false, false, FactOrigin.EXTRACTED, "", List.of(1L));
    }

    static Instant daysAgo(int d) {
        return NOW.minus(Duration.ofDays(d));
    }

    // ------------------------------------------------------------------ projection

    @Test
    void theProjectionFindsTheDirectionThatSeparatesTwoClusters() {
        // two clusters apart along dimension 3, spread a little along dimension 7
        Random r = new Random(4);
        List<float[]> vs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            float[] v = new float[16];
            for (int j = 0; j < v.length; j++) {
                v[j] = (float) (r.nextGaussian() * 0.01);
            }
            v[3] += i < 10 ? 1f : -1f;
            v[7] += (float) (r.nextGaussian() * 0.3);
            vs.add(v);
        }
        Projection.Result p = Projection.pca2(vs);
        for (int i = 0; i < 20; i++) {
            assertThat(Math.signum(p.points()[i][0])).isEqualTo(i < 10 ? Math.signum(p.points()[0][0]) : -Math.signum(p.points()[0][0]));
            assertThat(Math.abs(p.points()[i][0])).isCloseTo(1.0, within(0.1));
        }
        assertThat(p.explained()[0]).isGreaterThan(0.85);
        assertThat(p.explained()[1]).isGreaterThan(0.0).isLessThan(p.explained()[0]);
        assertThat(p.explained()[0] + p.explained()[1]).isLessThanOrEqualTo(1.0 + 1e-9);
    }

    @Test
    void theProjectionIsDeterministicSignsIncluded() {
        Random r = new Random(9);
        List<float[]> vs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            float[] v = new float[64];
            for (int j = 0; j < v.length; j++) {
                v[j] = (float) r.nextGaussian();
            }
            vs.add(v);
        }
        Projection.Result a = Projection.pca2(vs);
        Projection.Result b = Projection.pca2(new ArrayList<>(vs));
        assertThat(a.points()).isDeepEqualTo(b.points());
        // flipping every vector flips the data, not the chosen signs of the axes' largest loadings: the picture mirrors
        List<float[]> flipped = vs.stream().map(v -> {
            float[] w = v.clone();
            for (int j = 0; j < w.length; j++) {
                w[j] = -w[j];
            }
            return w;
        }).toList();
        Projection.Result c = Projection.pca2(flipped);
        for (int i = 0; i < 30; i++) {
            assertThat(c.points()[i][0]).isCloseTo(-a.points()[i][0], within(1e-6));
        }
    }

    @Test
    void zeroOneAndTwoFactsAreHandled() {
        assertThat(Projection.pca2(List.of()).points()).isEmpty();
        Projection.Result one = Projection.pca2(List.<float[]>of(new float[] {1, 2, 3}));
        assertThat(one.points()[0]).containsExactly(0, 0);
        Projection.Result two = Projection.pca2(List.of(new float[] {1, 0, 0}, new float[] {0, 1, 0}));
        assertThat(two.points()[0][0]).isCloseTo(-two.points()[1][0], within(1e-9));
        assertThat(Math.abs(two.points()[0][0])).isCloseTo(Math.sqrt(2) / 2, within(1e-6));
        assertThat(two.points()[0][1]).isCloseTo(0, within(1e-9));       // nothing left for a second axis
        assertThat(two.explained()[0]).isCloseTo(1.0, within(1e-9));
        Projection.Result same = Projection.pca2(List.of(new float[] {1, 1}, new float[] {1, 1}, new float[] {1, 1}));
        assertThat(same.points()).isDeepEqualTo(new double[][] {{0, 0}, {0, 0}, {0, 0}});
    }

    // ------------------------------------------------------------------ graph

    @Test
    void theGraphLinksSubjectsToTheOwnerAndToTheSubjectsTheyName() {
        Fact sister = fact("person:Claire", "Claire is the owner's sister.", daysAgo(9));
        Fact lyon = fact("person:Claire", "Claire lives in Lyon.", daysAgo(8));
        Fact city = fact("place:Lyon", "Lyon is two hours away by train.", daysAgo(7));
        Fact cat = fact("thing:Pixel", "Pixel is the owner's cat.", daysAgo(6));
        Fact job = fact("owner", "The owner works as a nurse.", daysAgo(5));
        Fact visit = fact("owner", "The owner visits claire every month.", daysAgo(4));   // any case, whole words
        Fact word = fact("owner", "The owner likes Lyonnaise potatoes.", daysAgo(3));     // not the name Lyon
        MemoryGraph.Graph g = MemoryGraph.build(List.of(sister, lyon, city, cat, job, visit, word), NOW, 60);

        assertThat(g.nodes()).extracting(MemoryGraph.Node::id)
                .containsExactly("owner", "person:claire", "place:lyon", "thing:pixel");
        assertThat(g.nodes().getFirst().type()).isEqualTo(MemoryGraph.NodeType.OWNER);
        MemoryGraph.Node claire = g.nodes().get(1);
        assertThat(claire.name()).isEqualTo("Claire");
        assertThat(claire.facts()).isEqualTo(3);             // two about her, one naming her
        assertThat(g.nodes().getFirst().facts()).isEqualTo(3);
        assertThat(g.edges()).extracting(e -> e.from() + "-" + e.to() + ":" + e.facts().size() + (e.mention() ? "m" : ""))
                .containsExactlyInAnyOrder("owner-person:claire:2m", "person:claire-place:lyon:1m",
                        "owner-place:lyon:1", "owner-thing:pixel:1");
    }

    @Test
    void pastPinnedAndSensitiveFactsMarkTheirNodes() {
        Fact moved = fact("place:Nice", "The owner lived in Nice.", daysAgo(100)).withExpiry(daysAgo(10), daysAgo(20), null);
        Fact health = new Fact(UUID.randomUUID(), "person:Tom", "Tom has asthma.", FactKind.STATE, 5, 0.9, Sensitivity.SENSITIVE,
                null, null, daysAgo(5), null, null, null, 0, false, true, FactOrigin.OWNER, "", List.of(2L));
        MemoryGraph.Graph g = MemoryGraph.build(List.of(moved, health), NOW, 60);
        MemoryGraph.Node nice = g.nodes().stream().filter(n -> n.id().equals("place:nice")).findFirst().orElseThrow();
        MemoryGraph.Node tom = g.nodes().stream().filter(n -> n.id().equals("person:tom")).findFirst().orElseThrow();
        assertThat(nice.past()).isTrue();
        assertThat(nice.current()).isZero();
        assertThat(tom.sensitive()).isTrue();
        assertThat(tom.pinned()).isTrue();
        assertThat(tom.past()).isFalse();
        assertThat(g.edges()).filteredOn(e -> e.to().equals("place:nice")).singleElement()
                .satisfies(e -> assertThat(e.current()).isFalse());
    }

    @Test
    void theGraphKeepsTheOwnerAndTheBusiestNodes() {
        List<Fact> fs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            for (int k = 0; k <= i; k++) {
                fs.add(fact("thing:item" + i, "Fact " + k + " about it.", daysAgo(30 - i)));
            }
        }
        MemoryGraph.Graph g = MemoryGraph.build(fs, NOW, 4);
        assertThat(g.nodes()).extracting(MemoryGraph.Node::id).containsExactly("owner", "thing:item9", "thing:item8", "thing:item7");
        assertThat(g.hiddenNodes()).isEqualTo(7);
        assertThat(g.edges()).allSatisfy(e -> assertThat(List.of("owner", "thing:item9", "thing:item8", "thing:item7"))
                .contains(e.from(), e.to()));
        assertThat(MemoryGraph.build(List.of(), NOW, 60).nodes()).extracting(MemoryGraph.Node::id).containsExactly("owner");
    }

    // ------------------------------------------------------------------ timeline

    @Test
    void aFactTheWorldEndedIsFollowedByTheOneThatReplacedIt() {
        Instant planned = daysAgo(3);           // one plan: the old fact expires when the new one is learned
        Fact nice = new Fact(UUID.randomUUID(), "owner", "The owner lives in Nice.", FactKind.STATE, 7, 0.9, Sensitivity.NORMAL,
                daysAgo(200), daysAgo(10), daysAgo(150), planned, null, null, 0, false, false, FactOrigin.EXTRACTED, "", List.of(1L));
        Fact lille = new Fact(UUID.randomUUID(), "owner", "The owner lives in Lille.", FactKind.STATE, 7, 0.9, Sensitivity.NORMAL,
                daysAgo(10), null, planned, null, null, null, 0, false, false, FactOrigin.EXTRACTED, "", List.of(2L));
        Fact other = fact("owner", "The owner likes tea.", daysAgo(40));
        List<FactTimeline.Lane> lanes = FactTimeline.lanes(List.of(lille, other, nice));

        assertThat(lanes).hasSize(2);
        FactTimeline.Lane home = lanes.getFirst();
        assertThat(home.bars()).extracting(b -> b.fact().statement())
                .containsExactly("The owner lives in Nice.", "The owner lives in Lille.");
        FactTimeline.Bar first = home.bars().getFirst();
        assertThat(first.start()).isEqualTo(daysAgo(200));
        assertThat(first.startKind()).isEqualTo("valid_from");
        assertThat(first.end()).isEqualTo(daysAgo(10));          // the world's end, not when memory learned it
        assertThat(first.endKind()).isEqualTo("valid_to");
        assertThat(first.next()).isEqualTo(lille.id());
        assertThat(first.nextKind()).isEqualTo("then");
        FactTimeline.Bar second = home.bars().get(1);
        assertThat(second.start()).isEqualTo(daysAgo(10));
        assertThat(second.end()).isNull();
        assertThat(second.endKind()).isEqualTo("open");
        assertThat(lanes.get(1).bars().getFirst().startKind()).isEqualTo("learned");
        assertThat(lanes.get(1).bars().getFirst().overlaps(daysAgo(7), NOW, NOW)).isTrue();      // open: until now
        assertThat(first.overlaps(daysAgo(7), NOW, NOW)).isFalse();
    }

    @Test
    void wordingsOfOneFactFollowOneAnotherFromWhenEachWasWritten() {
        Fact v2 = fact("person:Ana", "Ana works at the hospital in Lille.", daysAgo(5));
        Fact v1 = new Fact(UUID.randomUUID(), "person:Ana", "Ana works at a hospital.", FactKind.STATE, 5, 0.8, Sensitivity.NORMAL,
                daysAgo(60), null, daysAgo(30), daysAgo(5), v2.id(), null, 0, false, false, FactOrigin.EXTRACTED, "", List.of(1L));
        List<FactTimeline.Lane> lanes = FactTimeline.lanes(List.of(v2, v1));
        assertThat(lanes).singleElement().satisfies(l -> {
            assertThat(l.bars()).hasSize(2);
            FactTimeline.Bar a = l.bars().getFirst();
            FactTimeline.Bar b = l.bars().get(1);
            assertThat(a.fact()).isEqualTo(v1);
            assertThat(a.start()).isEqualTo(daysAgo(60));
            assertThat(a.end()).isEqualTo(daysAgo(5));
            assertThat(a.endKind()).isEqualTo("replaced");
            assertThat(a.nextKind()).isEqualTo("reworded");
            assertThat(b.start()).isEqualTo(daysAgo(5));
            assertThat(b.startKind()).isEqualTo("reworded");
            assertThat(b.end()).isNull();
        });
    }

    @Test
    void anAmbiguousSuccessorIsNotGuessedAndAnEndBeforeTheStartIsClamped() {
        Instant at = daysAgo(2);
        Fact old = new Fact(UUID.randomUUID(), "owner", "The owner drives a Clio.", FactKind.STATE, 5, 0.8, Sensitivity.NORMAL,
                null, daysAgo(40), daysAgo(20), at, null, null, 0, false, false, FactOrigin.EXTRACTED, "", List.of(1L));
        Fact a = fact("owner", "The owner drives a Zoe.", at);
        Fact b = fact("owner", "The owner cycles to work.", at);
        List<FactTimeline.Lane> lanes = FactTimeline.lanes(List.of(old, a, b));
        assertThat(lanes).hasSize(3);
        FactTimeline.Bar ended = lanes.stream().flatMap(l -> l.bars().stream()).filter(x -> x.fact() == old).findFirst().orElseThrow();
        assertThat(ended.next()).isNull();
        assertThat(ended.start()).isEqualTo(daysAgo(20));       // learned; the world's end came before it
        assertThat(ended.end()).isEqualTo(daysAgo(20));
    }
}
