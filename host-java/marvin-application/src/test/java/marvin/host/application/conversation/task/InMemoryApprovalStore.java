// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import marvin.host.application.conversation.port.out.ApprovalStore;
import marvin.host.domain.conversation.task.Approval;

/** The approval store in memory, for tests. */
public final class InMemoryApprovalStore implements ApprovalStore {
    public final Map<Long, Approval> approvals = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public Approval add(Approval a) {
        Approval s = a.withId(ids.incrementAndGet());
        approvals.put(s.id(), s);
        return s;
    }

    @Override
    public void update(Approval a) {
        approvals.put(a.id(), a);
    }

    @Override
    public Optional<Approval> get(long id) {
        return Optional.ofNullable(approvals.get(id));
    }

    @Override
    public List<Approval> recent(int decided) {
        List<Approval> all = new ArrayList<>(approvals.values());
        all.sort(Comparator.comparingLong(Approval::id).reversed());
        List<Approval> out = new ArrayList<>();
        int n = 0;
        for (Approval a : all) {
            if (!a.state().decided()) {
                out.add(a);
            } else if (n++ < decided) {
                out.add(a);
            }
        }
        return out;
    }

    @Override
    public int expirePending(double at, String reason) {
        int n = 0;
        for (Approval a : List.copyOf(approvals.values())) {
            if (!a.state().decided()) {
                approvals.put(a.id(), a.expire(at, reason));
                n++;
            }
        }
        return n;
    }
}
