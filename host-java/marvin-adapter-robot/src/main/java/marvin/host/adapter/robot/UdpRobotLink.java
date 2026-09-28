// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import marvin.host.adapter.robot.mvrec.RecordingWriter;
import marvin.host.application.robot.port.out.RobotOutbound;
import marvin.host.domain.robot.Board;
import marvin.host.domain.robot.Device;
import marvin.host.domain.robot.Endpoint;
import marvin.host.domain.robot.HostMessages;
import marvin.host.domain.robot.MessageType;
import marvin.host.domain.robot.RobotLinkProcessor;
import marvin.host.domain.robot.SensorFrame;
import marvin.host.domain.robot.Wire;

/**
 * The robot's UDP link, protocol v1 (docs/protocol.md), the Python {@code Receiver}'s behaviour.
 *
 * <p>The socket thread reads datagrams, records them (if asked), checks them through the
 * {@link RobotLinkProcessor}, answers each {@code HELLO} with a {@code HOST_ACK} right away, forwards
 * them to the tap (if any), and queues the frames for the {@link FrameDispatcher}. It never waits for
 * the application, so the robot always gets its {@code HOST_ACK} in time. Microphone frames also go
 * straight to the audio listeners, from the socket thread.
 *
 * <p>{@link #receive} can be called without a socket (replays, tests): the frames are then handled on
 * the caller's thread unless the dispatcher was started.
 */
