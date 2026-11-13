package com.termux.terminal;

import junit.framework.TestCase;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class NonBlockingWriteQueueTest extends TestCase {

    public void testLargePasteNeverBlocksProducerWithoutConsumer() {
        NonBlockingWriteQueue q = new NonBlockingWriteQueue(512, 2 * 1024 * 1024);
        byte[] payload = new byte[512 * 1024]; // 512 KB (128x old 4 KB blocking queue)
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((i * 31 + 7) & 0xFF);
        }

        // With zero active reader threads, write() must complete immediately without blocking.
        assertTrue(q.write(payload, 0, payload.length));
        assertEquals(payload.length, q.getStoredBytesForTest());

        ByteArrayOutputStream drained = new ByteArrayOutputStream(payload.length);
        byte[] readBuf = new byte[4096];
        while (drained.size() < payload.length) {
            int n = q.read(readBuf, false);
            assertTrue(n > 0);
            drained.write(readBuf, 0, n);
        }
        assertEquals(0, q.read(readBuf, false));
        assertTrue(Arrays.equals(payload, drained.toByteArray()));
    }

    public void testAtomicRejectionAboveMaxCap() {
        NonBlockingWriteQueue q = new NonBlockingWriteQueue(64, 256);
        assertTrue(q.write(new byte[200], 0, 200));
        // Exceeds remaining 56 bytes -> rejected atomically without partial write.
        assertFalse(q.write(new byte[60], 0, 60));
        assertEquals(200, q.getStoredBytesForTest());

        // Drain and verify full capacity is available again.
        assertEquals(200, q.read(new byte[256], false));
        assertTrue(q.write(new byte[256], 0, 256));
    }

    public void testBlockingReaderWakesOnWriteAndClose() throws Exception {
        final NonBlockingWriteQueue q = new NonBlockingWriteQueue(128, 1024);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread consumer = new Thread(() -> {
            byte[] buf = new byte[64];
            while (true) {
                int n = q.read(buf, true);
                if (n == -1) break;
                out.write(buf, 0, n);
            }
        });
        consumer.start();

        Thread.sleep(15);
        assertTrue(q.write(new byte[]{10, 20, 30}, 0, 3));
        assertTrue(q.write(new byte[]{40, 50}, 0, 2));
        // Zero-length write is a safe no-op.
        assertTrue(q.write(new byte[]{1}, 0, 0));

        long deadline = System.currentTimeMillis() + 1000;
        while (q.getStoredBytesForTest() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        q.close();
        consumer.join(1000);
        assertFalse(consumer.isAlive());
        assertFalse(q.write(new byte[]{1}, 0, 1));
        assertTrue(Arrays.equals(new byte[]{10, 20, 30, 40, 50}, out.toByteArray()));
    }

    /**
     * Several producers race a slow blocking consumer through chunk boundaries, oversized chunks and
     * full drains. Every accepted write must arrive contiguous (never interleaved with another write),
     * in per-producer order, and rejected writes must leave no partial bytes behind.
     */
    public void testConcurrentProducersPreserveFramingAndOrder() throws Exception {
        final NonBlockingWriteQueue q = new NonBlockingWriteQueue(64, 64 * 1024);
        final int producers = 4;
        final int writesPerProducer = 3000;
        final java.util.concurrent.atomic.AtomicIntegerArray accepted = new java.util.concurrent.atomic.AtomicIntegerArray(producers);
        final ByteArrayOutputStream drained = new ByteArrayOutputStream();

        Thread consumer = new Thread(() -> {
            byte[] buf = new byte[37]; // Odd size so reads straddle frames and chunks.
            while (true) {
                int n = q.read(buf, true);
                if (n == -1) break;
                drained.write(buf, 0, n);
            }
        });
        consumer.start();

        Thread[] threads = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            final int id = p;
            threads[p] = new Thread(() -> {
                java.util.Random rng = new java.util.Random(id);
                for (int seq = 0; seq < writesPerProducer; seq++) {
                    // Frame: [id][seq hi][seq lo][len hi][len lo][payload...]; payload sometimes exceeds the chunk size.
                    int payloadLen = rng.nextInt(10) == 0 ? 64 + rng.nextInt(400) : rng.nextInt(20);
                    byte[] frame = new byte[5 + payloadLen];
                    frame[0] = (byte) id;
                    frame[1] = (byte) (seq >> 8);
                    frame[2] = (byte) seq;
                    frame[3] = (byte) (payloadLen >> 8);
                    frame[4] = (byte) payloadLen;
                    for (int i = 0; i < payloadLen; i++) frame[5 + i] = (byte) (id * 31 + seq + i);
                    if (q.write(frame, 0, frame.length)) accepted.incrementAndGet(id);
                    else seq--; // Retry the same sequence number so order stays checkable.
                    if ((seq & 63) == 0) Thread.yield();
                }
            });
            threads[p].start();
        }
        for (Thread t : threads) t.join(30_000);

        long deadline = System.currentTimeMillis() + 10_000;
        while (q.getStoredBytesForTest() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
        q.close();
        consumer.join(5_000);
        assertFalse(consumer.isAlive());

        byte[] data;
        synchronized (drained) {
            data = drained.toByteArray();
        }
        int[] nextSeq = new int[producers];
        int pos = 0;
        while (pos < data.length) {
            assertTrue("truncated frame header at " + pos, pos + 5 <= data.length);
            int id = data[pos];
            int seq = ((data[pos + 1] & 0xFF) << 8) | (data[pos + 2] & 0xFF);
            int len = ((data[pos + 3] & 0xFF) << 8) | (data[pos + 4] & 0xFF);
            assertTrue("bad producer id " + id + " at " + pos, id >= 0 && id < producers);
            assertEquals("out-of-order frame for producer " + id, nextSeq[id] & 0xFFFF, seq);
            assertTrue("truncated payload at " + pos, pos + 5 + len <= data.length);
            for (int i = 0; i < len; i++) {
                assertEquals("corrupt payload byte", (byte) (id * 31 + nextSeq[id] + i), data[pos + 5 + i]);
            }
            nextSeq[id]++;
            pos += 5 + len;
        }
        for (int p = 0; p < producers; p++) {
            assertEquals(writesPerProducer, nextSeq[p]);
            assertEquals(writesPerProducer, accepted.get(p));
        }
    }
}
