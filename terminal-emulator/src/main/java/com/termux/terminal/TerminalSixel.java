package com.termux.terminal;

import android.graphics.Bitmap;

import java.util.Arrays;

/**
 * A terminal sixel received via `DCS q s..s ST` or `DCS P1; P2; P3; q s..s ST`.
 *
 * <p>Decodes Sixel bands into a pure-Java {@code int[]} pixel buffer to avoid per-pixel JNI
 * crossings and intermediate {@link Bitmap} reallocations, then materializes a single exact-sized
 * {@link Bitmap} once the sequence terminates.
 *
 * <p><b>See Also:</b>
 * <ul>
 *   <li>{@code TerminalEmulator.ESC_DCS__SIXEL}</li>
 *   <li><a href="https://vt100.net/docs/vt3xx-gp/chapter14.html">VT330/VT340 Sixel Graphics</a></li>
 *   <li><a href="https://en.wikipedia.org/wiki/Sixel">Sixel (Wikipedia)</a></li>
 * </ul>
 */
public final class TerminalSixel {

    public static final String LOG_TAG = "TerminalSixel";

    public static final int[] SIXEL__INITIAL_COLOR_MAP = {
        0xFF000000, 0xFF3333CC, 0xFFCC2323, 0xFF33CC33, 0xFFCC33CC, 0xFF33CCCC, 0xFFCCCC33, 0xFF777777,
        0xFF444444, 0xFF565699, 0xFF994444, 0xFF569956, 0xFF995699, 0xFF569999, 0xFF999956, 0xFFCCCCCC
    };

    /**
     * A sixel is a group of six pixels in a vertical column.
     */
    public static final int SIXEL__LINE_LEN = 6;

    /**
     * The amount of extra dimension added when resizing a sixel buffer when receiving more image data.
     */
    public static final int SIXEL__BITMAP_RESIZE_EXTRA_DIMENSION = 100;

    /**
     * The max value for the sixel Graphics Repeat Introducer.
     *
     * <p>The value {@code 8192} can support 8K images, see also {@link TerminalBitmap#MAX_BITMAP_SIZE}.
     */
    public static final int SIXEL__MAX_REPEAT = 8192;

    private final TerminalSessionClient mClient;

    private int[] mPixels;
    private int mBufferWidth;
    private int mBufferHeight;

    private int mWidth;
    private int mHeight;

    private int mCurX;
    private int mCurY;

    private final int[] mColorMap;
    private int mColor;

    private TerminalSixel(TerminalSessionClient client, int[] pixels, int bufferWidth, int bufferHeight) {
        mClient = client;

        mPixels = pixels;
        mBufferWidth = bufferWidth;
        mBufferHeight = bufferHeight;

        mWidth = 0;
        mHeight = 0;

        mCurX = 0;
        mCurY = 0;

        mColorMap = new int[256];
        System.arraycopy(SIXEL__INITIAL_COLOR_MAP, 0, mColorMap, 0, 16);
        mColor = mColorMap[0];
    }

    public static TerminalSixel build(TerminalSessionClient client, int bitmapWidth, int bitmapHeight) {
        if (!TerminalBitmap.isValidBitmapSize(bitmapWidth, bitmapHeight)) {
            Logger.logError(client, LOG_TAG, "Create sixel buffer for width " + bitmapWidth +
                " with height " + bitmapHeight + " failed: Invalid dimensions");
            return null;
        }
        try {
            int[] pixels = new int[bitmapWidth * bitmapHeight];
            return new TerminalSixel(client, pixels, bitmapWidth, bitmapHeight);
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            Logger.logError(client, LOG_TAG, "Create sixel buffer for width " + bitmapWidth +
                " with height " + bitmapHeight + " failed: " + t.getMessage());
            return null;
        }
    }

