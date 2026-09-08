/*
 * Copyright (C) 2026 The BestROM Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.homepage;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;

import java.text.BreakIterator;
import java.util.Locale;

/** Text helpers for the BestROM homepage. Stateless. */
public final class BestromGlyphs {

    private static final int LATIN_EXTENDED_A_END = 0x017F;
    private static final String FAMILY = "doto";

    private static volatile Paint sFacePaint;

    private BestromGlyphs() {
    }

    /**
     * Returns the first letter of {@code title}, lowercased for {@code locale}.
     *
     * <p>A character {@link BreakIterator} finds the first grapheme cluster, so a surrogate pair is
     * never split. The cluster is then cut back to its base character: an Indic cluster carries a
     * virama and one or two vowel signs, which together are twice as wide as the ring they have to
     * sit in. A fresh iterator is built per call: ICU caches the compiled rules per kind and locale
     * and hands back a clone, and a shared mutable instance would only be safe on the main thread.
     */
    public static String firstGrapheme(CharSequence title, Locale locale) {
        if (TextUtils.isEmpty(title)) {
            return "";
        }
        final String text = title.toString();
        final BreakIterator iterator = BreakIterator.getCharacterInstance(locale);
        iterator.setText(text);
        final int next = iterator.next();
        final int end = next == BreakIterator.DONE ? text.length() : next;
        return text.substring(0, baseEnd(text, end)).toLowerCase(locale);
    }

    /** Returns the offset of the first combining mark in {@code text} before {@code end}. */
    private static int baseEnd(String text, int end) {
        for (int i = 0; i < end; ) {
            final int codePoint = text.codePointAt(i);
            final int type = Character.getType(codePoint);
            if (i > 0 && (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK)) {
                return i;
            }
            i += Character.charCount(codePoint);
        }
        return end;
    }

    /**
     * Returns whether the dot matrix face can render the whole of {@code s}.
     *
     * <p>The block test is only a fast reject: the face declares 319 code points and leaves gaps in
     * both Latin-1 Supplement and Latin Extended-A, so each surviving code point is also asked of
     * the face itself. The paint is built once per process.
     */
    public static boolean isLatinOnly(CharSequence s) {
        if (TextUtils.isEmpty(s)) {
            return true;
        }
        final String text = s.toString();
        final Paint paint = facePaint();
        for (int i = 0; i < text.length(); ) {
            final int codePoint = text.codePointAt(i);
            if (codePoint > LATIN_EXTENDED_A_END
                    || !paint.hasGlyph(new String(Character.toChars(codePoint)))) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static Paint facePaint() {
        Paint paint = sFacePaint;
        if (paint == null) {
            synchronized (BestromGlyphs.class) {
                paint = sFacePaint;
                if (paint == null) {
                    paint = new Paint();
                    paint.setTypeface(Typeface.create(FAMILY, Typeface.NORMAL));
                    sFacePaint = paint;
                }
            }
        }
        return paint;
    }
}
