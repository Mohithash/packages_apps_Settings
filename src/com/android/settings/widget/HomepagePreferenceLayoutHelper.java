/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settings.R;
import com.android.settings.flags.Flags;
import com.android.settings.homepage.BestromGlyphs;
import com.android.settingslib.widget.SettingsThemeHelper;

import java.util.Locale;

/** Helper for homepage preference to manage layout. */
public class HomepagePreferenceLayoutHelper {

    private final Preference mPreference;
    private final boolean mBestromStyle;

    private View mIcon;
    private View mText;
    private View mAlertFrame;
    private View mAlertUnnumbered;
    private View mAlertNumberedFrame;
    private TextView mAlertNumberText;
    private TextView mGlyph;
    private TextView mTrailingValue;
    private boolean mIconVisible = true;
    private int mIconPaddingStart = -1;
    private int mTextPaddingStart = -1;
    private int mAlertValue = -1;
    private CharSequence mTrailingText;
    private CharSequence mGlyphSource;
    private Locale mGlyphLocale;
    private String mGlyphText = "";

    /** The interface for managing preference layouts on homepage */
    public interface HomepagePreferenceLayout {
        /** Returns a {@link HomepagePreferenceLayoutHelper}  */
        HomepagePreferenceLayoutHelper getHelper();

        /**
         * Asks the preference to rebind its holder. {@code Preference.notifyChanged} is protected,
         * so the preference has to make it reachable from the helper.
         */
        void notifyLayoutChanged();
    }

    public HomepagePreferenceLayoutHelper(Preference preference) {
        mPreference = preference;
        final Context context = preference.getContext();
        final boolean expressive = SettingsThemeHelper.isExpressiveTheme(context);
        mBestromStyle = expressive
                && context.getResources().getBoolean(R.bool.config_bestrom_homepage_style);
        preference.setLayoutResource(mBestromStyle
                ? R.layout.bestrom_homepage_preference
                : expressive
                        ? R.layout.homepage_preference_expressive
                        : R.layout.homepage_preference);
    }

    /** Sets whether the icon should be visible */
    public void setIconVisible(boolean visible) {
        mIconVisible = visible;
        if (mIcon != null) {
            mIcon.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /** Sets the icon padding start */
    public void setIconPaddingStart(int paddingStart) {
        mIconPaddingStart = paddingStart;
        if (mIcon != null && paddingStart >= 0) {
            mIcon.setPaddingRelative(paddingStart, mIcon.getPaddingTop(), mIcon.getPaddingEnd(),
                    mIcon.getPaddingBottom());
        }
    }

    /** Sets the text padding start */
    public void setTextPaddingStart(int paddingStart) {
        mTextPaddingStart = paddingStart;
        if (mText != null && paddingStart >= 0) {
            mText.setPaddingRelative(paddingStart, mText.getPaddingTop(), mText.getPaddingEnd(),
                    mText.getPaddingBottom());
        }
    }

    /**
     * Stores the short value shown at the end of a homepage row and asks for a rebind.
     *
     * <p>Deliberately writes no view: the helper belongs to a preference but the views belong to a
     * recycled holder, so a callback that fires after the holder has been rebound to another row
     * would write into the wrong row. {@link Preference#notifyChanged()} is a no-op before the
     * preference is attached, so a caller that sets the value while building the screen still pays
     * nothing.
     */
    public void setTrailingValue(CharSequence value) {
        if (TextUtils.equals(value, mTrailingText)) {
            return;
        }
        mTrailingText = value;
        if (mPreference instanceof HomepagePreferenceLayout) {
            ((HomepagePreferenceLayout) mPreference).notifyLayoutChanged();
        }
    }

    /** Sets the alert value and view */
    public void setAlert(int value) {
        if (Flags.homepageTileAlert()) {
            mAlertValue = value;
            if (mAlertFrame != null && mAlertUnnumbered != null
                    && mAlertNumberedFrame != null && mAlertNumberText != null) {
                mAlertFrame.setVisibility((value > 0) ? View.VISIBLE : View.GONE);
                // only display number if it's single digit, more than 1
                if (value == 1 || value > 9) {
                    mAlertNumberedFrame.setVisibility(View.GONE);
                    mAlertUnnumbered.setVisibility(View.VISIBLE);
                    mAlertFrame.setContentDescription(mAlertFrame.getResources()
                            .getString(R.string.homepage_unnumbered_alert_description));
                } else if (value > 1) {
                    mAlertUnnumbered.setVisibility(View.GONE);
                    mAlertNumberedFrame.setVisibility(View.VISIBLE);
                    mAlertNumberText.setVisibility(View.VISIBLE);
                    mAlertNumberText.setText(String.valueOf(value));
                    mAlertFrame.setContentDescription(mAlertFrame.getResources()
                            .getString(R.string.homepage_numbered_alert_description, value));
                }
            }
        }
    }

    void onBindViewHolder(PreferenceViewHolder holder) {
        mIcon = holder.findViewById(R.id.icon_frame);
        mText = holder.findViewById(R.id.text_frame);
        mAlertFrame = holder.findViewById(R.id.alert_frame);
        mAlertUnnumbered = holder.findViewById(R.id.alert_unnumbered);
        mAlertNumberedFrame = holder.findViewById(R.id.alert_numbered_frame);
        mAlertNumberText = (TextView) holder.findViewById(R.id.alert_number_fg);
        if (mBestromStyle) {
            mGlyph = (TextView) holder.findViewById(R.id.bestrom_homepage_glyph);
            mTrailingValue = (TextView) holder.findViewById(R.id.bestrom_trailing_value);
            bindGlyph();
            bindTrailingValue();
        }
        // The forked row has no icon image view, so androidx hides icon_frame on every bind and
        // this call is the only thing that brings the glyph back. Do not reorder it.
        setIconVisible(mIconVisible);
        setIconPaddingStart(mIconPaddingStart);
        setTextPaddingStart(mTextPaddingStart);
        setAlert(mAlertValue);
    }

    private void bindGlyph() {
        if (mGlyph == null) {
            return;
        }
        final CharSequence title = mPreference.getTitle();
        Locale locale = mGlyph.getResources().getConfiguration().getLocales().get(0);
        if (locale == null) {
            locale = Locale.getDefault();
        }
        if (!TextUtils.equals(title, mGlyphSource) || !locale.equals(mGlyphLocale)) {
            mGlyphText = BestromGlyphs.firstGrapheme(title, locale);
            mGlyphSource = title;
            mGlyphLocale = locale;
        }
        mGlyph.setTypeface(BestromTypefaces.doto900());
        if (!TextUtils.equals(mGlyph.getText(), mGlyphText)) {
            mGlyph.setText(mGlyphText);
        }
    }

    private void bindTrailingValue() {
        if (mTrailingValue == null) {
            return;
        }
        if (TextUtils.isEmpty(mTrailingText)) {
            mTrailingValue.setVisibility(View.GONE);
            return;
        }
        mTrailingValue.setTypeface(BestromTypefaces.doto700());
        if (!TextUtils.equals(mTrailingValue.getText(), mTrailingText)) {
            mTrailingValue.setText(mTrailingText);
        }
        mTrailingValue.setVisibility(View.VISIBLE);
    }
}
