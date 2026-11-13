package com.termux.terminal;

import java.util.Arrays;

/**
 * A row in a terminal, composed of a fixed number of cells.
 * <p>
 * The text in the row is stored in a char[] array, {@link #mText}, for quick access during rendering.
 */
public final class TerminalRow {

    private static final float SPARE_CAPACITY_FACTOR = 1.5f;

    /**
     * Max combining characters that can exist in a column, that are separate from the base character
     * itself. Any additional combining characters will be ignored and not added to the column.
     *
     * There does not seem to be limit in unicode standard for max number of combination characters
     * that can be combined but such characters are primarily under 10.
     *
     * "Section 3.6 Combination" of unicode standard contains combining characters info.
     * - https://www.unicode.org/versions/Unicode15.0.0/ch03.pdf
     * - https://en.wikipedia.org/wiki/Combining_character#Unicode_ranges
     * - https://stackoverflow.com/questions/71237212/what-is-the-maximum-number-of-unicode-combined-characters-that-may-be-needed-to
     *
     * UAX15-D3 Stream-Safe Text Format limits to max 30 combining characters.
     * > The value of 30 is chosen to be significantly beyond what is required for any linguistic or technical usage.
     * > While it would have been feasible to chose a smaller number, this value provides a very wide margin,
     * > yet is well within the buffer size limits of practical implementations.
     * - https://unicode.org/reports/tr15/#Stream_Safe_Text_Format
     * - https://stackoverflow.com/a/11983435/14686958
     *
     * We choose the value 15 because it should be enough for terminal based applications and keep
     * the memory usage low for a terminal row, won't affect performance or cause terminal to
     * lag or hang, and will keep malicious applications from causing harm. The value can be
     * increased if ever needed for legitimate applications.
     */
    private static final int MAX_COMBINING_CHARACTERS_PER_COLUMN = 15;

    /** The number of columns in this terminal row. */
    private final int mColumns;
    /** The text filling this terminal row. */
    public char[] mText;
    /** The number of java chars used in {@link #mText}. */
    private int mSpaceUsed;
    /** If this row has been line wrapped due to text output at the end of line. */
    boolean mLineWrap;
    /**
     * If this row was line wrapped because a wide character did not fit in the last column, so that
     * the character was moved to the next row and the last column was left blank although nothing
     * was written to it. Such a cell is not part of the text and must not be copied as a space.
     */
    boolean mLineWrapBeforeWideChar;
    /** The style bits of each cell in the row. See {@link TextStyle}. */
    final long[] mStyle;
    /** If this row might contain chars with width != 1, used for deactivating fast path */
    boolean mHasNonOneWidthOrSurrogateChars;
    /** If this row has a {@link TerminalBitmap}. Used for performance only. */
    public boolean mHasTerminalBitmap;

    /**
     * Cached cell-boundary cursor {@code (mCachedColumn -> mCachedCharIndex)} for {@code O(1)}
     * sequential column lookups on rows containing wide, surrogate, or combining characters.
     * Invariant: {@code mCachedColumn} is always the start column of a character (never the second
     * column of a wide character) and {@code mCachedCharIndex} is its start index in {@link #mText}.
     */
    private int mCachedColumn;
    private int mCachedCharIndex;

    /** Construct a blank row (containing only whitespace, ' ') with a specified style. */
    public TerminalRow(int columns, long style) {
        mColumns = columns;
        mText = new char[(int) (SPARE_CAPACITY_FACTOR * columns)];
        mStyle = new long[columns];
        clear(style);
    }

