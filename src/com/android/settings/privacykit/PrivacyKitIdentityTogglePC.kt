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

private const val TAG = "PrivacyKitIdentityPC"
private const val RULE_REAL = 0
private const val RULE_STATIC = 1

/**
 * Generic per-app identity-spoof toggle, backed by PrivacyKitService over
 * IPrivacyKitManager. ON = RULE_STATIC (stable generated fake value that
 * persists across app launches), OFF = RULE_REAL (revert to the true value).
 *
 * One class serves every single-field identifier (Android ID, serial, IMEI,
 * IMSI, ICCID, phone number). Build.* identity is a coherent multi-field
 * bundle and uses PrivacyKitBuildIdentityTogglePC instead.
 */
open class PrivacyKitIdentityTogglePC(
    context: Context,
    key: String,
    protected val packageName: String,
    protected val identifierKey: String,
    protected val coroutineScope: CoroutineScope
) : PrivacyKitTogglePreferenceController(context, key) {

    private var enabled = false
    private var preference: Preference? = null

    protected fun getManager(): IPrivacyKitManager? {
        return try {
            IPrivacyKitManager.Stub.asInterface(ServiceManager.getService("privacykit"))
        } catch (t: Throwable) {
            Log.w(TAG, "PrivacyKitService unavailable", t)
            null
        }
    }

    init {
        coroutineScope.launch {
            enabled = withContext(Dispatchers.IO) { readEnabled() }
            preference?.let { updateState(it) }
        }
    }

    protected open fun readEnabled(): Boolean {
        return try {
            getManager()?.getRuleType(packageName, identifierKey) != RULE_REAL
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read rule for $identifierKey", t)
            false
        }
    }

    override fun isChecked() = enabled

    override fun setChecked(checked: Boolean): Boolean {
        if (enabled == checked) return false
        enabled = checked
        coroutineScope.launch(Dispatchers.IO) { applyChecked(checked) }
        return true
    }

    protected open fun applyChecked(checked: Boolean) {
        try {
            val manager = getManager() ?: return
            if (checked) {
                manager.setIdentifierRule(packageName, identifierKey, RULE_STATIC, null)
            } else {
                manager.clearIdentifierRule(packageName, identifierKey)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to set rule for $identifierKey", t)
        }
    }

    override fun displayPreference(screen: PreferenceScreen) {
        super.displayPreference(screen)
        preference = screen.findPreference(preferenceKey)
    }
}
