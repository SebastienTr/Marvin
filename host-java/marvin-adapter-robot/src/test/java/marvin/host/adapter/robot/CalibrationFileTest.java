// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import marvin.host.domain.presence.BrainConfig;

/** calibration.json: the LD2450 signs, and the brain's thresholds tuned by its {@code brain} block. */
class CalibrationFileTest {

    @Test
    void theBrainBlockTunesTheThresholds(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(CalibrationFile.FILE_NAME);
        Files.writeString(f, """
                {"version": 1, "ld2450": {"x_sign": 1, "speed_sign": 1},
                 "brain": {"sit_max_m": 1.1, "stand_confirm_s": 3, "radar_speed_sign": -1}}""");
        assertThat(CalibrationFile.load(f).radarSpeedSign()).isEqualTo(1);
        BrainConfig c = CalibrationFile.brain(f, BrainConfig.DEFAULT);
        assertThat(c.sitMaxM()).isEqualTo(1.1);
        assertThat(c.standConfirmS()).isEqualTo(3.0);
        assertThat(c.radarSpeedSign()).isEqualTo(-1);
        assertThat(c.seatTauS()).isEqualTo(BrainConfig.DEFAULT.seatTauS());
        assertThat(c.values()).hasSameSizeAs(BrainConfig.DEFAULT.values());
    }

    @Test
    void noFileNoBlockOrABadOneIsTheBase(@TempDir Path dir) throws Exception {
        BrainConfig base = BrainConfig.DEFAULT.withRadarSpeedSign(1);
        assertThat(CalibrationFile.brain(dir.resolve("none.json"), base)).isEqualTo(base);
        Path f = dir.resolve(CalibrationFile.FILE_NAME);
        Files.writeString(f, "{\"version\": 1}");
        assertThat(CalibrationFile.brain(f, base)).isEqualTo(base);
        Files.writeString(f, "{\"brain\": {\"sit_max_m\": 1.0, \"sit_maxx\": 2}}");
        assertThat(CalibrationFile.brain(f, base)).as("an unknown key: the whole block is ignored").isEqualTo(base);
        Files.writeString(f, "{\"brain\": {\"sit_max_m\": \"far\"}}");
        assertThat(CalibrationFile.brain(f, base)).isEqualTo(base);
    }

    @Test
    void theDefaultsAreBrainPyWithThePostureHysteresis() {
        assertThat(BrainConfig.DEFAULT.with(java.util.Map.of("stand_confirm_s", 0, "seat_tau_s", 0, "resit_after_s", 0,
                "still_grace_s", 0, "sit_still_s", 3))).isEqualTo(BrainConfig.PYTHON);
    }
}
