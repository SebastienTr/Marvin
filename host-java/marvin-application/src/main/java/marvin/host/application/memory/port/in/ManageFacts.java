// SPDX-License-Identifier: MIT
package marvin.host.application.memory.port.in;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import marvin.host.application.memory.port.out.FactStore;
import marvin.host.domain.memory.Fact;
import marvin.host.domain.memory.MemoryEvent;
import marvin.host.domain.memory.Sensitivity;

/**
 * The owner's view and control of the facts (docs/design.md 5.6): every fact with its sources, one tap away;
 * edit, pin, forget. Every change is an owner event in the log, so the worker never undoes it.
 */
public interface ManageFacts {

    /** A fact with where it came from and its other versions. */
    record Detail(Fact fact, List<MemoryEvent> sources, List<Fact> versions) {
    }

    /** A page of facts and how many match. */
    record Page(List<Fact> facts, long total) {
    }

    /** What the owner changes in a fact (a {@code null} field is left as it is). */
    record Edit(String statement, String subject, String kind, Integer importance, Sensitivity sensitivity) {
    }

    Page list(FactStore.Query query);

    Optional<Detail> get(UUID id);

    /**
     * The owner states a fact ("remember that ...", or in the app): written at once, confidence 1, with an owner
     * event as its source.
     */
    Fact remember(String statement, String subject, Sensitivity sensitivity);

    /** A new version written by the owner; the old one is superseded. */
    Fact edit(UUID id, Edit edit);

    void pin(UUID id, boolean pinned);

    void archive(UUID id, boolean archived);

    /** The owner looked at suggested facts and keeps them ({@code reviewed} false: back to suggestions). */
    void review(java.util.Collection<UUID> ids, boolean reviewed);
}
