// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.tools;

import java.util.List;
import java.util.Map;

import marvin.host.application.conversation.port.out.MemoryContext;
import marvin.host.domain.conversation.tool.MemoryToolSpecs;
import marvin.host.domain.conversation.tool.ToolError;

/**
 * The memory tools (docs/design.md 5.3): {@code remember}, {@code recall} and {@code forget}, offered with the other
 * tools when memory is there. The tool context carries the conversation turn (a {@code forget} is confirmed in a
 * later turn than the one that proposed it) and whether someone else is in the room.
 */
public final class MemoryTools {
    /** Keys of the tool context the voice gives. */
    public static final String TURN = "turn";
    public static final String OTHERS_PRESENT = "others_present";

    private MemoryTools() {
    }

    /** The three tools over a memory port. */
    public static List<ToolRegistry.Tool> tools(MemoryContext memory) {
        return List.of(
                new ToolRegistry.Tool(MemoryToolSpecs.remember(), (args, ctx) -> value(memory.remember(str(args, "statement"), audience(ctx)))),
                new ToolRegistry.Tool(MemoryToolSpecs.recall(), (args, ctx) ->
                        value(memory.recall(str(args, "query"), str(args, "period"), audience(ctx)))),
                new ToolRegistry.Tool(MemoryToolSpecs.forget(), (args, ctx) ->
                        value(memory.forget(str(args, "query"), str(args, "confirm"), turn(ctx), audience(ctx)))));
    }

    private static Object value(MemoryContext.ToolAnswer a) {
        if (a.error() != null) {
            throw new ToolError(a.error());
        }
        return a.value();
    }

    private static String str(Map<String, Object> args, String key) {
        return args.get(key) instanceof String s ? s.strip() : "";
    }

    private static long turn(Map<String, Object> ctx) {
        return ctx.get(TURN) instanceof Number n ? Math.max(0, n.longValue()) : 0;     // never the app's -1
    }

    private static MemoryContext.Audience audience(Map<String, Object> ctx) {
        return new MemoryContext.Audience(Boolean.TRUE.equals(ctx.get(OTHERS_PRESENT)), false);
    }
}
