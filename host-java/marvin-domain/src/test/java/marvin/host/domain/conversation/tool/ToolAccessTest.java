// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * The tool classes are frozen for Marvin v1 (contract C7, docs/design.md 6.3): read, local write, external write. There
 * is no class for irreversible actions (sending an email, deleting anything remote, paying), so no such tool can be
 * registered: a connector's tool filter drops them before they become tools.
 */
class ToolAccessTest {
    @Test
    void theClassesAreFrozen() {
        assertThat(ToolAccess.values()).containsExactly(ToolAccess.READ, ToolAccess.LOCAL_WRITE, ToolAccess.EXTERNAL_WRITE);
    }

    @Test
    void irreversibleIsNeverAClass() {
        assertThat(Arrays.stream(ToolAccess.values()).map(Enum::name))
                .noneMatch(n -> n.contains("IRREVERSIBLE") || n.contains("SEND") || n.contains("DELETE"));
    }
}