public final class UdpRobotLink implements RobotOutbound, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(UdpRobotLink.class);
    /** Asked for; the OS may grant less (Linux: net.core.rmem_max, macOS: kern.ipc.maxsockbuf). */
    static final int RCVBUF_BYTES = 4 << 20;

    private final RobotLinkProcessor processor;
    private final FrameDispatcher dispatcher;
    private final long t0 = System.nanoTime();
    private final Map<Endpoint, Long> txSeq = new ConcurrentHashMap<>();
    private final List<Consumer<SensorFrame.AudioChunk>> audioListeners = new CopyOnWriteArrayList<>();
    private volatile DatagramSocket socket;
    private volatile RecordingWriter recorder;
    private volatile DatagramTap tap;
    private Thread thread;
    private volatile boolean running;
    private long acks;

    public UdpRobotLink(RobotLinkProcessor processor, FrameDispatcher dispatcher) {
        this.processor = processor;
        this.dispatcher = dispatcher;
    }

    /** Also write every received datagram to this recording (closed by {@link #close()}). */
    public void record(RecordingWriter writer) {
        this.recorder = writer;
    }

    /** Also forward every received datagram to this tap (closed by {@link #close()}). */
    public void tap(DatagramTap t) {
        this.tap = t;
    }

    /** Also call {@code fn} for every {@code AUDIO_IN}, from the socket thread: keep it short. */
    public void addAudioListener(Consumer<SensorFrame.AudioChunk> fn) {
        audioListeners.add(fn);
    }

    public void removeAudioListener(Consumer<SensorFrame.AudioChunk> fn) {
        audioListeners.remove(fn);
    }

    public RobotLinkProcessor processor() {
        return processor;
    }

    public FrameDispatcher dispatcher() {
        return dispatcher;
    }

    /** Opens the socket on {@code bind:port} (port 0: any) and starts the socket and dispatch threads. */
    public synchronized void start(String bind, int port) {
        if (running) {
            throw new IllegalStateException("already started");
        }
        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(false);        // a second host on the same port must fail, not share it
            try {
                s.setReceiveBufferSize(RCVBUF_BYTES);
            } catch (SocketException e) {
                s.setReceiveBufferSize(1 << 20);
            }
            s.bind(new InetSocketAddress(InetAddress.getByName(bind), port));
            s.setSoTimeout(200);
            s.setBroadcast(true);
            socket = s;
            log.debug("UDP receive buffer: {} bytes", s.getReceiveBufferSize());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot listen on UDP " + bind + ":" + port
                    + " (is another Marvin host running?)", e);
        }
        if (!dispatcher.running()) {
            dispatcher.start();
        }
        running = true;
        thread = Thread.ofPlatform().name("robot-udp").daemon().priority(Thread.MAX_PRIORITY).start(this::serve);
        log.info("listening for robots on UDP {}:{}", bind, boundPort());
    }

    /** The UDP port the socket is bound to, or -1 without a socket. */
    public int boundPort() {
        DatagramSocket s = socket;
        return s == null ? -1 : s.getLocalPort();
    }

    public boolean listening() {
        return running;
    }

    private void serve() {
        byte[] buf = new byte[4096];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                p.setLength(buf.length);
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (!running) {
                    break;
                }
                log.warn("UDP receive failed: {}", e.getMessage());
                continue;
            }
            Endpoint from = new Endpoint(p.getAddress().getHostAddress(), p.getPort());
            try {
                receive(p.getData(), p.getLength(), from, System.nanoTime());
            } catch (RuntimeException e) {
                log.warn("datagram from {} not handled", from, e);
            }
        }
    }

    /** One datagram, as if it had just arrived from {@code from}. */
    public void receive(byte[] data, int length, Endpoint from, long nanoTime) {
        RecordingWriter rec = recorder;
        if (rec != null) {
            rec.writeDatagram(data, length, from, nanoTime);
        }
        RobotLinkProcessor.Reception r = processor.handle(data, length, from);
        if (r.acknowledge()) {
            sendAck(from);
        }
        DatagramTap t = tap;
        if (t != null && r.error() == null) {
            t.forward(data, length, from);
        }
        if (r.error() != null) {
            log.debug("dropped datagram from {}: {}", from, r.error());
        }
        for (SensorFrame f : r.frames()) {
            if (f instanceof SensorFrame.Connected c) {
                announce(c.device());
                if (rec != null) {
                    rec.writeDevice(c.device(), nanoTime);
                }
            } else if (f instanceof SensorFrame.AudioChunk a) {
                for (Consumer<SensorFrame.AudioChunk> fn : audioListeners) {
                    fn.accept(a);           // the microphone must not wait for the dispatcher
                }
            }
            dispatcher.submit(f);
        }
    }

    private void announce(Device d) {
        var h = d.hello();
        String board = Board.name(h.board());
        log.info("{} ({}, {}{}) at {}", h.deviceName(), board != null ? board : h.board(), h.firmware(),
                h.simulated() ? ", simulated" : "", d.endpoint());
    }

    /** Microseconds since this link was created: the host clock of {@code HOST_ACK} and other messages. */
    public long hostClockUs() {
        return (System.nanoTime() - t0) / 1000;
    }

    private void sendAck(Endpoint to) {
        DatagramSocket s = socket;
        if (s == null) {
            return;                     // replay: nothing goes back to a robot
        }
        byte[] ack = HostMessages.hostAck(hostClockUs());
        try {
            s.send(new DatagramPacket(ack, ack.length, InetAddress.getByName(to.host()), to.port()));
            acks++;
        } catch (IOException e) {
            log.debug("HOST_ACK to {} failed: {}", to, e.getMessage());
        }
    }

    public long acksSent() {
        return acks;
    }

    /**
     * Sends a host → robot message from the listening socket to where the device's {@code HELLO}
     * came from. Each destination has its own sequence number; the header clock is the host clock.
     * Thread-safe; drops the message without a socket or on a network error.
     */
    @Override
    public void send(Device device, MessageType type, byte[] payload) {
        send(device.endpoint(), type, payload);
    }

    public void send(Endpoint to, MessageType type, byte[] payload) {
        long seq = txSeq.merge(to, 1L, (a, b) -> (a + b) & 0xFFFFFFFFL) - 1;
        DatagramSocket s = socket;
        if (s == null) {
            return;
        }
        byte[] d = Wire.pack(type, seq & 0xFFFFFFFFL, hostClockUs(), payload);
        try {
            s.send(new DatagramPacket(d, d.length, InetAddress.getByName(to.host()), to.port()));
        } catch (IOException e) {
            log.debug("send to {} failed: {}", to, e.getMessage());
        }
    }

    /** Stops the threads (the dispatcher drains for up to 2 s), closes the socket, the recording and the tap. */
    @Override
    public synchronized void close() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) {
            s.close();
        }
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        dispatcher.stop(2000);
        RecordingWriter rec = recorder;
        if (rec != null) {
            rec.close();
        }
        DatagramTap t = tap;
        if (t != null) {
            t.close();
        }
        socket = null;
    }
}
