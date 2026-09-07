package com.android.settings.spa.app.appinfo

import android.content.ActivityNotFoundException
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.ext.LogViewerApp
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.android.settings.R
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spaprivileged.model.app.installed
import com.android.settingslib.spaprivileged.model.app.userHandle

private const val TAG = "AppLogcatPreference"

@Composable
fun AppLogcatPreference(app: ApplicationInfo) {
    if (!app.installed) {
        return
    }

    val context = LocalContext.current
    val intent = remember(app.packageName) {
        LogViewerApp.getPackageLogcatIntent(app.packageName)
    }
    // BestROM may not ship app.grapheneos.logviewer
    // (device/xiaomi/peridot/debloat/Android.bp); hide the row instead of crashing.
    // remember() keeps this off every recomposition of the App info screen.
    val resolvable = remember(intent) {
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }
    if (!resolvable) {
        return
    }

    Preference(object : PreferenceModel {
        override val title = stringResource(R.string.view_logs)
        // Explicit () -> Unit: try/catch is an expression in Kotlin and Log.e returns
        // Int, so an inferred lambda type would be Any and would not override
        // PreferenceModel.onClick.
        override val onClick: () -> Unit = {
            try {
                context.startActivityAsUser(intent, app.userHandle)
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "no activity for " + LogViewerApp.getPackageName(), e)
            }
        }
    })
}
