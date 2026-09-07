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

package com.android.settings.deviceinfo.voltage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.drawable.Animatable
import android.os.SystemProperties
import android.view.View
import android.widget.ImageView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.settings.R
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.preference.PreferenceBinding

class VoltageMaintainerPreference :
    PreferenceMetadata, PreferenceBinding {

    override val key: String
        get() = "voltage_maintainer"

    override val title: Int
        get() = R.string.voltage_maintainer_title

    override val purpose: Int
        get() = title

    override fun createWidget(context: Context): Preference = MaintainerStatusPreference(context)

    override fun bind(preference: Preference, metadata: PreferenceMetadata) {
        super.bind(preference, metadata)
        val context = preference.context
        val statusPreference = preference as? MaintainerStatusPreference
        preference.isIconSpaceReserved = false
        statusPreference?.setStatusIcon(0, animate = false)
        // ro.bestrom.build.status is the literal OFFICIAL/UNOFFICIAL that
        // vendor/voltage/config/version.mk emits from VOLTAGE_BUILD_TYPE. Map it through
        // string resources rather than case-folding the property: .lowercase() would hit
        // the Turkish dotless-i, and the raw value is all-caps.
        val buildStatus = SystemProperties.get(BUILD_STATUS_PROPERTY, "")
        val statusText = when {
            buildStatus.equals("OFFICIAL", ignoreCase = true) ->
                context.getString(R.string.bestrom_build_status_official)
            buildStatus.equals("UNOFFICIAL", ignoreCase = true) ->
                context.getString(R.string.bestrom_build_status_unofficial)
            else -> context.getString(R.string.unknown)
        }
        preference.summary =
            "$statusText by ${context.getString(R.string.voltage_maintainer)}"
        preference.isCopyingEnabled = false
        preference.intent = Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.bestrom_maintainer_url)))
    }

    private class MaintainerStatusPreference(context: Context) : Preference(context) {
        private var statusIconRes: Int = 0
        private var animateIcon: Boolean = false

        init {
            widgetLayoutResource = R.layout.voltage_gpg_widget
        }

        fun setStatusIcon(resId: Int, animate: Boolean) {
            if (statusIconRes != resId || animateIcon != animate) {
                statusIconRes = resId
                animateIcon = animate
                notifyChanged()
            }
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val icon = holder.findViewById(R.id.gpg_status_icon)
            if (icon !is ImageView) {
                return
            }
            if (statusIconRes == 0) {
                icon.setImageDrawable(null)
                icon.visibility = View.GONE
                return
            }
            icon.visibility = View.VISIBLE
            icon.setImageResource(statusIconRes)
            val drawable = icon.drawable
            if (animateIcon && drawable is Animatable && !drawable.isRunning) {
                drawable.start()
            }
        }
    }

    companion object {
        const val BUILD_STATUS_PROPERTY: String = "ro.bestrom.build.status"
    }
}
