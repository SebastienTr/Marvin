// SPDX-License-Identifier: MIT
package marvin.host.domain.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** validate_settings of the Python host: same values, same messages. */
class AppSettingsTest {
    static final AppSettings D = AppSettings.defaults(50 * 60);

    @Test
    void defaults() {
        assertThat(D.toMap()).containsExactly(Map.entry("break_interval_min", 50),
                Map.entry("quiet_hours", Map.of("enabled", false, "start", "22:00", "end", "07:00")),
                Map.entry("voice", false), Map.entry("clock", "24h"), Map.entry("ui_sounds", true));
    }

    @Test
    void updates() {
        assertThat(D.apply(Map.of("break_interval_min", 45.0)).breakIntervalMin()).isEqualTo(45);
        assertThat(D.apply(Map.of("break_interval_min", 45.5)).breakIntervalMin()).isEqualTo(45.5);
        AppSettings q = D.apply(Map.of("quiet_hours", Map.of("enabled", true, "end", "07:30")));
        assertThat(q.quietHours()).isEqualTo(new AppSettings.QuietHours(true, "22:00", "07:30"));
        assertThat(q.quietHours().contains(23 * 60)).isTrue();
        assertThat(q.quietHours().contains(7 * 60 + 29)).isTrue();
        assertThat(q.quietHours().contains(12 * 60)).isFalse();
        assertThat(D.apply(Map.of("clock", "12h", "voice", true)).clock()).isEqualTo("12h");
    }

    @Test
    void refusals() {
        assertThatThrownBy(() -> D.apply(Map.of("break_interval_min", 0)))
                .hasMessage("break_interval_min must be a number of minutes between 1 and 240");
        assertThatThrownBy(() -> D.apply(Map.of("break_interval_min", true)))
                .hasMessage("break_interval_min must be a number of minutes between 1 and 240");
        assertThatThrownBy(() -> D.apply(Map.of("quiet_hours", List.of()))).hasMessage("quiet_hours must be an object");
        assertThatThrownBy(() -> D.apply(Map.of("quiet_hours", Map.of("start", "25:00"))))
                .hasMessage("expected a time HH:MM, got '25:00'");
        assertThatThrownBy(() -> D.apply(Map.of("quiet_hours", Map.of("nope", 1))))
                .hasMessage("unknown setting quiet_hours.nope");
        assertThatThrownBy(() -> D.apply(Map.of("quiet_hours", Map.of("enabled", "yes"))))
                .hasMessage("quiet_hours.enabled must be true or false");
        assertThatThrownBy(() -> D.apply(Map.of("ui_sounds", 1))).hasMessage("ui_sounds must be true or false");
        assertThatThrownBy(() -> D.apply(Map.of("clock", "25h"))).hasMessage("clock must be \"24h\" or \"12h\"");
        assertThatThrownBy(() -> D.apply(Map.of("colour", "red"))).hasMessage("unknown setting colour");
    }
}
