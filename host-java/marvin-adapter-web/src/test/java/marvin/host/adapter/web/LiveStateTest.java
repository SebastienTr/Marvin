// SPDX-License-Identifier: MIT
package marvin.host.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import marvin.host.domain.robot.event.RoomState;

/** The live state's {@code room}, as the Python host's {@code Room.snapshot()} writes it. */
class LiveStateTest {

    @Test
    void theRoomAsTheAppReadsIt() {
        assertThat(LiveState.room(null)).isNull();
        Map<String, Object> m = LiveState.room(new RoomState(12.34, "marvin-112233", false, true, false, false, 42.34,
                true, 23.44, 48.0, false, null));
        assertThat(m.keySet()).containsExactly("fresh", "age_s", "device", "simulated", "temperature_c", "humidity",
                "light", "light_word", "motion_sensor", "climate_sensor", "light_sensor", "warming_up", "motion",
                "motion_age_s");
        assertThat(m).containsEntry("fresh", true).containsEntry("age_s", 12.3).containsEntry("temperature_c", 23.4)
                .containsEntry("humidity", 48.0).containsEntry("light", null).containsEntry("light_word", null)
                .containsEntry("motion_sensor", true).containsEntry("motion_age_s", 42.3);
    }
}
