// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;

/**
 * The Python host of this repository, for the tests that run it beside the Java host (the simulator,
 * the recording reader). Tests that need it are skipped when Python or its dependencies are missing.
 */
final class PythonHost {
    private PythonHost() {
    }

    /** The repository root: the first parent of the working directory holding host/marvin_host. */
    static Path repo() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("host/marvin_host"))) {
            p = p.getParent();
        }
        Assumptions.assumeTrue(p != null, "the Python host (host/marvin_host) is not in a parent directory");
        return p;
    }

    /** $MARVIN_PYTHON, else host/.venv's python, else python3; skips the test if it cannot import the host. */
    static String python() {
        Path repo = repo();
        List<String> candidates = new ArrayList<>();
        String env = System.getenv("MARVIN_PYTHON");
        if (env != null && !env.isBlank()) {
            candidates.add(env);
        }
        Path venv = repo.resolve("host/.venv/bin/python");
        if (Files.isExecutable(venv)) {
            candidates.add(venv.toString());
        }
        candidates.add("python3");
        for (String py : candidates) {
            try {
                Process p = new ProcessBuilder(py, "-c", "import numpy, marvin_host.sim, marvin_host.record")
                        .directory(repo.resolve("host").toFile()).redirectErrorStream(true).start();
                if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return py;
                }
                p.destroyForcibly();
            } catch (IOException e) {
                // try the next one
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assumptions.abort("no Python that can import marvin_host (numpy missing?)");
        return null;
    }

    /** Starts {@code python -m marvin_host.cli args...} in host/, output to a log file. */
    static Process start(Path log, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(python(), "-m", "marvin_host.cli"));
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd).directory(repo().resolve("host").toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
    }

    /** Runs a Python snippet in host/ and returns its standard output. */
    static String run(String code) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(python(), "-c", code).directory(repo().resolve("host").toFile()).start();
        byte[] out = p.getInputStream().readAllBytes();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new AssertionError("python failed: " + err);
        }
        return new String(out, StandardCharsets.UTF_8);
    }
}
