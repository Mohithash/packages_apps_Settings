/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RECONSTRUCTED 2026-08-19, after the build box that held the original commits was
 * purged. Recovered from, in order of authority:
 *   1. the r49 ROM build (2026-08-18 13:44 UTC), Settings.apk decompiled with jadx -
 *      authoritative for structure, signatures, constants and control flow. Compose
 *      traceEventStart() strings carry the original source line numbers, which were
 *      used to confirm this file lines up with r49 line-for-line;
 *   2. packages_apps_Settings.patch from the 2026-08-18 13:43 UTC snapshot, whose
 *      hunks pin every symbol and most of the literal text;
 *   3. whole-file snapshots mined from the Claude Code transcripts - authoritative
 *      for the original names, comments and intent that decompilation destroys.
 * Confirmed FINAL: all 6 Compose trace line numbers in r49 match this file exactly.
 */
package com.android.settings.privacykit

import android.os.Bundle

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource

import com.android.settings.R
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.settingsBackground

/**
 * Bottom-tab home screen for PrivacyKit-Native: Apps / Profiles / History / Settings.
 *
 * Hosted by [com.android.settings.spa.SpaActivity], which already installs
 * SettingsTheme. Deliberately built on a bare [Scaffold] with **no** top app
 * bar instead of `SettingsScaffold`: the large collapsing Settings app bar ate
 * roughly a fifth of the screen just to repeat the app name the user already
 * chose from the launcher. This mirrors
 * `com.android.settingslib.spa.widget.scaffold.GlifScaffold`, the SPA scaffold
 * that is likewise chrome-less, so the page keeps the real Settings look:
 * `settingsBackground` container color and `WindowInsets.safeDrawing` content
 * insets.
 *
 * Dropping the app bar does not strand the user. Its navigation arrow only
 * called `NavControllerWrapperImpl.navigateBack()`, which is a plain
 * `onBackPressedDispatcher.onBackPressed()` -- exactly what the system back
 * gesture and the 3-button back key already drive. `SpaActivity` extends
 * `BrowseActivity`, whose `NavHost` is entered via
 * `navigate(dest) { popUpTo(startDestination) { inclusive = true } }`, so this
 * page is the only entry on the nav back stack; NavController therefore keeps
 * its back callback disabled and back falls through to the activity, finishing
 * it. Drill-down screens inside the tabs install their own `BackHandler`s and
 * consume back first, so back still means "up one level" there.
 *
 * The activity title is still set (invisibly) so TalkBack and the recents card
 * announce the page, which is the only thing the removed app bar did for
 * accessibility.
 */
object PrivacyKitHomePageProvider : SettingsPageProvider {
    override val name = "PrivacyKitHome"

    private data class Tab(val labelRes: Int, val icon: ImageVector)

    private val TABS = listOf(
        Tab(R.string.privacykit_tab_apps, Icons.Outlined.Apps),
        Tab(R.string.privacykit_tab_profiles, Icons.Outlined.Person),
        Tab(R.string.privacykit_tab_history, Icons.Outlined.History),
        Tab(R.string.privacykit_tab_settings, Icons.Outlined.Settings),
    )

    @Composable
    override fun Page(arguments: Bundle?) {
        // rememberSaveable so the selected tab survives rotation / process death.
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }

        // Replaces the internal ActivityTitle() that SettingsScaffold used to
        // call for us: no visible title, but TalkBack and recents still get one.
        val pageTitle = stringResource(R.string.privacykit_title)
        val activity = LocalActivity.current
        LaunchedEffect(activity, pageTitle) {
            activity?.title = pageTitle
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.settingsBackground,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                // NavigationBar applies its own gesture-bar / cutout insets, so
                // its items sit above the gesture bar while its background still
                // bleeds to the bottom edge.
                NavigationBar {
                    TABS.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            },
        ) { paddingValues ->
            // paddingValues: top = status bar (no top bar to absorb it),
            // bottom = measured NavigationBar height, sides = display cutout.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(top = SettingsDimension.paddingSmall),
            ) {
                when (selectedTab) {
                    0 -> PrivacyKitAppsTab()
                    1 -> PrivacyKitProfilesTab()
                    2 -> PrivacyKitHistoryTab()
                    else -> PrivacyKitSettingsTab()
                }
            }
        }
    }
}

