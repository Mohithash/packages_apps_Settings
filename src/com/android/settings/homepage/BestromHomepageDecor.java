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

import android.app.settings.SettingsEnums;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.settings.R;
import com.android.settings.core.SubSettingLauncher;
import com.android.settings.deviceinfo.aboutphone.MyDeviceInfoFragment;
import com.android.settings.widget.BestromDotGridDrawable;
import com.android.settings.widget.BestromTypefaces;

/**
 * Applies the BestROM decoration to the settings homepage.
 *
 * <p>Everything here runs once, from {@link SettingsHomepageActivity#onCreate}, on the main thread,
 * except the device card's text, which is filled from the view queue because the device name comes
 * from the settings provider. Everything else is a process-local system property, so there is no
 * account, user or package manager lookup.
 */
public final class BestromHomepageDecor {

    private static final String PROP_VERSION = "ro.bestrom.version";
    private static final String PROP_DEVICE = "ro.bestrom.device";
    private static final String PROP_MARKET_NAME = "ro.product.marketname";

    private BestromHomepageDecor() {
    }

    /** Fills in the homepage header and device card. */
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

        final View card = activity.findViewById(R.id.bestrom_device_card);
        if (card == null) {
            return;
        }
        if (!styled || !res.getBoolean(R.bool.config_bestrom_homepage_device_card)) {
            card.setVisibility(View.GONE);
            return;
        }
        ((ImageView) card.findViewById(R.id.bestrom_device_avatar))
                .setImageDrawable(new BestromDotGridDrawable(activity));
        card.setOnClickListener(v -> new SubSettingLauncher(activity)
                .setDestination(MyDeviceInfoFragment.class.getName())
                .setTitleRes(R.string.about_settings)
                .setSourceMetricsCategory(SettingsEnums.SETTINGS_HOMEPAGE)
                .launch());
        // The device name is the one value here that is not a process-local property: the first
        // read of it in a process is a binder call into the settings provider. Filling the card
        // from the view queue keeps that off the activity create path; the card stays hidden
        // until then.
        card.post(() -> fill(activity, card, shortVersion));
    }

    /** Fills the device card and shows it. */
    private static void fill(SettingsHomepageActivity activity, View card, String shortVersion) {
        final CharSequence name = deviceName(activity);
        final String device = SystemProperties.get(PROP_DEVICE, Build.DEVICE);
        final CharSequence sub = activity.getString(R.string.bestrom_device_card_summary,
                shortVersion, device);
        ((TextView) card.findViewById(R.id.bestrom_device_name)).setText(name);
        ((TextView) card.findViewById(R.id.bestrom_device_sub)).setText(sub);
        card.setContentDescription(activity.getString(
                R.string.bestrom_device_card_content_description, name, sub));
        card.setVisibility(View.VISIBLE);
    }

    /** Returns the ROM version truncated at the first dash, or an empty string. */
    static String shortVersion() {
        final String version = SystemProperties.get(PROP_VERSION, "");
        final int dash = version.indexOf('-');
        return dash > 0 ? version.substring(0, dash) : version;
    }

    /**
     * Resolves the device name exactly as {@code DeviceNamePreferenceController} does, so the card
     * and the About row always agree, without constructing that controller and the Wi-Fi and
     * Bluetooth handles it takes in its constructor.
     */
    private static CharSequence deviceName(Context context) {
        String name = Settings.Global.getString(context.getContentResolver(),
                Settings.Global.DEVICE_NAME);
        if (name == null) {
            name = SystemProperties.get(PROP_MARKET_NAME, null);
            if (name == null) {
                name = Build.MODEL;
            }
        }
        return name;
    }
}
