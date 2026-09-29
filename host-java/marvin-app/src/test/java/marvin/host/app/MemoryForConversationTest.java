// SPDX-License-Identifier: MIT
package marvin.host.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.out.MemoryContext;
import marvin.host.application.memory.ForgetConfirmations;
import marvin.host.application.memory.MemoryRecallService;
import marvin.host.application.memory.testing.FakeMemoryModel;
import marvin.host.application.memory.testing.MemoryFixture;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.FactOrigin;
import marvin.host.domain.memory.RetrievalScoring;
import marvin.host.domain.memory.Sensitivity;

/** With someone else in the room, the voice's memory tools never act as the owner. */
class MemoryForConversationTest {
    static final Instant T = Instant.parse("2026-09-29T10:00:00Z");
    static final MemoryContext.Audience GUEST = new MemoryContext.Audience(true, false);
    final MemoryFixture m = new MemoryFixture(T, new FakeMemoryModel());
    final MemoryRecallService recall = new MemoryRecallService(m.store.facts, m.store.log, m.store.episodes, m.store.profiles,
            m.embeddings, m.days, m.clock, new RetrievalScoring(1.0, 0.5, 0.7, 0.995, 0.3), () -> { });
    final ForgetConfirmations forgetting = new ForgetConfirmations(m.store.facts, m.admin, m.embeddings, m.clock, UUID::randomUUID);
    final MemoryForConversation memory = new MemoryForConversation(recall, m.admin, forgetting);

    @AfterEach
    void close() {
        recall.close();
    }

    @Test
    void aGuestsRememberIsOnlyASuggestion() {
        MemoryContext.ToolAnswer a = memory.remember("The owner's code name is Falcon.", GUEST);
        assertThat(a.toString()).contains("suggested");
        Fact f = m.store.facts.rows.values().iterator().next();
        assertThat(f.origin()).isEqualTo(FactOrigin.EXTRACTED);
        assertThat(f.reviewed()).isFalse();
        assertThat(f.confidence()).isLessThan(1.0);
    }

    @Test
    void aGuestCannotConfirmAForget() {
        Fact f = m.admin.remember("The owner lives in Lyon.", "owner", Sensitivity.NORMAL);
        MemoryContext.ToolAnswer proposed = memory.forget("Lyon", "", 1, -1, GUEST);
        assertThat(proposed.toString()).contains("The owner lives in Lyon.").contains("in the app").doesNotContain("confirm=");
        String code = forgetting.pending().getFirst().code();
        MemoryContext.ToolAnswer confirmed = memory.forget("Lyon", code, 2, -1, GUEST);
        assertThat(confirmed.toString()).contains("only the owner confirms");
        assertThat(m.store.facts.rows).containsKey(f.id());
        // the request waits in the app, where the owner confirms it
        assertThat(forgetting.confirm(code, -1, null).done()).isTrue();
        assertThat(m.store.facts.rows).doesNotContainKey(f.id());
        assertThat(Map.of("left", List.copyOf(m.store.facts.rows.keySet()))).containsEntry("left", List.of());
    }
}
