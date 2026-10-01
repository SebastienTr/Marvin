// SPDX-License-Identifier: MIT
package marvin.host.adapter.camera;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs a short process and gives what it wrote (the camera's picture on its standard output). */
@FunctionalInterface
interface ProcessRunner {

    /**
     * What a process did.
     *
     * @param exit     its exit status ({@code -1}: killed after the time allowed)
     * @param stdout   its standard output
     * @param stderr   the end of its standard error (at most {@link #STDERR_KEEP} bytes)
     * @param timedOut it did not finish in time and was killed
     */
    record Result(int exit, byte[] stdout, String stderr, boolean timedOut) {
    }

    int STDERR_KEEP = 8192;
    /** At most this much of the standard output is kept (a picture is far smaller). */
    int STDOUT_MAX = 16 * 1024 * 1024;

    /** @throws IOException the process could not be started */
    Result run(List<String> command, double timeoutS) throws IOException;

    /** The real one: the process's two outputs read on virtual threads, killed when it overstays. */
    ProcessRunner SYSTEM = (command, timeoutS) -> {
        Process p = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.PIPE).start();
        p.getOutputStream().close();                    // nothing to read: ffmpeg must not wait for keys
        CompletableFuture<byte[]> out = read(p.getInputStream(), STDOUT_MAX);
        CompletableFuture<byte[]> err = read(p.getErrorStream(), 1024 * 1024);
        boolean done;
        try {
            done = p.waitFor((long) (timeoutS * 1000), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (!done) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);     // a wrapper script's children too
            p.destroyForcibly();
            try {
                p.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] stdout = rest(out);
        byte[] stderr = rest(err);
        int from = Math.max(0, stderr.length - STDERR_KEEP);
        return new Result(done ? p.exitValue() : -1, stdout,
                new String(stderr, from, stderr.length - from, StandardCharsets.UTF_8), !done);
    };

    /** What was read, waiting a little for the end of it (a child that kept the pipe open is not waited for). */
    private static byte[] rest(CompletableFuture<byte[]> f) {
        try {
            return f.get(2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return new byte[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new byte[0];
        }
    }

    private static CompletableFuture<byte[]> read(InputStream in, int max) {
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        Thread.ofVirtual().name("marvin-camera-read").start(() -> {
            try (in) {
                byte[] b = in.readNBytes(max);
                in.transferTo(java.io.OutputStream.nullOutputStream());     // the rest, so the process never blocks
                f.complete(b);
            } catch (IOException e) {
                f.complete(new byte[0]);
            } catch (UncheckedIOException e) {
                f.complete(new byte[0]);
            }
        });
        return f;
    }
}
