package com.motvard.customdiscs.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BlockingInputStreamTest {

    @Test
    void singleChunkReadsBackOut() throws Exception {
        BlockingInputStream s = new BlockingInputStream(1024);
        s.offer(new byte[]{1, 2, 3, 4});
        s.finish();

        byte[] out = s.readAllBytes();
        assertArrayEquals(new byte[]{1, 2, 3, 4}, out);
    }

    @Test
    void multipleChunksConcatenate() throws Exception {
        BlockingInputStream s = new BlockingInputStream(1024);
        s.offer(new byte[]{1, 2});
        s.offer(new byte[]{3});
        s.offer(new byte[]{4, 5, 6});
        s.finish();

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, s.readAllBytes());
    }

    @Test
    void readReturnsMinusOneAfterFinish() throws Exception {
        BlockingInputStream s = new BlockingInputStream(1024);
        s.finish();
        assertEquals(-1, s.read());
    }

    @Test
    void readBlocksUntilOffer() throws Exception {
        BlockingInputStream s = new BlockingInputStream(1024);
        AtomicInteger result = new AtomicInteger(-2);
        CountDownLatch readerStarted = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            try {
                readerStarted.countDown();
                result.set(s.read());
            } catch (IOException e) {
                result.set(-99);
            }
        });
        reader.start();
        assertTrue(readerStarted.await(1, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertEquals(-2, result.get(), "reader must still be blocked");

        s.offer(new byte[]{42});
        reader.join(1000);
        assertEquals(42, result.get());
    }

    @Test
    void offerBlocksWhenBufferFull() throws Exception {
        BlockingInputStream s = new BlockingInputStream(4);
        s.offer(new byte[]{1, 2, 3, 4});

        AtomicInteger phase = new AtomicInteger(0);
        Thread producer = new Thread(() -> {
            try {
                phase.set(1);
                s.offer(new byte[]{5, 6});   // must block
                phase.set(2);
            } catch (InterruptedException e) {
                phase.set(-1);
            }
        });
        producer.start();
        Thread.sleep(100);
        assertEquals(1, phase.get(), "producer must still be blocked");

        // Drain one byte to free space
        assertEquals(1, s.read());
        Thread.sleep(50);
        assertEquals(1, phase.get(), "still 1 byte short of space — must remain blocked");

        // Drain one more — now 2 bytes are free and producer can write
        assertEquals(2, s.read());
        producer.join(1000);
        assertEquals(2, phase.get());
    }

    @Test
    void failPropagatesToReader() {
        BlockingInputStream s = new BlockingInputStream(1024);
        s.fail(new IOException("boom"));
        IOException ex = assertThrows(IOException.class, s::read);
        assertTrue(ex.getMessage().contains("BlockingInputStream failed"));
    }

    @Test
    void closeDrainsAndReturnsEof() throws Exception {
        BlockingInputStream s = new BlockingInputStream(1024);
        s.offer(new byte[]{1, 2, 3});
        s.close();
        assertEquals(-1, s.read());
    }
}
