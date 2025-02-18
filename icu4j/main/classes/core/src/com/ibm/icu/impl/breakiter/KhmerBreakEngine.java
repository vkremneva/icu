// © 2016 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html
/*
 *******************************************************************************
 * Copyright (C) 2014, International Business Machines Corporation and         *
 * others. All Rights Reserved.                                                *
 *******************************************************************************
 */
package com.ibm.icu.impl.breakiter;

import java.io.IOException;
import java.text.CharacterIterator;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.lang.UScript;
import com.ibm.icu.text.UnicodeSet;

public class KhmerBreakEngine extends DictionaryBreakEngine {

    // Constants for KhmerBreakIterator
    // How many words in a row are "good enough"?
    private static final byte KHMER_LOOKAHEAD = 3;
    // Will not combine a non-word with a preceding dictionary word longer than this
    private static final byte KHMER_ROOT_COMBINE_THRESHOLD = 3;
    // Will not combine a non-word that shares at least this much prefix with a
    // dictionary word with a preceding word
    private static final byte KHMER_PREFIX_COMBINE_THRESHOLD = 3;
    // Minimum word size
    private static final byte KHMER_MIN_WORD = 2;
    // Minimum number of characters for two words
    private static final byte KHMER_MIN_WORD_SPAN = KHMER_MIN_WORD * 2;


    private DictionaryMatcher fDictionary;
    private UnicodeSet fEndWordSet;
    private UnicodeSet fBeginWordSet;
    private UnicodeSet fMarkSet;

    public KhmerBreakEngine() throws IOException {
        // Initialize UnicodeSets
        UnicodeSet khmerWordSet = new UnicodeSet("[[:Khmer:]&[:LineBreak=SA:]]");
        fMarkSet = new UnicodeSet("[[:Khmer:]&[:LineBreak=SA:]&[:M:]]");
        fMarkSet.add(0x0020);
        fBeginWordSet = new UnicodeSet(0x1780, 0x17B3);

        khmerWordSet.compact();

        fEndWordSet = new UnicodeSet(khmerWordSet);
        fEndWordSet.remove(0x17D2); // KHMER SIGN COENG that combines some following characters

        // Compact for caching
        fMarkSet.compact();
        fEndWordSet.compact();
        fBeginWordSet.compact();

        // Freeze the static UnicodeSet
        khmerWordSet.freeze();
        fMarkSet.freeze();
        fEndWordSet.freeze();
        fBeginWordSet.freeze();

        setCharacters(khmerWordSet);
        // Initialize dictionary
        fDictionary = DictionaryData.loadDictionaryFor("Khmr");
    }

