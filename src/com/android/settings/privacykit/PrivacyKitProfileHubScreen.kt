/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.privacykit

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.ServiceManager
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsSpace

import android.privacykit.IPrivacyKitManager
import android.privacykit.PrivacyKitKeys

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.json.JSONArray
import org.json.JSONObject

private fun privacyKitManager(): IPrivacyKitManager? =
    IPrivacyKitManager.Stub.asInterface(ServiceManager.getService("privacykit"))

/**
 * Everything the hub header and its rows need, read in one pass off the main
 * thread. Each source is caught independently so one unavailable backend
 * cannot blank the whole screen.
 */
private data class PkHubState(
    val appLabel: String,
    val profileName: String,
    /** Raw PrivacyKitProfileStore MODE_* token; localized at render time. */
    val mode: String,
    val color: String?,
    val note: String?,
    /** Display name of the built-in template this profile's fingerprint came from, if any. */
    val templateName: String?,
    /** True when Build.FINGERPRINT carries any non-REAL rule on this profile. */
    val buildSpoofed: Boolean,
    /** Catalog keys whose rule is not RULE_REAL. */
    val configuredCount: Int,
    /** app + data + cache bytes, or null when StorageStatsManager would not answer. */
    val appDataBytes: Long?,
    val hasLaunchIntent: Boolean,
)

/**
 * Which nested screen the hub is currently showing. The hub owns its own
 * sub-screen stack (local state + BackHandler + early return) exactly like
 * every other PrivacyKit drill-down; nothing is pushed onto the SPA nav graph.
 */
private enum class PkHubDest {
    NONE,
    IDENTIFIERS,
    VERIFY,
    CONTROLS,
}

