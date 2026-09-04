/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.privacykit

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.os.Bundle

import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference

import com.android.internal.logging.nano.MetricsProto
import com.android.internal.util.voltage.VoltageUtils

import com.android.settings.R
import com.android.settings.core.SubSettingLauncher
import com.android.settings.dashboard.DashboardFragment
import com.android.settings.spa.SpaActivity.Companion.startSpaActivity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TAG = PrivacyKitPackageListFragment::class.simpleName
internal const val PACKAGE_INFO = "package_info"

/** App picker for PrivacyKit-Native per-app privacy controls (mirrors AppLockPackageListFragment). */
class PrivacyKitPackageListFragment : DashboardFragment() {

    private lateinit var pm: PackageManager
    private lateinit var launchablePackages: List<String>

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Redirect into the new Compose bottom-tab home screen (M1). Mirrors the
        // same onAttach->startSpaActivity+finish() bridge PrintSettingsFragment
        // uses, so both the launcher icon and the Privacy dashboard entry (which
        // both resolve to this Fragment) land on the new UI without any manifest
        // or dashboard-XML changes.
        context.startSpaActivity(PrivacyKitHomePageProvider.name)
        finish()
        pm = context.packageManager
        launchablePackages = VoltageUtils.launchablePackages(context)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        lifecycleScope.launch {
            val preferences = withContext(Dispatchers.Default) {
                pm.getInstalledPackages(PackageInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
                    .filter { packageInfo ->
                        launchablePackages.contains(packageInfo.packageName)
                    }
                    .sortedWith { first, second -> getLabel(first).compareTo(getLabel(second)) }
            }.map { packageInfo -> createPreference(packageInfo) }
            preferenceScreen?.let {
                preferences.forEach { pref -> it.addPreference(pref) }
            }
        }
    }

    private fun getLabel(packageInfo: PackageInfo) =
        packageInfo.applicationInfo?.loadLabel(pm).toString()

    private fun createPreference(packageInfo: PackageInfo): Preference {
        val label = getLabel(packageInfo)
        return Preference(requireContext()).apply {
            key = packageInfo.packageName
            title = label
            icon = packageInfo.applicationInfo?.loadIcon(pm)
            setOnPreferenceClickListener {
                SubSettingLauncher(requireContext())
                    .setDestination(PrivacyKitPackageConfigFragment::class.qualifiedName)
                    .setSourceMetricsCategory(metricsCategory)
                    .setTitleText(label)
                    .setArguments(
                        Bundle(1).apply { putParcelable(PACKAGE_INFO, packageInfo) }
                    )
                    .launch()
                true
            }
        }
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.VIEW_UNKNOWN

    override protected fun getPreferenceScreenResId() = R.xml.privacykit_package_list_settings

    override protected fun getLogTag() = TAG
}
