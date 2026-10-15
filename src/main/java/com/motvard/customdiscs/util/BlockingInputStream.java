package com.motvard.customdiscs.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bounded thread-safe pipe from a producer (network thread) to a consumer (decoder thread).
 * The producer hands over already-allocated {@code byte[]} buffers via {@link #offer(byte[])};
 * the consumer reads them through the standard {@link InputStream} API. Backpressure is
 * applied at {@link #offer} when the total buffered byte count would exceed the cap.
 * Uses an explicit deque rather than {@link java.io.PipedInputStream}, which is fragile when
 * the writer thread dies and races at close time.
 */
public final class BlockingInputStream extends InputStream {

    /** Fallback read timeout when none is supplied. */
    private static final long DEFAULT_READ_TIMEOUT_MS = 30_000L;

    /** If the producer makes no progress for this long, read() throws instead of pinning the
     *  SoundEngine streaming reader thread forever (e.g. network died, world unloaded). */
    private final long readTimeoutMs;
    private final int maxBufferedBytes;
    private final Deque<byte[]> queue = new ArrayDeque<>();
    private final Object lock = new Object();

    private byte[] current;
    private int currentPos;
    private int bufferedBytes;

    private boolean finished;
    private boolean closed;
    private IOException failure;

    public BlockingInputStream(int maxBufferedBytes) {
        this(maxBufferedBytes, DEFAULT_READ_TIMEOUT_MS);
    }

    public BlockingInputStream(int maxBufferedBytes, long readTimeoutMs) {
        if (maxBufferedBytes < 1) throw new IllegalArgumentException("maxBufferedBytes must be > 0");
        this.maxBufferedBytes = maxBufferedBytes;
        this.readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;
    }

    /**
     * Producer side. Blocks while the buffer is full, which is the backpressure signal.
     * After {@link #finish()}, {@link #fail(IOException)} or {@link #close()} this is a no-op.
     */
    public void offer(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) return;
        synchronized (lock) {
            while (!finished && !closed && failure == null && bufferedBytes + data.length > maxBufferedBytes) {
                lock.wait();
            }
            if (finished || closed || failure != null) return;
            queue.add(data);
            bufferedBytes += data.length;
            lock.notifyAll();
        }
    }

    /** Producer side. No more bytes will arrive; readers see EOF after draining. */
    public void finish() {
        synchronized (lock) {
            finished = true;
            lock.notifyAll();
        }
    }

    /** Producer side. Future reads throw the given exception. */
    public void fail(IOException cause) {
        synchronized (lock) {
            if (failure == null) failure = cause;
            lock.notifyAll();
        }
    }

    @Override
    public int read() throws IOException {
        synchronized (lock) {
            if (!ensureCurrent()) return -1;
            int b = current[currentPos++] & 0xFF;
            bufferedBytes--;
            lock.notifyAll();
            advanceIfDrained();
            return b;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) throw new NullPointerException();
        if (off < 0 || len < 0 || off + len > b.length) throw new IndexOutOfBoundsException();
        if (len == 0) return 0;

        synchronized (lock) {
            if (!ensureCurrent()) return -1;
            int toCopy = Math.min(len, current.length - currentPos);
            System.arraycopy(current, currentPos, b, off, toCopy);
            currentPos += toCopy;
            bufferedBytes -= toCopy;
            lock.notifyAll();
            advanceIfDrained();
            return toCopy;
        }
    }

    @Override
    public int available() {
        synchronized (lock) {
            return bufferedBytes;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            queue.clear();
            current = null;
            bufferedBytes = 0;
            lock.notifyAll();
        }
    }

    /** Wait until {@code current} holds unread bytes, or return false if no more will ever come. */
    private boolean ensureCurrent() throws IOException {
        while (current == null || currentPos >= current.length) {
            if (failure != null) throw new IOException("BlockingInputStream failed", failure);
            if (closed) return false;
            current = queue.poll();
            currentPos = 0;
            if (current == null) {
                if (finished) return false;
                try {
                    long before = System.nanoTime();
                    lock.wait(readTimeoutMs);
                    long waitedMs = (System.nanoTime() - before) / 1_000_000L;
                    // If we woke up still empty and the producer hasn't signalled us
                    // in this window, treat it as a hung producer and bail out.
                    if (queue.isEmpty() && !finished && failure == null && !closed
                            && waitedMs >= readTimeoutMs) {
                        throw new IOException("BlockingInputStream timed out after "
                                + readTimeoutMs + "ms with no data");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("read interrupted");
                }
            }
        }
        return true;
    }

    private void advanceIfDrained() {
        if (current != null && currentPos >= current.length) {
            current = null;
        }
    }
}
