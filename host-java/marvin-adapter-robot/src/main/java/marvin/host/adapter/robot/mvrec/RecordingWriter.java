// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot.mvrec;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.Endpoint;
import marvin.host.domain.robot.ProtocolV1;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends records to a new {@code .mvrec} file, as {@code record.RecordingWriter} does. Thread-safe.
 * gzip when the name ends in {@code .gz}.
 */
public final class RecordingWriter implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OutputStream out;
    private final GZIPOutputStream gzip;
    private final long t0Nanos;
    private final Map<Endpoint, Integer> ids = new HashMap<>();
    private long lastFlush;
    private long records;
    private boolean closed;

    /**
     * A new file, with the meta header the Python host writes ({@code host_start_unix}, {@code host_start},
     * {@code marvin_host}, {@code protocol}) plus {@code extra}.
     *
     * @param version this host's version, written as {@code marvin_host}
     */
    public static RecordingWriter create(Path path, String version, Map<String, ?> extra) {
        Instant now = Instant.now();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("host_start_unix", now.toEpochMilli() / 1000.0);
        meta.put("host_start", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx").format(
                now.truncatedTo(ChronoUnit.SECONDS).atZone(ZoneId.systemDefault())));
        meta.put("marvin_host", version);
        meta.put("protocol", ProtocolV1.VERSION);
        meta.put("host", "java");
        meta.putAll(extra);
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return new RecordingWriter(Files.newOutputStream(path), path.getFileName().toString().endsWith(".gz"),
                    JSON.writeValueAsBytes(meta), System.nanoTime());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A recording on any stream.
     *
     * @param meta    the header's JSON, as bytes
     * @param t0Nanos the {@link System#nanoTime()} the record times count from
     */
    public RecordingWriter(OutputStream raw, boolean compress, byte[] meta, long t0Nanos) throws IOException {
        if (meta.length > Mvrec.MAX_META) {
            throw new IllegalArgumentException("meta header too large");
        }
        this.gzip = compress ? new GZIPOutputStream(raw, 1 << 16, true) : null;
        this.out = new BufferedOutputStream(compress ? gzip : raw, 1 << 16);
        this.t0Nanos = t0Nanos;
        this.lastFlush = t0Nanos;
        ByteBuffer h = ByteBuffer.allocate(Mvrec.HEAD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        h.put(Mvrec.MAGIC).put((byte) Mvrec.VERSION).putInt(meta.length);
        out.write(h.array());
        out.write(meta);
    }

    /** Microseconds since the recording started, for a {@link System#nanoTime()} reading. */
    public long tUs(long nanoTime) {
        return Math.max(0, (nanoTime - t0Nanos) / 1000);
    }

    /** One received datagram, arrived at {@code nanoTime}. Datagrams over 65 533 bytes are skipped. */
    public synchronized void writeDatagram(byte[] data, int length, Endpoint from, long nanoTime) {
        writeDatagramAt(data, length, from, tUs(nanoTime));
        maybeFlush(nanoTime);
    }

    /** One datagram with an explicit record time (tests, conversions). */
    public synchronized void writeDatagramAt(byte[] data, int length, Endpoint from, long tUs) {
        if (closed || length > 0xFFFF - 2) {
            return;
        }
        Integer id = ids.get(from);
        if (id == null) {
            if (ids.size() > 0xFFFF) {
                return;
            }
            id = ids.size();
            ids.put(from, id);
            byte[] addr = from.toString().getBytes(StandardCharsets.UTF_8);
            record(Mvrec.ADDRESS, tUs, id, addr, addr.length);
        }
        record(Mvrec.DATAGRAM, tUs, id, data, length);
    }

    /** The {@code DEVICE} record written when a device's first {@code HELLO} is handled. */
    public synchronized void writeDevice(Device dev, long nanoTime) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("address", dev.endpoint().toString());
        info.put("device_name", dev.name());
        info.put("board", dev.hello().board());
        info.put("firmware", dev.hello().firmware());
        info.put("simulated", dev.hello().simulated());
        writeDeviceAt(JSON.writeValueAsBytes(info), tUs(nanoTime));
    }

    public synchronized void writeDeviceAt(byte[] json, long tUs) {
        if (!closed) {
            record(Mvrec.DEVICE, tUs, -1, json, Math.min(json.length, 0xFFFF));
        }
    }

    private void record(int kind, long tUs, int id, byte[] body, int length) {
        int n = length + (id >= 0 ? 2 : 0);
        ByteBuffer h = ByteBuffer.allocate(Mvrec.RECORD_HEAD_SIZE + (id >= 0 ? 2 : 0)).order(ByteOrder.LITTLE_ENDIAN);
        h.put((byte) kind).putLong(tUs).putShort((short) n);
        if (id >= 0) {
            h.putShort((short) id);
        }
        try {
            out.write(h.array());
            out.write(body, 0, length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        records++;
    }

    private void maybeFlush(long nanoTime) {
        if (nanoTime - lastFlush >= Mvrec.FLUSH_NANOS) {
            flush();
            lastFlush = nanoTime;
        }
    }

    /** Everything written so far can be read back (a gzip sync flush). */
    public synchronized void flush() {
        if (closed) {
            return;
        }
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized long records() {
        return records;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
