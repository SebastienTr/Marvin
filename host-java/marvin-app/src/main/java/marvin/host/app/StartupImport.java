// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import marvin.host.adapter.persistence.SqliteImporter;
import marvin.host.adapter.sidecar.DemoSeed;
import marvin.host.adapter.sidecar.PythonRuntime;
import marvin.host.domain.shared.Clocks;

/**
 * Before anything reads the database (settings, history): imports the Python host's {@code marvin.db}
 * once, or, in demo mode, a simulated past week made by the Python host's demo code. Done when the bean
 * is created; the settings and the history depend on it.
 */
public final class StartupImport {
    private static final Logger log = LoggerFactory.getLogger(StartupImport.class);

    /**
     * @param importFile the Python host's history to import once ({@code null}: none)
     * @param demoPython in demo mode, the Python that makes the simulated past week ({@code null}: live, or none)
     */
    public StartupImport(SqliteImporter importer, Path importFile, PythonRuntime demoPython, Clocks clocks, ZoneId zone) {
        if (demoPython != null) {
            seedDemo(importer, demoPython, clocks, zone);
        } else if (importFile != null) {
            try {
                SqliteImporter.Result r = importer.importOnce(importFile);
                if (!r.imported()) {
                    log.debug("history import of {}: {}", importFile, r.reason());
                }
            } catch (RuntimeException e) {
                log.warn("could not import the Python host's history {}: {}", importFile, e.getMessage());
            }
        }
    }

    private static void seedDemo(SqliteImporter importer, PythonRuntime python, Clocks clocks, ZoneId zone) {
        Path file = null;
        try {
            file = Files.createTempFile("marvin-demo-", ".db");
            long t0 = System.nanoTime();
            new DemoSeed(python).write(file, clocks.wallSeconds(), zone);
            SqliteImporter.Result r = importer.importOnce(file);
            log.info("demo: a simulated past week ({} events, {} conversation entries) in {} ms", r.events(),
                    r.conversation(), (System.nanoTime() - t0) / 1_000_000);
        } catch (Exception e) {
            log.warn("demo: no simulated past week ({})", e.getMessage());
        } finally {
            if (file != null) {
                for (String suffix : new String[] {"", "-wal", "-shm"}) {
                    try {
                        Files.deleteIfExists(Path.of(file + suffix));
                    } catch (java.io.IOException e) {
                        // a temporary file
                    }
                }
            }
        }
    }
}
