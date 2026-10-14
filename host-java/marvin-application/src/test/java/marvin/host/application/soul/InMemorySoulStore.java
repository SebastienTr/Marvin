// SPDX-License-Identifier: MIT
package marvin.host.application.soul;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import marvin.host.application.soul.port.out.SoulStore;
import marvin.host.domain.soul.SoulPart;
import marvin.host.domain.soul.SoulProposal;
import marvin.host.domain.soul.SoulVersion;

/** The soul's store in memory, for tests. */
public final class InMemorySoulStore implements SoulStore {
    public final List<SoulVersion> versions = new CopyOnWriteArrayList<>();
    public final List<SoulProposal> proposals = new CopyOnWriteArrayList<>();
    public Double lastReflection;

    @Override
    public Optional<SoulVersion> current(SoulPart part) {
        return versions.stream().filter(v -> v.part() == part).max(Comparator.comparingInt(SoulVersion::version));
    }

    @Override
    public synchronized SoulVersion addVersion(SoulPart part, String text, double created, String author, String note) {
        SoulVersion v = new SoulVersion(part, current(part).map(SoulVersion::version).orElse(0) + 1, text, created, author, note);
        versions.add(v);
        return v;
    }

    @Override
    public Optional<SoulVersion> version(SoulPart part, int version) {
        return versions.stream().filter(v -> v.part() == part && v.version() == version).findFirst();
    }

    @Override
    public List<SoulVersion> versions(int limit) {
        List<SoulVersion> all = new ArrayList<>(versions);
        all.sort(Comparator.comparingDouble(SoulVersion::created).thenComparingInt(SoulVersion::version).reversed());
        return all.subList(0, Math.min(limit, all.size()));
    }

    @Override
    public synchronized SoulProposal addProposal(SoulProposal p) {
        SoulProposal s = p.withId(proposals.size() + 1);
        proposals.add(s);
        return s;
    }

    @Override
    public synchronized void updateProposal(SoulProposal p) {
        proposals.replaceAll(x -> x.id() == p.id() ? p : x);
    }

    @Override
    public Optional<SoulProposal> proposal(long id) {
        return proposals.stream().filter(p -> p.id() == id).findFirst();
    }

    @Override
    public List<SoulProposal> awaitingOwner() {
        List<SoulProposal> out = new ArrayList<>(proposals.stream().filter(SoulProposal::awaitsOwner).toList());
        out.sort(Comparator.comparingLong(SoulProposal::id).reversed());
        return out;
    }

    @Override
    public Optional<Double> lastReflection() {
        return Optional.ofNullable(lastReflection);
    }

    @Override
    public void reflected(double at) {
        lastReflection = at;
    }
}