/**
 * The landing screen for one profile of one app.
 *
 * Every write reachable from here (`setIdentifierRule`, `setBooleanControl`)
 * is profile-blind on the backend: it lands on whatever `getActiveProfileId`
 * returns. The hub is entered *for* a specific profile, so [loadHubState]
 * re-asserts the active profile on entry and on every refresh - but only when
 * it actually differs, because `setActiveProfile` records a history entry
 * unconditionally and the hub reloads after every sub-screen close.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyKitProfileHubScreen(
    packageName: String,
    profileId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshToken by remember { mutableStateOf(0) }
    var dest by remember { mutableStateOf(PkHubDest.NONE) }
    var showTemplatePicker by remember { mutableStateOf(false) }
    var showModePicker by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var coloring by remember { mutableStateOf(false) }
    var noting by remember { mutableStateOf(false) }

    // Deliberately a plain state + LaunchedEffect rather than produceState: a
    // refresh keeps the previously loaded header on screen while the reload
    // runs, so coming back from a sub-screen doesn't blank the avatar.
    var state by remember { mutableStateOf<PkHubState?>(null) }
    LaunchedEffect(packageName, profileId, refreshToken) {
        state = withContext(Dispatchers.IO) { loadHubState(context, packageName, profileId) }
    }

    if (dest != PkHubDest.NONE) {
        // One shared closure behind both the BackHandler and each screen's own
        // onBack, so the refresh happens whichever handler wins: every one of
        // these screens can rewrite this profile's rules.
        val close: () -> Unit = {
            dest = PkHubDest.NONE
            refreshToken++
        }
        BackHandler(onBack = close)
        when (dest) {
            PkHubDest.IDENTIFIERS ->
                PrivacyKitIdentifiersScreen(packageName = packageName, onBack = close)
            PkHubDest.VERIFY ->
                PrivacyKitVerifyValuesScreen(packageName = packageName, onBack = close)
            PkHubDest.CONTROLS ->
                PrivacyKitAppControlsScreen(packageName = packageName, onBack = close)
            PkHubDest.NONE -> {}
        }
        return
    }

    BackHandler(onBack = onBack)

    val current = state

    if (showTemplatePicker && current != null) {
        PkHubTemplateDialog(
            currentTemplateName = current.templateName,
            buildSpoofed = current.buildSpoofed,
            onDismiss = { showTemplatePicker = false },
            onPicked = { template ->
                showTemplatePicker = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        pkApplyDeviceTemplate(packageName, profileId, template)
                    }
                    refreshToken++
                }
            },
        )
    }

    if (showModePicker && current != null) {
        ChangeModeDialog(
            currentMode = current.mode,
            onDismiss = { showModePicker = false },
            onModeSelected = { newMode ->
                showModePicker = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            privacyKitManager()?.setProfileMode(packageName, profileId, newMode)
                        } catch (e: Exception) {
                            // Fail open: RemoteException / SecurityException are
                            // not worth crashing Settings over.
                        }
                    }
                    refreshToken++
                }
            },
        )
    }

    if (renaming && current != null) {
        RenameProfileDialog(
            currentName = current.profileName,
            onDismiss = { renaming = false },
            onRename = { newName ->
                renaming = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            privacyKitManager()?.renameProfile(packageName, profileId, newName)
                        } catch (e: Exception) {
                        }
                    }
                    refreshToken++
                }
            },
        )
    }

    if (coloring && current != null) {
        ProfileColorDialog(
            currentColor = current.color,
            onDismiss = { coloring = false },
            onColorSelected = { token ->
                coloring = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            privacyKitManager()?.setProfileColor(packageName, profileId, token)
                        } catch (e: Exception) {
                        }
                    }
                    refreshToken++
                }
            },
        )
    }

    if (noting && current != null) {
        ProfileNoteDialog(
            currentNote = current.note,
            onDismiss = { noting = false },
            onSave = { text ->
                noting = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            privacyKitManager()?.setProfileNote(packageName, profileId, text)
                        } catch (e: Exception) {
                        }
                    }
                    // Re-read rather than echoing what was typed: the store
                    // trims and truncates, so the hub has to show what was
                    // actually persisted.
                    refreshToken++
                }
            },
        )
    }

    if (showEditSheet && current != null) {
        ModalBottomSheet(
            onDismissRequest = { showEditSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                PkCategoryTitle(stringResource(R.string.privacykit_hub_edit))
                PkPreference(
                    title = stringResource(R.string.privacykit_profile_rename),
                    onClick = {
                        showEditSheet = false
                        renaming = true
                    },
                )
                PkPreference(
                    title = stringResource(R.string.privacykit_profile_color),
                    summary = pkProfileColorName(current.color),
                    onClick = {
                        showEditSheet = false
                        coloring = true
                    },
                )
                PkPreference(
                    title = stringResource(R.string.privacykit_profile_note),
                    summary = current.note,
                    onClick = {
                        showEditSheet = false
                        noting = true
                    },
                )
                Spacer(Modifier.height(SettingsSpace.small1))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PkAvatarHeader(
            name = current?.profileName ?: "",
            subtitle = if (current == null) {
                null
            } else {
                stringResource(
                    R.string.privacykit_hub_subtitle,
                    current.appLabel,
                    pkModeLabel(current.mode),
                )
            },
            colorToken = current?.color,
            onBack = onBack,
            actions = {
                IconButton(
                    enabled = current != null && current.hasLaunchIntent,
                    onClick = {
                        val ok = pkPinProfileShortcut(
                            context = context,
                            packageName = packageName,
                            profileId = profileId,
                            appLabel = current?.appLabel ?: packageName,
                            profileName = current?.profileName ?: "",
                        )
                        if (!ok) {
                            Toast.makeText(
                                context,
                                R.string.privacykit_hub_shortcut_failed,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddToHomeScreen,
                        contentDescription =
                            stringResource(R.string.privacykit_hub_add_shortcut),
                    )
                }
                IconButton(
                    enabled = current != null,
                    onClick = { showEditSheet = true },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.privacykit_hub_edit),
                    )
                }
            },
        )

        if (current == null) {
            Box(Modifier.fillMaxWidth().weight(1f)) { PkLoading() }
        } else {
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            ) {
                PkCategory {
                    PkHubRow(
                        title = stringResource(R.string.privacykit_hub_template_title),
                        summary = when {
                            current.templateName != null -> current.templateName
                            current.buildSpoofed ->
                                stringResource(R.string.privacykit_device_profile_current_custom)
                            else ->
                                stringResource(R.string.privacykit_device_profile_current_real)
                        },
                        icon = Icons.Outlined.PhoneAndroid,
                        onClick = { showTemplatePicker = true },
                    )
                    PkHubRow(
                        title = stringResource(R.string.privacykit_identifiers_title),
                        summary = context.resources.getQuantityString(
                            R.plurals.privacykit_hub_identifier_count,
                            current.configuredCount,
                            current.configuredCount,
                        ),
                        icon = Icons.Outlined.Fingerprint,
                        onClick = { dest = PkHubDest.IDENTIFIERS },
                    )
                    PkHubRow(
                        title = stringResource(R.string.privacykit_hub_verify_title),
                        summary = stringResource(R.string.privacykit_hub_verify_summary),
                        icon = Icons.Outlined.Verified,
                        onClick = { dest = PkHubDest.VERIFY },
                    )
                    PkHubRow(
                        title = stringResource(R.string.privacykit_hub_mode_title),
                        summary = pkModeLabel(current.mode),
                        icon = Icons.Outlined.Layers,
                        onClick = { showModePicker = true },
                    )
                    PkHubRow(
                        title = stringResource(R.string.privacykit_hub_app_data_title),
                        summary = if (current.appDataBytes == null) {
                            stringResource(R.string.privacykit_hub_app_data_unknown)
                        } else {
                            stringResource(
                                R.string.privacykit_hub_app_data_summary,
                                Formatter.formatFileSize(context, current.appDataBytes),
                            )
                        },
                        icon = Icons.Outlined.Folder,
                        onClick = { pkOpenAppInfo(context, packageName) },
                    )
                    // Non-navigating on purpose: profiles are namespaced per
                    // package in PrivacyKitProfileStore, so exactly one app can
                    // ever use a given profile. There is nothing to drill into.
                    PkPreference(
                        title = stringResource(R.string.privacykit_hub_apps_using_title),
                        summary = stringResource(
                            R.string.privacykit_hub_apps_using_summary, current.appLabel),
                        icon = Icons.Outlined.Apps,
                    )
                }

                PkCategoryTitle(stringResource(R.string.privacykit_hub_advanced))
                PkCategory {
                    PkHubRow(
                        title = stringResource(R.string.privacykit_hub_controls_title),
                        summary = stringResource(R.string.privacykit_hub_controls_summary),
                        icon = Icons.Outlined.Tune,
                        onClick = { dest = PkHubDest.CONTROLS },
                    )
                }
                Spacer(Modifier.height(SettingsSpace.small4))
            }

            PkPinnedAction(
                text = stringResource(R.string.privacykit_hub_launch),
                icon = Icons.Outlined.RocketLaunch,
                enabled = current.hasLaunchIntent,
                note = if (current.hasLaunchIntent) {
                    null
                } else {
                    stringResource(R.string.privacykit_hub_no_launcher)
                },
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            pkAssertActiveProfile(packageName, profileId)
                        }
                        val intent = try {
                            context.packageManager.getLaunchIntentForPackage(packageName)
                        } catch (e: Exception) {
                            null
                        }
                        if (intent != null) {
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // The activity may have been disabled between
                                // the check and the tap; nothing to do but
                                // ignore it.
                            }
                        }
                    }
                },
            )
        }
    }
}

/**
 * Reads everything the hub shows in one pass.
 *
 * Deliberately blocking - callers run it on [Dispatchers.IO] - because it
 * costs one binder round-trip per catalog key plus label, storage-stats and
 * launcher-intent lookups.
 */
