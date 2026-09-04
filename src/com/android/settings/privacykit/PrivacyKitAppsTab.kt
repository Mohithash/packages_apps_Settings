/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RECONSTRUCTED 2026-08-19 after the build box was purged.
 *
 * Sources, in order of authority:
 *   - snap18c/patches/packages_apps_Settings.patch: this file's 25 hunks were replayed
 *     verbatim, and the result `git apply -R`s back to a 1009-line pre-image with ZERO
 *     fuzz and ZERO hunk offsets, then forward again byte-identically -> every context
 *     line, every changed line AND every between-hunk gap length is pinned to the
 *     post-18c state, i.e. what r49 was built from;
 *   - the transcript whole-file snapshot 2026-08-16 (pkrecover/best/PrivacyKitAppsTab.kt)
 *     supplied the text of the unchanged gaps. That snapshot is a divergent branch (it
 *     carries an in-tab colour/note editor that 18c moved into the profile hub), so only
 *     its gap regions were used - and the zero-offset reverse-apply proves each gap has
 *     exactly the right number of lines;
 *   - the r49 Settings.apk decompile (PrivacyKitAppsTabKt, 4099 lines) cross-checked:
 *     all 27 top-level functions match with no extras on either side, all 51
 *     R.string/R.plurals.privacykit_* references match exactly, and PkProfileItem's
 *     (id, name, mode, isActive, color, note, lastUsedAt) and PkManagedAppItem's
 *     (packageInfo, label, icon, profileCount, activeProfileName) constructor orders,
 *     ProfileRow's 8-parameter shape and the ProfileRow padding quadruple were read
 *     back out of the dex.
 * No TODOs: this file is believed byte-faithful to what r49 was built from.
 *
 * pkProfileColor / pkProfileColorName / PkProfileColorDot are defined HERE (18c added
 * them to this file), which is what PrivacyKitUi.kt and PrivacyKitProfilesTab.kt call.
 */
package com.android.settings.privacykit

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.graphics.drawable.Drawable
import android.os.ServiceManager
import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import android.app.usage.StorageStatsManager
import android.os.Process
import android.text.format.Formatter
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.android.settings.R
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.android.settingslib.spa.framework.theme.SettingsDimension

import android.privacykit.IPrivacyKitManager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun privacyKitManager(): IPrivacyKitManager? =
    IPrivacyKitManager.Stub.asInterface(ServiceManager.getService("privacykit"))

/*
 * Profile colour tokens, mirroring PrivacyKitProfileStore.COLOR_*.
 *
 * Mirrored by hand for the same reason MODE_* above is: PrivacyKitProfileStore
 * lives in com.android.server.privacykit and is not on the Settings app's
 * classpath, and the backend deliberately exposes no "list the valid tokens"
 * binder call (the AIDL documents the set instead). setProfileColor ignores any
 * token it does not recognise, so drift here silently does nothing rather than
 * corrupting a profile - but keep the two lists identical anyway.
 *
 * The backend stores a token rather than a packed ARGB int precisely so that
 * colour resolution can happen in the only layer that knows the current theme.
 * That is [pkProfileColor].
 */
internal const val PK_COLOR_BLUE = "blue"
internal const val PK_COLOR_GREEN = "green"
internal const val PK_COLOR_AMBER = "amber"
internal const val PK_COLOR_RED = "red"
internal const val PK_COLOR_PURPLE = "purple"
internal const val PK_COLOR_TEAL = "teal"
internal const val PK_COLOR_GREY = "grey"

/** Every colour token, in picker order. "No colour" (null) is offered separately. */
internal val PK_PROFILE_COLORS = listOf(
    PK_COLOR_BLUE,
    PK_COLOR_GREEN,
    PK_COLOR_AMBER,
    PK_COLOR_RED,
    PK_COLOR_PURPLE,
    PK_COLOR_TEAL,
    PK_COLOR_GREY,
)

/**
 * Mirrors PrivacyKitProfileStore.MAX_NOTE_LENGTH. The store truncates rather
 * than rejects, so the editor caps input at the same number instead of letting
 * the user type text that would be silently dropped on save.
 */
private const val PK_MAX_NOTE_LENGTH = 256

/**
 * PrivacyKitProfileStore.RULE_CUSTOM - a literal value the user (or a template)
 * supplied.
 *
 * Declared here rather than shared: the other screens in this package each keep
 * their own file-private copy, and top-level `private` in Kotlin is file scoped,
 * so theirs are not visible from this file.
 */
private const val RULE_CUSTOM = 4

/**
 * Below this many managed apps the list is short enough to scan by eye, so the
 * filter field is hidden to keep the screen uncluttered.
 */
