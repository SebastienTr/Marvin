// SPDX-License-Identifier: MIT
package marvin.host.domain.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import marvin.host.domain.vision.event.Frame;

class CameraChoiceTest {
    static final CameraChoice.Source MAC = new CameraChoice.Source("computer", true, "This computer's camera (the built-in one)", "");
    static final CameraChoice.Source NO_FFMPEG = new CameraChoice.Source("computer", false,
            "ffmpeg is not installed: Marvin cannot open this computer's camera", "brew install ffmpeg, then ./marvin restart");
    static final CameraChoice.Source ROBOT = new CameraChoice.Source("robot", true, "The robot's camera (marvin-a1b2c3 at 192.168.1.40)", "");
    static final CameraChoice.Source NO_ROBOT = new CameraChoice.Source("robot", false, "No robot with a camera is connected",
            "Turn the robot on: its camera works once it says hello.");

    @Test
    void autoPrefersTheRobotThenTheComputer() {
        assertThat(CameraChoice.pick("auto", MAC, ROBOT).source()).isEqualTo("robot");
        assertThat(CameraChoice.pick("auto", MAC, NO_ROBOT).source()).isEqualTo("computer");
        assertThat(CameraChoice.pick("auto", MAC, NO_ROBOT).reason()).isEqualTo(MAC.detail());
        assertThat(CameraChoice.pick("auto", NO_FFMPEG, ROBOT).source()).isEqualTo("robot");
        CameraChoice.Choice none = CameraChoice.pick("auto", NO_FFMPEG, NO_ROBOT);
        assertThat(none.available()).isFalse();
        assertThat(none.reason()).isEqualTo("No robot with a camera is connected; ffmpeg is not installed: Marvin cannot open "
                + "this computer's camera");
        assertThat(none.fix()).isEqualTo("brew install ffmpeg, then ./marvin restart");
    }

    @Test
    void aChosenCameraIsUsedOnlyWhenItCanTakeAPicture() {
        assertThat(CameraChoice.pick("computer", MAC, ROBOT).source()).isEqualTo("computer");
        assertThat(CameraChoice.pick("robot", MAC, ROBOT).source()).isEqualTo("robot");
        CameraChoice.Choice r = CameraChoice.pick("robot", MAC, NO_ROBOT);
        assertThat(r.available()).isFalse();
        assertThat(r.reason()).isEqualTo("No robot with a camera is connected");
        assertThat(r.fix()).startsWith("Turn the robot on");
        assertThat(CameraChoice.pick("computer", NO_FFMPEG, ROBOT).fix()).isEqualTo("brew install ffmpeg, then ./marvin restart");
        assertThat(CameraChoice.pick("computer", null, ROBOT).available()).isFalse();
    }

    @Test
    void offIsOffAndAnUnknownSettingIsAuto() {
        CameraChoice.Choice off = CameraChoice.pick("off", MAC, ROBOT);
        assertThat(off.available()).isFalse();
        assertThat(off.setting()).isEqualTo("off");
        assertThat(off.reason()).isEqualTo("The camera is off");
        assertThat(CameraChoice.pick("webcam", MAC, NO_ROBOT).setting()).isEqualTo("auto");
        assertThat(CameraChoice.pick(null, MAC, NO_ROBOT).source()).isEqualTo("computer");
        assertThat(CameraChoice.SETTINGS).containsExactly("auto", "computer", "robot", "off");
    }

    @Test
    void aFrameIsAPictureOfAtMostThePictureSizeAndSaysNothingOfItsPixels() {
        Frame f = new Frame(new byte[] {1, 2, 3}, 1280, 720, "computer", "This computer's camera", 0.91234);
        assertThat(f.describe()).containsEntry("width", 1280).containsEntry("height", 720).containsEntry("bytes", 3)
                .containsEntry("seconds", 0.912).containsEntry("source", "computer").doesNotContainKey("jpeg");
        assertThat(f.toString()).isEqualTo("Frame[computer 1280x720, 3 bytes]");
        assertThatThrownBy(() -> new Frame(new byte[1], 1920, 1080, "computer", "", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(f).isEqualTo(new Frame(new byte[] {1, 2, 3}, 1280, 720, "computer", "This computer's camera", 0.91234));
    }
}
