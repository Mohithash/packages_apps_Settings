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

package com.android.settings.widget;

import android.graphics.Typeface;
import android.graphics.fonts.FontVariationAxis;

import java.util.Arrays;
import java.util.List;

/**
 * Process-wide holder for the two weighted instances of the ROM's dot matrix family.
 *
 * <p>The framework's variation typeface cache is disabled on this build, so every call to
 * {@link Typeface#createFromTypefaceWithVariation} rebuilds the whole font collection. Building
 * each instance once here keeps that to two rebuilds for the life of the process, instead of one
 * per text view that would carry the variation settings in its text appearance.
 */
public final class BestromTypefaces {

    private static final String FAMILY = "doto";
    private static final String AXIS_WEIGHT = "wght";
    private static final String AXIS_ROUNDNESS = "ROND";

    private static volatile Typeface sDoto900;
    private static volatile Typeface sDoto700;

    private BestromTypefaces() {
    }

    /** Returns the dot matrix family at weight 900. */
    public static Typeface doto900() {
        Typeface typeface = sDoto900;
        if (typeface == null) {
            synchronized (BestromTypefaces.class) {
                typeface = sDoto900;
                if (typeface == null) {
                    typeface = create(900f);
                    sDoto900 = typeface;
                }
            }
        }
        return typeface;
    }

    /** Returns the dot matrix family at weight 700. */
    public static Typeface doto700() {
        Typeface typeface = sDoto700;
        if (typeface == null) {
            synchronized (BestromTypefaces.class) {
                typeface = sDoto700;
                if (typeface == null) {
                    typeface = create(700f);
                    sDoto700 = typeface;
                }
            }
        }
        return typeface;
    }

    private static Typeface create(float weight) {
        final Typeface base = Typeface.create(FAMILY, Typeface.NORMAL);
        final List<FontVariationAxis> axes = Arrays.asList(
                new FontVariationAxis(AXIS_WEIGHT, weight),
                new FontVariationAxis(AXIS_ROUNDNESS, 0f));
        try {
            return Typeface.createFromTypefaceWithVariation(base, axes);
        } catch (RuntimeException e) {
            // No variable instance to build from, which is the documented fallback: the family
            // then renders at the single weight it declares.
            return base;
        }
    }
}
