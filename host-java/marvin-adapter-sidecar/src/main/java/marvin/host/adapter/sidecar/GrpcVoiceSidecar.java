// SPDX-License-Identifier: MIT
package marvin.host.adapter.sidecar;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import marvin.host.application.conversation.port.out.VoiceRobotAudio;
import marvin.host.application.conversation.port.out.VoiceSidecar;
import marvin.host.contracts.voice.v1.AudioFrame;
import marvin.host.contracts.voice.v1.AudioRoute;
import marvin.host.contracts.voice.v1.Configure;
import marvin.host.contracts.voice.v1.CoreToVoice;
import marvin.host.contracts.voice.v1.OptionsRequest;
import marvin.host.contracts.voice.v1.RobotLink;
import marvin.host.contracts.voice.v1.VoiceGrpc;
import marvin.host.contracts.voice.v1.VoiceOptions;
import marvin.host.contracts.voice.v1.VoiceSettings;
import marvin.host.contracts.voice.v1.VoiceToCore;

/**
 * The voice sidecar ({@code python -m marvin_host.sidecar.voice}), supervised and spoken to over gRPC
 * ({@code marvin.voice.v1}, docs/design.md 4.2 and 4.3).
 *
 * <p>The process starts with the host (so the settings panel can list voices, and turning the voice on is
 * quick) and is restarted with a backoff when it stops. It prints {@code READY port=N}; the health check then
 * says when it serves. A random token, passed in its environment, keeps other local programs out.
 *
 * <p>While the conversation service wants a session, one is kept open: re-opened (with the last settings and
 * the robots with audio) when the process restarts, with a {@code starting} status in between. The robot's
 * audio passes through here: {@link #robotMic} relays {@code AUDIO_IN}, and the {@link RobotSpeaker} gets
 * the speaker frames, audio controls and sounds for the robot.
 */
