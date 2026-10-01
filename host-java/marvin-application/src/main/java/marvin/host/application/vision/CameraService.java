// SPDX-License-Identifier: MIT
package marvin.host.application.vision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import marvin.host.application.vision.port.in.Camera;
import marvin.host.application.vision.port.out.CameraListener;
import marvin.host.application.vision.port.out.FrameSource;
import marvin.host.domain.shared.Clocks;
import marvin.host.domain.vision.CameraChoice;
import marvin.host.domain.vision.event.Frame;

/**
 * Marvin's cameras (docs/vision.md): the {@code camera} setting picks one ({@code auto}: the robot's when a robot with a
 * camera is connected, else the computer's), and a picture is taken only when asked: the {@code look} tool, or the
 * app's camera test. One picture at a time (a camera cannot be opened twice); each one is told to the listeners (the
 * app shows it) and to the log, and none is kept.
 */
public final class CameraService implements Camera {
    private static final Logger log = Logger.getLogger("marvin.camera");

    /** How long a camera may take for one picture. */
    public static final double TIMEOUT_S = 10.0;

    private final Map<String, FrameSource> sources = new LinkedHashMap<>();
    private final Supplier<String> setting;
    private final Clocks clocks;
    private final List<CameraListener> listeners = new CopyOnWriteArrayList<>();
    private final ReentrantLock taking = new ReentrantLock();
    private volatile Map<String, Object> last;

    /**
     * @param sources the cameras this host has (by {@link FrameSource#name()})
     * @param setting the {@code camera} setting as it is now ({@code auto}, {@code computer}, {@code robot}, {@code off})
     */
    public CameraService(List<FrameSource> sources, Supplier<String> setting, Clocks clocks) {
        for (FrameSource s : sources) {
            this.sources.put(s.name(), s);
        }
        this.setting = Objects.requireNonNull(setting, "setting");
        this.clocks = Objects.requireNonNull(clocks, "clocks");
    }

    public void addListener(CameraListener l) {
        listeners.add(l);
    }

    private CameraChoice.Source availability(String name) {
        FrameSource s = sources.get(name);
        if (s == null) {
            return null;
        }
        try {
            return s.availability();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "the " + name + " camera could not say whether it works", e);
            return new CameraChoice.Source(name, false, "The " + name + " camera failed: " + e.getMessage(), "");
        }
    }

    private String setting() {
        try {
            return CameraChoice.setting(setting.get());
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "could not read the camera setting", e);
            return CameraChoice.AUTO;
        }
    }

    @Override
    public Status status() {
        CameraChoice.Source computer = availability(CameraChoice.COMPUTER);
        CameraChoice.Source robot = availability(CameraChoice.ROBOT);
        List<CameraChoice.Source> all = new ArrayList<>();
        if (computer != null) {
            all.add(computer);
        }
        if (robot != null) {
            all.add(robot);
        }
        return new Status(CameraChoice.pick(setting(), computer, robot), List.copyOf(all), last);
    }

    @Override
    public boolean available() {
        return status().choice().available();
    }

    @Override
    public Frame take(String purpose) {
        CameraChoice.Choice choice = status().choice();
        if (!choice.available()) {
            throw new NoPicture(choice.reason(), choice.fix(), true);
        }
        FrameSource source = sources.get(choice.source());
        Frame frame;
        taking.lock();          // one picture at a time: the camera cannot be opened twice
        try {
            double t0 = clocks.monotonicSeconds();
            try {
                frame = source.capture(TIMEOUT_S);
            } catch (FrameSource.CaptureFailed e) {
                log.warning("the " + choice.source() + " camera took no picture: " + e.getMessage()
                        + (e.fix().isEmpty() ? "" : ". " + e.fix()));
                throw new NoPicture(e.getMessage(), e.fix(), false);
            }
            if (frame.seconds() <= 0) {
                frame = new Frame(frame.jpeg(), frame.width(), frame.height(), frame.source(), frame.detail(),
                        clocks.monotonicSeconds() - t0);
            }
        } finally {
            taking.unlock();
        }
        Map<String, Object> info = new LinkedHashMap<>(frame.describe());
        info.put("purpose", purpose);
        info.put("t", clocks.wallSeconds());
        last = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(info));
        Frame f = frame;
        log.info(() -> "picture taken (" + purpose + "): " + f.source() + " camera, " + f.width() + "x" + f.height() + ", "
                + f.jpeg().length + " bytes in " + String.format(java.util.Locale.ROOT, "%.2f", f.seconds()) + " s");
        for (CameraListener l : listeners) {
            try {
                l.pictureTaken(info);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "camera listener failed", e);
            }
        }
        return frame;
    }
}
