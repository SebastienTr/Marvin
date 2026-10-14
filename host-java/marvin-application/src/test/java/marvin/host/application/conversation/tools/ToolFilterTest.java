// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.out.ToolSource;
import marvin.host.domain.conversation.tool.ToolAccess;
import marvin.host.domain.conversation.tool.ToolSpec;
import marvin.host.domain.conversation.tool.ToolSurface;

/**
 * The tool filter in the registry: whatever a source says about its tools, Marvin never gets one that sends or deletes
 * (it is not registered, a call to it is an unknown tool), and a tool that writes outside this computer is offered only in
 * a background task that asks the owner's approval for each call.
 */
class ToolFilterTest {
    final List<String> ran = new ArrayList<>();

    static ToolSpec spec(String name) {
        return new ToolSpec(name, "a tool", Map.of("type", "object", "properties", Map.of(), "required", List.of()), 5, false,
                false, false);
    }

    ToolSource.SourcedTool sourced(String name, ToolAccess access, Set<ToolSurface> surfaces) {
        return new ToolSource.SourcedTool(spec(name), access, surfaces, (a, c) -> {
            ran.add(name);
            return "done";
        });
    }

    /** A source that lies about everything: send and delete tools, as reads, offered everywhere. */
    ToolSource rogue() {
        return () -> List.of(
                sourced("gmail_send", ToolAccess.READ, ToolSurface.ALL),
                sourced("gmail_send_draft", ToolAccess.READ, ToolSurface.ALL),
                sourced("gmail_delete_message", ToolAccess.READ, ToolSurface.ALL),
                sourced("gmail_trash", ToolAccess.LOCAL_WRITE, ToolSurface.ALL),
                sourced("calendar_delete_event", ToolAccess.EXTERNAL_WRITE, ToolSurface.ALL),
                sourced("gmail_reply", ToolAccess.EXTERNAL_WRITE, Set.of(ToolSurface.TASK)),
                sourced("gmail_search", ToolAccess.READ, ToolSurface.ALL),
                sourced("gmail_create_draft", ToolAccess.EXTERNAL_WRITE, Set.of(ToolSurface.TASK)));
    }

    ToolRegistry conversation() {
        List<ToolRegistry.Tool> all = new ArrayList<>();
        all.addAll(ToolRegistry.fromSources(List.of(rogue()), ToolSurface.CONVERSATION));
        all.addAll(ToolRegistry.fromSources(List.of(rogue()), ToolSurface.TASK).stream()
                .filter(t -> !t.offeredIn(ToolSurface.CONVERSATION)).toList());
        return new ToolRegistry(all, true, true, () -> 0.0);
    }

    static List<String> names(List<ToolRegistry.Tool> tools) {
        return tools.stream().map(t -> t.spec().name()).toList();
    }

    @Test
    void noSendOrDeleteToolIsEverRegistered() {
        ToolRegistry conv = conversation();
        assertThat(names(conv.all())).containsExactlyInAnyOrder("gmail_search", "gmail_create_draft");
        for (ToolRegistry r : List.of(conv, conv.forTasks(List.of()), conv.forTasks(List.of(), true))) {
            assertThat(names(r.all())).noneMatch(n -> n.contains("send") || n.contains("delete") || n.contains("trash")
                    || n.contains("reply"));
            assertThat(r.ollamaTools() == null ? List.of() : r.ollamaTools().toString())
                    .asString().doesNotContain("send").doesNotContain("delete");
        }
    }

    @Test
    void aCallToARefusedToolIsAnUnknownToolAndNothingRuns() {
        ToolRegistry tasks = conversation().forTasks(List.of(), true);
        for (String name : List.of("gmail_send", "gmail_delete_message", "calendar_delete_event", "gmail_reply")) {
            var r = tasks.call(name, Map.of(), Map.of());
            assertThat(r.ok()).isFalse();
            assertThat(r.error()).startsWith("unknown tool");
        }
        assertThat(ran).isEmpty();
    }

    @Test
    void anExternalWriteIsNeverOfferedInTheConversation() {
        ToolRegistry conv = conversation();
        assertThat(names(conv.offered())).containsExactly("gmail_search");
        assertThat(conv.get("gmail_create_draft")).isNull();
        // even when a source says it may be offered there
        ToolSource lying = () -> List.of(sourced("calendar_create_event", ToolAccess.EXTERNAL_WRITE, ToolSurface.ALL));
        ToolRegistry r = new ToolRegistry(ToolRegistry.fromSources(List.of(lying), ToolSurface.CONVERSATION), true, true,
                () -> 0.0);
        assertThat(r.offered()).isEmpty();
        assertThat(r.call("calendar_create_event", Map.of(), Map.of()).ok()).isFalse();
        assertThat(ran).isEmpty();
    }

    @Test
    void aTaskOffersAnExternalWriteOnlyWithApprovals() {
        ToolRegistry conv = conversation();
        assertThat(names(conv.forTasks(List.of()).offered())).containsExactly("gmail_search");
        ToolRegistry withApprovals = conv.forTasks(List.of(), true);
        assertThat(withApprovals.approvals()).isTrue();
        assertThat(names(withApprovals.offered())).containsExactly("gmail_create_draft", "gmail_search");
        assertThat(withApprovals.get("gmail_create_draft").access()).isEqualTo(ToolAccess.EXTERNAL_WRITE);
    }

    @Test
    void aSourcesPreviewIsKept() {
        class Drafting implements ToolSource.Handler, marvin.host.domain.conversation.tool.ToolPreview {
            @Override
            public Object call(Map<String, Object> arguments, Map<String, Object> context) {
                return "drafted";
            }

            @Override
            public Map<String, Object> preview(Map<String, Object> arguments) {
                return Map.of("kind", "email_draft");
            }
        }
        ToolSource s = () -> List.of(new ToolSource.SourcedTool(spec("gmail_create_draft"), ToolAccess.EXTERNAL_WRITE,
                Set.of(ToolSurface.TASK), new Drafting()));
        var tools = ToolRegistry.fromSources(List.of(s), ToolSurface.TASK);
        assertThat(tools.getFirst().preview()).isNotNull();
        assertThat(tools.getFirst().preview().preview(Map.of())).containsEntry("kind", "email_draft");
    }
}
