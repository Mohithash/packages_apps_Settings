/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.privacykit

import android.content.Context
import android.privacykit.PrivacyKitKeys
import android.util.Log

import kotlinx.coroutines.CoroutineScope

private const val KEY = "privacykit_spoof_build_identity"
private const val TAG = "PrivacyKitIdentityPC"
private const val RULE_CUSTOM = 4

/**
 * "Spoof build identity" - gives one app a complete, internally coherent
 * device identity instead of this device's own.
 *
 * WHAT THIS USED TO DO, AND WHY IT WAS A LIE
 * ------------------------------------------
 * Until 2026-08 this toggle wrote RULE_STATIC to each of
 * PrivacyKitKeys.BUILD_KEYS. Every one of those eight keys is a coherent
 * build-identity key, and PrivacyKitRuleResolver#isRuleTypeAllowed refuses
 * RULE_STATIC for all of them - resolve() consults that method as its very
 * first statement and returns the REAL value on refusal. So the switch stored
 * a rule, read back as "on" (readEnabled only checks that the rule is not
 * RULE_REAL), and every app kept reading the real fingerprint, model, brand and
 * device. The user was told their build identity was spoofed and it was not:
 * the exact defect class this area is being audited for.
 *
 * It was refused for a good reason, too. A random per-field draw cannot produce
 * a coherent identity - PrivacyKitIdentifierGenerator has no case for any of
 * these keys, so RULE_STATIC would have minted sixteen hex characters where
 * Build.MODEL belongs - so "make the toggle's rule type allowed" was never the
 * fix.
 *
 * WHAT IT DOES NOW
 * ----------------
 * ON applies a real, curated, coherent device template through exactly the path
 * PrivacyKitDeviceProfilePC.applyTemplate uses: RULE_CUSTOM with a bundle of
 * values that agree with each other, which is the rule type the backend does
 * accept for these keys. OFF clears the whole bundle.
 *
 * The template is chosen deterministically from the package name, so the app
 * gets a stable identity across toggles, reboots and Settings restarts, and two
 * different apps get two different identities (one shared identity across every
 * app on the device would itself be a fingerprint).
 *
 * WHY THIS ROW WAS KEPT RATHER THAN DELETED
 * -----------------------------------------
 * Deleting the controller was the other candidate fix, and it is the wrong one
 * here: the Preference itself lives in res/xml/privacykit_package_config_settings.xml,
 * which this change does not own. Removing only the controller would leave a
 * switch on screen with nothing behind it - a control that visibly toggles and
 * provably does nothing, which is a louder version of the bug being fixed. The
 * row also has a job the ListPreference above it does not: one tap, no choice
 * to make.
 */
class PrivacyKitBuildIdentityTogglePC(
    context: Context,
    packageName: String,
    coroutineScope: CoroutineScope
) : PrivacyKitIdentityTogglePC(context, KEY, packageName, PrivacyKitKeys.KEY_BUILD_FINGERPRINT,
        coroutineScope) {

    override fun readEnabled(): Boolean {
        // Consider it "on" if the primary field (fingerprint) has a rule; the
        // fields are always written/cleared together by applyChecked below.
        return super.readEnabled()
    }

    /**
     * The template this package gets, or null if there are none.
     *
     * String.hashCode() is stable across processes and releases (its algorithm
     * is specified by the language, not by the VM), which is what makes the
     * choice reproducible: the same app is handed the same identity every time
     * the toggle is switched on. Math.floorMod keeps the index in range for the
     * package names whose hash is negative.
     */
    private fun templateFor(packageName: String): PrivacyKitDeviceTemplate? {
        val templates = PrivacyKitDeviceTemplates.TEMPLATES
        if (templates.isEmpty()) return null
        return templates[Math.floorMod(packageName.hashCode(), templates.size)]
    }

    override fun applyChecked(checked: Boolean) {
        try {
            val manager = getManager() ?: return
            if (checked) {
                val template = templateFor(packageName)
                if (template == null) {
                    Log.w(TAG, "No device templates available; build identity left real")
                    return
                }
                // RULE_CUSTOM, not RULE_STATIC: see the class comment. This is
                // the same call PrivacyKitDeviceProfilePC.applyTemplate makes.
                for ((key, value) in template.toFieldMap()) {
                    manager.setIdentifierRule(packageName, key, RULE_CUSTOM, value)
                }
            } else {
                // The full superset, not just BUILD_KEYS: a half-cleared bundle
                // leaves a build id from one device beside a fingerprint from
                // another. See PrivacyKitDeviceTemplates.IDENTITY_KEYS.
                for (key in PrivacyKitDeviceTemplates.IDENTITY_KEYS) {
                    manager.clearIdentifierRule(packageName, key)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to set Build.* identity rules", t)
        }
    }
}
