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
 * Confirmed FINAL: all 23 Compose trace line numbers in r49 match this file exactly, and lines 389-860 are byte-identical to the patch.
 */
package com.android.settings.privacykit

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.graphics.drawable.Drawable
import android.os.ServiceManager

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import com.android.internal.util.voltage.VoltageUtils
import com.android.settings.R
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsOpacity.alphaForEnabled
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsSize
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.framework.theme.isSpaExpressiveEnabled
import com.android.settingslib.spa.framework.theme.settingsBackground
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.CategoryTitle
import com.android.settingslib.spa.widget.ui.CircularLoadingBar
import com.android.settingslib.spa.widget.ui.Footer
import com.android.settingslib.spa.widget.ui.PlaceholderTitle
import com.android.settingslib.spa.widget.ui.SettingsBody
import com.android.settingslib.spa.widget.ui.SettingsIcon
import com.android.settingslib.spa.widget.ui.SettingsTitle
import com.android.settingslib.spa.widget.ui.createSettingsIcon

import android.privacykit.IPrivacyKitManager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Shared PrivacyKit UI vocabulary.
 *
 * Every PrivacyKit screen is expected to build itself out of these, so the
 * whole feature looks like the rest of AOSP Settings instead of like a
 * hand-rolled Compose app.
 *
 * Implementation policy:
 *   - Prefer the real SettingsLib SPA widgets (SpaLib is statically linked
 *     into Settings-core through the SpaPrivilegedLib-defaults, so every
 *     `public` symbol under com.android.settingslib.spa.* is on our compile
 *     classpath - see PrintSettingsPageProvider.kt for the same imports).
 *   - Hand-roll only where SPA has no public equivalent, and in that case
 *     rebuild the row out of the same SettingsDimension / SettingsSpace /
 *     SettingsShape tokens SPA itself uses, so the two are visually identical.
 *
 * The only hand-rolled pieces here are:
 *   - [PkPreference] when a `trailing` widget is requested. SPA's public
 *     `Preference` has no trailing-widget slot (the one that does,
 *     `BasePreference(..., widget = )`, is `internal` to SpaLib), so that
 *     variant re-implements SpaLib's own BaseLayout row.
 *   - [PkScreenHeader], because PrivacyKit's drill-down screens are swapped
 *     inside a tab body rather than pushed onto a nav graph, so they cannot
 *     use the SPA app bar.
 *   - The illustrated variant of [PkEmptyState].
 */

/**
 * A Settings-style category: an optional primary-colored title followed by a
 * rounded, grouped card of rows. Thin wrapper over SPA's `Category` so the
 * rows inside pick up the in-category highlight background automatically.
 */
@Composable
fun PkCategory(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Category(title = title, content = content)
}

/** Just the category title, for free-form (non-row) content below it. */
@Composable
fun PkCategoryTitle(title: String) {
    CategoryTitle(title = title)
}

/**
 * A category title followed by free-form content (text fields, buttons,
 * explanatory text) inset to the same horizontal margin a [PkCategory] uses.
 * Use this where the content is not a list of preference rows.
 */
@Composable
fun PkSection(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsSpace.small1,
                vertical = SettingsSpace.extraSmall4,
            ),
    ) {
        if (title != null) {
            CategoryTitle(title = title)
        }
        content()
    }
}

/**
 * The standard Settings preference row: optional leading icon, title, optional
 * summary underneath, optional trailing widget.
 *
 * With no trailing widget this is literally SPA's `Preference`, so it inherits
 * the real Settings typography, paddings, minimum height, disabled alpha and
 * in-category highlight. With a trailing widget it is a faithful
 * re-implementation of the same row (SPA's widget-carrying row is internal).
 */
@Composable
fun PkPreference(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val trailingWidget = trailing
    if (trailingWidget == null) {
        // Locals first: referencing the parameters directly from inside the
        // anonymous object's initializers would resolve to the object's own
        // (not yet initialized) properties.
        val rowTitle = title
        val rowSummary = summary ?: ""
        val rowIcon = createSettingsIcon(icon)
        val rowEnabled = enabled
        val rowOnClick = onClick
        Preference(
            model = object : PreferenceModel {
                override val title = rowTitle
                override val summary: () -> CharSequence = { rowSummary }
                override val icon = rowIcon
                override val enabled = { rowEnabled }
                override val onClick = rowOnClick
            },
        )
    } else {
        PkWidgetRow(
            title = title,
            summary = summary,
            icon = icon,
            enabled = enabled,
            onClick = onClick,
            trailing = trailingWidget,
        )
    }
}

