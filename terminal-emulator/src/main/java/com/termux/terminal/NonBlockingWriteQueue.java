package com.termux.terminal;

/**
 * Single-producer / multi-producer safe, single-consumer byte queue whose {@link #write} never
 * blocks in {@link Object#wait()}.
 *
 * <p>Used for forwarding user input, pastes and terminal query responses from the UI thread to the
 * background PTY writer thread without ever risking an Android input ANR when a paste exceeds 4 KB
 * or the foreground process stops draining {@code stdin}.
 *
 * <p>Steady-state typing reuses a single {@code mChunkSize} buffer in place with zero heap
 * allocations; large bursts chain temporary chunks up to {@code mMaxBytes} and release them back to
 * GC as soon as they are drained.
 */
final class NonBlockingWriteQueue {

    static final int DEFAULT_CHUNK_SIZE = 8 * 1024;
    static final int MAX_CHUNK_SIZE = 64 * 1024;
    static final int DEFAULT_MAX_BYTES = 8 * 1024 * 1024;

    private static final class Chunk {
        final byte[] data;
        int readPos;
        int writePos;
        Chunk next;

        Chunk(int capacity) {
            data = new byte[capacity];
        }
    }

    private final int mChunkSize;
    private final int mMaxBytes;

    private Chunk mHead;
    private Chunk mTail;
    private int mStoredBytes;
    private boolean mOpen = true;

    NonBlockingWriteQueue() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_MAX_BYTES);
    }

    NonBlockingWriteQueue(int chunkSize, int maxBytes) {
        if (chunkSize <= 0 || maxBytes < chunkSize) {
            throw new IllegalArgumentException("Invalid queue sizes: chunkSize=" + chunkSize + ", maxBytes=" + maxBytes);
        }
        mChunkSize = chunkSize;
        mMaxBytes = maxBytes;
        mHead = mTail = new Chunk(chunkSize);
    }

    public synchronized void close() {
        mOpen = false;
        mHead = null;
        mTail = null;
        mStoredBytes = 0;
        notifyAll();
    }

    /**
     * Enqueue {@code buffer[offset .. offset + lengthToWrite)} without blocking on consumer I/O.
     *
     * @return {@code true} if the entire slice was enqueued, {@code false} if the queue is closed
     *         or if accepting the slice would exceed {@code mMaxBytes}.
     */
    public boolean write(byte[] buffer, int offset, int lengthToWrite) {
        if (offset < 0 || lengthToWrite < 0 || offset + lengthToWrite > buffer.length) {
            throw new IllegalArgumentException("Invalid slice: offset=" + offset + ", length=" + lengthToWrite + ", buf=" + buffer.length);
        }
        if (lengthToWrite == 0) {
            return true;
        }

        synchronized (this) {
            if (!mOpen) return false;
            if (lengthToWrite > mMaxBytes - mStoredBytes) {
                // Atomic rejection prevents tearing multi-byte UTF-8 or bracketed paste framing.
                return false;
            }

            final boolean wasEmpty = (mStoredBytes == 0);
            int remaining = lengthToWrite;
            int srcOffset = offset;

            while (remaining > 0) {
                int spaceInTail = mTail.data.length - mTail.writePos;
                if (spaceInTail == 0) {
                    int nextCap = Math.max(mChunkSize, Math.min(remaining, MAX_CHUNK_SIZE));
                    Chunk next = new Chunk(nextCap);
                    mTail.next = next;
                    mTail = next;
                    spaceInTail = nextCap;
                }
                int toCopy = Math.min(spaceInTail, remaining);
                System.arraycopy(buffer, srcOffset, mTail.data, mTail.writePos, toCopy);
                mTail.writePos += toCopy;
                srcOffset += toCopy;
                remaining -= toCopy;
            }

            mStoredBytes += lengthToWrite;
            if (wasEmpty) notifyAll();
            return true;
        }
    }

    /**
     * Read available bytes into {@code buffer}, optionally blocking until bytes arrive or the queue
     * is closed.
     *
     * @return number of bytes read ({@code 0} only when {@code !block} and empty), or {@code -1} if closed.
     */
    public synchronized int read(byte[] buffer, boolean block) {
        while (mStoredBytes == 0 && mOpen) {
            if (!block) return 0;
            try {
                wait();
            } catch (InterruptedException ignored) {
                // Re-check loop condition.
            }
        }
        if (!mOpen) return -1;

        int totalRead = 0;
        int dstOffset = 0;
        int remainingSpace = buffer.length;

        while (remainingSpace > 0 && mStoredBytes > 0) {
            int availableInHead = mHead.writePos - mHead.readPos;
            int toCopy = Math.min(remainingSpace, availableInHead);
            System.arraycopy(mHead.data, mHead.readPos, buffer, dstOffset, toCopy);
            mHead.readPos += toCopy;
            dstOffset += toCopy;
            remainingSpace -= toCopy;
            totalRead += toCopy;
            mStoredBytes -= toCopy;

            if (mHead.readPos == mHead.writePos) {
                if (mHead.next != null) {
                    mHead = mHead.next;
                } else {
                    // Queue is completely drained; reuse the base chunk or shrink an oversized tail.
                    if (mHead.data.length > mChunkSize) {
                        mHead = mTail = new Chunk(mChunkSize);
                    } else {
                        mHead.readPos = 0;
                        mHead.writePos = 0;
                    }
                }
            }
        }
        return totalRead;
    }

    synchronized int getStoredBytesForTest() {
        return mStoredBytes;
    }
}