private fun loadHubState(context: Context, pkg: String, profileId: String): PkHubState {
    val pm = context.packageManager
    val label = try {
        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm)
            .toString()
    } catch (e: Exception) {
        pkg
    }
    val mgr = try {
        privacyKitManager()
    } catch (e: Exception) {
        null
    }
    pkAssertActiveProfile(pkg, profileId)

    val name = try {
        mgr?.getProfileName(pkg, profileId)
    } catch (e: Exception) {
        null
    } ?: profileId
    val mode = try {
        mgr?.getProfileMode(pkg, profileId)
    } catch (e: Exception) {
        null
    } ?: ""
    val color = try {
        mgr?.getProfileColor(pkg, profileId)
    } catch (e: Exception) {
        null
    }
    val note = try {
        mgr?.getProfileNote(pkg, profileId)
    } catch (e: Exception) {
        null
    }

    var configured = 0
    var buildSpoofed = false
    var templateName: String? = null
    if (mgr != null) {
        for (item in PrivacyKitIdentifierCatalog.allItems) {
            val type = try {
                mgr.getRuleType(pkg, item.key)
            } catch (e: Exception) {
                PkRuleType.REAL.serverValue
            }
            if (type != PkRuleType.REAL.serverValue) {
                configured++
            }
        }
        try {
            buildSpoofed = mgr.getRuleType(pkg, PrivacyKitKeys.KEY_BUILD_FINGERPRINT) !=
                    PkRuleType.REAL.serverValue
            if (buildSpoofed) {
                val fingerprint = mgr.getRuleValue(pkg, PrivacyKitKeys.KEY_BUILD_FINGERPRINT)
                templateName = PrivacyKitDeviceTemplates.TEMPLATES
                    .firstOrNull { it.fingerprint == fingerprint }
                    ?.displayName
            }
        } catch (e: Exception) {
            buildSpoofed = false
            templateName = null
        }
    }

    val hasLaunchIntent = try {
        pm.getLaunchIntentForPackage(pkg) != null
    } catch (e: Exception) {
        false
    }

    return PkHubState(
        appLabel = label,
        profileName = name,
        mode = mode,
        color = color,
        note = note,
        templateName = templateName,
        buildSpoofed = buildSpoofed,
        configuredCount = configured,
        appDataBytes = pkQueryAppDataBytes(context, pkg),
        hasLaunchIntent = hasLaunchIntent,
    )
}

