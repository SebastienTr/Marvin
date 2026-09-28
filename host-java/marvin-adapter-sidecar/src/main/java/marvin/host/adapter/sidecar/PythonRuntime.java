// SPDX-License-Identifier: MIT
package marvin.host.adapter.sidecar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the Python that runs the sidecars: {@code marvin.sidecar.python}, else the repository's
 * {@code host/.venv}, else {@code python3}; the first one that imports the Python host (and numpy).
 */
public final class PythonRuntime {
    private static final Logger log = LoggerFactory.getLogger(PythonRuntime.class);

    private final Path hostDir;
    private final String python;

    private PythonRuntime(Path hostDir, String python) {
        this.hostDir = hostDir;
        this.python = python;
    }

    /** The runtime, or empty (with the reason logged) when the Python host cannot be run here. */
    public static Optional<PythonRuntime> find(SidecarProperties props) {
        Path repo = props.repo() != null && !props.repo().toString().isBlank() ? props.repo() : searchRepo();
        if (repo == null || !Files.isDirectory(repo.resolve("host/marvin_host"))) {
            log.warn("the Python host (host/marvin_host) was not found: set MARVIN_REPO to the repository");
            return Optional.empty();
        }
        Path host = repo.resolve("host");
        List<String> candidates = new ArrayList<>();
        if (props.python() != null && !props.python().isBlank()) {
            candidates.add(props.python());
        }
        Path venv = host.resolve(".venv/bin/python");
        if (Files.isExecutable(venv)) {
            candidates.add(venv.toString());
        }
        candidates.add("python3");
        for (String py : candidates) {
            try {
                Process p = new ProcessBuilder(py, "-c", "import numpy, marvin_host.sim, marvin_host.ui.demo")
                        .directory(host.toFile()).redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return Optional.of(new PythonRuntime(host, py));
                }
                p.destroyForcibly();
            } catch (IOException e) {
                // try the next one
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        log.warn("no Python here can run the Python host ({}): ./marvin doctor", String.join(", ", candidates));
        return Optional.empty();
    }

    private static Path searchRepo() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("host/marvin_host"))) {
            p = p.getParent();
        }
        return p;
    }

    /** The Python host's directory ({@code host/}): the working directory of the sidecars. */
    public Path hostDir() {
        return hostDir;
    }

    public String python() {
        return python;
    }

    /** A process builder for {@code python args...} in {@code host/}. */
    public ProcessBuilder command(List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(python);
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(hostDir.toFile());
        pb.environment().put("PYTHONUNBUFFERED", "1");
        return pb;
    }
}