/**
 * Hand-rolled equivalent of SpaLib's internal BaseLayout row, used only when a
 * trailing widget is needed. Kept token-for-token in sync with
 * SettingsLib/Spa/.../preference/BaseLayout.kt.
 */
@Composable
private fun PkWidgetRow(
    title: String,
    summary: String?,
    icon: ImageVector?,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit,
) {
    val click = onClick
    val clickModifier = if (click != null) {
        Modifier.clickable(enabled = enabled, onClick = click)
    } else {
        Modifier
    }
    val surfaceBright = MaterialTheme.colorScheme.surfaceBright
    val rowModifier = if (isSpaExpressiveEnabled) {
        Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .heightIn(min = SettingsDimension.preferenceMinHeight)
            .background(color = surfaceBright, shape = SettingsShape.CornerExtraSmall2)
            .padding(end = SettingsDimension.itemPaddingEnd)
    } else {
        Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(end = SettingsDimension.itemPaddingEnd)
    }
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        if (isSpaExpressiveEnabled) {
            Spacer(Modifier.width(SettingsDimension.itemPaddingStart))
            if (icon != null) {
                Box(
                    modifier = Modifier.alphaForEnabled(enabled).size(SettingsSize.medium3),
                    contentAlignment = Alignment.Center,
                ) {
                    SettingsIcon(imageVector = icon)
                }
                Spacer(Modifier.width(SettingsSpace.extraSmall6))
            }
        } else {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .alphaForEnabled(enabled)
                        .size(SettingsDimension.itemIconContainerSize),
                    contentAlignment = Alignment.Center,
                ) {
                    SettingsIcon(imageVector = icon)
                }
            } else {
                Spacer(Modifier.width(SettingsDimension.itemPaddingStart))
            }
        }
        Column(
            modifier = Modifier
                .alphaForEnabled(enabled)
                .weight(1f)
                .padding(vertical = SettingsDimension.itemPaddingVertical),
        ) {
            SettingsTitle(title = title)
            if (summary != null) {
                SettingsBody(body = summary)
            }
        }
        trailing()
    }
}

/**
 * Header for PrivacyKit's nested screens. These are swapped inside a tab body
 * rather than pushed onto the SPA nav graph, so they cannot use the real
 * Settings app bar; this keeps them consistent with each other instead.
 */
@Composable
fun PkScreenHeader(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsSpace.extraSmall2,
                vertical = SettingsSpace.extraSmall2,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.privacykit_back),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = SettingsSpace.extraSmall4),
        )
        actions()
    }
}

/**
 * Settings' empty-state placeholder. Text only uses SPA's `PlaceholderTitle`
 * verbatim; supplying an icon and/or description renders the richer centered
 * variant with the same typography tokens.
 */
@Composable
fun PkEmptyState(
    text: String,
    icon: ImageVector? = null,
    description: String? = null,
) {
    if (icon == null && description == null) {
        PlaceholderTitle(title = text)
        return
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(SettingsDimension.itemPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(SettingsDimension.iconLarge),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(SettingsSpace.small1))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (description != null) {
                Spacer(Modifier.height(SettingsSpace.extraSmall4))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Centered indeterminate spinner, filling the available space. */
@Composable
fun PkLoading() {
    CircularLoadingBar(isLoading = true)
}

/** Settings' end-of-page explainer: an info icon above secondary body text. */
@Composable
fun PkFooter(text: String) {
    Footer(footerText = text)
}

/* ------------------------------------------------------------------------ *
 * Profile vocabulary shared by the Apps tab, the Profiles tab and the hub
 * ------------------------------------------------------------------------ */

/*
 * Profile modes, mirroring PrivacyKitProfileStore.MODE_*.
 *
 * Mirrored by hand because PrivacyKitProfileStore lives in
 * com.android.server.privacykit and is not on the Settings app's classpath;
 * the AIDL documents the set instead of exposing a "list the modes" call.
 */
internal const val MODE_ISOLATED = "isolated"
internal const val MODE_HYBRID = "hybrid"
internal const val MODE_SHARED = "shared"

/**
 * Localizes a raw PrivacyKitProfileStore MODE_* token. Lives here rather than
 * in one of the tabs because all three surfaces (Apps, Profiles, the profile
 * hub) render the same token and must never disagree about its name.
 */
@Composable
internal fun pkModeLabel(mode: String): String = when (mode) {
    MODE_ISOLATED -> stringResource(R.string.privacykit_mode_isolated)
    MODE_HYBRID -> stringResource(R.string.privacykit_mode_hybrid)
    MODE_SHARED -> stringResource(R.string.privacykit_mode_shared)
    else -> mode
}

/* ------------------------------------------------------------------------ *
 * Hub-style widgets
 * ------------------------------------------------------------------------ */

/**
 * Circular letter avatar for a profile: the first character of [name] over a
 * disc tinted with the profile's colour token.
 *
 * The token is resolved through [pkProfileColor], the same mapping the list
 * dot uses, so a profile that is blue in the list is blue here too. With no
 * colour (or an unknown token) it falls back to the theme's primary
 * container, which reads as "no colour" rather than as an arbitrary hue.
 */
@Composable
fun PkAvatar(
    name: String,
    colorToken: String? = null,
    size: Dp = SettingsDimension.itemIconContainerSize,
) {
    val accent = pkProfileColor(colorToken)
    val container = accent?.copy(alpha = 0.24f) ?: MaterialTheme.colorScheme.primaryContainer
    val content = accent ?: MaterialTheme.colorScheme.onPrimaryContainer
    // Codepoint-based so an emoji or a non-BMP script yields a whole glyph
    // instead of half a surrogate pair.
    val initial = remember(name) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            "?"
        } else {
            String(Character.toChars(Character.toUpperCase(trimmed.codePointAt(0))))
        }
    }
    Box(
        modifier = Modifier.size(size).clip(SettingsShape.CornerFull).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.headlineMedium,
            color = content,
        )
    }
}

