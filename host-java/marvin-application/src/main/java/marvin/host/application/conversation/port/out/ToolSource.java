// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.port.out;

import java.util.List;
import java.util.Map;
import java.util.Set;

import marvin.host.domain.conversation.tool.ToolAccess;
import marvin.host.domain.conversation.tool.ToolSpec;
import marvin.host.domain.conversation.tool.ToolSurface;

/**
 * Tools from outside the conversation: where a connector (email drafts, a calendar, the web) plugs in, as an adapter,
 * without the conversation knowing it. Each tool says what it does to the world ({@link ToolAccess}) and where it may be
 * offered ({@link ToolSurface}); the registry checks its arguments against its schema, runs it with its timeout, and
 * turns any failure into an error the model can talk about. A source is asked again for each question and each task, so
 * a tool can come and go (a connector connected, a scope granted).
 */
public interface ToolSource {

    /** The tools this source offers now; empty when it has none. Called often: keep it cheap. */
    List<SourcedTool> tools();

    /**
     * One tool.
     *
     * @param handler runs a call with its validated arguments and the context ({@code language}, ...) when the spec asks
     *                for it; returns a short text or anything JSON can encode; throws
     *                {@link marvin.host.domain.conversation.tool.ToolError} for an expected failure
     */
    record SourcedTool(ToolSpec spec, ToolAccess access, Set<ToolSurface> surfaces, Handler handler) {
        public SourcedTool {
            surfaces = surfaces == null || surfaces.isEmpty() ? ToolSurface.ALL : Set.copyOf(surfaces);
            access = access == null ? ToolAccess.READ : access;
        }
    }

    /** What a tool does. */
    @FunctionalInterface
    interface Handler {
        Object call(Map<String, Object> arguments, Map<String, Object> context);
    }
}
