// SPDX-License-Identifier: MIT
package marvin.host.domain.memory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A version of a block (the {@code block_version} table of docs/design.md 5.4). Every rewrite is a new version;
 * the active one is in the prompt; the others stay readable (with a diff) and restorable. A version replaced by a
 * newer active one is {@code superseded} (a status the design's list does not have: {@code reverted} is kept for a
 * version the owner undid).
 *
 * @param keptLines lines kept verbatim by every rewrite: written or pinned by the owner
 * @param evidence  the log events behind it (a rewrite's new and invalidated facts' sources)
 */
public record BlockVersion(long id, Block block, String content, int tokens, Status status, String rationale,
                           List<Long> evidence, Author author, Instant createdAt, Instant decidedAt,
                           List<String> keptLines) {

    public enum Status {
        PROPOSED, ACTIVE, SUPERSEDED, REJECTED, REVERTED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Status parse(String s) {
            return valueOf(s.toUpperCase(Locale.ROOT));
        }
    }

    public enum Author {
        OWNER, WORKER, REFLECTION;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Author parse(String s) {
            return valueOf(s.toUpperCase(Locale.ROOT));
        }
    }

    public BlockVersion {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(author, "author");
        content = content == null ? "" : content;
        rationale = rationale == null ? "" : rationale;
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        keptLines = List.copyOf(keptLines == null ? List.of() : keptLines);
    }
}