/**
 * Points the package's active profile at [profileId], but only when it is not
 * already there: `setActiveProfile` records a PROFILE_ACTIVATED history entry
 * unconditionally, and the hub reloads after every sub-screen close.
 *
 * Blocking binder calls: only ever call this on [Dispatchers.IO].
 */
private fun pkAssertActiveProfile(pkg: String, profileId: String) {
    try {
        val mgr = privacyKitManager() ?: return
        if (mgr.getActiveProfileId(pkg) != profileId) {
            mgr.setActiveProfile(pkg, profileId)
        }
    } catch (e: Exception) {
        // Fail open: without the service there is nothing to point anywhere.
    }
}

/** app + data + cache bytes for [pkg], or null when the stats query refuses. */
private fun pkQueryAppDataBytes(context: Context, pkg: String): Long? = try {
    val stats = context.getSystemService(StorageStatsManager::class.java)
    val appInfo = context.packageManager
        .getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
    val result = stats?.queryStatsForPackage(
        appInfo.storageUuid, pkg, Process.myUserHandle())
    if (result == null) null else result.appBytes + result.dataBytes + result.cacheBytes
} catch (e: Exception) {
    // No permission, no such package, or an IO error from the stats service.
    null
}

/** Opens the platform App info page - the only screen that can really clear app data. */
private fun pkOpenAppInfo(context: Context, pkg: String) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", pkg, null))
        context.startActivity(intent)
    } catch (e: Exception) {
    }
}

/**
 * Writes a template's coherent Build.* bundle onto this profile, or clears the
 * whole bundle when [template] is null. Mirrors the app-controls screen's
 * template writer; the active profile is re-asserted first because
 * `setIdentifierRule` takes no profile id.
 *
 * Blocking binder calls: only ever call this on [Dispatchers.IO].
 */
private fun pkApplyDeviceTemplate(
    pkg: String,
    profileId: String,
    template: PrivacyKitDeviceTemplate?,
) {
    try {
        pkAssertActiveProfile(pkg, profileId)
        val mgr = privacyKitManager() ?: return
        if (template == null) {
            // A half-cleared bundle is incoherent, so the whole thing goes
            // together - IDENTITY_KEYS, not BUILD_KEYS. The apply path below
            // writes the template's entire field map, which is wider than those
            // eight, and a clear narrower than the apply leaves fields from the
            // old template beside this device's real fingerprint.
            for (key in PrivacyKitDeviceTemplates.IDENTITY_KEYS) {
                mgr.clearIdentifierRule(pkg, key)
            }
            return
        }
        for ((key, value) in template.toFieldMap()) {
            mgr.setIdentifierRule(pkg, key, PkRuleType.CUSTOM.serverValue, value)
        }
    } catch (e: Exception) {
    }
}

