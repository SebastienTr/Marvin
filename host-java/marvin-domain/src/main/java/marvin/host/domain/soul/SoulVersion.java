// SPDX-License-Identifier: MIT
package marvin.host.domain.soul;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One version of a part of the soul, kept for ever (the owner can go back to any).
 *
 * @param version from 1, per part; 0: nothing written yet
 * @param created Unix seconds
 * @param author  {@link #OWNER} (the owner's edit or revert) or {@link #REFLECTION} (a proposal the owner approved)
 * @param note    why it changed ("Approved by the owner", "Reverted to version 2"), or {@code null}
 */
public record SoulVersion(SoulPart part, int version, String text, double created, String author, String note) {
    public static final String OWNER = "owner";
    public static final String REFLECTION = "reflection";

    public SoulVersion {
        Objects.requireNonNull(part, "part");
        text = text == null ? "" : text;
        if (!OWNER.equals(author) && !REFLECTION.equals(author)) {
            throw new IllegalArgumentException("author must be owner or reflection");
        }
        if (part == SoulPart.CORE && REFLECTION.equals(author)) {
            throw new IllegalArgumentException("the core is written by the owner only");
        }
    }

    /** As the app lists versions. */
    public Map<String, Object> toListMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        m.put("part", part.id());
        m.put("created", created);
        m.put("author", author);
        m.put("note", note);
        return m;
    }
}
