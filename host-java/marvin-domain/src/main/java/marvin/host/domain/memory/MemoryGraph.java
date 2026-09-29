// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Memory as a graph, for the app's "See your memory" (docs/memory.md, "Seeing memory"): the owner, and the people,
 * places and things facts are about, as nodes; the facts as edges. Only what memory already holds is used: a fact's
 * {@code subject}, and the names of other subjects its statement mentions (no guessing of names that memory has not
 * made a subject of its own). A fact about {@code person:Claire} links Claire to the owner; one that says "Claire
 * lives in Lyon" while {@code place:Lyon} is a subject links Claire to Lyon instead. A fact about the owner that
 * mentions nobody counts on the owner's node only.
 */
public final class MemoryGraph {
    /** The owner's node id. */
    public static final String OWNER = "owner";

    public enum NodeType {
        OWNER, PERSON, PLACE, THING;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A node.
     *
     * @param id        {@code owner} or {@code <type>:<name in lower case>}
     * @param name      the name as memory wrote it first
     * @param facts     how many facts touch it (about it, or naming it)
     * @param current   how many of those are current and not archived
     * @param pinned    one of its facts is pinned
     * @param sensitive one of its facts is sensitive
     * @param past      all of its facts are past or archived (drawn faded)
     */
    public record Node(String id, String name, NodeType type, int facts, int current, boolean pinned, boolean sensitive,
                       boolean past) {
    }

    /**
     * An edge: the facts that link two nodes.
     *
     * @param mention the link comes from a name in a statement, not only from the subject
     * @param current one of its facts is current and not archived (otherwise drawn faded)
     */
    public record Edge(String from, String to, List<UUID> facts, boolean mention, boolean current) {
    }

    /**
     * @param hiddenNodes nodes left out to keep the picture readable (the fewest facts first)
     */
    public record Graph(List<Node> nodes, List<Edge> edges, int facts, int hiddenNodes) {
    }

    private MemoryGraph() {
    }

    /** The node id of a subject. */
    public static String nodeId(String subject) {
        String s = FactCandidate.subject(subject);
        if (OWNER.equals(s)) {
            return OWNER;
        }
        int colon = s.indexOf(':');
        return s.substring(0, colon) + ":" + s.substring(colon + 1).strip().toLowerCase(Locale.ROOT);
    }

    /** A subject's name as shown ({@code person:Claire}: Claire; the owner: You). */
    public static String name(String subject) {
        String s = FactCandidate.subject(subject);
        return OWNER.equals(s) ? "You" : s.substring(s.indexOf(':') + 1).strip();
    }

    static NodeType type(String nodeId) {
        if (OWNER.equals(nodeId)) {
            return NodeType.OWNER;
        }
        return switch (nodeId.substring(0, nodeId.indexOf(':'))) {
            case "person" -> NodeType.PERSON;
            case "place" -> NodeType.PLACE;
            default -> NodeType.THING;
        };
    }

    /** Faded: no longer true, or archived. */
    static boolean faded(Fact f, Instant now) {
        return !f.current(now) || f.archived();
    }

    /**
     * The graph of these facts (superseded wordings should be left out by the caller: each fact once, in its latest
     * wording).
     *
     * @param maxNodes at most this many nodes, the owner always among them
     */
    public static Graph build(List<Fact> facts, Instant now, int maxNodes) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(OWNER, "You");
        List<Fact> ordered = facts.stream().sorted(Comparator.comparing(Fact::learnedAt).thenComparing(Fact::id)).toList();
        for (Fact f : ordered) {
            names.putIfAbsent(nodeId(f.subject()), name(f.subject()));
        }
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        names.forEach((id, n) -> {
            if (!OWNER.equals(id) && n.codePointCount(0, n.length()) >= 3) {
                patterns.put(id, Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(n) + "(?![\\p{L}\\p{N}])",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            }
        });

        Map<String, List<Fact>> touching = new LinkedHashMap<>();
        names.keySet().forEach(id -> touching.put(id, new ArrayList<>()));
        record Link(String a, String b, boolean mention) {
        }
        Map<List<String>, List<Fact>> linkFacts = new LinkedHashMap<>();
        Map<List<String>, Boolean> linkMention = new LinkedHashMap<>();
        for (Fact f : ordered) {
            String s = nodeId(f.subject());
            touching.get(s).add(f);
            Set<String> named = new LinkedHashSet<>();
            patterns.forEach((id, p) -> {
                if (!id.equals(s) && p.matcher(f.statement()).find()) {
                    named.add(id);
                }
            });
            List<Link> links = new ArrayList<>();
            if (named.isEmpty()) {
                if (!OWNER.equals(s)) {
                    links.add(new Link(OWNER, s, false));
                }
            } else {
                for (String t : named) {
                    touching.get(t).add(f);
                    links.add(new Link(s, t, true));
                }
            }
            for (Link l : links) {
                List<String> key = pair(l.a(), l.b());
                linkFacts.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
                linkMention.merge(key, l.mention(), Boolean::logicalOr);
            }
        }

        List<Node> all = new ArrayList<>();
        touching.forEach((id, fs) -> {
            int current = (int) fs.stream().filter(f -> !faded(f, now)).count();
            all.add(new Node(id, names.get(id), type(id), fs.size(), current, fs.stream().anyMatch(Fact::pinned),
                    fs.stream().anyMatch(f -> f.sensitivity() == Sensitivity.SENSITIVE),
                    !fs.isEmpty() && current == 0));
        });
        List<Node> kept = new ArrayList<>();
        all.stream().filter(n -> n.type() == NodeType.OWNER).forEach(kept::add);
        all.stream().filter(n -> n.type() != NodeType.OWNER)
                .sorted(Comparator.comparingInt(Node::facts).reversed().thenComparing(Node::id))
                .limit(Math.max(0, maxNodes - 1)).forEach(kept::add);
        Set<String> keptIds = new LinkedHashSet<>();
        kept.forEach(n -> keptIds.add(n.id()));

        List<Edge> edges = new ArrayList<>();
        linkFacts.forEach((key, fs) -> {
            if (keptIds.contains(key.get(0)) && keptIds.contains(key.get(1))) {
                edges.add(new Edge(key.get(0), key.get(1), fs.stream().map(Fact::id).toList(), linkMention.get(key),
                        fs.stream().anyMatch(f -> !faded(f, now))));
            }
        });
        return new Graph(List.copyOf(kept), List.copyOf(edges), facts.size(), all.size() - kept.size());
    }

    /** An unordered pair, the owner first, otherwise in id order: one edge per pair of nodes. */
    private static List<String> pair(String a, String b) {
        if (OWNER.equals(b) || (!OWNER.equals(a) && a.compareTo(b) > 0)) {
            return List.of(b, a);
        }
        return List.of(a, b);
    }
}
