/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.privacykit

import android.content.Context
import android.os.UserHandle

import androidx.preference.Preference
import androidx.preference.PreferenceScreen

import com.android.internal.util.voltage.HideDeveloperStatusUtils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEY = "privacykit_hide_dev_status"

/**
 * Obscura-equivalent settings spoof: ADB / Developer options / Wireless
 * debugging / Package Verifier / USB app verification / Accessibility, all
 * spoofed to their disabled state for this app as one combined toggle
 * ("hide_developer_status" is a single per-app list covering
 * all of those keys together - see HideDeveloperStatusUtils).
 */
class PrivacyKitDevStatusTogglePC(
    context: Context,
    private val packageName: String,
    private val coroutineScope: CoroutineScope
) : PrivacyKitTogglePreferenceController(context, KEY) {

    private val hideDeveloperStatusUtils = HideDeveloperStatusUtils()
    private var hidden = false
    private var preference: Preference? = null

    init {
        coroutineScope.launch {
            hidden = withContext(Dispatchers.IO) {
                isPackageInHideList(mContext.contentResolver)
            }
            preference?.let { updateState(it) }
        }
    }

    private fun isPackageInHideList(cr: android.content.ContentResolver): Boolean {
        val apps = android.provider.Settings.Secure.getString(
            cr, "hide_developer_status")
        return apps != null && apps.split(",").contains(packageName)
    }

    override fun isChecked() = hidden

    override fun setChecked(checked: Boolean): Boolean {
        if (hidden == checked) return false
        hidden = checked
        val userId = UserHandle.myUserId()
        coroutineScope.launch(Dispatchers.IO) {
            if (checked) {
                hideDeveloperStatusUtils.addApp(mContext, packageName, userId)
            } else {
                hideDeveloperStatusUtils.removeApp(mContext, packageName, userId)
            }
        }
        return true
    }

    override fun displayPreference(screen: PreferenceScreen) {
        super.displayPreference(screen)
        preference = screen.findPreference(preferenceKey)
    }
}
