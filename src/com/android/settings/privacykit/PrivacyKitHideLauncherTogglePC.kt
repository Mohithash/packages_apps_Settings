/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.privacykit

import android.content.Context
import android.os.UserHandle
import android.provider.Settings

import androidx.preference.Preference
import androidx.preference.PreferenceScreen

import com.android.internal.util.voltage.PrivacyKitBooleanListUtils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEY = "privacykit_hide_launcher"

/** "Hide Launcher" - hides this app's icon from the home screen/app drawer only. */
class PrivacyKitHideLauncherTogglePC(
    context: Context,
    private val packageName: String,
    private val coroutineScope: CoroutineScope
) : PrivacyKitTogglePreferenceController(context, KEY) {

    private var hidden = false
    private var preference: Preference? = null

    init {
        coroutineScope.launch {
            hidden = withContext(Dispatchers.IO) {
                PrivacyKitBooleanListUtils.contains(
                    mContext.contentResolver, "privacykit_hide_launcher_list", packageName)
            }
            preference?.let { updateState(it) }
        }
    }

    override fun isChecked() = hidden

    override fun setChecked(checked: Boolean): Boolean {
        if (hidden == checked) return false
        hidden = checked
        val userId = UserHandle.myUserId()
        coroutineScope.launch(Dispatchers.IO) {
            if (checked) {
                PrivacyKitBooleanListUtils.add(
                    mContext, "privacykit_hide_launcher_list", packageName, userId)
            } else {
                PrivacyKitBooleanListUtils.remove(
                    mContext, "privacykit_hide_launcher_list", packageName, userId)
            }
        }
        return true
    }

    override fun displayPreference(screen: PreferenceScreen) {
        super.displayPreference(screen)
        preference = screen.findPreference(preferenceKey)
    }
}
