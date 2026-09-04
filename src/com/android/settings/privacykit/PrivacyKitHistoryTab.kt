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
 * Confirmed FINAL: both Compose trace line numbers in r49 match this file exactly.
 */
package com.android.settings.privacykit

import android.content.pm.PackageManager
import android.os.ServiceManager

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

import com.android.settings.R
import com.android.settingslib.spa.widget.ui.LazyCategory

import android.privacykit.IPrivacyKitManager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_HISTORY_ENTRIES = 50

private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

/** Two spaces: the field separator PrivacyKitHistoryStore#format writes. */
private const val FIELD_SEPARATOR = "  "

/** Middle dot placed between the app name and the time in a row summary. */
private const val TIME_SEPARATOR = " · "

private fun privacyKitManager(): IPrivacyKitManager? =
    IPrivacyKitManager.Stub.asInterface(ServiceManager.getService("privacykit"))

/** One parsed log line, already rendered into the two lines of a Settings row. */
private data class PkHistoryRow(
    val title: String,
    val summary: String,
    val group: String,
)

/**
 * History tab: a read-only, newest-first log of recent PrivacyKit activity
 * (rule changes, profile management, access-control changes), sourced from
 * IPrivacyKitManager#getRecentHistory.
 *
 * The service hands back pre-formatted lines
 * ("yyyy-MM-dd HH:mm<sep>package<sep>ACTION[<sep>detail]", separator = two
 * spaces - see PrivacyKitHistoryStore#format). They are split back apart here
 * purely for presentation: the action becomes the row title, the detail plus
 * "app name - time" become the summary, and the date becomes a group heading.
 * No backend or AIDL behaviour is changed by any of this.
 */
@Composable
fun PrivacyKitHistoryTab() {
    val context = LocalContext.current

    val todayLabel = stringResource(R.string.privacykit_history_today)
    val yesterdayLabel = stringResource(R.string.privacykit_history_yesterday)
    val ruleSetLabel = stringResource(R.string.privacykit_history_action_rule_set)
    val ruleClearedLabel = stringResource(R.string.privacykit_history_action_rule_cleared)
    val profileClearedLabel = stringResource(R.string.privacykit_history_action_profile_cleared)
    val controlSetLabel = stringResource(R.string.privacykit_history_action_control_set)
    val profileCreatedLabel = stringResource(R.string.privacykit_history_action_profile_created)
    val profileActivatedLabel = stringResource(R.string.privacykit_history_action_profile_activated)
    val profileDeletedLabel = stringResource(R.string.privacykit_history_action_profile_deleted)

    val actionLabels = remember(
        ruleSetLabel,
        ruleClearedLabel,
        profileClearedLabel,
        controlSetLabel,
        profileCreatedLabel,
        profileActivatedLabel,
        profileDeletedLabel,
    ) {
        mapOf(
            "RULE_SET" to ruleSetLabel,
            "RULE_CLEARED" to ruleClearedLabel,
            "PROFILE_CLEARED" to profileClearedLabel,
            "CONTROL_SET" to controlSetLabel,
            "PROFILE_CREATED" to profileCreatedLabel,
            "PROFILE_ACTIVATED" to profileActivatedLabel,
            "PROFILE_DELETED" to profileDeletedLabel,
        )
    }

    // Both halves of the load now run on Dispatchers.IO. getRecentHistory()
    // marshals up to MAX_HISTORY_ENTRIES strings across binder, and
    // PrivacyKitHistoryStore#getRecent is synchronized against a record() that
    // writes to disk - on the main thread a concurrent rule change could park
    // Settings on that monitor. buildHistoryRows() then does one PackageManager
    // label lookup per unique package, which used to happen inside remember {},
    // i.e. on the main thread during composition.
    // null == still loading; empty list == genuinely no history yet, exactly as
    // before, so the empty state still cannot flash on entry.
    val rows by produceState<List<PkHistoryRow>?>(
            initialValue = null, context, actionLabels, todayLabel, yesterdayLabel) {
        value = withContext(Dispatchers.IO) {
            val raw: List<String> = try {
                privacyKitManager()?.getRecentHistory(MAX_HISTORY_ENTRIES) ?: emptyList()
            } catch (e: Exception) {
                // Fail open. The service may be absent (RemoteException) or may
                // reject the caller: every AIDL stub runs enforceCallingPermission,
                // which throws SecurityException - a RuntimeException that a
                // RemoteException-only catch would miss, crashing Settings.
                emptyList()
            }
            buildHistoryRows(
                pm = context.packageManager,
                raw = raw,
                actionLabels = actionLabels,
                todayLabel = todayLabel,
                yesterdayLabel = yesterdayLabel,
            )
        }
    }

    val list = rows
    if (list == null) {
        PkLoading()
        return
    }

    if (list.isEmpty()) {
        PkEmptyState(
            text = stringResource(R.string.privacykit_no_history),
            icon = Icons.Outlined.History,
            description = stringResource(R.string.privacykit_history_empty_description),
        )
        return
    }

    LazyCategory(
        count = list.size,
        key = { index -> index },
        groupTitle = { index -> list[index].group.takeIf { it.isNotEmpty() } },
    ) { index ->
        val row = list[index]
        PkPreference(
            title = row.title,
            summary = row.summary.takeIf { it.isNotEmpty() },
        )
    }
}

private fun buildHistoryRows(
    pm: PackageManager,
    raw: List<String>,
    actionLabels: Map<String, String>,
    todayLabel: String,
    yesterdayLabel: String,
): List<PkHistoryRow> {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val now = System.currentTimeMillis()
    val today = dateFormat.format(Date(now))
    val yesterday = dateFormat.format(Date(now - DAY_MILLIS))
    val labelCache = HashMap<String, String>()

    return raw.map { entry ->
        val parts = entry.split(FIELD_SEPARATOR)
        if (parts.size < 3) {
            // Unrecognised shape: show it verbatim rather than losing it.
            return@map PkHistoryRow(title = entry, summary = "", group = "")
        }
        val stamp = parts[0]
        val packageName = parts[1]
        val action = parts[2]
        val detail = if (parts.size > 3) {
            parts.subList(3, parts.size).joinToString(FIELD_SEPARATOR)
        } else {
            ""
        }

        val spaceIndex = stamp.indexOf(' ')
        val date = if (spaceIndex > 0) stamp.substring(0, spaceIndex) else stamp
        val time = if (spaceIndex > 0) stamp.substring(spaceIndex + 1) else ""

        val appLabel = labelCache.getOrPut(packageName) { resolveAppLabel(pm, packageName) }
        val summary = buildString {
            if (detail.isNotEmpty()) {
                append(detail)
                append('\n')
            }
            append(appLabel)
            if (time.isNotEmpty()) {
                append(TIME_SEPARATOR)
                append(time)
            }
        }

        PkHistoryRow(
            title = actionLabels[action] ?: humanizeAction(action),
            summary = summary,
            group = when (date) {
                today -> todayLabel
                yesterday -> yesterdayLabel
                else -> date
            },
        )
    }
}

private fun resolveAppLabel(pm: PackageManager, packageName: String): String = try {
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
} catch (e: PackageManager.NameNotFoundException) {
    packageName
}

/** Fallback for actions this build has no localized label for: FOO_BAR -> "Foo bar". */
private fun humanizeAction(action: String): String {
    val spaced = action.replace('_', ' ').trim()
    if (spaced.isEmpty()) {
        return action
    }
    return spaced.substring(0, 1).uppercase(Locale.US) +
        spaced.substring(1).lowercase(Locale.US)
}

