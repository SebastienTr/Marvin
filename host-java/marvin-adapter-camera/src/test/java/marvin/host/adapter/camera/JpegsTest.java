// SPDX-License-Identifier: MIT
package marvin.host.adapter.camera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

class JpegsTest {

    @Test
    void aPictureThatFitsIsKeptAsItIsALargerOneIsMadeSmaller() throws Exception {
        byte[] vga = Pictures.jpeg(640, 480);
        Jpegs.Sized s = Jpegs.fit(vga);
        assertThat(s.jpeg()).isSameAs(vga);
        assertThat(s.width()).isEqualTo(640);
        assertThat(s.height()).isEqualTo(480);

        Jpegs.Sized hd = Jpegs.fit(Pictures.jpeg(1920, 1080));
        assertThat(hd.width()).isEqualTo(1280);
        assertThat(hd.height()).isEqualTo(720);
        assertThat(Jpegs.size(hd.jpeg())).containsExactly(1280, 720);

        Jpegs.Sized portrait = Jpegs.fit(Pictures.jpeg(1200, 1600));
        assertThat(portrait.width()).isEqualTo(960);
        assertThat(portrait.height()).isEqualTo(1280);
        assertThatThrownBy(() -> Jpegs.fit("not a picture".getBytes())).isInstanceOf(Jpegs.NotAJpeg.class);
    }

    @Test
    void theFirstPictureOfWhatCame() {
        byte[] a = Pictures.jpeg(32, 24);
        byte[] b = Pictures.jpeg(16, 16);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("--frame\r\n\r\n".getBytes());
        o.writeBytes(a);
        o.writeBytes(b);
        assertThat(Jpegs.first(o.toByteArray())).isEqualTo(a);
        assertThat(Jpegs.first(a)).isEqualTo(a);
        assertThat(Jpegs.first("nothing".getBytes())).isNull();
        assertThat(Jpegs.first(java.util.Arrays.copyOf(a, a.length - 2))).as("cut short").isNull();
    }
}
