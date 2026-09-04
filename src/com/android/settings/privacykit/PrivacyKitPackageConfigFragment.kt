/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.privacykit

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Bundle

import androidx.lifecycle.lifecycleScope

import com.android.internal.logging.nano.MetricsProto

import com.android.settings.R
import com.android.settings.dashboard.DashboardFragment
import com.android.settingslib.core.AbstractPreferenceController

import android.privacykit.PrivacyKitKeys

private val TAG = PrivacyKitPackageConfigFragment::class.simpleName

/** Per-app PrivacyKit controls: Obscura-equivalent toggles + identity spoof toggles. */
class PrivacyKitPackageConfigFragment : DashboardFragment() {

    private lateinit var packageInfo: PackageInfo

    override fun onAttach(context: Context) {
        packageInfo = arguments?.getParcelable(PACKAGE_INFO, PackageInfo::class.java)!!
        super.onAttach(context)
    }

    override protected fun createPreferenceControllers(
        context: Context
    ): List<AbstractPreferenceController> {
        val pkg = packageInfo.packageName
        val scope = lifecycleScope
        return listOf(
            PrivacyKitHideAppTogglePC(context, pkg, scope),
            PrivacyKitHideLauncherTogglePC(context, pkg, scope),
            PrivacyKitDevStatusTogglePC(context, pkg, scope),
            PrivacyKitControlTogglePC(context, "privacykit_restrict_internet", pkg,
                    PrivacyKitKeys.CONTROL_RESTRICT_INTERNET, scope),
            PrivacyKitControlTogglePC(context, "privacykit_restrict_storage", pkg,
                    PrivacyKitKeys.CONTROL_RESTRICT_STORAGE, scope),
            PrivacyKitControlTogglePC(context, "privacykit_force_data_isolation", pkg,
                    PrivacyKitKeys.CONTROL_FORCE_DATA_ISOLATION, scope),
            PrivacyKitIdentityTogglePC(context, "privacykit_spoof_android_id", pkg,
                    PrivacyKitKeys.KEY_ANDROID_ID, scope),
            PrivacyKitIdentityTogglePC(context, "privacykit_spoof_serial", pkg,
                    PrivacyKitKeys.KEY_SERIAL, scope),
            PrivacyKitDeviceProfilePC(context, pkg, scope),
            PrivacyKitBuildIdentityTogglePC(context, pkg, scope),
            PrivacyKitIdentityTogglePC(context, "privacykit_spoof_imei", pkg,
                    PrivacyKitKeys.KEY_IMEI, scope),
            PrivacyKitIdentityTogglePC(context, "privacykit_spoof_imsi", pkg,
                    PrivacyKitKeys.KEY_IMSI, scope),
            PrivacyKitIdentityTogglePC(context, "privacykit_spoof_iccid", pkg,
                    PrivacyKitKeys.KEY_ICCID, scope),
            PrivacyKitIdentityTogglePC(context, "privacykit_spoof_phone_number", pkg,
                    PrivacyKitKeys.KEY_PHONE_NUMBER, scope),
        )
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.VIEW_UNKNOWN

    override protected fun getPreferenceScreenResId() = R.xml.privacykit_package_config_settings

    override protected fun getLogTag() = TAG
}
