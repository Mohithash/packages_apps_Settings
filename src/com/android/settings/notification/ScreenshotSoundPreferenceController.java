/*
 * Copyright (C) 2026 VoltageOS
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
package com.android.settings.notification;

import static com.android.settings.notification.SettingPref.TYPE_SYSTEM;

import android.content.Context;

import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.core.lifecycle.Lifecycle;

public class ScreenshotSoundPreferenceController extends SettingPrefController {
    private static final String KEY_SCREENSHOT_SOUND = "screenshot_shutter_sound";

    public ScreenshotSoundPreferenceController(Context context, SettingsPreferenceFragment parent,
            Lifecycle lifecycle) {
        super(context, parent, lifecycle);
        // BestROM: screenshot shutter sound ships off (0 = default off).
        mPreference = new SettingPref(TYPE_SYSTEM, KEY_SCREENSHOT_SOUND, KEY_SCREENSHOT_SOUND, 0);
    }
}
