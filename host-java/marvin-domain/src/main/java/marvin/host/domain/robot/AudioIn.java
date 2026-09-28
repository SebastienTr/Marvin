// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.nio.ByteBuffer;

/**
 * {@code AUDIO_IN} payload: the sample index of the first sample (u32, restarts at 0 when the
 * microphone starts), then PCM samples (i16, 16 kHz).
 */
public record AudioIn(long index, short[] pcm) {

    public byte[] encode() {
        ByteBuffer b = Wire.le(4 + 2 * pcm.length);
        b.putInt((int) index);
        for (short s : pcm) {
            b.putShort(s);
        }
        return b.array();
    }

    public static AudioIn decode(byte[] payload) {
        if (payload.length < 4 || (payload.length - 4) % 2 != 0) {
            throw new ProtocolException("bad AUDIO_IN length");
        }
        ByteBuffer b = Wire.le(payload);
        long index = Integer.toUnsignedLong(b.getInt());
        short[] pcm = new short[(payload.length - 4) / 2];
        b.asShortBuffer().get(pcm);
        return new AudioIn(index, pcm);
    }
}
