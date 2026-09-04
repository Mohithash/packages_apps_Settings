/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.privacykit

import android.content.Context
import android.os.ServiceManager
import android.privacykit.IPrivacyKitManager
import android.util.Log

import androidx.preference.Preference
import androidx.preference.PreferenceScreen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PrivacyKitControlPC"

/**
 * Generic per-app boolean control toggle backed by PrivacyKitService's
 * enforced controls (Restrict Internet / Restrict Storage / Force Data
 * Isolation), via IPrivacyKitManager#getBooleanControl / setBooleanControl.
 */
class PrivacyKitControlTogglePC(
    context: Context,
    key: String,
    private val packageName: String,
    private val control: String,
    private val coroutineScope: CoroutineScope
) : PrivacyKitTogglePreferenceController(context, key) {

    private var enabled = false
    private var preference: Preference? = null

    private fun getManager(): IPrivacyKitManager? {
        return try {
            IPrivacyKitManager.Stub.asInterface(ServiceManager.getService("privacykit"))
        } catch (t: Throwable) {
            Log.w(TAG, "PrivacyKitService unavailable", t)
            null
        }
    }

    init {
        coroutineScope.launch {
            enabled = withContext(Dispatchers.IO) {
                try {
                    getManager()?.getBooleanControl(packageName, control) ?: false
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to read control $control", t)
                    false
                }
            }
            preference?.let { updateState(it) }
        }
    }

    override fun isChecked() = enabled

    override fun setChecked(checked: Boolean): Boolean {
        if (enabled == checked) return false
        enabled = checked
        coroutineScope.launch(Dispatchers.IO) {
            try {
                getManager()?.setBooleanControl(packageName, control, checked)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to set control $control", t)
            }
        }
        return true
    }

    override fun displayPreference(screen: PreferenceScreen) {
        super.displayPreference(screen)
        preference = screen.findPreference(preferenceKey)
    }
}
