// SPDX-License-Identifier: MIT
package marvin.host.app;

import java.net.ConnectException;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.env.Environment;

/**
 * A start that fails because PostgreSQL cannot be reached says so in one line, with what to run, instead of a
 * long Flyway and connection-pool stack trace.
 */
final class DatabaseUnreachableAnalyzer extends AbstractFailureAnalyzer<ConnectException> {
    private final Environment env;

    DatabaseUnreachableAnalyzer(Environment env) {
        this.env = env;
    }

    @Override
    protected FailureAnalysis analyze(Throwable root, ConnectException cause) {
        if (!mentionsTheDatabase(root)) {
            return null;
        }
        String url = env.getProperty("spring.datasource.url", "the configured database");
        return new FailureAnalysis("cannot reach PostgreSQL at " + url + " (" + cause.getMessage() + ")",
                "./marvin status, then ./marvin doctor (is the database running, and on that port?)", cause);
    }

    private static boolean mentionsTheDatabase(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause() == x ? null : x.getCause()) {
            String n = x.getClass().getName();
            if (x instanceof java.sql.SQLException || n.startsWith("org.flywaydb") || n.startsWith("org.postgresql")
                    || n.startsWith("com.zaxxer.hikari")) {
                return true;
            }
        }
        return false;
    }
}
