/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.privacykit

import android.content.Context
import android.os.UserHandle

import androidx.preference.Preference
import androidx.preference.PreferenceScreen

import com.android.internal.util.voltage.HideAppListUtils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEY = "privacykit_hide_app"

/** "Hide App" - existing VoltageOS "hide_applist" backend, no new service needed. */
class PrivacyKitHideAppTogglePC(
    context: Context,
    private val packageName: String,
    private val coroutineScope: CoroutineScope
) : PrivacyKitTogglePreferenceController(context, KEY) {

    private val hideAppListUtils = HideAppListUtils()
    private var hidden = false
    private var preference: Preference? = null

    init {
        coroutineScope.launch {
            hidden = withContext(Dispatchers.IO) {
                HideAppListUtils.getApps(mContext).contains(packageName)
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
                hideAppListUtils.addApp(mContext, packageName, userId)
            } else {
                hideAppListUtils.removeApp(mContext, packageName, userId)
            }
        }
        return true
    }

    override fun displayPreference(screen: PreferenceScreen) {
        super.displayPreference(screen)
        preference = screen.findPreference(preferenceKey)
    }
}
