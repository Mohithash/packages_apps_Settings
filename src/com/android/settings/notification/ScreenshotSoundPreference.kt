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
package com.android.settings.notification

import android.content.Context
import com.android.settings.R
import com.android.settings.contract.KEY_SCREENSHOT_SOUND
import com.android.settingslib.datastore.KeyValueStore
import com.android.settingslib.datastore.SettingsSystemStore
import com.android.settingslib.metadata.ReadWritePermit
import com.android.settingslib.metadata.SensitivityLevel
import com.android.settingslib.metadata.SwitchPreference

class ScreenshotSoundPreference :
    SwitchPreference(
        "screenshot_shutter_sound",
        purpose = R.string.screenshot_shutter_sound_purpose,
        R.string.screenshot_shutter_sound_title
    ) {

    override fun tags(context: Context) = arrayOf(KEY_SCREENSHOT_SOUND)

    // BestROM: wrap the System store so an unset value reports OFF, matching the
    // getIntForUser(..., 0, ...) default used by ScreenshotController.
    override fun storage(context: Context): KeyValueStore =
        DefaultOffStore(SettingsSystemStore.get(context))

    override fun getReadPermissions(context: Context) = SettingsSystemStore.getReadPermissions()

    override fun getReadPermit(context: Context, callingPid: Int, callingUid: Int) =
        ReadWritePermit.ALLOW

    override fun getWritePermissions(context: Context) = SettingsSystemStore.getWritePermissions()

    override fun getWritePermit(context: Context, callingPid: Int, callingUid: Int) =
        ReadWritePermit.ALLOW

    override val sensitivityLevel
        get() = SensitivityLevel.NO_SENSITIVITY

    /**
     * Delegates every call to [delegate] via Kotlin interface delegation, but
     * returns false for an unset boolean so the switch defaults to OFF before the
     * user has ever written the setting.
     */
    private class DefaultOffStore(
        private val delegate: KeyValueStore,
    ) : KeyValueStore by delegate {

        override fun contains(key: String) = true

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> getValue(key: String, valueType: Class<T>): T? {
            delegate.getValue(key, valueType)?.let { return it }
            return if (valueType == java.lang.Boolean::class.java) false as T else null
        }
    }
}
