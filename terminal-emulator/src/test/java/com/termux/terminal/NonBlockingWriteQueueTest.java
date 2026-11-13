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

    public void testSanitizePasteTextMatchesEscC1AndCrLfRules() {
        String plain = "hello world";
        assertSame(plain, TerminalEmulator.sanitizePasteText(plain));
        assertEquals("a\rb\rc\r\rd", TerminalEmulator.sanitizePasteText("a\r\nb\nc\r\r\nd"));
        // ESC sitting between \r and \n collapses to \r\n -> single \r, matching sequential regex rules.
        assertEquals("x\ry", TerminalEmulator.sanitizePasteText("x\r\u001B\ny"));
        assertEquals("abcd", TerminalEmulator.sanitizePasteText("\u001Bab\u0085cd\u009F"));
        assertEquals("", TerminalEmulator.sanitizePasteText("\u001B\u009B"));
    }
}
