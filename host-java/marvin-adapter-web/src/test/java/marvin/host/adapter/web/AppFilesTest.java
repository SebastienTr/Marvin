// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The Java host's app (resources/app). It is its own app since the new visual direction: the Python host keeps its
 * older one in host/marvin_host/ui/static (docs/ui.md). These checks keep it servable, self-contained and
 * consistent: every file is served and every served file exists; every script and style the page loads, and every
 * module a script imports, is one of them; nothing comes from the network; no inline script or style (the CSP);
 * and the Day and Night appearances define the same variables, so they share every rule.
 */
class AppFilesTest {
    private static final Pattern STATIC_REF = Pattern.compile("(?:src|href)=\"/static/([^\"]+)\"");
    private static final Pattern IMPORT = Pattern.compile("(?:import|from)\\s+\"\\./([^\"]+)\"");
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'`)]+");
    private static final Set<String> ALLOWED_URLS = Set.of("http://www.w3.org/2000/svg");

    static Path appDir() throws URISyntaxException {
        return Path.of(AppFilesTest.class.getResource("/app/index.html").toURI()).getParent();
    }

    static String read(String name) throws IOException {
        try (InputStream in = AppFilesTest.class.getResourceAsStream("/app/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static List<String> matches(Pattern p, String text) {
        Matcher m = p.matcher(text);
        List<String> out = new java.util.ArrayList<>();
        while (m.find()) {
            out.add(m.group(m.groupCount() >= 1 ? 1 : 0));
        }
        return out;
    }

    @Test
    void everyFileIsServedAndEveryServedFileExists() throws Exception {
        Set<String> files = new TreeSet<>();
        try (var list = Files.list(appDir())) {
            list.forEach(f -> files.add(f.getFileName().toString()));
        }
        assertThat(files).isEqualTo(new TreeSet<>(AppController.STATIC_TYPES.keySet()));
    }

    @Test
    void thePageAndItsModulesLoadOnlyServedFiles() throws Exception {
        Set<String> loaded = new LinkedHashSet<>(matches(STATIC_REF, read("index.html")));
        assertThat(loaded).contains("style.css", "theme.js", "app.js");
        for (String name : AppController.STATIC_TYPES.keySet()) {
            if (name.endsWith(".js")) {
                loaded.addAll(matches(IMPORT, read(name)));
            }
        }
        assertThat(AppController.STATIC_TYPES.keySet()).containsAll(loaded);
        // every script is used: loaded by the page or imported by another one
        for (String name : AppController.STATIC_TYPES.keySet()) {
            if (name.endsWith(".js")) {
                assertThat(loaded).as("%s is loaded", name).contains(name);
            }
        }
    }

    @Test
    void nothingComesFromTheNetworkAndNothingIsInline() throws Exception {
        for (String name : AppController.STATIC_TYPES.keySet()) {
            String text = read(name);
            for (String url : matches(URL, text)) {
                assertThat(ALLOWED_URLS).as("%s refers to %s", name, url).contains(url);
            }
            if (!name.endsWith(".webmanifest")) {
                assertThat(text).as("%s has its licence header", name).contains("SPDX-License-Identifier: MIT");
            }
        }
        String page = read("index.html");
        assertThat(page).doesNotContain("<style").doesNotContain(" style=");
        assertThat(Pattern.compile("<script(?![^>]*\\bsrc=)").matcher(page).find()).as("inline script").isFalse();
        assertThat(page).doesNotContainPattern("\\son[a-z]+=\"");
    }

    @Test
    void dayAndNightDefineTheSameVariables() throws Exception {
        String css = read("style.css");
        Set<String> day = variables(block(css, ":root {"));
        Set<String> night = variables(block(css, ":root[data-theme=\"night\"] {"));
        assertThat(night).isNotEmpty();
        // the fonts and sizes are shared; every colour Day defines, Night defines too, and nothing else
        Set<String> shared = Set.of("--display", "--sans", "--mono", "--radius", "--space");
        day.removeAll(shared);
        assertThat(night).isEqualTo(day);
    }

    private static String block(String css, String selector) {
        int i = css.indexOf(selector);
        assertThat(i).as(selector).isGreaterThanOrEqualTo(0);
        return css.substring(i, css.indexOf('}', i));
    }

    private static Set<String> variables(String block) {
        return new TreeSet<>(matches(Pattern.compile("(--[a-z0-9-]+)\\s*:"), block));
    }
}
