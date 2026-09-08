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

import android.content.res.Resources;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.android.settings.R;
import com.android.settings.widget.BestromTypefaces;

/**
 * Applies the BestROM decoration to the settings homepage.
 *
 * <p>Everything here runs once, from {@link SettingsHomepageActivity#onCreate}, on the main thread.
 * The only data it reads are process-local system properties, so there is no binder traffic.
 */
public final class BestromHomepageDecor {

    private static final String PROP_VERSION = "ro.bestrom.version";

    private BestromHomepageDecor() {
    }

    /** Fills in the homepage header. */
    public static void apply(SettingsHomepageActivity activity) {
        final Resources res = activity.getResources();
        final boolean styled = res.getBoolean(R.bool.config_bestrom_homepage_style);
        final String shortVersion = shortVersion();

        final TextView eyebrow = activity.findViewById(R.id.bestrom_homepage_eyebrow);
        if (eyebrow != null) {
            if (!styled || TextUtils.isEmpty(shortVersion)) {
                eyebrow.setVisibility(View.GONE);
            } else {
                eyebrow.setText(activity.getString(R.string.bestrom_homepage_eyebrow,
                        shortVersion));
            }
        }

        final TextView title = activity.findViewById(R.id.bestrom_homepage_title);
        if (title != null) {
            if (!styled) {
                title.setVisibility(View.GONE);
            } else if (BestromGlyphs.isLatinOnly(title.getText())) {
                title.setTypeface(BestromTypefaces.doto900());
            } else {
                // The dot matrix family only covers Latin, so a localised title falls back to the
                // ROM body face. Automatic sizing keeps governing the size either way.
                title.setTextAppearance(R.style.TextAppearance_Bestrom_PageTitle_Fallback);
            }
        }
    }

    /** Returns the ROM version truncated at the first dash, or an empty string. */
    static String shortVersion() {
        final String version = SystemProperties.get(PROP_VERSION, "");
        final int dash = version.indexOf('-');
        return dash > 0 ? version.substring(0, dash) : version;
    }
}
