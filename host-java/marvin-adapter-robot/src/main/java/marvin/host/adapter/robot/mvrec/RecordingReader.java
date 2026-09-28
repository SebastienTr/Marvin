// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot.mvrec;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import marvin.host.domain.robot.Endpoint;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a recording's datagrams, as {@code record.RecordingReader} does. {@link #meta()} is the header,
 * {@link #devices()} the {@code DEVICE} records seen so far. A file cut short ends at its last complete
 * record and sets {@link #truncated()}; a corrupt record is a {@link RecordingException}.
 */
public final class RecordingReader implements Iterable<RecordingReader.Datagram>, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RecordingReader.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One recorded datagram.
     *
     * @param tUs host time of arrival, microseconds since the recording started
     */
    public record Datagram(long tUs, Endpoint from, byte[] data) {
        public double seconds() {
            return tUs / 1e6;
        }
    }

    private final String name;
    private final InputStream in;
    private final Map<String, Object> meta;
    private final byte[] metaBytes;
    private final List<Map<String, Object>> devices = new ArrayList<>();
    private boolean truncated;
    private long records;
    private boolean iterated;

    public RecordingReader(Path path) {
        this(open(path), path.toString());
    }

    /** From a stream (plain or gzip, detected). */
    public RecordingReader(InputStream raw, String name) {
        this.name = name;
        try {
            BufferedInputStream b = new BufferedInputStream(raw, 1 << 16);
            b.mark(2);
            int m0 = b.read();
            int m1 = b.read();
            b.reset();
            this.in = m0 == 0x1f && m1 == 0x8b ? new BufferedInputStream(new GZIPInputStream(b, 1 << 16), 1 << 16) : b;
            byte[] head = read(Mvrec.HEAD_SIZE);
            if (head.length < Mvrec.HEAD_SIZE || !Arrays.equals(Arrays.copyOf(head, 5), Mvrec.MAGIC)) {
                throw new RecordingException(name + ": not a Marvin recording");
            }
            ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            int version = head[5] & 0xff;
            if (version != Mvrec.VERSION) {
                throw new RecordingException(name + ": unsupported recording version " + version);
            }
            long n = Integer.toUnsignedLong(h.getInt(6));
            if (n > Mvrec.MAX_META) {
                throw new RecordingException(name + ": corrupt header");
            }
            this.metaBytes = read((int) n);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = JSON.readValue(metaBytes, Map.class);
                this.meta = m;
            } catch (JacksonException e) {
                throw new RecordingException(name + ": corrupt header", e);
            }
        } catch (IOException e) {
            closeQuietly(raw);
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            closeQuietly(raw);
            throw e;
        }
    }

    private static InputStream open(Path path) {
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Up to n bytes; fewer only at the end of the data (a gzip stream cut short counts as the end). */
    private byte[] read(int n) throws IOException {
        try {
            return in.readNBytes(n);
        } catch (EOFException | ZipException e) {
            return new byte[0];
        }
    }

    public Map<String, Object> meta() {
        return meta;
    }

    /** The header's JSON exactly as stored. */
    public byte[] metaBytes() {
        return metaBytes.clone();
    }

    public List<Map<String, Object>> devices() {
        return List.copyOf(devices);
    }

    public boolean truncated() {
        return truncated;
    }

    public long records() {
        return records;
    }

    /** The datagrams, once: a reader is a single pass over the file. */
    @Override
    public Iterator<Datagram> iterator() {
        if (iterated) {
            throw new IllegalStateException("a recording is read once");
        }
        iterated = true;
        return new Iterator<>() {
            private final Map<Integer, Endpoint> senders = new HashMap<>();
            private Datagram next;
            private boolean done;

            @Override
            public boolean hasNext() {
                if (next == null && !done) {
                    next = advance();
                    done = next == null;
                }
                return next != null;
            }

            @Override
            public Datagram next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Datagram d = next;
                next = null;
                return d;
            }

            private Datagram advance() {
                try {
                    while (true) {
                        byte[] head = read(Mvrec.RECORD_HEAD_SIZE);
                        if (head.length == 0) {
                            return null;
                        }
                        if (head.length < Mvrec.RECORD_HEAD_SIZE) {
                            return cutShort();
                        }
                        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
                        int kind = head[0] & 0xff;
                        long tUs = h.getLong(1);
                        int n = h.getShort(9) & 0xffff;
                        byte[] body = read(n);
                        if (body.length < n) {
                            return cutShort();
                        }
                        records++;
                        if (kind == Mvrec.DATAGRAM) {
                            if (n < 2) {
                                throw new RecordingException(name + ": corrupt record " + records);
                            }
                            int id = (body[0] & 0xff) | (body[1] & 0xff) << 8;
                            Endpoint from = senders.get(id);
                            if (from == null) {
                                throw new RecordingException(name + ": record " + records
                                        + " comes from an unknown sender");
                            }
                            return new Datagram(tUs, from, Arrays.copyOfRange(body, 2, n));
                        } else if (kind == Mvrec.ADDRESS) {
                            try {
                                int id = (body[0] & 0xff) | (body[1] & 0xff) << 8;
                                senders.put(id, Endpoint.parse(new String(body, 2, n - 2, StandardCharsets.UTF_8)));
                            } catch (RuntimeException e) {
                                throw new RecordingException(name + ": corrupt record " + records, e);
                            }
                        } else if (kind == Mvrec.DEVICE) {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> d = JSON.readValue(body, Map.class);
                                devices.add(d);
                            } catch (JacksonException e) {
                                log.warn("{}: unreadable device record {}, skipped", name, records);
                            }
                        }
                        // other kinds: from a later version, skipped
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            private Datagram cutShort() {
                truncated = true;
                log.warn("{}: truncated after {} records", name, records);
                return null;
            }
        };
    }

    @Override
    public void close() {
        closeQuietly(in);
    }

    private static void closeQuietly(InputStream s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
