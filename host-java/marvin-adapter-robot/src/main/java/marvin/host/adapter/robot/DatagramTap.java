// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import marvin.host.domain.robot.Endpoint;

/**
 * The debug tap (design 4.4): forwards every datagram received from a robot, unchanged, to a UDP
 * port on this machine, where the Python receiver and its Rerun viewer read them
 * ({@code marvin-host run --port 47110 --no-ui}). Each robot's datagrams leave from a socket of
 * their own, so the viewer still tells the devices apart (the robot and the MR60BHA2 bridge). What
 * the viewer sends back (its {@code HOST_ACK}s) is ignored.
 */
public final class DatagramTap implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DatagramTap.class);
    private static final int MAX_SOURCES = 64;

    private final InetSocketAddress target;
    private final Map<Endpoint, DatagramSocket> sockets = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private long forwarded;

    public DatagramTap(InetSocketAddress target) {
        this.target = target;
    }

    public InetSocketAddress target() {
        return target;
    }

    /** Forwards one datagram from {@code from}. Never throws. */
    public void forward(byte[] data, int length, Endpoint from) {
        if (closed) {
            return;
        }
        DatagramSocket s = sockets.get(from);
        if (s == null) {
            if (sockets.size() >= MAX_SOURCES) {
                return;
            }
            try {
                s = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            } catch (SocketException e) {
                log.debug("tap socket for {} not opened: {}", from, e.getMessage());
                return;
            }
            DatagramSocket prev = sockets.putIfAbsent(from, s);
            if (prev != null) {
                s.close();
                s = prev;
            }
        }
        try {
            s.send(new DatagramPacket(data, length, target));
            forwarded++;
        } catch (IOException e) {
            log.debug("tap to {} failed: {}", target, e.getMessage());
        }
    }

    public long forwarded() {
        return forwarded;
    }

    @Override
    public void close() {
        closed = true;
        sockets.values().forEach(DatagramSocket::close);
        sockets.clear();
    }
}
