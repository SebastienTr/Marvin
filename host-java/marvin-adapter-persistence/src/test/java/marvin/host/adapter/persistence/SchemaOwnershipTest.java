// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Each bounded context owns its schema (docs/design.md 10.4): a context's store (and its share of the import)
 * names no other context's schema in its SQL. Checked on the sources, so a new query that reaches across fails
 * here before it can make two contexts inseparable.
 */
class SchemaOwnershipTest {
    static final Path SOURCES = Path.of("src/main/java/marvin/host/adapter/persistence");

    /** Source file (or nested class) → the schema it owns. */
    static final Map<String, String> OWNER = Map.of(
            "JdbcPresenceHistoryStore.java", "presence",
            "JdbcConversationStore.java", "conversation",
            "JdbcSettingsStore.java", "settings");

    static final Pattern SCHEMA = Pattern.compile("\\b(" + String.join("|", ContextMigrations.SCHEMAS) + ")\\.[a-z_]+");

    @Test
    void storesStayInTheirOwnSchema() throws IOException {
        List<String> problems = new ArrayList<>();
        for (var e : OWNER.entrySet()) {
            problems.addAll(foreign(e.getKey(), Files.readString(SOURCES.resolve(e.getKey())), e.getValue()));
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void eachContextImportsOnlyItsOwnTables() throws IOException {
        String src = Files.readString(SOURCES.resolve("SqliteImporter.java"));
        List<String> problems = new ArrayList<>();
        for (String ctx : List.of("Presence", "Settings", "Conversation")) {
            int i = src.indexOf("static final class " + ctx + "Import");
            int j = src.indexOf("\n    }\n", i);
            assertThat(i).as(ctx + "Import").isPositive();
            problems.addAll(foreign(ctx + "Import", src.substring(i, j), ctx.toLowerCase(java.util.Locale.ROOT)));
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void theRuleBites() {
        assertThat(foreign("x", "jdbc.sql(\"SELECT * FROM presence.event JOIN settings.setting\")", "presence"))
                .containsExactly("x names settings.setting");
    }

    static List<String> foreign(String where, String src, String own) {
        List<String> out = new ArrayList<>();
        Matcher m = SCHEMA.matcher(src);
        while (m.find()) {
            if (!m.group(1).equals(own) && inString(src, m.start())) {
                out.add(where + " names " + m.group());
            }
        }
        return out;
    }

    /** Inside a string literal on its line (SQL), not a Java package or a comment. */
    private static boolean inString(String src, int at) {
        int line = src.lastIndexOf('\n', at) + 1;
        String before = src.substring(line, at);
        if (before.strip().startsWith("*") || before.contains("//")) {
            return false;
        }
        return before.chars().filter(c -> c == '"').count() % 2 == 1;
    }
}
