// SPDX-License-Identifier: MIT
package marvin.host.application.conversation.task;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * One model for the voice and the background tasks: who gets it when both want it. The voice always comes first.
 *
 * <p>With a model server that answers one request at a time ({@code parallel} 1: Ollama's default, and always the case
 * for Qwen3.8, whose architecture Ollama runs one request at a time whatever {@code OLLAMA_NUM_PARALLEL} says), a task's
 * request would make a spoken question wait for a whole reasoning step. So while someone talks with Marvin (a question
 * heard, Marvin listening, thinking or speaking) a task does not start a request, and one already running is cut
 * ({@link Lease#preempted()}): the task makes it again once the conversation has been over for {@link #GRACE_S}. Ollama
 * keeps both prompts' prefixes cached, so the cost is the cut request's thinking.
 *
 * <p>With a server that answers several at once ({@code parallel} 2 or more), tasks run beside the conversation and are
 * never cut; each request is then slower, as they share the computer.
 */
public final class ModelLane {
    /** After a conversation, how long a task waits before it takes the model again (a follow-up question comes fast). */
    public static final double GRACE_S = 2.0;

    private final int parallel;
    private final DoubleSupplier clock;
    private final Object lock = new Object();
    private final Set<Lease> leases = ConcurrentHashMap.newKeySet();
    private boolean voiceBusy;
    private double voiceIdleSince = Double.NEGATIVE_INFINITY;

    /**
     * @param parallel         requests the model server answers at once (1 when unknown)
     * @param monotonicSeconds the clock
     */
    public ModelLane(int parallel, DoubleSupplier monotonicSeconds) {
        this.parallel = Math.max(1, parallel);
        this.clock = monotonicSeconds;
    }

    public int parallel() {
        return parallel;
    }

    /** Whether tasks give way to the voice (the server answers one request at a time). */
    public boolean yieldsToVoice() {
        return parallel < 2;
    }

    /** The voice says whether a conversation is going on; a running task request is cut when one starts. */
    public void voice(boolean busy) {
        synchronized (lock) {
            if (busy == voiceBusy) {
                return;
            }
            voiceBusy = busy;
            if (!busy) {
                voiceIdleSince = clock.getAsDouble();
            } else if (yieldsToVoice()) {
                for (Lease l : leases) {
                    l.preempted = true;
                }
            }
            lock.notifyAll();
        }
    }

    public boolean voiceBusy() {
        synchronized (lock) {
            return voiceBusy;
        }
    }

    /** Whether a task may use the model now. */
    public boolean free() {
        synchronized (lock) {
            return !yieldsToVoice() || !voiceBusy && clock.getAsDouble() - voiceIdleSince >= GRACE_S;
        }
    }

    /**
     * Waits until a task may use the model (at once with a server that runs several requests), then holds it for one
     * request. Returns {@code null} when {@code cancelled} says so first.
     *
     * @param waiting told once, when the task has to wait for the conversation
     */
    public Lease acquire(BooleanSupplier cancelled, Runnable waiting) throws InterruptedException {
        boolean told = false;
        while (!cancelled.getAsBoolean()) {
            synchronized (lock) {
                if (free()) {
                    Lease l = new Lease();
                    leases.add(l);
                    return l;
                }
            }
            if (!told) {
                told = true;
                if (waiting != null) {
                    waiting.run();
                }
            }
            synchronized (lock) {
                lock.wait(100);
            }
        }
        return null;
    }

    /** The model, held by a task for one request. */
    public final class Lease implements AutoCloseable {
        private volatile boolean preempted;

        /** The conversation needed the model: the request must stop now, and be made again later. */
        public boolean preempted() {
            return preempted;
        }

        @Override
        public void close() {
            leases.remove(this);
        }
    }
}
