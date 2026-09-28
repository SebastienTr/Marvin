// SPDX-License-Identifier: MIT
package marvin.host.domain.presence.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventKindTest {

    @Test
    void faceCodesFollowTheProtocolTable() {
        assertThat(EventKind.values()).extracting(EventKind::faceCode).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(EventKind.fromWireName("sat_down")).contains(EventKind.SAT_DOWN);
        assertThat(EventKind.fromWireName("nope")).isEmpty();
    }
}
