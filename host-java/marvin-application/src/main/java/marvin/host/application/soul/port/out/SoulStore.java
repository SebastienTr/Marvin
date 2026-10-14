// SPDX-License-Identifier: MIT
package marvin.host.application.soul.port.out;

import java.util.List;
import java.util.Optional;

import marvin.host.domain.soul.SoulPart;
import marvin.host.domain.soul.SoulProposal;
import marvin.host.domain.soul.SoulVersion;

/** The soul's versions and the reflection's proposals, kept for ever (memory's schema: {@code soul_version}, ...). */
public interface SoulStore {

    /** The newest version of a part, if any was written. */
    Optional<SoulVersion> current(SoulPart part);

    /** Adds the next version of a part (its number is the last one plus one) and returns it. */
    SoulVersion addVersion(SoulPart part, String text, double created, String author, String note);

    Optional<SoulVersion> version(SoulPart part, int version);

    /** The versions of both parts, newest first, at most {@code limit}. */
    List<SoulVersion> versions(int limit);

    /** Stores a new proposal ({@code id} 0) and returns it with its id. */
    SoulProposal addProposal(SoulProposal proposal);

    /** Replaces a stored proposal (its state, its decision). */
    void updateProposal(SoulProposal proposal);

    Optional<SoulProposal> proposal(long id);

    /** The proposals the owner has not decided on yet (pending, or rejected by the guardrails), newest first. */
    List<SoulProposal> awaitingOwner();

    /** When the reflection last ran (Unix seconds), whatever it found. */
    Optional<Double> lastReflection();

    void reflected(double at);
}
