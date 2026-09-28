// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MessageTypeTest {

    @Test
    void codesAreUniqueAndSplitByDirection() {
        assertThat(MessageType.values()).extracting(MessageType::code).doesNotHaveDuplicates();
        for (MessageType t : MessageType.values()) {
            boolean hostToRobot = (t.code() & 0x80) != 0;
            assertThat(t.direction() == MessageType.Direction.HOST_TO_ROBOT).as(t.name()).isEqualTo(hostToRobot);
        }
    }

    @Test
    void unknownCodesAreEmpty() {
        assertThat(MessageType.fromCode(0x02)).contains(MessageType.LIDAR);
        assertThat(MessageType.fromCode(0x7f)).isEmpty();
    }
}
