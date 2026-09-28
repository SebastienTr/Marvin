// SPDX-License-Identifier: MIT
package marvin.host.adapter.sidecar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The demo's robot: the Python simulator ({@code marvin-host sim}) sending protocol v1 to the host's UDP
 * port on this computer, like a real simulated robot on the LAN. Supervised: restarted a few seconds
 * after it stops, until {@link #stop()}.
 */
public final class SimulatorSidecar {
    private static final Logger log = LoggerFactory.getLogger(SimulatorSidecar.class);
    static final long RESTART_AFTER_MS = 5000;

    private final PythonRuntime python;
    private final int port;
    private volatile boolean running;
    private volatile Process process;
    private Thread supervisor;

    public SimulatorSidecar(PythonRuntime python, int port) {
        this.python = python;
        this.port = port;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        supervisor = Thread.ofVirtual().name("simulator-sidecar").start(this::supervise);
    }

    private void supervise() {
        while (running) {
            try {
                ProcessBuilder pb = python.command(List.of("-m", "marvin_host.cli", "sim", "--host", "127.0.0.1",
                        "--port", Integer.toString(port)));
                pb.redirectErrorStream(true);
                Process p = pb.start();
                process = p;
                log.info("simulated robot started (Python simulator, pid {}) sending to UDP {}", p.pid(), port);
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = r.readLine()) != null;) {
                        log.debug("[simulator] {}", line);
                    }
                }
                int code = p.waitFor();
                if (running) {
                    log.warn("the simulated robot stopped (exit {}); restarting it", code);
                }
            } catch (IOException e) {
                log.warn("could not start the simulated robot: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                if (running) {
                    Thread.sleep(RESTART_AFTER_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public synchronized void stop() {
        running = false;
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        if (supervisor != null) {
            supervisor.interrupt();
        }
    }

    public boolean alive() {
        Process p = process;
        return running && p != null && p.isAlive();
    }
}