public final class GrpcVoiceSidecar implements VoiceSidecar, VoiceRobotAudio, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger("marvin.sidecar.voice");
    private static final Pattern READY = Pattern.compile("^READY port=(\\d+)$");
    private static final Pattern NEEDS = Pattern.compile("^the voice sidecar needs (.*?): (.*)$");
    static final long HEALTH_TIMEOUT_MS = 20_000;

    private final PythonRuntime python;
    private final List<String> extraArgs;
    private final String token = HexFormat.of().formatHex(new SecureRandom().generateSeed(16));
    private final SupervisedProcess process;
    private final Object lock = new Object();
    private final Map<String, Boolean> robots = new ConcurrentHashMap<>();
    private volatile RobotSpeaker robotAudio;
    private volatile ManagedChannel channel;
    private volatile int port;
    private volatile boolean serving;
    private volatile String unavailable = "";
    private volatile String unavailableFix = "";

    private volatile Settings wanted;
    private volatile Signals signals;
    private StreamObserver<CoreToVoice> session;
    private long sessionSeq;

    /**
     * @param python    the Python that runs the sidecar, {@code null} when none can (the voice then reports why)
     * @param extraArgs more arguments for the sidecar (its test mode: {@code --fake --say ...})
     */
    public GrpcVoiceSidecar(PythonRuntime python, List<String> extraArgs) {
        this.python = python;
        this.extraArgs = List.copyOf(extraArgs);
        Supplier<ProcessBuilder> cmd = () -> {
            List<String> args = new ArrayList<>(List.of("-m", "marvin_host.sidecar.voice", "--port", "0"));
            args.addAll(this.extraArgs);
            ProcessBuilder pb = python.command(args);
            pb.environment().put("MARVIN_SIDECAR_TOKEN", token);
            return pb;
        };
        this.process = python == null ? null : new SupervisedProcess("voice", cmd, this::stdout);
        if (process != null) {
            process.onExit(code -> down(code));
            process.reportedElsewhere(text -> text.startsWith("voice: "));      // its state: the app shows it
        } else {
            unavailable = "The voice needs the Python host (host/marvin_host) and a Python to run it";
            unavailableFix = "./marvin up --voice (or set MARVIN_PYTHON to a Python with host[sidecar,voice] installed)";
        }
    }

    @Override
    public void setSpeaker(RobotSpeaker sink) {
        this.robotAudio = sink;
    }

    /** Starts the sidecar process (the session opens when the voice is turned on). */
    public void start() {
        if (process != null) {
            process.start();
        }
    }

    public SupervisedProcess process() {
        return process;
    }

    /** Whether the sidecar serves; else why not ({@link #unavailableReason()}). */
    public boolean serving() {
        return serving;
    }

    public String unavailableReason() {
        return unavailable;
    }

    public String unavailableFix() {
        return unavailableFix;
    }

    // ------------------------------------------------------------------ the process

    private void stdout(String line) {
        Matcher m = READY.matcher(line.strip());
        if (m.matches()) {
            int p = Integer.parseInt(m.group(1));
            Thread.ofVirtual().name("voice-sidecar-connect").start(() -> connect(p));
            return;
        }
        Matcher n = NEEDS.matcher(line.strip());
        if (n.matches()) {
            unavailable = "The voice sidecar needs " + n.group(1);
            unavailableFix = "In the host folder: " + n.group(2).replaceFirst("^in the host folder, ", "")
                    + ", or ./marvin up --voice";
            log.warn("{}: {}", unavailable, unavailableFix);
        }
    }

    private void connect(int p) {
        ManagedChannel ch = NettyChannelBuilder.forAddress("127.0.0.1", p).usePlaintext()
                .intercept(new TokenInterceptor(token)).build();
        long until = System.currentTimeMillis() + HEALTH_TIMEOUT_MS;
        while (System.currentTimeMillis() < until) {
            try {
                HealthCheckResponse r = HealthGrpc.newBlockingStub(ch).withDeadlineAfter(2, TimeUnit.SECONDS)
                        .check(HealthCheckRequest.newBuilder().setService("").build());
                if (r.getStatus() == HealthCheckResponse.ServingStatus.SERVING) {
                    ManagedChannel old;
                    synchronized (lock) {
                        old = channel;
                        channel = ch;
                        port = p;
                        serving = true;
                        unavailable = "";
                        unavailableFix = "";
                    }
                    if (old != null) {
                        old.shutdownNow();
                    }
                    process.markRunning();
                    log.info("voice sidecar ready on port {}", p);
                    reopen();
                    return;
                }
            } catch (StatusRuntimeException e) {
                // not yet
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("the voice sidecar did not pass its health check on port {}", p);
        ch.shutdownNow();
    }

    private void down(int code) {
        ManagedChannel old;
        synchronized (lock) {
            serving = false;
            old = channel;
            channel = null;
            session = null;
            sessionSeq++;
        }
        if (old != null) {
            old.shutdownNow();
        }
        if (code == 2 && unavailable.isEmpty()) {
            unavailable = "The voice sidecar cannot start";
            unavailableFix = "./marvin up --voice installs what it needs";
        }
        if (wanted != null) {
            emitDown();
        }
    }

    private void emitDown() {
        Signals s = signals;
        if (s == null) {
            return;
        }
        if (!unavailable.isEmpty()) {
            s.signal(new Status("error", false, unavailable, unavailableFix, "", "", null));
        } else {
            s.signal(new Status("starting", false, "", "", "", "", null));
        }
    }

    // ------------------------------------------------------------------ the session

    @Override
    public void open(Settings settings, Signals signals) {
        boolean fresh;
        synchronized (lock) {
            fresh = session == null;
            this.wanted = settings;
            this.signals = signals;
        }
        if (python == null) {
            signals.signal(new Status("error", false, unavailable, unavailableFix, "", "", null));
            return;
        }
        if (!fresh) {
            send(CoreToVoice.newBuilder().setConfigure(configure(settings)).build());
            return;
        }
        if (!serving) {
            start();
            if (!unavailable.isEmpty() && process.state() == SupervisedProcess.State.BACKING_OFF) {
                emitDown();
            } else {
                signals.signal(new Status("starting", false, "", "", "", "", null));
            }
            return;                                 // opened when the sidecar is ready
        }
        reopen();
    }

    /** Opens a session for the wanted settings, if any and none is open. */
    private void reopen() {
        Settings s = wanted;
        ManagedChannel ch = channel;
        if (s == null || ch == null) {
            return;
        }
        long seq;
        StreamObserver<CoreToVoice> obs;
        synchronized (lock) {
            if (session != null) {
                return;
            }
            seq = ++sessionSeq;
            obs = VoiceGrpc.newStub(ch).session(new Receiver(seq));
            session = obs;
        }
        send(CoreToVoice.newBuilder().setConfigure(configure(s)).build());
        robots.forEach((device, audio) -> send(CoreToVoice.newBuilder()
                .setRobotLink(RobotLink.newBuilder().setDevice(device).setConnected(true).setHasAudio(audio)).build()));
    }

    @Override
    public void close() {
        StreamObserver<CoreToVoice> s;
        synchronized (lock) {
            wanted = null;
            s = session;
            session = null;
            sessionSeq++;
        }
        if (s != null) {
            try {
                s.onCompleted();
            } catch (RuntimeException e) {
                // already gone
            }
        }
    }

    /** Stops the sidecar process for good (the host is quitting). */
    public void shutdown() {
        close();
        if (process != null) {
            process.stop();
        }
        ManagedChannel ch = channel;
        if (ch != null) {
            ch.shutdownNow();
        }
    }

    private void send(CoreToVoice m) {
        synchronized (lock) {
            if (session == null) {
                return;
            }
            try {
                session.onNext(m);
            } catch (RuntimeException e) {
                log.debug("voice session: {}", e.getMessage());
            }
        }
    }

    static Configure configure(Settings s) {
        VoiceSettings.Builder b = VoiceSettings.newBuilder()
                .setStt(nz(s.stt())).setSttModel(nz(s.sttModel())).setTts(nz(s.tts())).setTtsVoice(nz(s.ttsVoice()))
                .setLanguage(nz(s.language())).setDefaultLanguage(nz(s.defaultLanguage()))
                .setWake(s.wake()).setDuplex(s.duplex()).setEchoTailS(s.echoTailS()).setFollowUpS(s.followUpS())
                .setListenWindowS(s.listenWindowS()).setSpeculativeStt(s.speculativeStt())
                .setEndSilenceMs(s.endSilenceMs()).setChime(s.chime())
                .setInputDevice(nz(s.inputDevice())).setOutputDevice(nz(s.outputDevice()));
        if (s.continueGraceS() != null) {
            b.setContinueGraceS(s.continueGraceS());
        }
        if (s.endSilenceLongMs() != null) {
            b.setEndSilenceLongMs(s.endSilenceLongMs());
        }
        return Configure.newBuilder().setContractVersion(1).setSettings(b)
                .setRoute(s.robot() ? AudioRoute.AUDIO_ROUTE_ROBOT : AudioRoute.AUDIO_ROUTE_LOCAL).build();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Override
    public void send(Command command) {
        CoreToVoice.Builder b = CoreToVoice.newBuilder();
        switch (command) {
            case ReplyStart r -> b.setReplyStart(marvin.host.contracts.voice.v1.ReplyStart.newBuilder()
                    .setReplyId(r.replyId()).setLanguage(nz(r.language())).setProactive(r.proactive())
                    .setUtteranceUid(r.utteranceUid()));
            case Text t -> b.setText(marvin.host.contracts.voice.v1.TextPiece.newBuilder().setReplyId(t.replyId()).setText(t.text()));
            case ReplyEnd e -> b.setReplyEnd(marvin.host.contracts.voice.v1.ReplyEnd.newBuilder().setReplyId(e.replyId())
                    .setError(nz(e.error())));
            case Say s -> b.setSay(marvin.host.contracts.voice.v1.Say.newBuilder().setReplyId(s.replyId()).setText(s.text())
                    .setLanguage(nz(s.language())).setForce(s.force()).setProactive(s.proactive()));
            case Filler f -> b.setFiller(marvin.host.contracts.voice.v1.Filler.newBuilder().setReplyId(f.replyId())
                    .setText(f.text()).setLanguage(nz(f.language())));
            case ListenNow l -> b.setListenNow(marvin.host.contracts.voice.v1.ListenNow.newBuilder().setOn(l.on()));
            case Mute m -> b.setMute(marvin.host.contracts.voice.v1.Mute.newBuilder().setMuted(m.muted()));
            case StopSpeaking s -> b.setStop(marvin.host.contracts.voice.v1.StopSpeaking.getDefaultInstance());
            case Ask a -> b.setAsk(marvin.host.contracts.voice.v1.Ask.newBuilder().setText(a.text()).setLanguage(nz(a.language())));
        }
        send(b.build());
    }

    // ------------------------------------------------------------------ robot audio

    /** A robot connected ({@code hasAudio}: it has a microphone and a speaker) or left. */
    @Override
    public void robotLink(String device, boolean connected, boolean hasAudio) {
        if (connected) {
            robots.put(device, hasAudio);
        } else {
            robots.remove(device);
        }
        send(CoreToVoice.newBuilder().setRobotLink(RobotLink.newBuilder().setDevice(device).setConnected(connected)
                .setHasAudio(hasAudio)).build());
    }

    /** Whether a robot with a microphone and a speaker is connected. */
    @Override
    public boolean robotWithAudio() {
        return robots.containsValue(true);
    }

    /** A robot's {@code AUDIO_IN}, relayed as it came (same samples, sample index and robot clock). */
    @Override
    public void robotMic(String device, long sampleIndex, long robotTimeUs, short[] pcm) {
        if (!robots.getOrDefault(device, false)) {
            return;
        }
        byte[] b = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            b[2 * i] = (byte) pcm[i];
            b[2 * i + 1] = (byte) (pcm[i] >> 8);
        }
        send(CoreToVoice.newBuilder().setRobotMic(AudioFrame.newBuilder().setDevice(device)
                .setSampleIndex((int) sampleIndex).setRobotTimeUs(robotTimeUs).setPcm(ByteString.copyFrom(b))).build());
    }

    // ------------------------------------------------------------------ options

    @Override
    public Optional<Options> options() {
        ManagedChannel ch = channel;
        if (ch == null) {
            return Optional.empty();
        }
        try {
            VoiceOptions o = VoiceGrpc.newBlockingStub(ch).withDeadlineAfter(5, TimeUnit.SECONDS)
                    .options(OptionsRequest.getDefaultInstance());
            return Optional.of(new Options(
                    o.getSttList().stream().map(b -> new Backend(b.getName(), b.getInstalled(), b.getWhy())).toList(),
                    List.copyOf(o.getSttModelsList()),
                    o.getTtsList().stream().map(b -> new Backend(b.getName(), b.getInstalled(), b.getWhy())).toList(),
                    o.getVoicesList().stream().map(v -> new Voice(v.getId(), v.getEngine(), v.getLanguage(),
                            v.getLocale(), v.getInstalled())).toList()));
        } catch (StatusRuntimeException e) {
            log.debug("voice options: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ what the sidecar says

    private final class Receiver implements StreamObserver<VoiceToCore> {
        private final long seq;

        Receiver(long seq) {
            this.seq = seq;
        }

        private boolean current() {
            synchronized (lock) {
                return seq == sessionSeq;
            }
        }

        @Override
        public void onNext(VoiceToCore m) {
            switch (m.getMCase()) {
                case ROBOT_SPEAKER -> {
                    RobotSpeaker r = robotAudio;
                    if (r != null) {
                        var f = m.getRobotSpeaker();
                        byte[] b = f.getPcm().toByteArray();
                        short[] pcm = new short[b.length / 2];
                        for (int i = 0; i < pcm.length; i++) {
                            pcm[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
                        }
                        r.speaker(f.getDevice(), f.getStream(), Integer.toUnsignedLong(f.getSampleIndex()), pcm);
                    }
                    return;
                }
                case CTRL -> {
                    RobotSpeaker r = robotAudio;
                    if (r != null) {
                        r.control(m.getCtrl().getDevice(), m.getCtrl().getCommand(), m.getCtrl().getArgument());
                    }
                    return;
                }
                case SOUND -> {
                    RobotSpeaker r = robotAudio;
                    if (r != null) {
                        r.sound(m.getSound().getDevice(), m.getSound().getId());
                    }
                    return;
                }
                default -> {
                }
            }
            if (!current()) {
                return;
            }
            Signal s = signal(m);
            Signals sig = signals;
            if (s != null && sig != null) {
                sig.signal(s);
            }
        }

        @Override
        public void onError(Throwable t) {
            ended("voice session ended: " + t.getMessage());
        }

        @Override
        public void onCompleted() {
            ended("voice session closed by the sidecar");
        }

        private void ended(String why) {
            boolean again;
            synchronized (lock) {
                if (seq != sessionSeq) {
                    return;
                }
                session = null;
                again = wanted != null;
            }
            if (!again) {
                return;
            }
            log.info("{}: opening it again", why);
            emitDown();
            if (serving) {
                Thread.ofVirtual().name("voice-session-reopen").start(() -> {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    reopen();
                });
            }
        }
    }

    static Signal signal(VoiceToCore m) {
        return switch (m.getMCase()) {
            case STATUS -> {
                var s = m.getStatus();
                String state = s.getState().name().toLowerCase(java.util.Locale.ROOT);
                if ("state_unspecified".equals(state)) {
                    state = "starting";
                }
                yield new Status(state, s.getMuted(), s.getError(), s.getFix(), s.getStt(), s.getTts(),
                        s.hasListenS() ? s.getListenS() : null, s.getHearing());
            }
            case HEARD -> {
                var h = m.getHeard();
                yield new Heard(h.getUid(), h.getText(), h.getRaw(), h.getLanguage(), h.getSource(),
                        new LinkedHashMap<>(h.getLatencyMap()), h.getWallTime(), h.getContinuesList());
            }
            case IGNORED -> {
                var i = m.getIgnored();
                yield new Ignored(i.getText(), i.getReason(), i.hasDbfs() ? i.getDbfs() : null);
            }
            case LEVEL -> new Level(m.getLevel().getMic(), m.getLevel().getSpeech(), m.getLevel().getGated());
            case PARTIAL -> new Partial(m.getPartial().getUid(), m.getPartial().getText());
            case UTTERANCE -> new Utterance(m.getUtterance().getState().name().toLowerCase(java.util.Locale.ROOT),
                    m.getUtterance().getUid());
            case SAY -> {
                var p = m.getSay();
                List<Double> env = new ArrayList<>(p.getEnvelopeCount());
                for (float f : p.getEnvelopeList()) {
                    env.add(Double.parseDouble(Float.toString(f)));     // the value Python sent, not float32's expansion
                }
                yield new SayProgress(p.getReplyId(), p.getText(), p.getSeconds(), env);
            }
            case INTERRUPTED -> new Interrupted(m.getInterrupted().getReplyId(), m.getInterrupted().getReason(),
                    m.getInterrupted().getUtteranceUid());
            case SPOKEN -> {
                var r = m.getSpoken();
                yield new ReplySpoken(r.getReplyId(), r.getUtteranceUid(), r.getText(), r.getInterrupted(),
                        new LinkedHashMap<>(r.getLatencyMap()));
            }
            default -> null;
        };
    }

    /** {@code authorization: Bearer <token>} on every call. */
    private record TokenInterceptor(String token) implements ClientInterceptor {
        private static final Metadata.Key<String> AUTH = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

        @Override
        public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
                @Override
                public void start(Listener<R> listener, Metadata headers) {
                    headers.put(AUTH, "Bearer " + token);
                    super.start(listener, headers);
                }
            };
        }
    }

    /** For tests: waits until the sidecar serves. */
    public boolean awaitServing(long timeoutMs) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (!serving && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        return serving;
    }

    public int port() {
        return port;
    }
}