/**
 * Header for hub-style screens: a back arrow with an action slot, then a
 * centred [PkAvatar], the profile name and a grey subtitle.
 *
 * Replaces [PkScreenHeader] only where a screen is *about* one profile; plain
 * drill-downs keep the title-bar header so they stay consistent with the rest
 * of Settings.
 */
@Composable
fun PkAvatarHeader(
    name: String,
    subtitle: String? = null,
    colorToken: String? = null,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = SettingsSpace.extraSmall2,
                    vertical = SettingsSpace.extraSmall2,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.privacykit_back),
                )
            }
            Spacer(Modifier.weight(1f))
            actions()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = SettingsSpace.small1,
                    end = SettingsSpace.small1,
                    bottom = SettingsSpace.small4,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PkAvatar(name = name, colorToken = colorToken)
            Spacer(Modifier.height(SettingsSpace.extraSmall6))
            Text(
                text = name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.height(SettingsSpace.extraSmall1))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A [PkPreference] with a trailing chevron: the standard "opens something"
 * row of a hub screen.
 *
 * Deliberately routed through the existing row instead of a new layout, so
 * paddings, typography, minimum height and disabled alpha stay identical to
 * every other Settings row. The chevron is the auto-mirrored arrow so it
 * flips in RTL.
 */
@Composable
fun PkHubRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    PkPreference(
        title = title,
        summary = summary,
        icon = icon,
        enabled = enabled,
        onClick = onClick,
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alphaForEnabled(enabled).size(SettingsSize.small2),
            )
        },
    )
}

/**
 * The full-width pill button a hub screen pins under its scrolling content,
 * on an opaque strip so rows pass behind it cleanly.
 *
 * [note] is for the one-line reason a disabled action is disabled: an action
 * that cannot run has to say why rather than just going grey.
 */
@Composable
fun PkPinnedAction(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    note: String? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.settingsBackground)
            .padding(
                horizontal = SettingsSpace.small1,
                vertical = SettingsSpace.extraSmall6,
            ),
    ) {
        if (!note.isNullOrEmpty()) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = SettingsSpace.extraSmall2),
            )
        }
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = SettingsShape.CornerFull,
            contentPadding = PaddingValues(
                horizontal = SettingsSpace.small4,
                vertical = SettingsDimension.buttonPaddingVertical,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = SettingsSize.medium4),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(SettingsSize.small2),
                )
                Spacer(Modifier.width(SettingsSpace.extraSmall4))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ------------------------------------------------------------------------ *
 * Add-app picker
 * ------------------------------------------------------------------------ */

private fun pkPickerManager(): IPrivacyKitManager? =
    IPrivacyKitManager.Stub.asInterface(ServiceManager.getService("privacykit"))

/**
 * One pickable app. Carries the [ApplicationInfo] rather than a decoded
 * Drawable so the whole installed-package list can be built without decoding
 * ~150 adaptive icons up front; each row decodes only what is on screen.
 */
data class PkPickerApp(
    val packageName: String,
    val label: String,
    val applicationInfo: ApplicationInfo,
)

