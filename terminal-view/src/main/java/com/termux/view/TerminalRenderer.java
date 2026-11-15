package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;

import com.termux.terminal.TerminalBitmap;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();
    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect mBitmapSrcRect = new Rect();
    private final RectF mBitmapDstRect = new RectF();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];
    private final GlyphWidthCache mGlyphWidthCache = new GlyphWidthCache(1024);

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            final int selx1 = rowSelectionStartCol(row, selectionY1, selectionY2, selectionX1, selectionX2);
            final int selx2 = rowSelectionEndCol(row, columns, selectionY1, selectionY2, selectionX1, selectionX2);

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                final long style = lineObject.getStyle(column);
                if (TextStyle.isTerminalBitmap(style)) {
                    if (lastRunStartColumn >= 0 && column > lastRunStartColumn) {
                        flushTextRun(mEmulator, canvas, line, palette, heightOffset, reverseVideo, cursorShape,
                            lastRunStartColumn, column, lastRunStartIndex, currentCharIndex,
                            measuredWidthForRun, lastRunInsideCursor, lastRunInsideSelection, lastRunStyle);
                    }

                    int spanStartCol = column;
                    int firstSliceX = TextStyle.getTerminalBitmapX(style);
                    int sliceY = TextStyle.getTerminalBitmapY(style);
                    int spanCols = bitmapSpanColumns(lineObject, spanStartCol, columns);
                    for (int i = 0; i < spanCols; i++) {
                        currentCharIndex = advanceOneCellInText(line, currentCharIndex, charsUsedInLine);
                    }

                    drawBitmapSpan(screen, canvas, palette, style, spanStartCol, spanCols,
                        firstSliceX, sliceY, heightOffset, cursorX, cursorShape, selx1, selx2);

                    column += spanCols;
                    measuredWidthForRun = 0.f;
                    lastRunStyle = 0;
                    lastRunInsideCursor = false;
                    lastRunInsideSelection = false;
                    lastRunStartColumn = -1;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = false;
                    continue;
                }

                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final int codePointWcWidth = WcWidth.width(codePoint);
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                final float measuredCodePointWidth;
                if (codePoint < asciiMeasures.length) {
                    measuredCodePointWidth = asciiMeasures[codePoint];
                } else {
                    float cachedWidth = mGlyphWidthCache.get(codePoint);
                    if (cachedWidth < 0f) {
                        // Reset paint styling left over from any previous drawTextRun() so cached
                        // non-ASCII measurements use the same baseline paint state as asciiMeasures.
                        mTextPaint.setFakeBoldText(false);
                        mTextPaint.setTextSkewX(0f);
                        cachedWidth = mTextPaint.measureText(line, currentCharIndex, charsForCodePoint);
                        mGlyphWidthCache.put(codePoint, cachedWidth);
                    }
                    measuredCodePointWidth = cachedWidth;
                }
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                if (shouldStartNewTextRun(lastRunStartColumn, style, lastRunStyle,
                    insideCursor, lastRunInsideCursor, insideSelection, lastRunInsideSelection,
                    fontWidthMismatch, lastRunFontWidthMismatch)) {
                    if (lastRunStartColumn >= 0 && column > lastRunStartColumn) {
                        flushTextRun(mEmulator, canvas, line, palette, heightOffset, reverseVideo, cursorShape,
                            lastRunStartColumn, column, lastRunStartIndex, currentCharIndex,
                            measuredWidthForRun, lastRunInsideCursor, lastRunInsideSelection, lastRunStyle);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            if (lastRunStartColumn >= 0 && lastRunStartColumn < columns) {
                flushTextRun(mEmulator, canvas, line, palette, heightOffset, reverseVideo, cursorShape,
                    lastRunStartColumn, columns, lastRunStartIndex, currentCharIndex,
                    measuredWidthForRun, lastRunInsideCursor, lastRunInsideSelection, lastRunStyle);
            }
        }
    }

    private void flushTextRun(TerminalEmulator mEmulator, Canvas canvas, char[] line, int[] palette,
                              float heightOffset, boolean reverseVideo, int cursorShape,
                              int startColumn, int endColumn, int startIndex, int endIndex,
                              float measuredWidthForRun, boolean insideCursor, boolean insideSelection, long style) {
        final int columnWidthSinceLastRun = endColumn - startColumn;
        final int charsSinceLastRun = endIndex - startIndex;
        int cursorColor = insideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
        boolean invertCursorTextColor = insideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
        drawTextRun(canvas, line, palette, heightOffset, startColumn, columnWidthSinceLastRun,
            startIndex, charsSinceLastRun, measuredWidthForRun,
            cursorColor, cursorShape, style, reverseVideo || invertCursorTextColor || insideSelection);
    }

    static int rowSelectionStartCol(int row, int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        if (selectionX1 < 0 || selectionX2 < 0 || row < selectionY1 || row > selectionY2) return -1;
        return (row == selectionY1) ? selectionX1 : 0;
    }

    static int rowSelectionEndCol(int row, int columns, int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        if (selectionX1 < 0 || selectionX2 < 0 || row < selectionY1 || row > selectionY2) return -1;
        return (row == selectionY2) ? selectionX2 : columns;
    }

    static int bitmapSpanColumns(TerminalRow lineObject, int startCol, int columns) {
        long firstStyle = lineObject.getStyle(startCol);
        int bitmapNum = TextStyle.getTerminalBitmapNum(firstStyle);
        int firstSliceX = TextStyle.getTerminalBitmapX(firstStyle);
        int sliceY = TextStyle.getTerminalBitmapY(firstStyle);
        int spanCols = 1;
        while (startCol + spanCols < columns) {
            long nextStyle = lineObject.getStyle(startCol + spanCols);
            if (!TextStyle.isTerminalBitmap(nextStyle)
                || TextStyle.getTerminalBitmapNum(nextStyle) != bitmapNum
                || TextStyle.getTerminalBitmapY(nextStyle) != sliceY
                || TextStyle.getTerminalBitmapX(nextStyle) != firstSliceX + spanCols) {
                break;
            }
            spanCols++;
        }
        return spanCols;
    }

    static boolean shouldStartNewTextRun(int lastRunStartColumn, long style, long lastRunStyle,
                                         boolean insideCursor, boolean lastRunInsideCursor,
                                         boolean insideSelection, boolean lastRunInsideSelection,
                                         boolean fontWidthMismatch, boolean lastRunFontWidthMismatch) {
        return lastRunStartColumn < 0
            || style != lastRunStyle
            || insideCursor != lastRunInsideCursor
            || insideSelection != lastRunInsideSelection
            || fontWidthMismatch
            || lastRunFontWidthMismatch;
    }

    static int advanceOneCellInText(char[] line, int currentCharIndex, int charsUsedInLine) {
        if (currentCharIndex < charsUsedInLine) {
            currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
            while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
            }
        }
        return currentCharIndex;
    }

    private void drawBitmapSpan(TerminalBuffer screen, Canvas canvas, int[] palette, long style,
                                int startCol, int spanCols, int firstSliceX, int sliceY, float heightOffset,
                                int cursorX, int cursorShape, int selx1, int selx2) {
        TerminalBitmap terminalBitmap = screen.getTerminalBitmap(style);
        if (terminalBitmap != null) {
            Bitmap bitmap = terminalBitmap.getBitmap();
            int cellW = terminalBitmap.getCellWidth();
            int cellH = terminalBitmap.getCellHeight();
            if (bitmap != null && cellW > 0 && cellH > 0) {
                int srcLeft = firstSliceX * cellW;
                int srcTop = sliceY * cellH;
                int srcRight = Math.min(terminalBitmap.getWidth(), (firstSliceX + spanCols) * cellW);
                int srcBottom = Math.min(terminalBitmap.getHeight(), (sliceY + 1) * cellH);
                if (srcLeft < srcRight && srcTop < srcBottom) {
                    float left = startCol * mFontWidth;
                    float top = heightOffset - mFontLineSpacing;
                    float dstWidth = (srcRight - srcLeft) * (mFontWidth / cellW);
                    float dstHeight = (srcBottom - srcTop) * ((float) mFontLineSpacing / cellH);
                    mBitmapSrcRect.left = srcLeft;
                    mBitmapSrcRect.top = srcTop;
                    mBitmapSrcRect.right = srcRight;
                    mBitmapSrcRect.bottom = srcBottom;
                    mBitmapDstRect.left = left;
                    mBitmapDstRect.top = top;
                    mBitmapDstRect.right = left + dstWidth;
                    mBitmapDstRect.bottom = top + dstHeight;
                    canvas.drawBitmap(bitmap, mBitmapSrcRect, mBitmapDstRect, mBitmapPaint);
                }
            }
        }

        if (selx1 >= 0 && selx2 >= 0) {
            int selStart = Math.max(startCol, selx1);
            int selEnd = Math.min(startCol + spanCols - 1, selx2);
            if (selStart <= selEnd) {
                mTextPaint.setColor((palette[TextStyle.COLOR_INDEX_FOREGROUND] & 0x00ffffff) | 0x66000000);
                canvas.drawRect(selStart * mFontWidth, heightOffset - mFontLineSpacing,
                    (selEnd + 1) * mFontWidth, heightOffset, mTextPaint);
            }
        }

        if (cursorX >= startCol && cursorX < startCol + spanCols) {
            mTextPaint.setColor(palette[TextStyle.COLOR_INDEX_CURSOR]);
            float cursorLeft = cursorX * mFontWidth;
            float cursorRight = cursorLeft + mFontWidth;
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
                cursorHeight /= 4.f;
            } else if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
                cursorRight -= ((cursorRight - cursorLeft) * 3.f) / 4.f;
            }
            canvas.drawRect(cursorLeft, heightOffset - cursorHeight, cursorRight, heightOffset, mTextPaint);
        }
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.f;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= (((right - left) * 3) / 4.f);
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // The text alignment is the default Paint.Align.LEFT.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);
            } else {
                canvas.drawText(text, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, mTextPaint);
            }
        }

        if (savedMatrix) canvas.restore();
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
