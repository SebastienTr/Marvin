// SPDX-License-Identifier: MIT
package marvin.host.adapter.sidecar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.domain.robot.HostMessages;

/**
 * The gRPC client against the real Python voice sidecar in its test mode ({@code --fake}: a scripted
 * microphone, a Whisper that recognises the script by pitch, a fake voice): the process, the session, a
 * heard question answered with streamed text, the options, and the robot route with relayed audio.
 */
class GrpcVoiceSidecarIT {
    static final double RATE = 16000;
    GrpcVoiceSidecar voice;
    final List<VoiceSidecar.Signal> signals = new CopyOnWriteArrayList<>();
    final List<String> robotOut = new CopyOnWriteArrayList<>();

    static PythonRuntime python() {
        Path repo = Path.of("").toAbsolutePath();
        while (repo != null && !Files.isDirectory(repo.resolve("host/marvin_host"))) {
            repo = repo.getParent();
        }
        assumeTrue(repo != null, "the Python host is not here");
        PythonRuntime py = PythonRuntime.find(new SidecarProperties(null, repo, false, false, true, List.of())).orElse(null);
        assumeTrue(py != null, "no Python can run the Python host");
        try {
            Process p = py.command(List.of("-c", "import grpc, grpc_health")).start();
            assumeTrue(p.waitFor() == 0, "the sidecar extra (gRPC) is not installed");
        } catch (Exception e) {
            assumeTrue(false, e.getMessage());
        }
        return py;
    }

    GrpcVoiceSidecar start(List<String> args) throws InterruptedException {
        voice = new GrpcVoiceSidecar(python(), args);
        voice.setSpeaker(new marvin.host.application.conversation.port.out.VoiceRobotAudio.RobotSpeaker() {
            @Override
            public void speaker(String device, int stream, long sampleIndex, short[] pcm) {
                robotOut.add("speaker " + device + " " + pcm.length);
            }

            @Override
            public void control(String device, int command, int argument) {
                robotOut.add("ctrl " + device + " " + command);
            }

            @Override
            public void sound(String device, int id) {
                robotOut.add("sound " + device + " " + id);
            }
        });
        voice.start();
        assertThat(voice.awaitServing(30_000)).as("the sidecar serves").isTrue();
        return voice;
    }

    @AfterEach
    void stop() {
        if (voice != null) {
            voice.shutdown();
        }
    }

    static VoiceSidecar.Settings settings(boolean robot) {
        return new VoiceSidecar.Settings("auto", "", "auto", "", "", "fr", true, false, 0, 5, 0, true, 0, true, "", "",
                robot);
    }