    /** NOTE: The sourceX2 is exclusive. */
    public void copyInterval(TerminalRow line, int sourceX1, int sourceX2, int destinationX) {
        mHasNonOneWidthOrSurrogateChars |= line.mHasNonOneWidthOrSurrogateChars;
        final int x1 = line.findStartOfColumn(sourceX1);
        final int x2 = line.findStartOfColumn(sourceX2);
        boolean startingFromSecondHalfOfWideChar = (sourceX1 > 0 && line.wideDisplayCharacterStartingAt(sourceX1 - 1));
        final char[] sourceChars = (this == line) ? Arrays.copyOf(line.mText, line.mText.length) : line.mText;
        int latestNonCombiningWidth = 0;
        for (int i = x1; i < x2; i++) {
            char sourceChar = sourceChars[i];
            int codePoint = Character.isHighSurrogate(sourceChar) ? Character.toCodePoint(sourceChar, sourceChars[++i]) : sourceChar;
            if (startingFromSecondHalfOfWideChar) {
                // Just treat copying second half of wide char as copying whitespace.
                codePoint = ' ';
                startingFromSecondHalfOfWideChar = false;
            }
            int w = WcWidth.width(codePoint);
            if (w > 0) {
                destinationX += latestNonCombiningWidth;
                sourceX1 += latestNonCombiningWidth;
                latestNonCombiningWidth = w;
            }
            setChar(destinationX, codePoint, line.getStyle(sourceX1));
        }
    }

    public int getSpaceUsed() {
        return mSpaceUsed;
    }

    /** Note that the column may end of second half of wide character. */
    public int findStartOfColumn(int column) {
        if (column == 0) return 0;
        if (column == mColumns) return getSpaceUsed();
        if (!mHasNonOneWidthOrSurrogateChars) return column;
        if (column == mCachedColumn) return mCachedCharIndex;

        int currentColumn = 0;
        int currentCharIndex = 0;
        if (column > mCachedColumn) {
            currentColumn = mCachedColumn;
            currentCharIndex = mCachedCharIndex;
        }

        final char[] text = mText;
        final int spaceUsed = mSpaceUsed;
        while (true) {
            int newCharIndex = currentCharIndex;
            char c = text[newCharIndex++];
            boolean isHigh = Character.isHighSurrogate(c);
            int codePoint = isHigh ? Character.toCodePoint(c, text[newCharIndex++]) : c;
            int wcwidth = WcWidth.width(codePoint);
            if (wcwidth > 0) {
                currentColumn += wcwidth;
                if (currentColumn == column) {
                    newCharIndex = skipTrailingCombiningChars(text, newCharIndex, spaceUsed);
                    mCachedColumn = column;
                    mCachedCharIndex = newCharIndex;
                    return newCharIndex;
                } else if (currentColumn > column) {
                    // Wide column straddling target column; cache the wide char's start column.
                    mCachedColumn = currentColumn - wcwidth;
                    mCachedCharIndex = currentCharIndex;
                    return currentCharIndex;
                }
            }
            currentCharIndex = newCharIndex;
        }
    }

    private static int skipTrailingCombiningChars(char[] text, int charIndex, int spaceUsed) {
        while (charIndex < spaceUsed) {
            char c = text[charIndex];
            if (Character.isHighSurrogate(c)) {
                if (WcWidth.width(Character.toCodePoint(c, text[charIndex + 1])) <= 0) {
                    charIndex += 2;
                } else {
                    break;
                }
            } else if (WcWidth.width(c) <= 0) {
                charIndex++;
            } else {
                break;
            }
        }
        return charIndex;
    }

    private boolean wideDisplayCharacterStartingAt(int column) {
        if (!mHasNonOneWidthOrSurrogateChars || column < 0 || column >= mColumns - 1) {
            return false;
        }
        if (column == mCachedColumn) {
            return mCachedCharIndex < mSpaceUsed && WcWidth.width(mText, mCachedCharIndex) == 2;
        }

        int currentColumn = 0;
        int currentCharIndex = 0;
        if (column > mCachedColumn) {
            currentColumn = mCachedColumn;
            currentCharIndex = mCachedCharIndex;
        }

        final char[] text = mText;
        final int spaceUsed = mSpaceUsed;
        while (currentCharIndex < spaceUsed) {
            int startIndex = currentCharIndex;
            char c = text[currentCharIndex++];
            int codePoint = Character.isHighSurrogate(c) ? Character.toCodePoint(c, text[currentCharIndex++]) : c;
            int wcwidth = WcWidth.width(codePoint);
            if (wcwidth > 0) {
                if (currentColumn == column) {
                    mCachedColumn = column;
                    mCachedCharIndex = startIndex;
                    return wcwidth == 2;
                }
                currentColumn += wcwidth;
                if (currentColumn > column) {
                    mCachedColumn = currentColumn - wcwidth;
                    mCachedCharIndex = startIndex;
                    return false;
                }
            }
        }
        return false;
    }