private const val APP_SEARCH_THRESHOLD = 3

/** A managed package plus everything the list row needs, resolved once off the main thread. */
private data class PkManagedAppItem(
    val packageInfo: PackageInfo,
    val label: String,
    val icon: Drawable?,
    val profileCount: Int,
    val activeProfileName: String?,
)

/** One profile of one package, with its display name/mode and active state resolved. */
private data class PkProfileItem(
    val id: String,
    val name: String,
    val mode: String,
    val isActive: Boolean,
    /** PrivacyKitProfileStore COLOR_* token, or null when the user picked none. */
    val color: String?,
    /** The user's free-text note, or null. Already trimmed/truncated by the store. */
    val note: String?,
    /** Millis when this profile was last made active; 0 when never. */
    val lastUsedAt: Long = 0L,
)

/**
 * Apps tab: apps are explicitly added via the + FAB (mirroring the real
 * PrivacyKit app), each with one or more named Profiles ("accounts") that
 * can be created, renamed, re-moded, duplicated, made active, launched, or
 * deleted. Tapping a profile makes it active and opens the identifier
 * catalog, which always operates on whichever profile is currently active
 * for that package.
 */
@Composable
fun PrivacyKitAppsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var viewingPackage by remember { mutableStateOf<PackageInfo?>(null) }
    var showAppPicker by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }

    val viewing = viewingPackage
    if (viewing != null) {
        BackHandler { viewingPackage = null }
        ProfileListScreen(
            packageInfo = viewing,
            onBack = { viewingPackage = null },
            onProfilesChanged = { refreshToken++ },
        )
        return
    }

    if (showAppPicker) {
        PkAppPickerSheet(
            title = stringResource(R.string.privacykit_add_app),
            onDismiss = { showAppPicker = false },
            onAppPicked = { app ->
                showAppPicker = false
                // createProfile is a binder call that used to run on the main
                // thread from here. The sheet hands the pick back on the main
                // thread, so both the write and the PackageInfo re-resolve go
                // to IO before the profile list is opened.
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            privacyKitManager()?.createProfile(
                                    app.packageName, "Default", MODE_HYBRID)
                        } catch (e: Exception) {
                            // Fail open. PrivacyKitService may not be up
                            // (RemoteException), or may reject the caller -
                            // enforceCallingPermission throws SecurityException,
                            // a RuntimeException. Either way the pick just will
                            // not stick this time; the user can retry.
                        }
                    }
                    val picked = withContext(Dispatchers.IO) {
                        try {
                            context.packageManager.getPackageInfo(
                                    app.packageName,
                                    PackageInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
                        } catch (e: Exception) {
                            null
                        }
                    }
                    refreshToken++
                    viewingPackage = picked
                }
            },
            loadApps = { loadAddablePrivacyKitApps(context) },
        )
    }

    // null == still loading; empty list == genuinely no managed apps. Keeping the
    // two apart stops the "no apps added yet" empty state from flashing on entry.
    val managedApps by produceState<List<PkManagedAppItem>?>(
            initialValue = null, context, refreshToken) {
        value = withContext(Dispatchers.IO) { loadPkManagedAppItems(context) }
    }

    Box(Modifier.fillMaxSize()) {
        val apps = managedApps
        when {
            apps == null -> PrivacyKitLoading()
            apps.isEmpty() -> PrivacyKitEmptyState(
                icon = Icons.Outlined.Apps,
                title = stringResource(R.string.privacykit_apps_empty_title),
                description = stringResource(R.string.privacykit_apps_empty_summary),
                modifier = Modifier.fillMaxSize(),
            )
            else -> {
                val showSearch = apps.size > APP_SEARCH_THRESHOLD
                val trimmed = query.trim()
                val filtered = if (!showSearch || trimmed.isEmpty()) apps else apps.filter {
                    it.label.contains(trimmed, ignoreCase = true) ||
                        it.packageInfo.packageName.contains(trimmed, ignoreCase = true)
                }
                Column(Modifier.fillMaxSize()) {
                    if (showSearch) {
                        AppSearchField(query = query, onQueryChange = { query = it })
                    }
                    if (filtered.isEmpty()) {
                        PrivacyKitEmptyState(
                            icon = Icons.Filled.Search,
                            title = stringResource(R.string.privacykit_apps_no_matches_title),
                            description =
                                    stringResource(R.string.privacykit_apps_no_matches_summary),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(filtered, key = { it.packageInfo.packageName }) { app ->
                                PkAppRow(app) { viewingPackage = app.packageInfo }
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { showAppPicker = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.privacykit_add_app))
        }
    }
}

/**
 * Resolves every managed package's label/icon/profile count/active profile name in
 * one pass. Deliberately blocking - callers run it on [Dispatchers.IO] - because
 * each package costs three binder round-trips plus icon/label loading.
 */
private fun loadPkManagedAppItems(context: Context): List<PkManagedAppItem> {
    val pm = context.packageManager
    val mgr = privacyKitManager() ?: return emptyList()
    val managed = try {
        mgr.getManagedPackages() ?: emptyList<String>()
    } catch (e: Exception) {
        return emptyList()
    }
    return managed.mapNotNull { pkg ->
        val packageInfo = try {
            pm.getPackageInfo(pkg, PackageInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } catch (e: PackageManager.NameNotFoundException) {
            return@mapNotNull null
        }
        val appInfo = packageInfo.applicationInfo ?: return@mapNotNull null
        val profileCount = try {
            mgr.listProfileIds(pkg)?.size ?: 0
        } catch (e: Exception) {
            0
        }
        val activeName = try {
            val activeId = mgr.getActiveProfileId(pkg)
            if (activeId != null) mgr.getProfileName(pkg, activeId) else null
        } catch (e: Exception) {
            null
        }
        PkManagedAppItem(
            packageInfo = packageInfo,
            label = appInfo.loadLabel(pm).toString(),
            icon = appInfo.loadIcon(pm),
            profileCount = profileCount,
            activeProfileName = activeName,
        )
    }.sortedBy { it.label.lowercase() }
}

/** Loads every profile of one package together with which one is currently active. */
private fun loadProfiles(packageName: String): List<PkProfileItem> {
    val mgr = privacyKitManager() ?: return emptyList()
    return try {
        val activeId = mgr.getActiveProfileId(packageName)
        (mgr.listProfileIds(packageName) ?: emptyList<String>()).map { id ->
            PkProfileItem(
                id = id,
                name = mgr.getProfileName(packageName, id) ?: id,
                mode = mgr.getProfileMode(packageName, id) ?: "",
                isActive = id == activeId,
                // Caught individually rather than under the outer try: colour
                // and note are decoration, and a single throw from either (a
                // Settings.apk pushed onto a system_server that predates these
                // two transactions) would otherwise empty the whole list and
                // make every profile disappear.
                color = try {
                    mgr.getProfileColor(packageName, id)
                } catch (e: Exception) {
                    null
                },
                lastUsedAt = try {
                    mgr.getProfileLastUsed(packageName, id)
                } catch (e: Exception) {
                    0L
                },
                note = try {
                    mgr.getProfileNote(packageName, id)
                } catch (e: Exception) {
                    null
                },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Client-side profile duplication.
 *
 * There is no clone method on IPrivacyKitManager, and the rule getters/setters
 * (getRuleType/getRuleValue/setIdentifierRule) all operate on whichever profile is
 * *active* for the package at the moment they are called - they take no profileId.
 * So duplicating means temporarily re-pointing the active profile at the source,
 * reading, re-pointing it at the new clone, writing, and then putting the active
 * profile back exactly where the user left it.
 *
 * This function is intentionally NOT a suspend function and contains no suspension
 * points: callers wrap the whole thing in a single withContext(Dispatchers.IO), so
 * coroutine cancellation (e.g. the user navigating away mid-clone) can never
 * interrupt it part-way through and strand the wrong profile as active.
 *
 * Returns true only if the clone was created and its rules written.
 */
private fun cloneProfileBlocking(
    mgr: IPrivacyKitManager,
    packageName: String,
    sourceProfileId: String,
    newName: String,
): Boolean {
    val originalActiveId: String? = try {
        mgr.getActiveProfileId(packageName)
    } catch (e: Exception) {
        null
    }
    // Where the active profile has to end up when we're done. If the package
    // somehow reported no active profile, falling back to the source is the
    // least surprising outcome.
    val restoreTargetId = originalActiveId ?: sourceProfileId
    // Only restore if we actually moved the active profile, so a clone that
    // fails before touching anything leaves no trace (and no history entry).
    var retargeted = false
    try {
        // 1. Point the active profile at the source so the rule getters read it.
        if (originalActiveId != sourceProfileId) {
            mgr.setActiveProfile(packageName, sourceProfileId)
            retargeted = true
        }
        // 2. Snapshot every configured (non-REAL) rule off the source profile.
        //    REAL is the absence of a rule, so those need no copying at all.
        val copied = ArrayList<Triple<String, Int, String?>>()
        for (item in PrivacyKitIdentifierCatalog.allItems) {
            val ruleType = mgr.getRuleType(packageName, item.key)
            if (ruleType == PkRuleType.REAL.serverValue) continue
            val customValue = if (ruleType == PkRuleType.CUSTOM.serverValue) {
                mgr.getRuleValue(packageName, item.key)
            } else {
                null
            }
            copied.add(Triple(item.key, ruleType, customValue))
        }
        // 3. Create the clone carrying the source's mode. createProfile only
        //    auto-activates when the package had no active profile at all, which
        //    cannot be true here, so step 4 re-points it explicitly rather than
        //    depending on that behaviour either way.
        val sourceMode = try {
            mgr.getProfileMode(packageName, sourceProfileId) ?: MODE_HYBRID
        } catch (e: Exception) {
            MODE_HYBRID
        }
        val newProfileId = mgr.createProfile(packageName, newName, sourceMode) ?: return false
        // 4. Point the active profile at the clone and replay the snapshot into it.
        mgr.setActiveProfile(packageName, newProfileId)
        retargeted = true
        for ((key, ruleType, customValue) in copied) {
            mgr.setIdentifierRule(packageName, key, ruleType, customValue)
        }
        return true
    } catch (e: Exception) {
        return false
    } finally {
        // 5. Always hand the active profile back. Leaving the clone (or the
        //    source) active would silently change which identity every later
        //    read for this package resolves against.
        if (retargeted) {
            try {
                mgr.setActiveProfile(packageName, restoreTargetId)
            } catch (e: Exception) {
            }
        }
    }
}

/**
 * Duplicates a profile, preferring the server-side clone.
 *
 * IPrivacyKitManager#cloneProfile copies the mode, the colour, the note and
 * every rule (including each CUSTOM literal) in one transaction, and never
 * re-points the package's active profile: it cannot strand the wrong identity
 * as active, it records one PROFILE_CREATED history entry instead of a burst of
 * PROFILE_ACTIVATED ones, and it is the only path that carries a profile's
 * colour and note across to the copy.
 *
 * [cloneProfileBlocking] stays as the fallback for the one case that still
 * needs it - a Settings.apk pushed onto a system_server built before that
 * transaction existed, where the call comes back null (or throws) rather than
 * cloning. Its no-suspension-point contract is unchanged, and this wrapper adds
 * none of its own, so callers can still wrap the whole thing in a single
 * withContext(Dispatchers.IO).
 */
private fun duplicateProfileBlocking(
    mgr: IPrivacyKitManager,
    packageName: String,
    sourceProfileId: String,
    newName: String,
): Boolean {
    try {
        if (mgr.cloneProfile(packageName, sourceProfileId, newName) != null) {
            return true
        }
    } catch (e: Exception) {
        // Fall through to the client-side path below.
    }
    return cloneProfileBlocking(mgr, packageName, sourceProfileId, newName)
}

private fun pluralProfilesLabel(context: Context, count: Int): String =
    context.resources.getQuantityString(R.plurals.privacykit_profile_count, count, count)

/** "3 profiles  •  Work" - profile count plus which one is currently active. */
private fun managedAppSummary(context: Context, app: PkManagedAppItem): String {
    val counts = pluralProfilesLabel(context, app.profileCount)
    val active = app.activeProfileName
    return if (active.isNullOrBlank()) counts else "$counts  •  $active"
}

/**
 * Resolves a PrivacyKitProfileStore colour token to a Material colour that is
 * legible on both the light and the dark Settings surface.
 *
 * Each token carries a light/dark pair rather than one fixed hue, because a
 * single mid-tone value that reads well on the light surface washes out on the
 * dark one and vice versa - which is the whole reason the backend persists a
 * token instead of a packed ARGB int. The two halves are the standard palette's
 * 600 and 200 steps, the same light/dark relationship AOSP uses for its own
 * accent colours.
 *
 * Returns null both for "no colour" and for a token this build does not know,
 * so callers draw nothing at all rather than an arbitrary fallback that would
 * claim the profile is colour-coded when it is not.
 */
@Composable
internal fun pkProfileColor(token: String?): Color? {
    if (token == null) return null
    val dark = isSystemInDarkTheme()
    return when (token) {
        PK_COLOR_BLUE -> if (dark) Color(0xFF8AB4F8) else Color(0xFF1A73E8)
        PK_COLOR_GREEN -> if (dark) Color(0xFF81C995) else Color(0xFF1E8E3E)
        PK_COLOR_AMBER -> if (dark) Color(0xFFFDD663) else Color(0xFFE37400)
        PK_COLOR_RED -> if (dark) Color(0xFFF28B82) else Color(0xFFD93025)
        PK_COLOR_PURPLE -> if (dark) Color(0xFFD7AEFB) else Color(0xFF9334E6)
        PK_COLOR_TEAL -> if (dark) Color(0xFF78D9EC) else Color(0xFF007B83)
        PK_COLOR_GREY -> if (dark) Color(0xFF9AA0A6) else Color(0xFF5F6368)
        else -> null
    }
}

/** Localized name of a colour token; the null token is "No colour". */
@Composable
internal fun pkProfileColorName(token: String?): String = when (token) {
    PK_COLOR_BLUE -> stringResource(R.string.privacykit_color_blue)
    PK_COLOR_GREEN -> stringResource(R.string.privacykit_color_green)
    PK_COLOR_AMBER -> stringResource(R.string.privacykit_color_amber)
    PK_COLOR_RED -> stringResource(R.string.privacykit_color_red)
    PK_COLOR_PURPLE -> stringResource(R.string.privacykit_color_purple)
    PK_COLOR_TEAL -> stringResource(R.string.privacykit_color_teal)
    PK_COLOR_GREY -> stringResource(R.string.privacykit_color_grey)
    else -> stringResource(R.string.privacykit_profile_color_none)
}

/**
 * The dot drawn immediately before a profile's name, shared by the Apps tab's
 * profile list, the Profiles tab and the colour picker.
 *
 * Draws nothing - not even a gap - when the profile has no colour, so a
 * profile the user never coloured looks exactly as it did before colours
 * existed, and "no colour" stays visibly different from "deliberately grey".
 */
@Composable
internal fun PkProfileColorDot(token: String?) {
    val color = pkProfileColor(token) ?: return
    Spacer(
        Modifier
            .padding(end = SettingsDimension.paddingSmall)
            .size(10.dp)
            .background(color = color, shape = CircleShape),
    )
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
private fun AppSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        label = { Text(stringResource(R.string.privacykit_search_apps)) },
        modifier = Modifier.fillMaxWidth()
                .padding(SettingsDimension.textFieldPadding)
                .padding(vertical = SettingsDimension.paddingSmall),
    )
}

@Composable
private fun PkAppRow(app: PkManagedAppItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val summary = managedAppSummary(context, app)
    Row(
        Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = SettingsDimension.preferenceMinHeight)
                .padding(
                    start = SettingsDimension.itemPaddingStart,
                    end = SettingsDimension.itemPaddingEnd,
                    top = SettingsDimension.itemPaddingVertical,
                    bottom = SettingsDimension.itemPaddingVertical,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = rememberDrawablePainter(app.icon),
            contentDescription = null,
            modifier = Modifier.size(SettingsDimension.appIconItemSize),
        )
        Column(Modifier.weight(1f).padding(start = SettingsDimension.paddingLarge)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProfileListScreen(
    packageInfo: PackageInfo,
    onBack: () -> Unit,
    onProfilesChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pkg = packageInfo.packageName
    val label = packageInfo.applicationInfo?.loadLabel(context.packageManager).toString()
    var showCreateDialog by remember { mutableStateOf(false) }
    // One storage query per package, not per row: the figure is a property of
    // the app, and only an Isolated profile has separate data anyway.
    val appDataBytes by produceState<Long?>(initialValue = null, pkg) {
        value = withContext(Dispatchers.IO) { pkAppDataBytes(context, pkg) }
    }
    val appDataLabel = appDataBytes?.let { Formatter.formatFileSize(context, it) }
    var refreshToken by remember { mutableStateOf(0) }
    var confirmDeleteTarget by remember { mutableStateOf<PkProfileItem?>(null) }
    var hubTarget by remember { mutableStateOf<PkProfileItem?>(null) }
    var cloning by remember { mutableStateOf(false) }
    var aiRunning by remember { mutableStateOf(false) }

    val hub = hubTarget
    if (hub != null) {
        // One shared closure behind both the BackHandler and the hub's own
        // onBack, so the refresh happens whichever of the two dispatcher-
        // registered handlers wins: the hub can rename, re-colour, re-mode and
        // rewrite the rules of the very profile this list is showing.
        // Explicitly typed because `refreshToken++` would otherwise infer
        // `() -> Int`.
        val closeHub: () -> Unit = {
            hubTarget = null
            refreshToken++
            onProfilesChanged()
        }
        BackHandler(onBack = closeHub)
        PrivacyKitProfileHubScreen(
            packageName = pkg,
            profileId = hub.id,
            onBack = closeHub,
        )
        return
    }

    // A clone temporarily re-points the package's active profile; swallow Back
    // while that is in flight so the screen can't be torn down mid-sequence.
    BackHandler(enabled = cloning) {}

    val profiles by produceState<List<PkProfileItem>?>(initialValue = null, pkg, refreshToken) {
        value = withContext(Dispatchers.IO) { loadProfiles(pkg) }
    }

    if (showCreateDialog) {
        CreateProfileDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, mode ->
                showCreateDialog = false
                try {
                    privacyKitManager()?.createProfile(pkg, name, mode)
                } catch (e: Exception) {
                }
                refreshToken++
                onProfilesChanged()
            },
        )
    }

    val deleteTarget = confirmDeleteTarget
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteTarget = null },
            title = { Text(stringResource(R.string.privacykit_delete_profile_title)) },
            text = { Text(stringResource(R.string.privacykit_delete_profile_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            privacyKitManager()?.deleteProfile(pkg, deleteTarget.id)
                        } catch (e: Exception) {
                        }
                        refreshToken++
                        onProfilesChanged()
                        confirmDeleteTarget = null
                    },
                ) { Text(stringResource(R.string.privacykit_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteTarget = null }) {
                    Text(stringResource(R.string.privacykit_cancel))
                }
            },
        )
    }

    if (cloning) {
        // Modal and un-dismissable on purpose: it blocks any other profile action
        // while cloneProfileBlocking() is juggling the active profile.
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.privacykit_profile_duplicating)) },
            text = {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = SettingsDimension.paddingLarge),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            },
            confirmButton = {},
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.privacykit_back))
            }
            Text(label, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
            // One tap: new profile + AI identity + launch. The same generator
            // is still reachable per-profile from the template picker; this is
            // the shortcut, not a second source of truth.
            if (aiRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(
                    onClick = {
                        if (!pkAiConfigured(context)) {
                            Toast.makeText(
                                context,
                                R.string.privacykit_ai_not_configured,
                                Toast.LENGTH_LONG,
                            ).show()
                            return@IconButton
                        }
                        aiRunning = true
                        scope.launch {
                            val made = pkAiCreateProfileAndLaunch(context, pkg)
                            aiRunning = false
                            refreshToken++
                            onProfilesChanged()
                            if (made == null) {
                                Toast.makeText(
                                    context,
                                    R.string.privacykit_device_profile_ai_failed,
                                    Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.privacykit_ai_launching, made),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription =
                            stringResource(R.string.privacykit_ai_quick_profile),
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            val list = profiles
            // Only the single most recently opened profile gets the mark; 0 means
            // nothing has been opened yet, in which case no row is marked.
            val newestUsedAt = list?.maxOfOrNull { it.lastUsedAt } ?: 0L
            when {
                list == null -> PrivacyKitLoading()
                list.isEmpty() -> PrivacyKitEmptyState(
                    icon = Icons.Outlined.Person,
                    title = stringResource(R.string.privacykit_profiles_empty_title),
                    description = stringResource(R.string.privacykit_profiles_empty_summary),
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { entry ->
                        ProfileRow(
                            entry = entry,
                            appDataLabel = appDataLabel,
                            isLastOpened = entry.lastUsedAt > 0L &&
                                    entry.lastUsedAt == newestUsedAt,
                            onOpen = {
                                // Make this profile active before opening its
                                // hub: every write reachable from there
                                // (setIdentifierRule, setBooleanControl) takes
                                // no profile id and lands on whichever profile
                                // is active for the package.
                                try {
                                    privacyKitManager()?.setActiveProfile(pkg, entry.id)
                                } catch (e: Exception) {
                                }
                                refreshToken++
                                onProfilesChanged()
                                hubTarget = entry
                            },
                            onSetActive = {
                                try {
                                    privacyKitManager()?.setActiveProfile(pkg, entry.id)
                                } catch (e: Exception) {
                                }
                                refreshToken++
                                onProfilesChanged()
                            },
                            onLaunch = {
                                try {
                                    privacyKitManager()?.setActiveProfile(pkg, entry.id)
                                } catch (e: Exception) {
                                }
                                refreshToken++
                                onProfilesChanged()
                                context.packageManager.getLaunchIntentForPackage(pkg)?.let {
                                    context.startActivity(it)
                                }
                            },
                            onDuplicate = {
                                if (!cloning) {
                                    cloning = true
                                    val copyName = context.getString(
                                            R.string.privacykit_profile_duplicate_name, entry.name)
                                    scope.launch {
                                        val mgr = privacyKitManager()
                                        val ok = if (mgr == null) {
                                            false
                                        } else {
                                            withContext(Dispatchers.IO) {
                                                duplicateProfileBlocking(
                                                        mgr, pkg, entry.id, copyName)
                                            }
                                        }
                                        cloning = false
                                        refreshToken++
                                        onProfilesChanged()
                                        if (!ok) {
                                            Toast.makeText(
                                                context,
                                                R.string.privacykit_profile_duplicate_failed,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                }
                            },
                            onDelete = { confirmDeleteTarget = entry },
                        )
                    }
                }
            }
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            ) {
                Icon(Icons.Filled.Add,
                        contentDescription = stringResource(R.string.privacykit_create_profile))
            }
        }
    }
}

/**
 * app + data + cache bytes for [pkg], or null when the stats query refuses.
 * Blocking - call from Dispatchers.IO, never from composition.
 */
private fun pkAppDataBytes(context: Context, pkg: String): Long? = try {
    val stats = context.getSystemService(StorageStatsManager::class.java)
    val appInfo = context.packageManager
        .getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
    val result = stats?.queryStatsForPackage(
        appInfo.storageUuid, pkg, Process.myUserHandle())
    if (result == null) null else result.appBytes + result.dataBytes + result.cacheBytes
} catch (e: Exception) {
    null
}

@Composable
private fun ProfileRow(
    entry: PkProfileItem,
    /** Formatted app+data+cache size shared by every profile of this package. */
    appDataLabel: String?,
    /** True for the single most recently opened profile of this package. */
    isLastOpened: Boolean,
    onOpen: () -> Unit,
    onSetActive: () -> Unit,
    onLaunch: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val activeLabel = stringResource(R.string.privacykit_active)
    val mode = pkModeLabel(entry.mode)
    val lastOpenedLabel = stringResource(R.string.privacykit_profile_last_opened)
    val summary = buildString {
        append(mode)
        if (entry.isActive) append("  •  ").append(activeLabel)
        if (appDataLabel != null) {
            append("  •  ")
            append(stringResource(R.string.privacykit_profile_app_data, appDataLabel))
        }
        if (isLastOpened) append("  •  ").append(lastOpenedLabel)
    }
    // Local val so the null check below narrows the type inside the lambda.
    val note = entry.note

    Row(
        Modifier.fillMaxWidth()
                .clickable(onClick = onOpen)
                .heightIn(min = SettingsDimension.preferenceMinHeight)
                .padding(
                    start = SettingsDimension.itemPaddingStart,
                    end = SettingsDimension.paddingSmall,
                    top = SettingsDimension.itemPaddingVertical,
                    bottom = SettingsDimension.itemPaddingVertical,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isActive) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = activeLabel,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(SettingsDimension.itemIconSize),
            )
        } else {
            Spacer(Modifier.size(SettingsDimension.itemIconSize))
        }
        Column(
            Modifier.weight(1f).padding(
                start = SettingsDimension.paddingLarge,
                end = SettingsDimension.paddingSmall,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The colour sits next to the name it labels. The active tick
                // keeps its own leading slot, so a coloured profile and an
                // active one never compete for the same piece of the row and
                // a red profile can't be misread as an error state.
                PkProfileColorDot(entry.color)
                Text(
                    text = entry.name,
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
        // Launching the app under a chosen identity is what this whole screen
        // exists for and is by far the most-used action on the row, so it is a
        // real button instead of the second item of an overflow menu. It is
        // deliberately no longer duplicated inside that menu.
        Button(onClick = onLaunch) {
            Text(stringResource(R.string.privacykit_launch))
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription =
                            stringResource(R.string.privacykit_profile_more_actions),
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.privacykit_profile_set_active)) },
                    enabled = !entry.isActive,
                    onClick = {
                        menuExpanded = false
                        onSetActive()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.privacykit_profile_duplicate)) },
                    onClick = {
                        menuExpanded = false
                        onDuplicate()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.privacykit_delete_profile)) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** True when an AI provider is configured well enough to generate an identity. */
private fun pkAiConfigured(context: android.content.Context): Boolean = try {
    PrivacyKitAiGenerator.isConfigured(context)
} catch (e: Exception) {
    false
}

/**
 * One tap on the profile list: generate an identity with AI, put it on a NEW
 * profile, make that profile active, and start the app under it. Returns the
 * generated device name, or null if anything short of that happened.
 *
 * A new profile rather than overwriting the active one: the whole point of the
 * list screen is that profiles are separate identities, and silently rewriting
 * the one the user already tuned would be destructive.
 *
 * The force-stop is not optional. PrivacyKit resolves an app's identity when its
 * process starts, so an app that is already running would come back to the
 * foreground still wearing the previous identity and the new profile would look
 * broken. Settings holds FORCE_STOP_PACKAGES and does this on the App info page.
 *
 * Fails soft at every step - a refused API call, a rejected profile create or an
 * app with no launcher entry all end as null, never an exception out of the
 * composable.
 */
private suspend fun pkAiCreateProfileAndLaunch(
    context: android.content.Context,
    pkg: String,
): String? = withContext(Dispatchers.IO) {
    val template = try {
        PrivacyKitAiGenerator.generate(context)
    } catch (e: Exception) {
        null
    } ?: return@withContext null

    val mgr = privacyKitManager() ?: return@withContext null
    val profileId = try {
        mgr.createProfile(pkg, template.displayName, MODE_HYBRID)
    } catch (e: Exception) {
        null
    } ?: return@withContext null

    try {
        mgr.setActiveProfile(pkg, profileId)
        for ((key, value) in template.toFieldMap()) {
            mgr.setIdentifierRule(pkg, key, RULE_CUSTOM, value)
        }
    } catch (e: Exception) {
        // A partially applied bundle is still reported, so the user can see the
        // profile that was created and inspect it rather than being told nothing
        // happened.
    }

    try {
        context.getSystemService(android.app.ActivityManager::class.java)
            ?.forceStopPackage(pkg)
    } catch (e: Exception) {
        // No permission or the app was not running.
    }
    try {
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    } catch (e: Exception) {
        // No launcher entry, or it was disabled between the lookup and the start.
    }
    template.displayName
}

@Composable
private fun CreateProfileDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(MODE_HYBRID) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.privacykit_create_profile)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.privacykit_profile_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ModeOption(MODE_ISOLATED, mode, R.string.privacykit_mode_isolated) { mode = it }
                ModeOption(MODE_HYBRID, mode, R.string.privacykit_mode_hybrid) { mode = it }
                ModeOption(MODE_SHARED, mode, R.string.privacykit_mode_shared) { mode = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalName = if (name.isBlank()) "Profile" else name
                    onCreate(finalName, mode)
                },
            ) { Text(stringResource(R.string.privacykit_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.privacykit_cancel)) }
        },
    )
}

