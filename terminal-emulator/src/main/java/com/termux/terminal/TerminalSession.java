package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.view.Choreographer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int, int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_SYNC_OUTPUT_TIMEOUT = 2;
    private static final int MSG_DRAIN_FALLBACK = 3;
    private static final int MSG_PROCESS_EXITED = 4;

    /** Minimum VSync frame window (8 ms, ~120 Hz) for refilling the foreground parse token bucket. */
    private static final long FRAME_WINDOW_NS = 8_000_000L;
    /**
     * Per-frame main-thread VT parsing time budget (4 ms) when actively displayed, leaving headroom
     * within an 8.3 ms 120 Hz frame for input handling and {@code onDraw()} during heavy floods.
     */
    private static final long FRAME_PARSE_BUDGET_NS = 4_000_000L;
    /**
     * Parse slice (12 ms) when the session is in the background or the display VSync is inactive
     * (e.g. Doze / screen off), draining output at full speed without waiting on {@link Choreographer}.
     */
    private static final long BACKGROUND_SLICE_BUDGET_NS = 12_000_000L;
    /** Fallback wakeup if {@link Choreographer} does not fire within 20 ms (e.g. Doze / screen off). */
    private static final long VSYNC_FALLBACK_TIMEOUT_MS = 20L;
    /** Watchdog timeout (100 ms) after which an unclosed DECSET 2026 frame flushes automatically. */
    private static final long SYNC_OUTPUT_TIMEOUT_MS = 100L;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(256 * 1024);
    /** Whether a {@link #MSG_NEW_INPUT} wakeup is queued for the main thread. */
    private final AtomicBoolean mNewInputMessagePending = new AtomicBoolean();
    /** True while this session is attached to a visible {@code TerminalView} on an active window. */
    private volatile boolean mActivelyDisplayed;
    /**
     * A non-blocking queue written to from the main thread due to user interaction or terminal
     * protocol responses, and drained on a dedicated writer thread into {@link #mTerminalFileDescriptor}.
     */
    final NonBlockingWriteQueue mTerminalToProcessIOQueue = new NonBlockingWriteQueue();
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    int mShellPid;

    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    int mShellExitStatus;

    /**
     * The file descriptor referencing the master half of a pseudo-terminal pair, resulting from calling
     * {@link JNI#createSubprocess(String, String, String[], String[], int[], int, int, int, int)}.
     */
    private int mTerminalFileDescriptor;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final MainThreadHandler mMainThreadHandler = new MainThreadHandler();

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;


    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns, cellWidthPixels, cellHeightPixels);
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);

        int[] processId = new int[1];
        mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns, cellWidthPixels, cellHeightPixels);
        mShellPid = processId[0];
        mClient.setTerminalShellPid(this, mShellPid);

        final FileDescriptor terminalFileDescriptorWrapped = wrapFileDescriptor(mTerminalFileDescriptor, mClient);

        new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                try (InputStream termIn = new FileInputStream(terminalFileDescriptorWrapped)) {
                    final byte[] buffer = new byte[32 * 1024];
                    while (true) {
                        int read = termIn.read(buffer);
                        if (read == -1) return;
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                        if (mNewInputMessagePending.compareAndSet(false, true)) {
                            mMainThreadHandler.enqueueNewInputWakeup();
                        }
                    }
                } catch (Exception e) {
                    // Ignore, just shutting down.
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[16 * 1024];
                try (FileOutputStream termOut = new FileOutputStream(terminalFileDescriptorWrapped)) {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        termOut.write(buffer, 0, bytesToWrite);
                    }
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }.start();

        new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                int processExitCode = JNI.waitFor(mShellPid);
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
            }
        }.start();

    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mShellPid > 0) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /**
     * Update whether this session is currently attached to a visible {@code TerminalView}.
     * Only actively displayed sessions pace output floods against {@link Choreographer} VSync;
     * background or screen-off sessions drain output slices directly without VSync throttling.
     */
    public void setActivelyDisplayed(boolean activelyDisplayed) {
        if (mActivelyDisplayed == activelyDisplayed) return;
        mActivelyDisplayed = activelyDisplayed;
        if (!activelyDisplayed) {
            mMainThreadHandler.onDisplayDeactivated();
        }
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by sending SIGKILL to the shell. */
    public void finishIfRunning() {
        if (isRunning()) {
            try {
                Os.kill(mShellPid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                Logger.logWarn(mClient, LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
            }
        }
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }

        // Stop the reader and writer threads, and close the I/O streams
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        JNI.close(mTerminalFileDescriptor);
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor, TerminalSessionClient client) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // For desktop java:
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            Logger.logStackTraceWithMessage(client, LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e);
            System.exit(1);
        }
        return result;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[32 * 1024];
        private boolean mFrameCallbackScheduled;
        private boolean mFallbackScheduled;
        private boolean mSyncTimeoutScheduled;
        private boolean mScreenUpdateDeferredBySync;
        private long mBudgetWindowStartNs;
        private long mFrameBudgetRemainingNs = FRAME_PARSE_BUDGET_NS;

        private final Choreographer.FrameCallback mFrameCallback = frameTimeNanos -> {
            mFrameCallbackScheduled = false;
            if (mFallbackScheduled) {
                mFallbackScheduled = false;
                removeMessages(MSG_DRAIN_FALLBACK);
            }
            mBudgetWindowStartNs = System.nanoTime();
            mFrameBudgetRemainingNs = FRAME_PARSE_BUDGET_NS;
            if (mEmulator != null) {
                processAvailableInput(false, false);
            }
        };

        void enqueueNewInputWakeup() {
            Message msg = obtainMessage(MSG_NEW_INPUT);
            markAsynchronous(msg);
            if (!sendMessage(msg)) {
                mNewInputMessagePending.set(false);
            }
        }

        private void sendAsyncDelayed(int what, long delayMs) {
            Message msg = obtainMessage(what);
            markAsynchronous(msg);
            sendMessageDelayed(msg, delayMs);
        }

        private void markAsynchronous(Message msg) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                msg.setAsynchronous(true);
            }
        }

        void onDisplayDeactivated() {
            if (!mFrameCallbackScheduled && !mFallbackScheduled) return;
            cancelFrameDrainCallbacks();
            if (mEmulator != null) {
                enqueueNewInputWakeup();
            }
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_NEW_INPUT) {
                if (!mFrameCallbackScheduled && mEmulator != null) {
                    processAvailableInput(false, false);
                }
                return;
            }

            if (msg.what == MSG_DRAIN_FALLBACK) {
                mFallbackScheduled = false;
                if (mFrameCallbackScheduled) {
                    mFrameCallbackScheduled = false;
                    try {
                        Choreographer.getInstance().removeFrameCallback(mFrameCallback);
                    } catch (RuntimeException ignored) {
                    }
                }
                mBudgetWindowStartNs = System.nanoTime();
                mFrameBudgetRemainingNs = FRAME_PARSE_BUDGET_NS;
                if (mEmulator != null) {
                    // Display VSync did not arrive within 20 ms (e.g. Doze / screen off): drain unthrottled.
                    processAvailableInput(false, true);
                }
                return;
            }

            if (msg.what == MSG_SYNC_OUTPUT_TIMEOUT) {
                mSyncTimeoutScheduled = false;
                if (mEmulator != null && mEmulator.isSynchronizedOutputActive()) {
                    mEmulator.clearSynchronizedOutput();
                }
                if (mScreenUpdateDeferredBySync) {
                    mScreenUpdateDeferredBySync = false;
                    notifyScreenUpdate();
                }
                return;
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                cancelScheduledCallbacks();
                if (mEmulator != null) {
                    processAvailableInput(true, false);
                    mEmulator.clearSynchronizedOutput();
                    mScreenUpdateDeferredBySync = false;
                }

                int exitCode = (Integer) msg.obj;
                cleanupResources(exitCode);

                String exitDescription = "\r\n[Process completed";
                if (exitCode > 0) {
                    // Non-zero process exit.
                    exitDescription += " (code " + exitCode + ")";
                } else if (exitCode < 0) {
                    // Negated signal.
                    exitDescription += " (signal " + (-exitCode) + ")";
                }
                exitDescription += " - press Enter]";

                byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

        private void processAvailableInput(boolean drainAll, boolean forceBackgroundSlice) {
            final boolean paceWithVsync = !drainAll && !forceBackgroundSlice && mActivelyDisplayed;
            final long startNs = System.nanoTime();
            final long sliceBudgetNs;

            if (drainAll) {
                sliceBudgetNs = Long.MAX_VALUE;
            } else if (paceWithVsync) {
                if (startNs - mBudgetWindowStartNs >= FRAME_WINDOW_NS) {
                    mBudgetWindowStartNs = startNs;
                    mFrameBudgetRemainingNs = FRAME_PARSE_BUDGET_NS;
                }
                if (mFrameBudgetRemainingNs <= 0L) {
                    scheduleNextFrameDrain();
                    return;
                }
                sliceBudgetNs = mFrameBudgetRemainingNs;
            } else {
                sliceBudgetNs = BACKGROUND_SLICE_BUDGET_NS;
            }

            boolean appendedAny = false;
            boolean yieldedWithMoreData = false;

            while (true) {
                int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
                if (bytesRead <= 0) {
                    // Clear pending wakeup and double-check the queue to close the race window
                    // with TermSessionInputReader without ever dropping a wakeup.
                    mNewInputMessagePending.set(false);
                    bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
                    if (bytesRead <= 0) {
                        break;
                    }
                    mNewInputMessagePending.set(true);
                }
                mEmulator.append(mReceiveBuffer, bytesRead);
                appendedAny = true;

                if (!drainAll && (System.nanoTime() - startNs) >= sliceBudgetNs) {
                    yieldedWithMoreData = true;
                    break;
                }
            }

            if (paceWithVsync && appendedAny) {
                long totalElapsed = System.nanoTime() - startNs;
                mFrameBudgetRemainingNs = Math.max(0L, mFrameBudgetRemainingNs - totalElapsed);
            }

            if (yieldedWithMoreData) {
                mNewInputMessagePending.set(true);
                if (paceWithVsync) {
                    scheduleNextFrameDrain();
                } else {
                    enqueueNewInputWakeup();
                }
            }

            mEmulator.consumeSynchronizedFrameCompleted();
            if (mEmulator.isSynchronizedOutputActive()) {
                if (appendedAny) {
                    mScreenUpdateDeferredBySync = true;
                }
                if (!mSyncTimeoutScheduled) {
                    mSyncTimeoutScheduled = true;
                    sendAsyncDelayed(MSG_SYNC_OUTPUT_TIMEOUT, SYNC_OUTPUT_TIMEOUT_MS);
                }
            } else {
                if (mSyncTimeoutScheduled) {
                    mSyncTimeoutScheduled = false;
                    removeMessages(MSG_SYNC_OUTPUT_TIMEOUT);
                }
                if (appendedAny || mScreenUpdateDeferredBySync) {
                    mScreenUpdateDeferredBySync = false;
                    notifyScreenUpdate();
                }
            }
        }

        private void scheduleNextFrameDrain() {
            if (mFrameCallbackScheduled) return;
            try {
                Choreographer.getInstance().postFrameCallback(mFrameCallback);
                mFrameCallbackScheduled = true;
                if (!mFallbackScheduled) {
                    mFallbackScheduled = true;
                    sendAsyncDelayed(MSG_DRAIN_FALLBACK, VSYNC_FALLBACK_TIMEOUT_MS);
                }
            } catch (RuntimeException e) {
                // Fallback for headless test environments without a Choreographer.
                sendAsyncDelayed(MSG_NEW_INPUT, 8L);
            }
        }

        private void cancelFrameDrainCallbacks() {
            if (mFrameCallbackScheduled) {
                mFrameCallbackScheduled = false;
                try {
                    Choreographer.getInstance().removeFrameCallback(mFrameCallback);
                } catch (RuntimeException ignored) {
                }
            }
            if (mFallbackScheduled) {
                mFallbackScheduled = false;
                removeMessages(MSG_DRAIN_FALLBACK);
            }
        }

        private void cancelScheduledCallbacks() {
            cancelFrameDrainCallbacks();
            if (mSyncTimeoutScheduled) {
                mSyncTimeoutScheduled = false;
                removeMessages(MSG_SYNC_OUTPUT_TIMEOUT);
            }
            removeMessages(MSG_NEW_INPUT);
        }

    }

}
