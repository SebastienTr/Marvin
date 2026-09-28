// SPDX-License-Identifier: MIT
package marvin.host.adapter.sidecar;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The demo's past week, made by the Python host's own demo code ({@code ui/demo.py}
 * {@code seed_history} and {@code seed_conversations}) into a new SQLite history, which the host then
 * imports like any {@code marvin.db}. One source of simulated days for both hosts.
 */
public final class DemoSeed {
    private final PythonRuntime python;

    public DemoSeed(PythonRuntime python) {
        this.python = python;
    }

    /** Writes the history as of {@code now} (Unix seconds, local days in {@code zone}) to {@code out}. */
    public void write(Path out, double now, ZoneId zone) throws IOException, InterruptedException {
        Path script = Files.createTempFile("marvin-demo-seed", ".py");
        try {
            try (InputStream in = DemoSeed.class.getResourceAsStream("/marvin/sidecar/demo_seed.py")) {
                Files.write(script, in.readAllBytes());
            }
            Files.deleteIfExists(out);
            ProcessBuilder pb = python.command(List.of(script.toString(), out.toString(),
                    String.format(Locale.ROOT, "%.6f", now)));
            pb.environment().put("TZ", zone.getId());
            pb.environment().put("PYTHONPATH", python.hostDir().toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("the demo seed did not finish in 2 minutes");
            }
            if (p.exitValue() != 0) {
                throw new IOException("the demo seed failed: " + output.strip());
            }
        } finally {
            Files.deleteIfExists(script);
        }
    }
}
