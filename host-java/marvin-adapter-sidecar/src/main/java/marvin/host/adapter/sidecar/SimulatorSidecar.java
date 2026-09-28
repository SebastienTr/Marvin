// SPDX-License-Identifier: MIT
package marvin.host.adapter.sidecar;

import java.util.List;

/**
 * The demo's robot: the Python simulator ({@code marvin-host sim}) sending protocol v1 to the host's UDP
 * port on this computer, like a real simulated robot on the LAN. Supervised: restarted with a backoff when
 * it stops, until {@link #stop()}.
 */
public final class SimulatorSidecar {
    private final SupervisedProcess process;

    public SimulatorSidecar(PythonRuntime python, int port) {
        this.process = new SupervisedProcess("simulator", () -> python.command(List.of("-m", "marvin_host.cli", "sim",
                "--host", "127.0.0.1", "--port", Integer.toString(port))), line -> { });
    }

    public void start() {
        process.start();
    }

    public void stop() {
        process.stop();
    }

    public boolean alive() {
        return process.alive();
    }
}