/**
 * Searchable app picker as a modal bottom sheet.
 *
 * Three states are kept apart on purpose, because the dialog this replaces
 * collapsed all of them into one blank rectangle: still enumerating packages
 * (spinner), nothing left to add, and nothing matching the query.
 *
 * [loadApps] is injected as a suspend lambda and run on [Dispatchers.IO], so
 * the sheet itself knows nothing about PrivacyKit and never touches a binder
 * on the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PkAppPickerSheet(
    title: String,
    onDismiss: () -> Unit,
    onAppPicked: (PkPickerApp) -> Unit,
    loadApps: suspend () -> List<PkPickerApp>,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }

    // Run the exit animation, then hand control back: calling onAppPicked
    // straight away would yank the sheet off screen with no transition.
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) action()
        }
    }

    // null == still enumerating packages. Never conflated with "no apps".
    val apps by produceState<List<PkPickerApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadApps() }
    }

    val source = apps
    val filtered = remember(source, query) {
        val q = query.trim()
        when {
            source == null -> null
            q.isEmpty() -> source
            else -> source.filter {
                it.label.contains(q, ignoreCase = true) ||
                    it.packageName.contains(q, ignoreCase = true)
            }
        }
    }

    // Bounded rather than fixed: a hard height overflows short screens and
    // wastes space on tall ones.
    val listHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceBright,
        shape = SettingsShape.CornerExtraLarge1,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(
                    start = SettingsDimension.dialogItemPaddingHorizontal,
                    end = SettingsDimension.dialogItemPaddingHorizontal,
                    bottom = SettingsSpace.extraSmall6,
                ),
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                shape = SettingsShape.CornerFull,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription =
                                    stringResource(R.string.privacykit_clear_search),
                            )
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.privacykit_search_apps)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsDimension.dialogItemPaddingHorizontal),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SettingsSize.large2, max = listHeight)
                    .padding(top = SettingsSpace.extraSmall6),
                contentAlignment = Alignment.Center,
            ) {
                val list = filtered
                when {
                    list == null -> Box(
                        Modifier.fillMaxWidth().height(listHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    list.isEmpty() -> PkEmptyState(
                        text = if (query.isBlank()) {
                            stringResource(R.string.privacykit_picker_none_left_title)
                        } else {
                            stringResource(R.string.privacykit_apps_no_matches_title)
                        },
                        icon = Icons.Filled.Search,
                        description = if (query.isBlank()) {
                            stringResource(R.string.privacykit_picker_none_left_summary)
                        } else {
                            stringResource(R.string.privacykit_apps_no_matches_summary)
                        },
                    )

                    else -> LazyColumn(Modifier.fillMaxWidth()) {
                        items(list, key = { it.packageName }) { app ->
                            PkAppPickerRow(app) { closeThen { onAppPicked(app) } }
                        }
                    }
                }
            }

            Spacer(Modifier.height(SettingsSpace.small1))
        }
    }
}

/** One picker row: icon, label and package name - the line that disambiguates clones. */
@Composable
private fun PkAppPickerRow(app: PkPickerApp, onClick: () -> Unit) {
    val pm = LocalContext.current.packageManager
    // Decoded only while the row is composed, and only once per package.
    val icon by produceState<Drawable?>(initialValue = null, app.packageName) {
        value = withContext(Dispatchers.IO) { app.applicationInfo.loadIcon(pm) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = SettingsSize.large2)
            .padding(
                horizontal = SettingsDimension.dialogItemPaddingHorizontal,
                vertical = SettingsSpace.extraSmall4,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = rememberDrawablePainter(icon),
            contentDescription = null,
            modifier = Modifier.size(SettingsDimension.appIconItemSize),
        )
        Column(Modifier.weight(1f).padding(start = SettingsDimension.paddingLarge)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Every launchable package PrivacyKit does not already manage.
 *
 * Deliberately blocking - [PkAppPickerSheet] runs it on [Dispatchers.IO] -
 * because loadLabel() reaches into each target package's resources.
 */
suspend fun loadAddablePrivacyKitApps(context: Context): List<PkPickerApp> {
    val pm = context.packageManager
    val launchable = VoltageUtils.launchablePackages(context).toSet()
    val managed = try {
        pkPickerManager()?.getManagedPackages()?.toSet() ?: emptySet<String>()
    } catch (e: Exception) {
        // Fail open: service down or caller rejected. Offering every launchable
        // app beats offering none, and re-adding a managed app is a no-op.
        emptySet<String>()
    }
    return pm.getInstalledPackages(PackageInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        .asSequence()
        .filter { it.packageName in launchable && it.packageName !in managed }
        .mapNotNull { info ->
            val appInfo = info.applicationInfo ?: return@mapNotNull null
            PkPickerApp(
                packageName = info.packageName,
                label = appInfo.loadLabel(pm).toString(),
                applicationInfo = appInfo,
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        .toList()
}

