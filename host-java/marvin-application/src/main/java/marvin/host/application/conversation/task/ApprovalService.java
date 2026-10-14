// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.task;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import marvin.host.application.conversation.port.in.ApprovalControl;
import marvin.host.application.conversation.port.out.ApprovalListener;
import marvin.host.application.conversation.port.out.ApprovalStore;
import marvin.host.domain.conversation.task.Approval;
import marvin.host.domain.conversation.task.Task;
import marvin.host.domain.shared.Clocks;

/**
 * The owner's approvals (docs/design.md 6.3): a task that wants to call a tool that writes outside this computer asks
 * ({@link #request}) and waits ({@link #await}) until the owner approves or rejects the call in the app, its task is
 * cancelled, or nobody answers within {@link #expireS()} (then it expires: the task goes on without it). Every change is
 * kept and published ({@code approval} on the app's stream). After a restart, the approvals still pending are expired:
 * their tasks were interrupted.
 */
public final class ApprovalService implements ApprovalControl {
    private static final Logger log = Logger.getLogger("marvin.tasks");
    /** Decided approvals the app lists. */
    public static final int DECIDED_SHOWN = 50;
    /** How long a call waits for the owner by default: an hour. */
    public static final double DEFAULT_EXPIRE_S = 3600;
    static final String HOST_STOPPED = "Marvin's host stopped while it waited";
    static final String CANCELLED = "Cancelled with its task";

    private final ApprovalStore store;
    private final Clocks clocks;
    private final double expireS;
    private final List<ApprovalListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Long, CompletableFuture<Approval>> waiting = new ConcurrentHashMap<>();
    private final Object deciding = new Object();

    public ApprovalService(ApprovalStore store, Clocks clocks, double expireS) {
        this.store = store;
        this.clocks = clocks;
        this.expireS = expireS > 0 ? expireS : DEFAULT_EXPIRE_S;
        try {
            int n = store.expirePending(clocks.wallSeconds(), HOST_STOPPED);
            if (n > 0) {
                log.info(n + " approval(s) expired by the last stop");
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not expire the approvals the last stop left pending", e);
        }
    }

    public void addListener(ApprovalListener l) {
        listeners.add(l);
    }

    public double expireS() {
        return expireS;
    }

    // ------------------------------------------------------------------ for tasks

    /** Asks the owner's approval of a call (its validated arguments, its preview); returns it, pending and stored. */
    public Approval request(Task task, String tool, Map<String, Object> arguments, Map<String, Object> preview) {
        Approval a = store.add(Approval.pending(task.id(), tool, clocks.wallSeconds(), task.title(), preview, arguments));
        waiting.put(a.id(), new CompletableFuture<>());
        log.info("approval " + a.id() + " asked: " + tool + " for " + task.title());
        publish(a);
        return a;
    }

    /**
     * Waits for the owner's decision; never throws but for an interruption. Expires the approval when {@code cancelled}
     * becomes true (its task was cancelled) or when it waited {@link #expireS()}.
     *
     * @return the approval, decided
     */
    public Approval await(long id, BooleanSupplier cancelled) throws InterruptedException {
        CompletableFuture<Approval> f = waiting.computeIfAbsent(id, k -> new CompletableFuture<>());
        try {
            Optional<Approval> now = store.get(id);
            if (now.isPresent() && now.get().state().decided()) {
                return now.get();
            }
            while (true) {
                try {
                    return f.get(200, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    Approval a = store.get(id).orElseThrow(() -> new NotFound(id));
                    if (a.state().decided()) {
                        return a;
                    }
                    if (cancelled.getAsBoolean()) {
                        return expire(id, CANCELLED);
                    }
                    if (clocks.wallSeconds() - a.created() >= expireS) {
                        return expire(id, "no answer within " + Math.round(expireS / 60) + " min");
                    }
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
        } finally {
            waiting.remove(id);
        }
    }

    /** The approved call ran and made something the owner can open (the draft in Gmail). */
    public void ran(long id, String openUrl) {
        if (openUrl == null || openUrl.isBlank()) {
            return;
        }
        synchronized (deciding) {
            store.get(id).ifPresent(a -> {
                Approval b = a.withOpenUrl(openUrl);
                store.update(b);
                publish(b);
            });
        }
    }

    private Approval expire(long id, String why) {
        synchronized (deciding) {
            Approval a = store.get(id).orElseThrow(() -> new NotFound(id));
            if (a.state().decided()) {
                return a;
            }
            return decided(a.expire(clocks.wallSeconds(), why));
        }
    }

    // ------------------------------------------------------------------ the owner

    @Override
    public List<Approval> approvals() {
        return store.recent(DECIDED_SHOWN);
    }

    @Override
    public Approval approve(long id) {
        synchronized (deciding) {
            Approval a = store.get(id).orElseThrow(() -> new NotFound(id));
            if (a.state().decided()) {
                throw new AlreadyDecided(a);
            }
            return decided(a.approve(clocks.wallSeconds()));
        }
    }

    @Override
    public Approval reject(long id, String reason) {
        synchronized (deciding) {
            Approval a = store.get(id).orElseThrow(() -> new NotFound(id));
            if (a.state().decided()) {
                throw new AlreadyDecided(a);
            }
            return decided(a.reject(clocks.wallSeconds(), reason == null ? "" : reason));
        }
    }

    private Approval decided(Approval a) {
        store.update(a);
        log.info("approval " + a.id() + " " + a.state().id() + (a.reason().isEmpty() ? "" : ": " + a.reason()));
        publish(a);
        CompletableFuture<Approval> f = waiting.get(a.id());
        if (f != null) {
            f.complete(a);
        }
        return a;
    }

    private void publish(Approval a) {
        Map<String, Object> payload = a.toEventMap();
        for (ApprovalListener l : listeners) {
            try {
                l.onApproval(payload);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "an approval listener failed", e);
            }
        }
    }
}
