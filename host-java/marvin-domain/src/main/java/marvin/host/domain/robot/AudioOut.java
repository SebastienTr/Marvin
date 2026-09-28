// SPDX-License-Identifier: MIT
package marvin.host.domain.robot;

import java.nio.ByteBuffer;

/**
 * {@code AUDIO_OUT} payload: stream id (u16), sample index of the first sample in that stream (u32),
 * then 1 to {@value ProtocolV1#AUDIO_OUT_MAX_SAMPLES} PCM samples (i16).
 */
public record AudioOut(int stream, long index, short[] pcm) {

    public byte[] encode() {
        if (pcm.length < 1 || pcm.length > ProtocolV1.AUDIO_OUT_MAX_SAMPLES) {
            throw new ProtocolException("AUDIO_OUT carries 1 to " + ProtocolV1.AUDIO_OUT_MAX_SAMPLES
                    + " samples, not " + pcm.length);
        }
        ByteBuffer b = Wire.le(6 + 2 * pcm.length);
        b.putShort((short) stream).putInt((int) index);
        for (short s : pcm) {
            b.putShort(s);
        }
        return b.array();
    }

    public static AudioOut decode(byte[] payload) {
        int n2 = payload.length - 6;
        if (n2 < 2 || n2 % 2 != 0 || n2 / 2 > ProtocolV1.AUDIO_OUT_MAX_SAMPLES) {
            throw new ProtocolException("bad AUDIO_OUT length");
        }
        ByteBuffer b = Wire.le(payload);
        int stream = b.getShort() & 0xffff;
        long index = Integer.toUnsignedLong(b.getInt());
        short[] pcm = new short[n2 / 2];
        b.asShortBuffer().get(pcm);
        return new AudioOut(stream, index, pcm);
    }
}
