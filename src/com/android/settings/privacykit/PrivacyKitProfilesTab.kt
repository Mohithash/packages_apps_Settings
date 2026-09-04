/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RECONSTRUCTED 2026-08-19 after the build box was purged.
 *
 * Sources, in order of authority:
 *   - transcript whole-file snapshot 2026-08-18T07:11Z (scratchpad/pk/PrivacyKitProfilesTab.kt),
 *     which `git apply -R` accepts with ZERO fuzz against
 *     snap18c/patches/packages_apps_Settings.patch -> it IS the final r49 state;
 *   - the r49 Settings.apk decompile (PrivacyKitProfilesTabKt) cross-checked: every
 *     top-level function and every string resource matches, with no extras on either side.
 * No TODOs: this file is believed byte-faithful to what r49 was built from.
 */
package com.android.settings.privacykit

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.ServiceManager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

import com.android.settings.R
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.android.settingslib.spa.framework.theme.SettingsDimension

import android.privacykit.IPrivacyKitManager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun privacyKitManager(): IPrivacyKitManager? =
    IPrivacyKitManager.Stub.asInterface(ServiceManager.getService("privacykit"))

/**
 * The profile a hub screen is currently open for. Profile ids are namespaced
 * per package in PrivacyKitProfileStore, so both halves are needed to name one.
 */
private data class PkProfileTarget(val packageName: String, val profileId: String)

/** One profile row in the cross-app list, fully resolved off the main thread. */
private data class PkGroupProfileItem(
    val id: String,
    val name: String,
    val mode: String,
    val isActive: Boolean,
    /** PrivacyKitProfileStore COLOR_* token, or null when the user picked none. */
    val color: String?,
    /** The user's free-text note, or null. Already trimmed/truncated by the store. */
    val note: String?,
)

/**
 * A single managed package's resolved label/icon plus its profiles, used to
 * render one group (header + profile rows) in the flat list.
 */
private data class PkAppProfileGroup(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val profiles: List<PkGroupProfileItem>,
)

/**
 * Profiles tab: a flat, cross-app view of every profile of every managed
 * app in one scrollable list, grouped under a small header row per app, so
 * the user doesn't have to drill into each app in the Apps tab to see e.g.
 * every "isolated" profile at a glance. Tapping a profile row makes it the
 * active profile for that package and opens that profile's hub.
 */
