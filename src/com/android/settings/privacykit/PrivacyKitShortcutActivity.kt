/*
 * Copyright (C) 2026 BestROM
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.privacykit

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.ServiceManager
import android.privacykit.IPrivacyKitManager
import android.widget.Toast
import com.android.settings.R

/**
 * No-UI trampoline behind a pinned "launch this app under this profile" shortcut.
 *
 * A shortcut cannot just carry the app's launch intent: that starts the app under
 * whatever profile is currently active, which defeats the purpose. So the shortcut
 * points here, and this activity does the three things that have to happen in
 * order - make the profile active, stop any process still running under the old
 * identity, then start the app - and finishes without ever drawing.
 *
 * The force-stop matters: PrivacyKit resolves an app's identity when its process
 * starts, so a process that is already alive would simply be brought forward still
 * wearing the previous profile.
 *
 * Not exported. The launcher starts pinned shortcuts through LauncherApps on the
 * publishing app's behalf, so it does not need to be world-launchable - and
 * exporting it would let any app on the device flip PrivacyKit profiles.
 */
/**
 * The app-callable PrivacyKit binder.
 *
 * Declared here rather than shared because every screen file in this package
 * declares its own file-private copy - top-level `private` in Kotlin is file
 * scoped, so there is nothing to import.
 */
private fun privacyKitManager(): IPrivacyKitManager? =
    IPrivacyKitManager.Stub.asInterface(ServiceManager.getService("privacykit"))

class PrivacyKitShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE)
        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
        if (pkg.isNullOrBlank() || profileId.isNullOrBlank()) {
            finish()
            return
        }

        // Point the package at this profile. Only when it is not already there:
        // setActiveProfile records a history entry unconditionally.
        try {
            val mgr = privacyKitManager()
            if (mgr != null && mgr.getActiveProfileId(pkg) != profileId) {
                mgr.setActiveProfile(pkg, profileId)
            }
        } catch (e: Exception) {
            // Fail open: without the service there is no profile to point anywhere,
            // and launching the app is still better than doing nothing.
        }

        try {
            getSystemService(ActivityManager::class.java)?.forceStopPackage(pkg)
        } catch (e: Exception) {
            // No FORCE_STOP_PACKAGES, or the app was not running.
        }

        val launch = try {
            packageManager.getLaunchIntentForPackage(pkg)
        } catch (e: Exception) {
            null
        }
        if (launch == null) {
            Toast.makeText(this, R.string.privacykit_hub_no_launcher, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } catch (e: Exception) {
            // The launcher activity was disabled between the lookup and the start.
        }
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "com.android.settings.privacykit.extra.PACKAGE"
        const val EXTRA_PROFILE_ID = "com.android.settings.privacykit.extra.PROFILE_ID"
    }
}