/** Device-template picker: the built-in templates plus "real identity". */
@Composable
private fun PkHubTemplateDialog(
    currentTemplateName: String?,
    buildSpoofed: Boolean,
    onDismiss: () -> Unit,
    onPicked: (PrivacyKitDeviceTemplate?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.privacykit_device_profile_pick_title)) },
        text = {
            // Scrollable: the template list is far longer than the maximum
            // dialog height, and M3's AlertDialog does not scroll for you.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PkHubTemplateRow(
                    title = stringResource(R.string.privacykit_device_profile_none),
                    selected = !buildSpoofed,
                    onClick = { onPicked(null) },
                )
                for (template in PrivacyKitDeviceTemplates.TEMPLATES) {
                    PkHubTemplateRow(
                        title = template.displayName,
                        selected = template.displayName == currentTemplateName,
                        onClick = { onPicked(template) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.privacykit_cancel)) }
        },
    )
}

@Composable
private fun PkHubTemplateRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = SettingsDimension.paddingSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = title, modifier = Modifier.padding(start = SettingsDimension.paddingSmall))
    }
}

/**
 * Filters `exportBackup()` down to one package and one profile.
 *
 * This couples the share action to PrivacyKitService's backup schema
 * (root.profiles.packages[pkg].profiles[]), which `importBackup` also parses -
 * a schema change breaks this silently, so it returns null rather than
 * guessing whenever the shape is not what it expects. A dedicated
 * `exportProfile` AIDL would remove the coupling.
 *
 * Blocking binder call: only ever call this on [Dispatchers.IO].
 */
private fun pkExportSingleProfileJson(pkg: String, profileId: String): String? = try {
    val mgr = privacyKitManager()
    val raw = mgr?.exportBackup()
    if (raw.isNullOrEmpty()) {
        null
    } else {
        val root = JSONObject(raw)
        val packages = root.optJSONObject("profiles")?.optJSONObject("packages")
        val pkgObj = packages?.optJSONObject(pkg)
        val profiles = pkgObj?.optJSONArray("profiles")
        var wanted: JSONObject? = null
        if (profiles != null) {
            for (i in 0 until profiles.length()) {
                val entry = profiles.optJSONObject(i)
                if (entry != null && entry.optString("id") == profileId) {
                    wanted = entry
                    break
                }
            }
        }
        if (wanted == null) {
            null
        } else {
            val outPkg = JSONObject()
                .put("activeProfileId", profileId)
                .put("profiles", JSONArray().put(wanted))
            JSONObject()
                .put("formatVersion", root.optInt("formatVersion", 1))
                .put("exportedAt", System.currentTimeMillis())
                .put("profiles", JSONObject().put("packages", JSONObject().put(pkg, outPkg)))
                .toString()
        }
    }
} catch (e: Exception) {
    null
}


/**
 * Pins a launcher shortcut that activates [profileId] and then starts the app.
 *
 * The shortcut cannot simply be the app's own launch intent: that would start the
 * app under whatever profile happened to be active, which is the opposite of the
 * point. It targets [PrivacyKitShortcutActivity], a no-UI trampoline that sets
 * the profile first.
 *
 * Returns false when the launcher does not support pinning (older or third-party
 * launchers) or the request is refused, so the caller can say so rather than
 * leaving the user tapping a button that appears to do nothing.
 */