@Composable
internal fun RenameProfileDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.privacykit_profile_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.privacykit_profile_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onRename(name.trim()) },
            ) { Text(stringResource(R.string.privacykit_profile_rename)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.privacykit_cancel)) }
        },
    )
}

@Composable
internal fun ChangeModeDialog(
    currentMode: String,
    onDismiss: () -> Unit,
    onModeSelected: (String) -> Unit,
) {
    var mode by remember(currentMode) {
        mutableStateOf(if (currentMode.isBlank()) MODE_HYBRID else currentMode)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.privacykit_profile_change_mode)) },
        text = {
            Column {
                ModeOption(MODE_ISOLATED, mode, R.string.privacykit_mode_isolated) { mode = it }
                ModeOption(MODE_HYBRID, mode, R.string.privacykit_mode_hybrid) { mode = it }
                ModeOption(MODE_SHARED, mode, R.string.privacykit_mode_shared) { mode = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onModeSelected(mode) }) {
                Text(stringResource(R.string.privacykit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.privacykit_cancel)) }
        },
    )
}

/**
 * Colour picker. Selecting applies immediately and closes - there is nothing to
 * confirm, and the change is a single tap to undo - so the only button is
 * Cancel, in the confirm slot.
 */
@Composable
internal fun ProfileColorDialog(
    currentColor: String?,
    onDismiss: () -> Unit,
    onColorSelected: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.privacykit_profile_color_title)) },
        text = {
            // Scrollable: eight rows plus the title and the button can exceed
            // the maximum dialog height on a short screen, and M3's AlertDialog
            // does not scroll its text slot for you.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ColorOption(null, currentColor, onColorSelected)
                for (token in PK_PROFILE_COLORS) {
                    ColorOption(token, currentColor, onColorSelected)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.privacykit_cancel)) }
        },
    )
}

