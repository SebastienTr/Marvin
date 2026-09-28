// SPDX-License-Identifier: MIT
package marvin.host.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import marvin.host.adapter.robot.UdpRobotLink;
import marvin.host.application.presence.PresenceService;
import marvin.host.domain.robot.HostMessages;
import marvin.host.domain.robot.Hello;
import marvin.host.domain.robot.Ld2450;
import marvin.host.domain.robot.MessageType;
import marvin.host.domain.robot.Wire;

/**
 * The whole host starts on the same PostgreSQL image as ./marvin up, answers its health check, and
 * links with a robot that says HELLO.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "marvin.mode=demo", "marvin.robot.bind=127.0.0.1",
                "marvin.robot.port=0", "marvin.robot.calibration-file=no-such-calibration.json"})
@Testcontainers(disabledWithoutDocker = true)
class MarvinHostApplicationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));

    @LocalServerPort
    int port;

    @Autowired
    UdpRobotLink link;

    @Autowired
    PresenceService presence;

    @Test
    void startsAndAnswersHealth() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"status\":\"ok\"").contains("\"mode\":\"demo\"").contains("pgvector");
    }

    @Test
    void linksWithARobotAndFeedsTheBrain() throws Exception {
        assertThat(link.listening()).isTrue();
        try (DatagramSocket robot = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            robot.setSoTimeout(3000);
            InetAddress host = InetAddress.getLoopbackAddress();
            Hello hello = new Hello(new byte[] {0x24, 0x0a, (byte) 0xc4, 0x0a, 0x0b, 0x0c}, 1, 1, -60, 5000, "0.6.0");
            byte[] h = Wire.pack(MessageType.HELLO, 0, 1_000_000, hello.encode());
            robot.send(new DatagramPacket(h, h.length, host, link.boundPort()));
            byte[] buf = new byte[64];
            DatagramPacket ack = new DatagramPacket(buf, buf.length);
            robot.receive(ack);
            assertThat(Wire.header(buf, ack.getLength()).type()).isEqualTo(MessageType.HOST_ACK.code());
            assertThat(HostMessages.hostAckClock(Wire.payload(buf, ack.getLength()))).isPositive();
            for (int i = 1; i <= 10; i++) {
                byte[] t = Wire.pack(MessageType.LD2450, i, 1_000_000 + i * 100_000L,
                        Ld2450.build(List.of(new Ld2450.Target(200, 1500, 0, 320))));
                robot.send(new DatagramPacket(t, t.length, host, link.boundPort()));
            }
        }
        long end = System.nanoTime() + 5_000_000_000L;
        String body = "";
        while (System.nanoTime() < end) {
            body = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            if (body.contains("marvin-0a0b0c") && presence.state().present()) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(body).contains("\"robot\"").contains("marvin-0a0b0c (robot, simulated)");
        assertThat(presence.state().present()).as("arrived after 0.5 s of sightings").isTrue();
        assertThat(presence.recentEvents()).extracting(e -> e.kind().wireName()).containsExactly("arrived");
    }
}
