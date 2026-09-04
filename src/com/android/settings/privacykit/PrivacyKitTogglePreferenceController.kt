/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.privacykit

import android.content.Context

import com.android.settings.core.TogglePreferenceController

/**
 * Common base for all PrivacyKit per-app toggle preferences (Obscura-style
 * controls and identity-spoof toggles). Mirrors AppLockTogglePreferenceController.
 */
abstract class PrivacyKitTogglePreferenceController(
    context: Context,
    key: String,
) : TogglePreferenceController(context, key) {

    override fun getAvailabilityStatus() = AVAILABLE

    override fun getSliceHighlightMenuRes() = 0
}