    @Override
    public boolean equals(Object obj) {
        // Normally is a singleton, but it's possible to have duplicates
        //   during initialization. All are equivalent.
        return obj instanceof KhmerBreakEngine;
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public boolean handles(int c) {
        int script = UCharacter.getIntPropertyValue(c, UProperty.SCRIPT);
        return (script == UScript.KHMER);
    }

    @Override
    public int divideUpDictionaryRange(CharacterIterator fIter, int rangeStart, int rangeEnd,
            DequeI foundBreaks) {

        if ((rangeEnd - rangeStart) < KHMER_MIN_WORD_SPAN) {
            return 0;  // Not enough characters for word
        }
        int wordsFound = 0;
        int wordLength;
        int current;
        PossibleWord words[] = new PossibleWord[KHMER_LOOKAHEAD];
        for (int i = 0; i < KHMER_LOOKAHEAD; i++) {
            words[i] = new PossibleWord();
        }
        int uc;

        fIter.setIndex(rangeStart);

        while ((current = fIter.getIndex()) < rangeEnd) {
            wordLength = 0;

            //Look for candidate words at the current position
            int candidates = words[wordsFound % KHMER_LOOKAHEAD].candidates(fIter, fDictionary, rangeEnd);

            // If we found exactly one, use that
            if (candidates == 1) {
                wordLength = words[wordsFound % KHMER_LOOKAHEAD].acceptMarked(fIter);
                wordsFound += 1;
            }

            // If there was more than one, see which one can take us forward the most words
            else if (candidates > 1) {
                boolean foundBest = false;
                // If we're already at the end of the range, we're done
                if (fIter.getIndex() < rangeEnd) {
                    do {
                        if (words[(wordsFound+1)%KHMER_LOOKAHEAD].candidates(fIter, fDictionary, rangeEnd) > 0) {
                            // Followed by another dictionary word; mark first word as a good candidate
                            words[wordsFound%KHMER_LOOKAHEAD].markCurrent();

                            // If we're already at the end of the range, we're done
                            if (fIter.getIndex() >= rangeEnd) {
                                break;
                            }

                            // See if any of the possible second words is followed by a third word
                            do {
                                // If we find a third word, stop right away
                                if (words[(wordsFound+2)%KHMER_LOOKAHEAD].candidates(fIter, fDictionary, rangeEnd) > 0) {
                                    words[wordsFound%KHMER_LOOKAHEAD].markCurrent();
                                    foundBest = true;
                                    break;
                                }
                            } while (words[(wordsFound+1)%KHMER_LOOKAHEAD].backUp(fIter));
                        }
                    } while (words[wordsFound%KHMER_LOOKAHEAD].backUp(fIter) && !foundBest);
                }
                wordLength = words[wordsFound%KHMER_LOOKAHEAD].acceptMarked(fIter);
                wordsFound += 1;
            }

            // We come here after having either found a word or not. We look ahead to the
            // next word. If it's not a dictionary word, we will combine it with the word we
            // just found (if there is one), but only if the preceding word does not exceed
            // the threshold.
            // The text iterator should now be positioned at the end of the word we found.
            if (fIter.getIndex() < rangeEnd && wordLength < KHMER_ROOT_COMBINE_THRESHOLD) {
                // If it is a dictionary word, do nothing. If it isn't, then if there is
                // no preceding word, or the non-word shares less than the minimum threshold
                // of characters with a dictionary word, then scan to resynchronize
                if (words[wordsFound%KHMER_LOOKAHEAD].candidates(fIter, fDictionary, rangeEnd) <= 0 &&
                        (wordLength == 0 ||
                                words[wordsFound%KHMER_LOOKAHEAD].longestPrefix() < KHMER_PREFIX_COMBINE_THRESHOLD)) {
                    // Look for a plausible word boundary
                    int remaining = rangeEnd - (current + wordLength);
                    int pc = fIter.current();
                    int chars = 0;
                    for (;;) {
                        fIter.next();
                        uc = fIter.current();
                        chars += 1;
                        if (--remaining <= 0) {
                            break;
                        }
                        if (fEndWordSet.contains(pc) && fBeginWordSet.contains(uc)) {
                            // Maybe. See if it's in the dictionary.
                            int candidate = words[(wordsFound + 1) %KHMER_LOOKAHEAD].candidates(fIter, fDictionary, rangeEnd);
                            fIter.setIndex(current + wordLength + chars);
                            if (candidate > 0) {
                                break;
                            }
                        }
                        pc = uc;
                    }

                    // Bump the word count if there wasn't already one
                    if (wordLength <= 0) {
                        wordsFound += 1;
                    }

                    // Update the length with the passed-over characters
                    wordLength += chars;
                } else {
                    // Backup to where we were for next iteration
                    fIter.setIndex(current+wordLength);
                }
            }

            // Never stop before a combining mark.
            int currPos;
            while ((currPos = fIter.getIndex()) < rangeEnd && fMarkSet.contains(fIter.current())) {
                fIter.next();
                wordLength += fIter.getIndex() - currPos;
            }

            // Look ahead for possible suffixes if a dictionary word does not follow.
            // We do this in code rather than using a rule so that the heuristic
            // resynch continues to function. For example, one of the suffix characters
            // could be a typo in the middle of a word.
            // NOT CURRENTLY APPLICABLE TO KHMER

            // Did we find a word on this iteration? If so, push it on the break stack
            if (wordLength > 0) {
                foundBreaks.push(Integer.valueOf(current + wordLength));
            }
        }

        // Don't return a break for the end of the dictionary range if there is one there
        if (foundBreaks.peek() >= rangeEnd) {
            foundBreaks.pop();
            wordsFound -= 1;
        }

        return wordsFound;
    }

}
