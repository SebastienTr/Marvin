// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot.mvrec;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import marvin.host.domain.robot.Endpoint;

/**
 * Feeds a recording's datagrams to a receiver, as {@code record.replay} does: in real time, faster, or
 * as fast as possible; optionally looping (the device clock then jumps back, as when a robot reboots).
 */
public final class RecordingReplay {

    /** Takes one datagram, as a socket would give it. */
    @FunctionalInterface
    public interface Receiver {
        void receive(byte[] data, int length, Endpoint from, long nanoTime);
    }

    private RecordingReplay() {
    }

    /**
     * @param speed 1 = real time, 2 = twice as fast, 0 or infinite = as fast as possible
     * @param stop  checked between datagrams; true ends the replay
     * @return the datagrams replayed
     */
    public static long replay(Path path, Receiver rx, double speed, boolean loop, BooleanSupplier stop) {
        boolean fast = speed <= 0 || Double.isInfinite(speed);
        long n = 0;
        do {
            long start = System.nanoTime();
            try (RecordingReader reader = new RecordingReader(path)) {
                for (RecordingReader.Datagram d : reader) {
                    if (stop.getAsBoolean()) {
                        return n;
                    }
                    if (!fast) {
                        long due = start + (long) (d.tUs() * 1000 / speed);
                        long delay = due - System.nanoTime();
                        while (delay > 0) {
                            try {
                                Thread.sleep(Math.min(delay / 1_000_000 + 1, 200));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return n;
                            }
                            if (stop.getAsBoolean()) {
                                return n;
                            }
                            delay = due - System.nanoTime();
                        }
                    }
                    rx.receive(d.data(), d.data().length, d.from(), System.nanoTime());
                    n++;
                }
            }
        } while (loop && !stop.getAsBoolean());
        return n;
    }
}
