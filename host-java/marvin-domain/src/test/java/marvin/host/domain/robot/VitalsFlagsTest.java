// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The VITALS flags byte: bit 0 valid (protocol v1), bits 1-2 the MR60BHA2 bridge's signal quality. */
class VitalsFlagsTest {

    static byte[] payload(int flags) {
        byte[] p = new Vitals(true, 14.25, 68.5, 0, 0, 812).encode();
        p[0] = (byte) flags;
        return p;
    }

    @Test
    void aProtocolV1SenderReportsNoQuality() {
        Vitals v = Vitals.decode(payload(0x01));
        assertThat(v.valid()).isTrue();
        assertThat(v.qualityReported()).isFalse();
        assertThat(v.steadyIfReported()).isNull();
        assertThat(v.encode()[0]).isEqualTo((byte) 0x01);       // the simulators' bytes are unchanged
    }

    @Test
    void theBridgeReportsItsQuality() {
        Vitals steady = Vitals.decode(payload(0x07));
        assertThat(steady.valid()).isTrue();
        assertThat(steady.steadyIfReported()).isTrue();
        assertThat(steady.encode()[0]).isEqualTo((byte) 0x07);
        Vitals shaky = Vitals.decode(payload(0x03));
        assertThat(shaky.valid()).isTrue();
        assertThat(shaky.steadyIfReported()).isFalse();
        Vitals nobody = Vitals.decode(payload(0x02));
        assertThat(nobody.valid()).isFalse();
        assertThat(nobody.steadyIfReported()).isFalse();
    }

    @Test
    void steadyWithoutQualityReportedMeansNothing() {
        Vitals v = Vitals.decode(payload(0x05));
        assertThat(v.valid()).isTrue();
        assertThat(v.qualityReported()).isFalse();
        assertThat(v.steady()).isFalse();
        assertThat(new Vitals(true, 14, 60, 0, 0, 900, false, true).encode()[0]).isEqualTo((byte) 0x01);
    }

    @Test
    void theRestOfThePayloadIsUnchanged() {
        Vitals v = Vitals.decode(payload(0x07));
        assertThat(v.breathRate()).isEqualTo(14.25);
        assertThat(v.heartRate()).isEqualTo(68.5);
        assertThat(v.distanceMm()).isEqualTo(812);
    }
}