@Composable
fun PrivacyKitProfilesTab() {
    val context = LocalContext.current
    var hubTarget by remember { mutableStateOf<PkProfileTarget?>(null) }
    var refreshToken by remember { mutableStateOf(0) }

    val hub = hubTarget
    if (hub != null) {
        // One shared closure behind both the BackHandler and the hub's own
        // onBack: the hub can rename, re-colour and re-mode the very profile
        // this list is showing, so the list has to reload whichever of the two
        // dispatcher-registered handlers wins. Explicitly typed because
        // `refreshToken++` would otherwise infer `() -> Int`.
        val closeHub: () -> Unit = {
            hubTarget = null
            refreshToken++
        }
        BackHandler(onBack = closeHub)
        PrivacyKitProfileHubScreen(
            packageName = hub.packageName,
            profileId = hub.profileId,
            onBack = closeHub,
        )
        return
    }

    // null == still loading; empty list == genuinely nothing managed yet. Keeping
    // the two apart stops the empty state from flashing every time the tab opens.
    val groups by produceState<List<PkAppProfileGroup>?>(
            initialValue = null, context, refreshToken) {
        value = withContext(Dispatchers.IO) { loadProfileGroups(context) }
    }

    Box(Modifier.fillMaxSize()) {
        val list = groups
        when {
            list == null -> PrivacyKitLoading()
            list.isEmpty() -> PrivacyKitEmptyState(
                icon = Icons.Outlined.Person,
                title = stringResource(R.string.privacykit_profiles_empty_title),
                description = stringResource(R.string.privacykit_profiles_tab_empty_summary),
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                list.forEach { group ->
                    item(key = "header_${group.packageName}") {
                        ProfilesGroupHeader(label = group.label, icon = group.icon)
                    }
                    items(group.profiles, key = { "${group.packageName}_${it.id}" }) { profile ->
                        ProfileSummaryRow(
                            profile = profile,
                            onOpen = {
                                try {
                                    privacyKitManager()?.setActiveProfile(
                                            group.packageName, profile.id)
                                } catch (e: Exception) {
                                    // Fail open: setActiveProfile can throw
                                    // SecurityException (enforceCallingPermission)
                                    // as well as RemoteException, and neither is
                                    // worth crashing Settings over.
                                }
                                refreshToken++
                                hubTarget = PkProfileTarget(
                                        group.packageName, profile.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Resolves every managed package's label/icon plus each of its profiles'
 * name/mode/active state. Deliberately blocking - callers run it on
 * [Dispatchers.IO] - because it costs several binder round-trips per profile.
 */
private fun loadProfileGroups(context: Context): List<PkAppProfileGroup> {
    val pm = context.packageManager
    val mgr = privacyKitManager() ?: return emptyList()
    val packages = try {
        mgr.getManagedPackages() ?: emptyList<String>()
    } catch (e: Exception) {
        return emptyList()
    }
    return packages.mapNotNull { pkg ->
        val appInfo = try {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } catch (e: PackageManager.NameNotFoundException) {
            return@mapNotNull null
        }
        val profiles = try {
            val activeId = mgr.getActiveProfileId(pkg)
            (mgr.listProfileIds(pkg) ?: emptyList<String>()).map { id ->
                PkGroupProfileItem(
                    id = id,
                    name = mgr.getProfileName(pkg, id) ?: id,
                    mode = mgr.getProfileMode(pkg, id) ?: "",
                    isActive = id == activeId,
                    // Caught individually rather than under the outer try:
                    // colour and note are decoration, and a single throw from
                    // either would otherwise collapse this package's whole
                    // profile list to empty.
                    color = try {
                        mgr.getProfileColor(pkg, id)
                    } catch (e: Exception) {
                        null
                    },
                    note = try {
                        mgr.getProfileNote(pkg, id)
                    } catch (e: Exception) {
                        null
                    },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        PkAppProfileGroup(
            packageName = pkg,
            label = appInfo.loadLabel(pm).toString(),
            icon = appInfo.loadIcon(pm),
            profiles = profiles,
        )
    }.sortedBy { it.label.lowercase() }
}

@Composable
private fun PrivacyKitLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PrivacyKitEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(SettingsDimension.paddingExtraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SettingsDimension.iconLarge),
        )
        Spacer(Modifier.height(SettingsDimension.paddingLarge))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(SettingsDimension.paddingSmall))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProfilesGroupHeader(label: String, icon: Drawable?) {
    Row(
        Modifier.fillMaxWidth().padding(
            start = SettingsDimension.itemPaddingStart,
            end = SettingsDimension.itemPaddingEnd,
            top = SettingsDimension.paddingExtraLarge,
            bottom = SettingsDimension.paddingSmall,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = rememberDrawablePainter(icon),
            contentDescription = null,
            modifier = Modifier.size(SettingsDimension.appIconItemSize),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = SettingsDimension.paddingLarge),
        )
    }
}

@Composable
private fun ProfileSummaryRow(profile: PkGroupProfileItem, onOpen: () -> Unit) {
    val activeLabel = stringResource(R.string.privacykit_active)
    val mode = pkModeLabel(profile.mode)
    val summary = if (profile.isActive) "$mode  •  $activeLabel" else mode
    // Local val so the null check below narrows the type inside the lambda.
    val note = profile.note

    Row(
        Modifier.fillMaxWidth()
                .clickable(onClick = onOpen)
                .heightIn(min = SettingsDimension.preferenceMinHeight)
                .padding(
                    // Indented past the group header's app icon so profile rows
                    // read as children of the app they belong to.
                    start = SettingsDimension.itemPaddingStart +
                            SettingsDimension.appIconItemSize + SettingsDimension.paddingLarge,
                    end = SettingsDimension.itemPaddingEnd,
                    top = SettingsDimension.itemPaddingVertical,
                    bottom = SettingsDimension.itemPaddingVertical,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Same dot, same token->colour mapping and the same "draw
                // nothing when uncoloured" rule as the Apps tab: one profile
                // must not look colour-coded in one tab and plain in the other.
                // Editing stays in the Apps tab - this list has no per-row
                // actions at all.
                PkProfileColorDot(profile.color)
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note != null && note.isNotBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (profile.isActive) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = activeLabel,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(SettingsDimension.itemIconSize),
            )
        }
    }
}

