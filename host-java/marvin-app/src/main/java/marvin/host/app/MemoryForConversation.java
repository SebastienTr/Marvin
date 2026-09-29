// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import marvin.host.application.conversation.port.out.MemoryContext;
import marvin.host.application.memory.port.in.ConfirmForgetting;
import marvin.host.application.memory.port.in.ManageFacts;
import marvin.host.application.memory.port.in.RecallMemory;
import marvin.host.domain.conversation.ContextAssembler;
import marvin.host.domain.memory.EventFeeds;
import marvin.host.domain.memory.Fact;

/**
 * The conversation's memory port over memory's in-ports (docs/design.md 5.3). Only ports meet here: the conversation
 * knows nothing of memory's types, memory nothing of the conversation's; this class translates between them and
 * holds no behaviour of its own.
 */
final class MemoryForConversation implements MemoryContext {
    private final RecallMemory recall;
    private final ManageFacts facts;
    private final ConfirmForgetting forgetting;

    MemoryForConversation(RecallMemory recall, ManageFacts facts, ConfirmForgetting forgetting) {
        this.recall = recall;
        this.facts = facts;
        this.forgetting = forgetting;
    }

    private static RecallMemory.Audience audience(Audience a) {
        return new RecallMemory.Audience(a.othersPresent(), a.cloudModel());
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Profile profile() {
        return recall.profile().map(v -> new Profile(v.id(), v.content())).orElse(Profile.NONE);
    }

    @Override
    public Recollection recollect(String question, Audience audience) {
        RecallMemory.Recollection r = recall.recollect(question, audience(audience));
        Map<String, Double> timings = new LinkedHashMap<>();
        timings.put("embed", r.embedSeconds());
        timings.put("search", r.searchSeconds());
        return new Recollection(items(r.gist()), items(r.facts()), timings, r.problem());
    }

    private static List<ContextAssembler.Item> items(List<RecallMemory.Line> lines) {
        return lines.stream().map(l -> new ContextAssembler.Item(l.key(), l.text(), l.score(), l.detail())).toList();
    }

    @Override
    public void used(Collection<String> keys) {
        List<UUID> ids = new ArrayList<>();
        for (String k : keys) {
            try {
                ids.add(UUID.fromString(k));
            } catch (IllegalArgumentException e) {
                // not a fact (a summary's sentence)
            }
        }
        recall.used(ids);
    }

    @Override
    public ToolAnswer remember(String statement, Audience audience) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            if (audience.othersPresent()) {
                Fact f = facts.suggest(statement, "owner", null);
                m.put("suggested", f.statement());
                m.put("note", "Someone else is in the room: this waits for the owner to keep it in the app. Say it is noted.");
                return ToolAnswer.ok(m);
            }
            Fact f = facts.remember(statement, "owner", null);
            m.put("remembered", f.statement());
            return ToolAnswer.ok(m);
        } catch (IllegalArgumentException e) {
            return ToolAnswer.failed(e.getMessage());
        }
    }

    @Override
    public ToolAnswer recall(String query, String period, Audience audience) {
        return ToolAnswer.ok(recall.recall(query, period, audience(audience)));
    }

    @Override
    public ToolAnswer forget(String query, String confirm, long turn, long said, Audience audience) {
        if (confirm == null || confirm.isBlank()) {
            ConfirmForgetting.Proposal p = forgetting.proposeMatching(query, "voice", turn,
                    said >= 0 ? EventFeeds.conversationRef(said) : "", audience(audience));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("matches", p.facts().stream().map(Fact::statement).toList());
            if (p.facts().isEmpty()) {
                m.put("note", "nothing in memory matches: say so");
            } else {
                if (audience.othersPresent()) {
                    m.put("note", "Nothing is forgotten yet. Someone else is in the room: the owner confirms this in the app, "
                            + "where the request now waits. Say so.");
                } else {
                    m.put("confirm", p.code());
                    m.put("note", "Nothing is forgotten yet. Tell the person what would be forgotten and ask them to confirm; "
                            + "only after they say yes, call forget again with this confirmation code.");
                }
            }
            return ToolAnswer.ok(m);
        }
        if (audience.othersPresent()) {
            return ToolAnswer.failed("someone else is in the room: only the owner confirms a forget, in the app (the request "
                    + "waits on Home)");
        }
        ConfirmForgetting.Outcome o = forgetting.confirm(confirm, turn, null);
        if (!o.done()) {
            return ToolAnswer.failed(o.reason());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("forgotten", o.forgotten().facts());
        return ToolAnswer.ok(m);
    }
}