    <T extends VoiceSidecar.Signal> T await(Class<T> type, Predicate<T> p, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            for (VoiceSidecar.Signal s : signals) {
                if (type.isInstance(s) && p.test(type.cast(s))) {
                    return type.cast(s);
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + signals);
    }

    static void waitFor(BooleanSupplier cond, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertThat(cond.getAsBoolean()).isTrue();
    }

    @Test
    void aHeardQuestionIsAnsweredWithStreamedText() throws InterruptedException {
        start(List.of("--fake", "--say", "1:Marvin, quelle heure est-il ?", "--fake-speed", "1"));
        voice.open(settings(false), signals::add);
        await(VoiceSidecar.Status.class, s -> s.state().equals("idle"), 20_000);
        VoiceSidecar.Heard h = await(VoiceSidecar.Heard.class, x -> true, 20_000);
        assertThat(h.text()).isEqualTo("quelle heure est-il ?");
        assertThat(h.raw()).isEqualTo("Marvin, quelle heure est-il ?");
        assertThat(h.language()).isEqualTo("fr");
        assertThat(h.latency()).containsKey("endpoint");
        voice.send(new VoiceSidecar.ReplyStart(41, "fr", false, h.uid()));
        voice.send(new VoiceSidecar.Text(41, "Il est neuf heures.\n"));
        voice.send(new VoiceSidecar.Text(41, "Bonne journée.\n"));
        voice.send(new VoiceSidecar.ReplyEnd(41, ""));
        VoiceSidecar.ReplySpoken r = await(VoiceSidecar.ReplySpoken.class, x -> x.replyId() == 41, 20_000);
        assertThat(r.text()).isEqualTo("Il est neuf heures. Bonne journée.");
        assertThat(r.utteranceUid()).isEqualTo(h.uid());
        assertThat(r.latency()).containsKeys("audio_start", "total");
        assertThat(signals).anyMatch(s -> s instanceof VoiceSidecar.SayProgress p && p.text().equals("Il est neuf heures.")
                && !p.envelope().isEmpty());
        assertThat(signals).anyMatch(s -> s instanceof VoiceSidecar.Level);
        assertThat(signals).anyMatch(s -> s instanceof VoiceSidecar.Utterance u && u.state().equals("start"));
        assertThat(signals).anyMatch(s -> s instanceof VoiceSidecar.Status st && st.state().equals("speaking"));

        VoiceSidecar.Options o = voice.options().orElseThrow();
        assertThat(o.tts()).extracting(VoiceSidecar.Backend::name).contains("auto", "say", "piper", "espeak");
        assertThat(o.sttModels()).contains("auto", "turbo");
    }

    @Test
    void theRobotRouteHearsTheRelayedMicrophoneAndSpeaksThroughTheRobot() throws InterruptedException {
        start(List.of("--fake", "--say", "300:Marvin, quelle heure est-il ?"));
        voice.open(settings(true), signals::add);
        VoiceSidecar.Status err = await(VoiceSidecar.Status.class, s -> s.state().equals("error"), 20_000);
        assertThat(err.error()).isEqualTo("No robot with a microphone and a speaker is connected");
        voice.robotLink("marvin-a1b2c3", true, true);
        await(VoiceSidecar.Status.class, s -> s.state().equals("idle"), 20_000);
        waitFor(() -> robotOut.contains("ctrl marvin-a1b2c3 " + HostMessages.AUDIO_MIC_START), 5000);
        // the robot's microphone: quiet, phrase 0 (a tone at 110 Hz with its harmonics), quiet again
        long index = 0;
        for (int i = 0; i < 175; i++) {
            short[] pcm = new short[320];
            boolean speech = i >= 40 && i < 120;
            for (int j = 0; j < pcm.length; j++) {
                double t = (index + j) / RATE;
                double x = 0;
                if (speech) {
                    for (int h = 1; h < 8; h++) {
                        x += Math.sin(2 * Math.PI * 110 * h * t) / h;
                    }
                    x *= (0.6 + 0.4 * Math.abs(Math.sin(2 * Math.PI * 3 * t))) * 5000;
                } else {
                    x = Math.sin(j * 1.7) * 30;
                }
                pcm[j] = (short) x;
            }
            voice.robotMic("marvin-a1b2c3", index, index * 62, pcm);
            index += pcm.length;
            Thread.sleep(20);
        }
        VoiceSidecar.Heard h = await(VoiceSidecar.Heard.class, x -> true, 20_000);
        assertThat(h.text()).isEqualTo("quelle heure est-il ?");
        voice.send(new VoiceSidecar.ReplyStart(7, "fr", false, h.uid()));
        voice.send(new VoiceSidecar.Text(7, "Neuf heures.\n"));
        voice.send(new VoiceSidecar.ReplyEnd(7, ""));
        await(VoiceSidecar.ReplySpoken.class, x -> x.replyId() == 7, 20_000);
        assertThat(robotOut).anyMatch(s -> s.startsWith("speaker marvin-a1b2c3 "));
        int before = signals.size();
        voice.robotLink("marvin-a1b2c3", false, false);
        waitFor(() -> signals.subList(before, signals.size()).stream()
                .anyMatch(s -> s instanceof VoiceSidecar.Status st && st.state().equals("error")), 20_000);
        assertThat(voice.robotWithAudio()).isFalse();
        assertThat(robotOut).contains("ctrl marvin-a1b2c3 " + HostMessages.AUDIO_MIC_STOP);
    }

    @Test
    void theSessionComesBackWhenTheSidecarRestarts() throws InterruptedException {
        start(List.of("--fake"));
        voice.open(settings(false), signals::add);
        await(VoiceSidecar.Status.class, s -> s.state().equals("idle"), 20_000);
        int before = signals.size();
        ProcessHandle.of(voice.process().pid()).orElseThrow().destroyForcibly();
        waitFor(() -> signals.subList(before, signals.size()).stream()
                .anyMatch(s -> s instanceof VoiceSidecar.Status st && st.state().equals("starting")), 10_000);
        int restarted = signals.size();
        waitFor(() -> signals.subList(restarted, signals.size()).stream()
                .anyMatch(s -> s instanceof VoiceSidecar.Status st && st.state().equals("idle")), 30_000);
        assertThat(voice.process().restarts()).isGreaterThanOrEqualTo(1);
    }
}
