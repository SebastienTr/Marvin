// SPDX-License-Identifier: MIT
package marvin.host.adapter.robot;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

import marvin.host.domain.robot.Hello;
import marvin.host.domain.robot.MessageType;
import marvin.host.domain.robot.Wire;

/** A minimal robot on localhost for tests: sends datagrams to the host, reads what comes back. */
final class FakeRobot implements AutoCloseable {
    final DatagramSocket socket;
    private final InetSocketAddress host;
    private long seq;

    FakeRobot(int hostPort) throws IOException {
        socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        socket.setSoTimeout(2000);
        host = new InetSocketAddress(InetAddress.getLoopbackAddress(), hostPort);
    }

    static Hello hello(int board, int flags, int lastByte) {
        return new Hello(new byte[] {0x24, 0x0a, (byte) 0xc4, 0, 0, (byte) lastByte}, board, flags, -58, 1234, "0.6.0");
    }

    void send(MessageType type, long tUs, byte[] payload) throws IOException {
        byte[] d = Wire.pack(type, seq++, tUs, payload);
        socket.send(new DatagramPacket(d, d.length, host));
    }

    /** The next datagram, or null after the timeout. */
    byte[] receive(int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        try {
            socket.receive(p);
        } catch (SocketTimeoutException e) {
            return null;
        }
        return Arrays.copyOf(buf, p.getLength());
    }

    int port() {
        return socket.getLocalPort();
    }

    @Override
    public void close() {
        socket.close();
    }
}