private fun pkPinProfileShortcut(
    context: Context,
    packageName: String,
    profileId: String,
    appLabel: String,
    profileName: String,
): Boolean = try {
    val sm = context.getSystemService(ShortcutManager::class.java)
    if (sm == null || !sm.isRequestPinShortcutSupported) {
        false
    } else {
        val label = if (profileName.isBlank()) appLabel else "$appLabel - $profileName"
        val intent = Intent(context, PrivacyKitShortcutActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(PrivacyKitShortcutActivity.EXTRA_PACKAGE, packageName)
            putExtra(PrivacyKitShortcutActivity.EXTRA_PROFILE_ID, profileId)
        }
        // The app's own icon, so the shortcut reads as "that app" on the home
        // screen; the PrivacyKit icon is only a fallback.
        val icon = try {
            val d = context.packageManager.getApplicationIcon(packageName)
            val bmp = android.graphics.Bitmap.createBitmap(
                maxOf(d.intrinsicWidth, 1), maxOf(d.intrinsicHeight, 1),
                android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            d.setBounds(0, 0, canvas.width, canvas.height)
            d.draw(canvas)
            android.graphics.drawable.Icon.createWithAdaptiveBitmap(bmp)
        } catch (e: Exception) {
            android.graphics.drawable.Icon.createWithResource(
                context, R.drawable.ic_privacykit_launcher)
        }
        val info = ShortcutInfo.Builder(context, "pk-$packageName-$profileId")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(icon)
            .setIntent(intent)
            .build()
        sm.requestPinShortcut(info, null)
    }
} catch (e: Exception) {
    false
}

/** Hands [text] to the system share sheet; the user picks the target, not us. */
private fun pkShareText(context: Context, text: String) {
    try {
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null))
    } catch (e: Exception) {
    }
}

/* ------------------------------------------------------------------------ *
 * Verify values
 * ------------------------------------------------------------------------ */

/** One configured key, with what this app reads for it and what the device really is. */
private data class PkVerifyRow(
    val label: String,
    /** null when Settings cannot legitimately know the real value for this key. */
    val realValue: String?,
    /** What `resolveIdentifier` hands the app right now. */
    val seenValue: String?,
    /** Whether the key is fully enforced by this build's hooks. */
    val enforced: Boolean,
)

/**
 * Read-only cross-check: for every configured key, what an app reads versus
 * what the device really is.
 *
 * This is the honesty machinery made checkable per app: a key whose rule is
 * set but whose resolved value still equals the real one is exactly the
 * "configured but not enforced" case the catalog's `enforcedKeys` set
 * describes statically.
 */
@Composable
private fun PrivacyKitVerifyValuesScreen(packageName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val rows by produceState<List<PkVerifyRow>?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) { loadVerifyRows(packageName) }
    }

    Column(Modifier.fillMaxSize()) {
        PkScreenHeader(
            title = stringResource(R.string.privacykit_hub_verify_title),
            onBack = onBack,
        )
        val list = rows
        when {
            list == null -> Box(Modifier.fillMaxWidth().weight(1f)) { PkLoading() }
            list.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f)) {
                PkEmptyState(
                    text = stringResource(R.string.privacykit_verify_empty_title),
                    description = stringResource(R.string.privacykit_verify_empty_summary),
                    icon = Icons.Outlined.Verified,
                )
            }
            else -> Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            ) {
                PkCategory {
                    for (row in list) {
                        PkPreference(title = row.label, summary = verifySummary(context, row))
                    }
                }
                PkFooter(text = stringResource(R.string.privacykit_verify_footer))
                Spacer(Modifier.height(SettingsSpace.small4))
            }
        }
    }
}

/**
 * One `resolveIdentifier` round-trip per configured key.
 *
 * Blocking binder calls: only ever call this on [Dispatchers.IO].
 */
private fun loadVerifyRows(packageName: String): List<PkVerifyRow> {
    val mgr = privacyKitManager() ?: return emptyList()
    val rows = ArrayList<PkVerifyRow>()
    for (item in PrivacyKitIdentifierCatalog.allItems) {
        val type = try {
            mgr.getRuleType(packageName, item.key)
        } catch (e: Exception) {
            PkRuleType.REAL.serverValue
        }
        if (type == PkRuleType.REAL.serverValue) continue
        val real = pkRealValueOf(item.key)
        val seen = if (real == null) {
            null
        } else {
            try {
                mgr.resolveIdentifier(packageName, item.key, real)
            } catch (e: Exception) {
                null
            }
        }
        rows.add(
            PkVerifyRow(
                label = item.label,
                realValue = real,
                seenValue = seen,
                enforced = PrivacyKitIdentifierCatalog.enforcementOf(item.key) ==
                        PkEnforcement.FULL,
            ),
        )
    }
    return rows
}