    public void clear(long style) {
        Arrays.fill(mText, ' ');
        Arrays.fill(mStyle, style);
        mSpaceUsed = mColumns;
        mHasNonOneWidthOrSurrogateChars = false;
        mHasTerminalBitmap = false;
        mLineWrapBeforeWideChar = false;
        mCachedColumn = 0;
        mCachedCharIndex = 0;
    }

    // https://github.com/steven676/Android-Terminal-Emulator/commit/9a47042620bec87617f0b4f5d50568535668fe26
    public void setChar(int columnToSet, int codePoint, long style) {
        if (columnToSet  < 0 || columnToSet >= mStyle.length)
            throw new IllegalArgumentException("TerminalRow.setChar(): columnToSet=" + columnToSet + ", codePoint=" + codePoint + ", style=" + style);

        mStyle[columnToSet] = style;

        if (!mHasTerminalBitmap && TextStyle.isTerminalBitmap(style)) {
            mHasTerminalBitmap = true;
        }

        final int newCodePointDisplayWidth = WcWidth.width(codePoint);

        // Fast path when we don't have any chars with width != 1
        if (!mHasNonOneWidthOrSurrogateChars) {
            if (codePoint >= Character.MIN_SUPPLEMENTARY_CODE_POINT || newCodePointDisplayWidth != 1) {
                mHasNonOneWidthOrSurrogateChars = true;
                mCachedColumn = mCachedCharIndex = Math.max(0, columnToSet - 1);
            } else {
                mText[columnToSet] = (char) codePoint;
                return;
            }
        }

        final boolean newIsCombining = newCodePointDisplayWidth <= 0;

        boolean wasExtraColForWideChar = (columnToSet > 0) && wideDisplayCharacterStartingAt(columnToSet - 1);

        if (newIsCombining) {
            // When standing at second half of wide character and inserting combining:
            if (wasExtraColForWideChar) columnToSet--;
        } else {
            // Check if we are overwriting the second half of a wide character starting at the previous column:
            if (wasExtraColForWideChar) setChar(columnToSet - 1, ' ', style);
            // Check if we are overwriting the first half of a wide character starting at the next column:
            boolean overwritingWideCharInNextColumn = newCodePointDisplayWidth == 2 && wideDisplayCharacterStartingAt(columnToSet + 1);
            if (overwritingWideCharInNextColumn) setChar(columnToSet + 1, ' ', style);
        }

        char[] text = mText;
        final int oldStartOfColumnIndex = findStartOfColumn(columnToSet);
        final int oldCodePointDisplayWidth = WcWidth.width(text, oldStartOfColumnIndex);

        // Get the number of elements in the mText array this column uses now
        int oldCharactersUsedForColumn;
        if (columnToSet + oldCodePointDisplayWidth < mColumns) {
            int oldEndOfColumnIndex = findStartOfColumn(columnToSet + oldCodePointDisplayWidth);
            oldCharactersUsedForColumn = oldEndOfColumnIndex - oldStartOfColumnIndex;
        } else {
            // Last character.
            oldCharactersUsedForColumn = mSpaceUsed - oldStartOfColumnIndex;
        }

        // Anchor the cursor cache at columnToSet before mutating mText[oldStartOfColumnIndex..].
        // Keeping it at columnToSet (rather than advancing past it) also ensures the next sequential
        // setChar's wideDisplayCharacterStartingAt(nextCol - 1) hits the cache immediately.
        mCachedColumn = columnToSet;
        mCachedCharIndex = oldStartOfColumnIndex;

        // If MAX_COMBINING_CHARACTERS_PER_COLUMN already exist in column, then ignore adding additional combining characters.
        if (newIsCombining) {
            int combiningCharsCount = WcWidth.zeroWidthCharsCount(mText, oldStartOfColumnIndex, oldStartOfColumnIndex + oldCharactersUsedForColumn);
            if (combiningCharsCount >= MAX_COMBINING_CHARACTERS_PER_COLUMN)
                return;
        }

        // Find how many chars this column will need
        int newCharactersUsedForColumn = Character.charCount(codePoint);
        if (newIsCombining) {
            // Combining characters are added to the contents of the column instead of overwriting them, so that they
            // modify the existing contents.
            // FIXME: Unassigned characters also get width=0.
            newCharactersUsedForColumn += oldCharactersUsedForColumn;
        }

        int oldNextColumnIndex = oldStartOfColumnIndex + oldCharactersUsedForColumn;
        int newNextColumnIndex = oldStartOfColumnIndex + newCharactersUsedForColumn;

        final int javaCharDifference = newCharactersUsedForColumn - oldCharactersUsedForColumn;
        if (javaCharDifference > 0) {
            // Shift the rest of the line right.
            int oldCharactersAfterColumn = mSpaceUsed - oldNextColumnIndex;
            if (mSpaceUsed + javaCharDifference > text.length) {
                // We need to grow the array
                char[] newText = new char[text.length + mColumns];
                System.arraycopy(text, 0, newText, 0, oldNextColumnIndex);
                System.arraycopy(text, oldNextColumnIndex, newText, newNextColumnIndex, oldCharactersAfterColumn);
                mText = text = newText;
            } else {
                System.arraycopy(text, oldNextColumnIndex, text, newNextColumnIndex, oldCharactersAfterColumn);
            }
        } else if (javaCharDifference < 0) {
            // Shift the rest of the line left.
            System.arraycopy(text, oldNextColumnIndex, text, newNextColumnIndex, mSpaceUsed - oldNextColumnIndex);
        }
        mSpaceUsed += javaCharDifference;

        // Store char. A combining character is stored at the end of the existing contents so that it modifies them:
        //noinspection ResultOfMethodCallIgnored - since we already now how many java chars is used.
        Character.toChars(codePoint, text, oldStartOfColumnIndex + (newIsCombining ? oldCharactersUsedForColumn : 0));

        if (oldCodePointDisplayWidth == 2 && newCodePointDisplayWidth == 1) {
            // Replace second half of wide char with a space. Which mean that we actually add a ' ' java character.
            if (mSpaceUsed + 1 > text.length) {
                char[] newText = new char[text.length + mColumns];
                System.arraycopy(text, 0, newText, 0, newNextColumnIndex);
                System.arraycopy(text, newNextColumnIndex, newText, newNextColumnIndex + 1, mSpaceUsed - newNextColumnIndex);
                mText = text = newText;
            } else {
                System.arraycopy(text, newNextColumnIndex, text, newNextColumnIndex + 1, mSpaceUsed - newNextColumnIndex);
            }
            text[newNextColumnIndex] = ' ';

            ++mSpaceUsed;
        } else if (oldCodePointDisplayWidth == 1 && newCodePointDisplayWidth == 2) {
            if (columnToSet == mColumns - 1) {
                mCachedColumn = 0;
                mCachedCharIndex = 0;
                throw new IllegalArgumentException("Cannot put wide character in last column");
            } else if (columnToSet == mColumns - 2) {
                // Truncate the line to the second part of this wide char:
                mSpaceUsed = newNextColumnIndex;
            } else {
                // Overwrite the contents of the next column, which mean we actually remove java characters. Due to the
                // check at the beginning of this method we know that we are not overwriting a wide char.
                int newNextNextColumnIndex = skipTrailingCombiningChars(text,
                    newNextColumnIndex + (Character.isHighSurrogate(text[newNextColumnIndex]) ? 2 : 1), mSpaceUsed);
                int nextLen = newNextNextColumnIndex - newNextColumnIndex;

                // Shift the array leftwards.
                System.arraycopy(text, newNextNextColumnIndex, text, newNextColumnIndex, mSpaceUsed - newNextNextColumnIndex);
                mSpaceUsed -= nextLen;
            }
        }
    }

    boolean isBlank() {
        for (int charIndex = 0, charLen = getSpaceUsed(); charIndex < charLen; charIndex++)
            if (mText[charIndex] != ' ') return false;
        return true;
    }

    public final long getStyle(int column) {
        return mStyle[column];
    }

}
