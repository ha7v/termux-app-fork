package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RecordingCanvas;
import android.os.Build;

import java.util.Properties;

/**
 * A terminal bitmap for inline Sixel and {@code OSC 1337} images.
 */
public final class TerminalBitmap {

    public static final String LOG_TAG = "TerminalBitmap";

    /** Callback used while laying out multi-row bitmaps so scrolling honors emulator margins and scroll counters. */
    public interface ScrollHandler {
        void scrollDownOneLine();
    }

    /**
     * Maximum width or height of an Android {@code Canvas}/{@code SkImageShader} bitmap (`32766`).
     *
     * @see <a href="https://cs.android.com/android/platform/superproject/+/android-16.0.0_r1:frameworks/base/graphics/java/android/graphics/Canvas.java;l=76-78">Canvas.MAXIMUM_BITMAP_SIZE</a>
     */
    public static final int MAX_BITMAP_DIMENSION = 32766;

    /**
     * The max size of a Terminal {@link Bitmap} for its pixels, synced with
     * {@link RecordingCanvas}{@code .MAX_BITMAP_SIZE} (`100-200MB` depending on device and Android version).
     */
    public static final int MAX_BITMAP_SIZE = initMaxBitmapSize();

    private static int initMaxBitmapSize() {
        int defaultSize = 100 * 1024 * 1024; // 100 MB
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                defaultSize = 150 * 1024 * 1024; // 150 MB
            }
            Properties systemProperties = AndroidUtils.getSystemProperties(LOG_TAG);
            String maxTextureSizeString = systemProperties.getProperty("ro.hwui.max_texture_allocation_size");
            if (maxTextureSizeString == null) return defaultSize;
            int maxTextureSize = Integer.parseInt(maxTextureSizeString);
            return maxTextureSize > 0 ? maxTextureSize : defaultSize;
        } catch (Throwable ignored) {
            return defaultSize;
        }
    }

    /**
     * True when native Android {@link Bitmap} allocation is backed by a working graphics runtime;
     * false on headless JVM unit test stubs where geometry and cell layout still run end-to-end.
     */
    static final boolean BITMAP_RUNTIME_AVAILABLE = detectBitmapRuntime();

    private static boolean detectBitmapRuntime() {
        try {
            Bitmap probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            return probe != null && probe.getWidth() == 1 && probe.getHeight() == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Return whether {@code width x height} fits within Android Canvas and texture memory bounds. */
    public static boolean isValidBitmapSize(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_BITMAP_DIMENSION || height > MAX_BITMAP_DIMENSION) {
            return false;
        }
        return (long) width * height * 4L <= MAX_BITMAP_SIZE;
    }

    private final int mBitmapNum;
    private final Bitmap mBitmap;

    private final int mWidth;
    private final int mHeight;
    private final int mCellWidth;
    private final int mCellHeight;
    private final int mCellColumns;

    private final int mLastRow;
    private final long mByteCount;
    private final boolean mOverwroteBitmap;

    private TerminalBitmap(int bitmapNum, Bitmap bitmap,
                           int width, int height, int cellWidth, int cellHeight,
                           int cellColumns, int lastRow,
                           long byteCount, boolean overwroteBitmap) {
        mBitmapNum = bitmapNum;
        mBitmap = bitmap;

        mWidth = width;
        mHeight = height;
        mCellWidth = cellWidth;
        mCellHeight = cellHeight;
        mCellColumns = cellColumns;

        mLastRow = lastRow;
        mByteCount = byteCount;
        mOverwroteBitmap = overwroteBitmap;
    }

    /** Build a {@link TerminalBitmap} from a {@link TerminalSixel} with scroll callback and margins. */
    public static TerminalBitmap build(TerminalBuffer terminalBuffer, int bitmapNum, TerminalSixel terminalSixel,
                                       int x, int y, int cellWidth, int cellHeight, boolean scrollEnabled,
                                       ScrollHandler scrollHandler, int bottomMargin, int rightMargin) {
        try {
            if (terminalSixel == null || cellWidth <= 0 || cellHeight <= 0
                || x < 0 || x >= terminalBuffer.mColumns || y < 0 || y >= terminalBuffer.mScreenRows) {
                return null;
            }
            int sixelWidth = terminalSixel.getWidth();
            int sixelHeight = terminalSixel.getHeight();
            if (sixelWidth <= 0 || sixelHeight <= 0) {
                return null;
            }

            int effectiveRightMargin = (rightMargin > x && rightMargin <= terminalBuffer.mColumns)
                ? rightMargin : terminalBuffer.mColumns;
            long maxCols = Math.min(effectiveRightMargin - x, TextStyle.MAX_TERMINAL_BITMAP_CELLS);
            long maxWidth = Math.min((long) MAX_BITMAP_DIMENSION, maxCols * (long) cellWidth);
            int contentWidth = (int) Math.min(sixelWidth, maxWidth);

            long maxHeight = Math.min((long) MAX_BITMAP_DIMENSION,
                (long) TextStyle.MAX_TERMINAL_BITMAP_CELLS * cellHeight);
            if (!scrollEnabled) {
                maxHeight = Math.min(maxHeight, (long) (terminalBuffer.mScreenRows - y) * cellHeight);
            }
            int contentHeight = (int) Math.min(sixelHeight, maxHeight);

            if (!isValidBitmapSize(contentWidth, contentHeight)) {
                Logger.logError(terminalBuffer.getClient(), LOG_TAG,
                    "Create terminal bitmap " + bitmapNum + " from terminal sixel failed:" +
                        " Invalid content dimensions " + contentWidth + "x" + contentHeight);
                return null;
            }

            Bitmap bitmap = BITMAP_RUNTIME_AVAILABLE
                ? terminalSixel.createBitmap(contentWidth, contentHeight) : null;
            if (BITMAP_RUNTIME_AVAILABLE && bitmap == null) {
                Logger.logError(terminalBuffer.getClient(), LOG_TAG,
                    "Create terminal bitmap " + bitmapNum + " from terminal sixel failed");
                return null;
            }

            return layoutBitmapOnScreen(terminalBuffer, bitmapNum, bitmap,
                x, y, cellWidth, cellHeight, contentWidth, contentHeight,
                scrollEnabled, scrollHandler, bottomMargin, effectiveRightMargin);
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            Logger.logError(terminalBuffer.getClient(), LOG_TAG,
                "Create terminal bitmap " + bitmapNum + " from terminal sixel failed: " + t.getMessage());
            return null;
        }
    }

    /** Build a {@link TerminalBitmap} from an image `byte[]` with scroll callback and margins. */
    public static TerminalBitmap build(TerminalBuffer terminalBuffer, int bitmapNum, byte[] image,
                                       int x, int y, int cellWidth, int cellHeight,
                                       int width, int height, boolean shouldPreserveAspectRatio,
                                       ScrollHandler scrollHandler, int bottomMargin, int rightMargin) {
        try {
            if (cellWidth <= 0 || cellHeight <= 0
                || x < 0 || x >= terminalBuffer.mColumns || y < 0 || y >= terminalBuffer.mScreenRows) {
                return null;
            }

            if (image == null) {
                Logger.logError(terminalBuffer.getClient(), LOG_TAG,
                    "Create terminal bitmap " + bitmapNum + " from image byte array failed: Image data not set");
                return null;
            }

            int effectiveRightMargin = (rightMargin > x && rightMargin <= terminalBuffer.mColumns)
                ? rightMargin : terminalBuffer.mColumns;
            int maxCols = Math.min(effectiveRightMargin - x, TextStyle.MAX_TERMINAL_BITMAP_CELLS);
            int maxWidth = (int) Math.min((long) MAX_BITMAP_DIMENSION, (long) maxCols * cellWidth);
            int maxHeight = (int) Math.min((long) MAX_BITMAP_DIMENSION,
                (long) TextStyle.MAX_TERMINAL_BITMAP_CELLS * cellHeight);

            // Always inspect bounds first before allocating decoded pixel memory.
            BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
            boundsOptions.inJustDecodeBounds = true;
            try {
                BitmapFactory.decodeByteArray(image, 0, image.length, boundsOptions);
            } catch (Throwable t) {
                if (t instanceof OutOfMemoryError) System.gc();
                Logger.logWarn(terminalBuffer.getClient(), LOG_TAG,
                    "Decode bitmap bounds failed while creating terminal bitmap " + bitmapNum +
                        " from image byte array: " + t.getMessage());
            }

            int imageWidth = boundsOptions.outWidth;
            int imageHeight = boundsOptions.outHeight;
            if (imageWidth <= 0 || imageHeight <= 0) {
                Logger.logError(terminalBuffer.getClient(), LOG_TAG,
                    "Create terminal bitmap " + bitmapNum + " from image byte array failed:" +
                        " Invalid image dimensions " + imageWidth + "x" + imageHeight);
                return null;
            }

            int newWidth;
            int newHeight;
            if (width <= 0 && height <= 0) {
                newWidth = imageWidth;
                newHeight = imageHeight;
            } else if (shouldPreserveAspectRatio) {
                double wFactor = width > 0 ? (double) width / imageWidth : Double.MAX_VALUE;
                double hFactor = height > 0 ? (double) height / imageHeight : Double.MAX_VALUE;
                double factor = Math.min(wFactor, hFactor);
                newWidth = (int) Math.min(MAX_BITMAP_DIMENSION, Math.max(1L, Math.round(factor * imageWidth)));
                newHeight = (int) Math.min(MAX_BITMAP_DIMENSION, Math.max(1L, Math.round(factor * imageHeight)));
            } else {
                newWidth = width > 0 ? width : imageWidth;
                newHeight = height > 0 ? height : imageHeight;
            }

            int targetCroppedWidth = Math.min(newWidth, maxWidth);
            int targetHeight = Math.min(newHeight, maxHeight);
            if (!isValidBitmapSize(targetCroppedWidth, targetHeight)) {
                Logger.logError(terminalBuffer.getClient(), LOG_TAG,
                    "Create terminal bitmap " + bitmapNum + " from image byte array failed:" +
                        " Target dimensions " + targetCroppedWidth + "x" + targetHeight +
                        " exceed max bitmap size " + MAX_BITMAP_SIZE);
                return null;
            }

            int scaleFactor = 1;
            while ((long) imageHeight >= 2L * newHeight * scaleFactor
                && (long) imageWidth >= 2L * newWidth * scaleFactor) {
                scaleFactor *= 2;
            }
            while (!isValidBitmapSize(Math.max(1, imageWidth / scaleFactor), Math.max(1, imageHeight / scaleFactor))
                && scaleFactor < (1 << 15)) {
                scaleFactor *= 2;
            }

            Bitmap newBitmap;
            try {
                if (scaleFactor > 1) {
                    BitmapFactory.Options scaleOptions = new BitmapFactory.Options();
                    scaleOptions.inSampleSize = scaleFactor;
                    newBitmap = BitmapFactory.decodeByteArray(image, 0, image.length, scaleOptions);
                } else {
                    newBitmap = BitmapFactory.decodeByteArray(image, 0, image.length);
                }
            } catch (Throwable t) {
                if (t instanceof OutOfMemoryError) System.gc();
                Logger.logError(terminalBuffer.getClient(), LOG_TAG,
                    "Create terminal bitmap " + bitmapNum + " from image byte array failed:" +
                        " Decode scaled bitmap for scale factor " + scaleFactor + " failed: " + t.getMessage());
                return null;
            }
            if (newBitmap == null) {
                Logger.logError(terminalBuffer.getClient(), LOG_TAG,
                    "Create terminal bitmap " + bitmapNum + " from image byte array failed:" +
                        " Decoded bitmap not set for scale factor " + scaleFactor);
                return null;
            }

            // Crop horizontally before scaling if the requested width exceeds terminal bounds.
            if (newWidth > maxWidth) {
                int cropWidth = (int) Math.max(1L, ((long) newBitmap.getWidth() * maxWidth) / newWidth);
                try {
                    newBitmap = Bitmap.createBitmap(newBitmap, 0, 0,
                        Math.min(cropWidth, newBitmap.getWidth()), newBitmap.getHeight());
                    newWidth = maxWidth;
                } catch (Throwable t) {
                    if (t instanceof OutOfMemoryError) System.gc();
                    Logger.logError(terminalBuffer.getClient(), LOG_TAG,
                        "Create terminal bitmap " + bitmapNum + " from image byte array failed:" +
                            " Horizontal crop failed: " + t.getMessage());
                    return null;
                }
            }

            if (newHeight > maxHeight) {
                int cropHeight = (int) Math.max(1L, ((long) newBitmap.getHeight() * maxHeight) / newHeight);
                try {
                    newBitmap = Bitmap.createBitmap(newBitmap, 0, 0,
                        newBitmap.getWidth(), Math.min(cropHeight, newBitmap.getHeight()));
                    newHeight = maxHeight;
                } catch (Throwable t) {
                    if (t instanceof OutOfMemoryError) System.gc();
                    Logger.logError(terminalBuffer.getClient(), LOG_TAG,
                        "Create terminal bitmap " + bitmapNum + " from image byte array failed:" +
                            " Vertical crop failed: " + t.getMessage());
                    return null;
                }
            }

            if (newBitmap.getWidth() != newWidth || newBitmap.getHeight() != newHeight) {
                if (!isValidBitmapSize(newWidth, newHeight)) {
                    Logger.logError(terminalBuffer.getClient(), LOG_TAG,
                        "Create terminal bitmap " + bitmapNum + " from image byte array failed:" +
                            " Scaled dimensions " + newWidth + "x" + newHeight + " exceed max bitmap size");
                    return null;
                }
                try {
                    newBitmap = Bitmap.createScaledBitmap(newBitmap, newWidth, newHeight, true);
                } catch (Throwable t) {
                    if (t instanceof OutOfMemoryError) System.gc();
                    Logger.logError(terminalBuffer.getClient(), LOG_TAG,
                        "Create terminal bitmap " + bitmapNum + " from image byte array failed:" +
                            " Create scaled bitmap failed: " + t.getMessage());
                    return null;
                }
            }

            if (newBitmap == null) {
                Logger.logError(terminalBuffer.getClient(), LOG_TAG,
                    "Create terminal bitmap " + bitmapNum + " from image byte array failed: New bitmap not set");
                return null;
            }

            return layoutBitmapOnScreen(terminalBuffer, bitmapNum, newBitmap,
                x, y, cellWidth, cellHeight, newBitmap.getWidth(), newBitmap.getHeight(),
                true, scrollHandler, bottomMargin, effectiveRightMargin);
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) System.gc();
            Logger.logError(terminalBuffer.getClient(), LOG_TAG,
                "Create terminal bitmap " + bitmapNum + " from image byte array failed: " + t.getMessage());
            return null;
        }
    }

    /** Lay out a validated {@link Bitmap} onto {@link TerminalBuffer} cell slices, scrolling via {@code scrollHandler} as needed. */
    private static TerminalBitmap layoutBitmapOnScreen(TerminalBuffer terminalBuffer, int bitmapNum, Bitmap bitmap,
                                                       int x, int y, int cellWidth, int cellHeight,
                                                       int contentWidth, int contentHeight, boolean scrollEnabled,
                                                       ScrollHandler scrollHandler, int bottomMargin, int rightMargin) {
        if (BITMAP_RUNTIME_AVAILABLE && bitmap == null) {
            throw new IllegalArgumentException("Cannot create terminal bitmap from an unset bitmap");
        }
        if (cellWidth <= 0 || cellHeight <= 0 || contentWidth <= 0 || contentHeight <= 0
            || x < 0 || x >= terminalBuffer.mColumns || y < 0 || y >= terminalBuffer.mScreenRows) {
            return null;
        }

        int bitmapWidth = contentWidth;
        int bitmapHeight = contentHeight;
        if (bitmap != null) {
            if (bitmap.getWidth() > MAX_BITMAP_DIMENSION || bitmap.getHeight() > MAX_BITMAP_DIMENSION
                || bitmap.getAllocationByteCount() > MAX_BITMAP_SIZE) {
                Logger.logError(terminalBuffer.getClient(), LOG_TAG,
                    "Cannot create terminal bitmap " + bitmapNum + ": bitmap dimensions " +
                        bitmap.getWidth() + "x" + bitmap.getHeight() + " (" +
                        bitmap.getAllocationByteCount() + " bytes) exceed max bitmap size " + MAX_BITMAP_SIZE);
                return null;
            }
            bitmapWidth = Math.min(contentWidth, bitmap.getWidth());
            bitmapHeight = Math.min(contentHeight, bitmap.getHeight());
        }

        if (!isValidBitmapSize(bitmapWidth, bitmapHeight)) {
            return null;
        }

        int effectiveRightMargin = (rightMargin > x && rightMargin <= terminalBuffer.mColumns)
            ? rightMargin : terminalBuffer.mColumns;
        int effectiveBottomMargin = (bottomMargin > 0 && bottomMargin <= terminalBuffer.mScreenRows)
            ? bottomMargin : terminalBuffer.mScreenRows;

        int maxCols = Math.min(effectiveRightMargin - x, TextStyle.MAX_TERMINAL_BITMAP_CELLS);
        int cellCols = Math.min(maxCols, (bitmapWidth + cellWidth - 1) / cellWidth);
        int cellRows = Math.min(TextStyle.MAX_TERMINAL_BITMAP_CELLS, (bitmapHeight + cellHeight - 1) / cellHeight);
        if (cellCols <= 0 || cellRows <= 0) {
            return null;
        }

        int curRow = y;
        int lastRow = y;
        int drawnRows = 0;
        boolean overwroteBitmap = false;
        boolean insideScrollRegion = y < effectiveBottomMargin;
        int rowLimit = insideScrollRegion ? effectiveBottomMargin : terminalBuffer.mScreenRows;

        for (int i = 0; i < cellRows; i++) {
            if (i > 0) {
                if (curRow + 1 < rowLimit) {
                    curRow++;
                } else if (scrollEnabled && insideScrollRegion) {
                    if (scrollHandler != null) {
                        scrollHandler.scrollDownOneLine();
                    } else {
                        terminalBuffer.scrollDownOneLine(0, effectiveBottomMargin, TextStyle.NORMAL);
                    }
                } else {
                    break;
                }
            }

            TerminalRow targetLine = terminalBuffer.allocateFullLineIfNecessary(
                terminalBuffer.externalToInternalRow(curRow));
            if (targetLine.mHasTerminalBitmap) {
                overwroteBitmap = true;
            }
            for (int j = 0; j < cellCols; j++) {
                terminalBuffer.setChar(x + j, curRow, '+', TextStyle.encodeTerminalBitmap(bitmapNum, j, i));
            }
            lastRow = curRow;
            drawnRows++;
        }

        int clampedWidth = (int) Math.min((long) bitmapWidth, (long) cellCols * cellWidth);
        int clampedHeight = (int) Math.min((long) bitmapHeight, (long) drawnRows * cellHeight);
        long byteCount = bitmap != null
            ? bitmap.getAllocationByteCount()
            : (long) clampedWidth * clampedHeight * 4L;

        return new TerminalBitmap(bitmapNum, bitmap,
            clampedWidth, clampedHeight, cellWidth, cellHeight, cellCols,
            lastRow, byteCount, overwroteBitmap);
    }

    public int getBitmapNum() {
        return mBitmapNum;
    }

    public Bitmap getBitmap() {
        return mBitmap;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public int getCellWidth() {
        return mCellWidth;
    }

    public int getCellHeight() {
        return mCellHeight;
    }

    public int getCellColumns() {
        return mCellColumns;
    }

    public int getLastRow() {
        return mLastRow;
    }

    public long getByteCount() {
        return mByteCount;
    }

    public boolean overwroteBitmap() {
        return mOverwroteBitmap;
    }

}