/**
 * The real value of [key] as seen by Settings itself.
 *
 * Only the globally-readable identifiers are here. Per-app derived values
 * (android_id, app_set_id, gsf_id, media_drm_id, first_install_time, the
 * firebase ids) are minted per package and Settings cannot read another app's
 * copy, so they deliberately return null and render as "not readable" rather
 * than being guessed at.
 */
private fun pkRealValueOf(key: String): String? = try {
    when (key) {
        PrivacyKitKeys.KEY_BUILD_MODEL -> Build.MODEL
        PrivacyKitKeys.KEY_BUILD_BRAND -> Build.BRAND
        PrivacyKitKeys.KEY_BUILD_MANUFACTURER -> Build.MANUFACTURER
        PrivacyKitKeys.KEY_BUILD_FINGERPRINT -> Build.FINGERPRINT
        PrivacyKitKeys.KEY_BUILD_BOARD -> Build.BOARD
        PrivacyKitKeys.KEY_BUILD_DEVICE -> Build.DEVICE
        PrivacyKitKeys.KEY_BUILD_PRODUCT -> Build.PRODUCT
        PrivacyKitKeys.KEY_BUILD_HARDWARE -> Build.HARDWARE
        PrivacyKitKeys.KEY_BUILD_ID -> Build.ID
        PrivacyKitKeys.KEY_BUILD_TYPE -> Build.TYPE
        PrivacyKitKeys.KEY_BUILD_TAGS -> Build.TAGS
        PrivacyKitKeys.KEY_BUILD_DISPLAY -> Build.DISPLAY
        PrivacyKitKeys.KEY_BUILD_BOOTLOADER -> Build.BOOTLOADER
        PrivacyKitKeys.KEY_BUILD_HOST -> Build.HOST
        PrivacyKitKeys.KEY_BUILD_USER -> Build.USER
        PrivacyKitKeys.KEY_BUILD_SOC_MANUFACTURER -> Build.SOC_MANUFACTURER
        PrivacyKitKeys.KEY_BUILD_SOC_MODEL -> Build.SOC_MODEL
        PrivacyKitKeys.KEY_BUILD_RADIO_VERSION -> Build.getRadioVersion()
        PrivacyKitKeys.KEY_SUPPORTED_ABIS -> Build.SUPPORTED_ABIS.joinToString(",")
        PrivacyKitKeys.KEY_SUPPORTED_32_BIT_ABIS -> Build.SUPPORTED_32_BIT_ABIS.joinToString(",")
        PrivacyKitKeys.KEY_SUPPORTED_64_BIT_ABIS -> Build.SUPPORTED_64_BIT_ABIS.joinToString(",")
        PrivacyKitKeys.KEY_OS_VERSION_RELEASE -> Build.VERSION.RELEASE
        PrivacyKitKeys.KEY_OS_VERSION_INCREMENTAL -> Build.VERSION.INCREMENTAL
        PrivacyKitKeys.KEY_OS_SECURITY_PATCH -> Build.VERSION.SECURITY_PATCH
        PrivacyKitKeys.KEY_OS_CODENAME -> Build.VERSION.CODENAME
        PrivacyKitKeys.KEY_OS_BASE_OS -> Build.VERSION.BASE_OS
        PrivacyKitKeys.KEY_DEVICE_TIMEZONE -> java.util.TimeZone.getDefault().id
        PrivacyKitKeys.KEY_DEVICE_LOCALE -> java.util.Locale.getDefault().toLanguageTag()
        else -> null
    }
} catch (e: Exception) {
    null
}

/** "Spoofed / Matches your real device / Configured but not enforced" plus both values. */
private fun verifySummary(context: Context, row: PkVerifyRow): String {
    val real = row.realValue
    val seen = row.seenValue
    if (real == null || seen == null) {
        return context.getString(R.string.privacykit_verify_status_unknown)
    }
    val status = when {
        seen != real -> context.getString(R.string.privacykit_verify_status_spoofed)
        row.enforced -> context.getString(R.string.privacykit_verify_status_matches_real)
        else -> context.getString(R.string.privacykit_verify_status_not_enforced)
    }
    return context.getString(R.string.privacykit_verify_row_summary, status, seen, real)
}