    /** Materialize a {@link Bitmap} cropped to {@code (width, height)} from the decoded pixel buffer. */
    public Bitmap createBitmap(int width, int height) {
        if (mPixels == null || !TerminalBitmap.isValidBitmapSize(width, height)) {
            return null;
        }
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            if (bitmap == null) {
                return null;
            }
            int copyWidth = Math.min(width, mBufferWidth);
            int copyHeight = Math.min(height, mBufferHeight);
            if (copyWidth < width || copyHeight < height) {
                bitmap.eraseColor(0);
            }
            if (copyWidth > 0 && copyHeight > 0) {
                bitmap.setPixels(mPixels, 0, mBufferWidth, 0, 0, copyWidth, copyHeight);
            }
            return bitmap;
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            Logger.logError(mClient, LOG_TAG, "Materialize sixel bitmap for width " + width +
                " with height " + height + " failed: " + t.getMessage());
            return null;
        }
    }

    public int getPixel(int x, int y) {
        if (mPixels == null || x < 0 || x >= mWidth || y < 0 || y >= mHeight
            || x >= mBufferWidth || y >= mBufferHeight) {
            return 0;
        }
        return mPixels[y * mBufferWidth + x];
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public int getCurY() {
        return mCurY;
    }

    public boolean readData(int codePoint, int repeat) {
        if (mPixels == null) {
            return false;
        }

        if (repeat < 0 || repeat > SIXEL__MAX_REPEAT) {
            Logger.logError(mClient, LOG_TAG, "The sixel repeat value " + repeat + " is invalid (max " + SIXEL__MAX_REPEAT + ")");
            return false;
        }

        // Graphics Carriage Return `$` (https://vt100.net/docs/vt3xx-gp/chapter14.html#S14.3.4).
        if (codePoint == '$') {
            mCurX = 0;
            return true;
        }

        // Graphics New Line `-` (https://vt100.net/docs/vt3xx-gp/chapter14.html#S14.3.5).
        if (codePoint == '-') {
            if (mCurY > TerminalBitmap.MAX_BITMAP_DIMENSION - SIXEL__LINE_LEN) {
                Logger.logError(mClient, LOG_TAG, "The sixel cursor y position overflowed max dimension: " + mCurY);
                return false;
            }
            mCurX = 0;
            mCurY += SIXEL__LINE_LEN;
            return true;
        }

        // Sixel data characters are in the range of `?` (0x3F) to `~` (0x7E).
        if (repeat == 0 || codePoint < '?' || codePoint > '~') {
            return true;
        }

        if ((long) mCurX + repeat > TerminalBitmap.MAX_BITMAP_DIMENSION) {
            Logger.logError(mClient, LOG_TAG, "The sixel cursor x position overflowed max dimension: " + mCurX + " + " + repeat);
            return false;
        }

        int endX = mCurX + repeat;
        int b = codePoint - '?';
        if (b == 0) {
            // '?' has no lit pixels; advance horizontal geometry without touching pixel memory.
            int candidateWidth = Math.max(mWidth, endX);
            if (mHeight > 0 && !TerminalBitmap.isValidBitmapSize(candidateWidth, mHeight)) {
                Logger.logError(mClient, LOG_TAG, "The sixel dimensions " + candidateWidth + "x" + mHeight +
                    " exceed max bitmap size " + TerminalBitmap.MAX_BITMAP_SIZE);
                return false;
            }
            mCurX = endX;
            mWidth = candidateWidth;
            return true;
        }

        // Track the exact lowest lit pixel row instead of rounding up to a 6px band,
        // so images ending mid-band do not spill into an extra terminal row.
        int highestBit = 31 - Integer.numberOfLeadingZeros(b & 0x3f);
        int lowestPixelY = mCurY + highestBit + 1;
        int requiredWidth = Math.max(mWidth, endX);
        int requiredHeight = Math.max(mHeight, lowestPixelY);

        if (!ensureCapacity(requiredWidth, requiredHeight, true)) {
            return false;
        }

        int color = mColor;
        int stride = mBufferWidth;
        int[] pixels = mPixels;
        for (int i = 0; i <= highestBit; i++) {
            if ((b & (1 << i)) != 0) {
                int rowStart = (mCurY + i) * stride + mCurX;
                if (repeat == 1) {
                    pixels[rowStart] = color;
                } else {
                    Arrays.fill(pixels, rowStart, rowStart + repeat, color);
                }
            }
        }

        mCurX = endX;
        mWidth = requiredWidth;
        mHeight = requiredHeight;
        return true;
    }

    public boolean resize(int sixelWidth, int sixelHeight) {
        if (mPixels == null || sixelWidth < 1 || sixelHeight < 1) {
            return false;
        }
        return ensureCapacity(sixelWidth, sixelHeight, false);
    }

    private boolean ensureCapacity(int minWidth, int minHeight, boolean growAmortized) {
        if (minWidth <= mBufferWidth && minHeight <= mBufferHeight) {
            return true;
        }
        if (!TerminalBitmap.isValidBitmapSize(minWidth, minHeight)) {
            Logger.logError(mClient, LOG_TAG, "The sixel buffer with width " + minWidth +
                " and height " + minHeight + " exceeds max bitmap size " + TerminalBitmap.MAX_BITMAP_SIZE);
            return false;
        }

        int newWidth = Math.max(mBufferWidth, minWidth);
        int newHeight = Math.max(mBufferHeight, minHeight);
        if (growAmortized) {
            if (mBufferWidth < minWidth) {
                long candidateW = Math.max((long) minWidth + SIXEL__BITMAP_RESIZE_EXTRA_DIMENSION, (long) mBufferWidth * 2L);
                newWidth = (int) Math.min(TerminalBitmap.MAX_BITMAP_DIMENSION, candidateW);
            }
            if (mBufferHeight < minHeight) {
                long candidateH = Math.max((long) minHeight + SIXEL__BITMAP_RESIZE_EXTRA_DIMENSION, (long) mBufferHeight * 2L);
                newHeight = (int) Math.min(TerminalBitmap.MAX_BITMAP_DIMENSION, candidateH);
            }
            if (!TerminalBitmap.isValidBitmapSize(newWidth, newHeight)) {
                newWidth = Math.max(mBufferWidth, minWidth);
                newHeight = Math.max(mBufferHeight, minHeight);
                if (!TerminalBitmap.isValidBitmapSize(newWidth, newHeight)) {
                    Logger.logError(mClient, LOG_TAG, "The sixel buffer with width " + newWidth +
                        " and height " + newHeight + " exceeds max bitmap size " + TerminalBitmap.MAX_BITMAP_SIZE);
                    return false;
                }
            }
        } else if (!TerminalBitmap.isValidBitmapSize(newWidth, newHeight)) {
            Logger.logError(mClient, LOG_TAG, "The sixel buffer with width " + newWidth +
                " and height " + newHeight + " exceeds max bitmap size " + TerminalBitmap.MAX_BITMAP_SIZE);
            return false;
        }

        try {
            int[] newPixels = new int[newWidth * newHeight];
            int copyRows = Math.min(mHeight, mBufferHeight);
            int copyCols = Math.min(mWidth, mBufferWidth);
            if (copyCols > 0) {
                for (int y = 0; y < copyRows; y++) {
                    System.arraycopy(mPixels, y * mBufferWidth, newPixels, y * newWidth, copyCols);
                }
            }
            mPixels = newPixels;
            mBufferWidth = newWidth;
            mBufferHeight = newHeight;
            return true;
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            Logger.logError(mClient, LOG_TAG, "Resize sixel buffer to width " + newWidth +
                " and height " + newHeight + " failed: " + t.getMessage());
            return false;
        }
    }

    public void setColor(int color) {
        if (color >= 0 && color < mColorMap.length) {
            mColor = mColorMap[color];
        }
    }

    public void setRGBColor(int color, int r, int g, int b) {
        if (color >= 0 && color < mColorMap.length) {
            int red = Math.max(0, Math.min(100, r)) * 255 / 100;
            int green = Math.max(0, Math.min(100, g)) * 255 / 100;
            int blue = Math.max(0, Math.min(100, b)) * 255 / 100;
            mColor = 0xff000000 | (red << 16) | (green << 8) | blue;
            mColorMap[color] = mColor;
        }
    }

}