@Composable
private fun ColorOption(token: String?, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onSelect(token) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected == token, onClick = { onSelect(token) })
        Spacer(Modifier.size(SettingsDimension.paddingSmall))
        PkProfileColorDot(token)
        Text(pkProfileColorName(token))
    }
}

/**
 * Note editor. Empty saves as "no note", which is how the note is removed -
 * PrivacyKitProfileStore treats null, empty and whitespace-only identically.
 */
@Composable
internal fun ProfileNoteDialog(
    currentNote: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var note by remember(currentNote) { mutableStateOf(currentNote ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.privacykit_profile_note_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = note,
                    // Capped here as well as in the store: the store truncates
                    // at MAX_NOTE_LENGTH rather than rejecting, so letting the
                    // field grow past it would show the user text that is
                    // guaranteed not to survive the save.
                    onValueChange = { if (it.length <= PK_MAX_NOTE_LENGTH) note = it },
                    label = { Text(stringResource(R.string.privacykit_profile_note_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.privacykit_profile_note_clear_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(note) }) {
                Text(stringResource(R.string.privacykit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.privacykit_cancel)) }
        },
    )
}

@Composable
private fun ModeOption(value: String, selected: String, labelRes: Int, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Text(stringResource(labelRes), modifier = Modifier.padding(start = 8.dp))
    }
}
