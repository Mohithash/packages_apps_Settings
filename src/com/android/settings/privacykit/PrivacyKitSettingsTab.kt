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
 * Confirmed FINAL: all 17 Compose trace line numbers in r49 match this file exactly.
 */
package com.android.settings.privacykit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.RemoteException
import android.os.ServiceManager
import android.privacykit.IPrivacyKitManager
import android.util.Log

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

import com.android.settings.R
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.widget.ui.SettingsBody

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.io.InputStream

private const val TAG = "PrivacyKitSettingsTab"

/**
 * Hard ceiling on an imported device-profile file. The whole file is held in
 * memory and then parsed, so an accidentally-picked multi-megabyte document has
 * to fail fast rather than take the process down.
 */
private const val MAX_IMPORT_CHARS = 512 * 1024

private fun privacyKitManager(): IPrivacyKitManager? =
    IPrivacyKitManager.Stub.asInterface(ServiceManager.getService("privacykit"))

/**
 * Result of reading a backup file off the main thread: exactly one of the two
 * fields is non-null. Needed because the read has to distinguish "the provider
 * handed back no stream" from "the read threw", and each maps to a different
 * status message - the same two messages as before.
 */
private class PkBackupRead(val json: String?, val error: String?)

/**
 * Reads a SAF stream as UTF-8 text, refusing anything over [MAX_IMPORT_CHARS].
 * Blocking - callers are already on Dispatchers.IO.
 */
private fun readBoundedText(input: InputStream): String {
    val reader = input.bufferedReader(Charsets.UTF_8)
    val builder = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
        val read = reader.read(buffer)
        if (read < 0) break
        builder.append(buffer, 0, read)
        if (builder.length > MAX_IMPORT_CHARS) throw IOException("file too large")
    }
    return builder.toString()
}

/**
 * Opens [url] in whatever app handles web links, when one exists.
 *
 * A device image with no browser - or with link handling turned off - must
 * never take Settings down, so every failure is logged and swallowed and the
 * row simply does nothing. Cheap enough to call straight from a click handler.
 */
private fun pkOpenUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No activity can open this link", e)
    } catch (t: Throwable) {
        Log.w(TAG, "Could not open this link", t)
    }
}

/**
 * Settings tab: AI providers, imported device profiles, Backup & Restore, and
 * About.
 *
 * AI provider configuration is a drill-down: this tab shows one summary row and
 * [PrivacyKitAiProvidersScreen] owns the provider choice and its credentials.
 *
 * Laid out as real Settings categories - a [PkSection] for each block of
 * free-form controls, a [PkCategory] of [PkPreference] rows for the actions -
 * so it matches the rest of Settings. Backup & Restore and About are unchanged:
 * same SharedPreferences keys, same SAF contracts, same exportBackup/importBackup
 * calls, same merge-vs-replace dialog.
 */
@Composable
fun PrivacyKitSettingsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // AI provider config lives on its own screen now; this tab keeps only the
    // one-line summary of it. aiRefresh is bumped on the way back so the row
    // re-reads whatever was just saved.
    var showAiProviders by remember { mutableStateOf(false) }
    var aiRefresh by remember { mutableStateOf(0) }
    // SharedPreferences read, so it happens off the main thread. Starts blank
    // rather than guessing: the row never briefly claims a state that is untrue.
    val aiSummary by produceState(initialValue = "", aiRefresh) {
        value = withContext(Dispatchers.IO) { pkAiProvidersRowSummary(context) }
    }

    // Imported device profiles.
    var importedCount by remember { mutableStateOf(0) }
    var templatesRefresh by remember { mutableStateOf(0) }
    var templateStatus by remember { mutableStateOf<String?>(null) }
    var showRemoveImportedDialog by remember { mutableStateOf(false) }

    var backupStatus by remember { mutableStateOf<String?>(null) }
    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var showMergeDialog by remember { mutableStateOf(false) }

    if (showAiProviders) {
        // One lambda for both exits: the screen registers its own BackHandler
        // too, and whichever of the two the dispatcher picks must refresh the
        // summary row identically. Explicitly typed () -> Unit because the last
        // expression is aiRefresh++, which would otherwise infer () -> Int.
        val closeAiProviders: () -> Unit = {
            showAiProviders = false
            aiRefresh++
        }
        BackHandler(onBack = closeAiProviders)
        PrivacyKitAiProvidersScreen(onBack = closeAiProviders)
        return
    }

    LaunchedEffect(templatesRefresh) {
        importedCount = withContext(Dispatchers.IO) {
            PrivacyKitImportedTemplates.importedCount(context)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        // exportBackup() serialises the whole profile store across binder and the
        // SAF write goes out to whichever DocumentsProvider owns the target tree;
        // both grow with the number of profiles, so both run on Dispatchers.IO.
        // Only the resulting status string is applied back on the main thread,
        // and the messages themselves are unchanged.
        scope.launch {
            val status = withContext(Dispatchers.IO) {
                try {
                    val json = privacyKitManager()?.exportBackup()
                    if (json == null) {
                        context.getString(
                            R.string.privacykit_backup_export_failed,
                            context.getString(R.string.privacykit_service_unavailable),
                        )
                    } else {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                        }
                        context.getString(R.string.privacykit_backup_exported)
                    }
                } catch (e: RemoteException) {
                    context.getString(R.string.privacykit_backup_export_failed, e.message ?: "")
                } catch (e: RuntimeException) {
                    context.getString(R.string.privacykit_backup_export_failed, e.message ?: "")
                } catch (e: IOException) {
                    context.getString(R.string.privacykit_backup_export_failed, e.message ?: "")
                }
            }
            backupStatus = status
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        // Reading through the SAF stream can block on a remote provider, so the
        // read happens on Dispatchers.IO; the dialog/status state it feeds is
        // still written on the main thread, with the same two failure messages.
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    }
                    if (text == null) {
                        PkBackupRead(
                            json = null,
                            error = context.getString(
                                R.string.privacykit_backup_import_failed,
                                context.getString(R.string.privacykit_backup_read_failed),
                            ),
                        )
                    } else {
                        PkBackupRead(json = text, error = null)
                    }
                } catch (e: IOException) {
                    PkBackupRead(
                        json = null,
                        error = context.getString(
                            R.string.privacykit_backup_import_failed, e.message ?: ""),
                    )
                }
            }
            val json = read.json
            if (json == null) {
                backupStatus = read.error
            } else {
                pendingImportJson = json
                showMergeDialog = true
            }
        }
    }

    // Device-profile import: a separate, much smaller payload than a full
    // backup (identity templates only, no rules and no history), so it gets its
    // own SAF contracts and its own status line.
    val templateImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val status = withContext(Dispatchers.IO) {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        readBoundedText(input)
                    }
                    if (text == null) {
                        context.getString(
                            R.string.privacykit_templates_import_failed,
                            context.getString(R.string.privacykit_backup_read_failed),
                        )
                    } else {
                        val result = PrivacyKitImportedTemplates.importJson(context, text)
                        val error = result.error
                        when {
                            error != null -> context.getString(
                                R.string.privacykit_templates_import_failed, error)
                            result.skipped > 0 -> context.getString(
                                R.string.privacykit_templates_imported_skipped,
                                result.added, result.skipped)
                            else -> context.getString(
                                R.string.privacykit_templates_imported, result.added)
                        }
                    }
                } catch (e: IOException) {
                    context.getString(R.string.privacykit_templates_import_failed, e.message ?: "")
                } catch (e: JSONException) {
                    context.getString(R.string.privacykit_templates_import_failed, e.message ?: "")
                } catch (e: RuntimeException) {
                    context.getString(R.string.privacykit_templates_import_failed, e.message ?: "")
                }
            }
            templateStatus = status
            templatesRefresh++
        }
    }

    val templateExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val status = withContext(Dispatchers.IO) {
                try {
                    val json = PrivacyKitImportedTemplates.exportJson(context)
                    val written = context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                        true
                    }
                    if (written == null) {
                        context.getString(
                            R.string.privacykit_templates_export_failed,
                            context.getString(R.string.privacykit_backup_read_failed),
                        )
                    } else {
                        context.getString(
                            R.string.privacykit_templates_exported,
                            PrivacyKitImportedTemplates.all(context).size,
                        )
                    }
                } catch (e: IOException) {
                    context.getString(R.string.privacykit_templates_export_failed, e.message ?: "")
                } catch (e: JSONException) {
                    context.getString(R.string.privacykit_templates_export_failed, e.message ?: "")
                } catch (e: RuntimeException) {
                    context.getString(R.string.privacykit_templates_export_failed, e.message ?: "")
                }
            }
            templateStatus = status
        }
    }

    fun performImport(merge: Boolean) {
        // Read synchronously, before launching: every caller clears
        // pendingImportJson immediately after this returns, and the import
        // itself now finishes later.
        val json = pendingImportJson ?: return
        // importBackup() deserialises the file and re-applies every rule in it,
        // so it scales with profile count - off the main thread it goes. The
        // Merge / Replace / Cancel choices and their messages are unchanged.
        scope.launch {
            val status = withContext(Dispatchers.IO) {
                try {
                    val count = privacyKitManager()?.importBackup(json, merge)
                    if (count != null) {
                        context.getString(R.string.privacykit_backup_imported, count)
                    } else {
                        context.getString(
                            R.string.privacykit_backup_import_failed,
                            context.getString(R.string.privacykit_service_unavailable),
                        )
                    }
                } catch (e: RemoteException) {
                    context.getString(R.string.privacykit_backup_import_failed, e.message ?: "")
                } catch (e: RuntimeException) {
                    context.getString(R.string.privacykit_backup_import_failed, e.message ?: "")
                }
            }
            backupStatus = status
        }
    }

    if (showMergeDialog) {
        AlertDialog(
            onDismissRequest = {
                showMergeDialog = false
                pendingImportJson = null
            },
            title = { Text(stringResource(R.string.privacykit_restore_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.privacykit_restore_message))
                    Spacer(Modifier.height(SettingsSpace.extraSmall6))
                    // De-emphasized relative to Cancel/Merge below, since this is
                    // the more destructive and less common of the two import
                    // modes - a plain in-body TextButton keeps it from sharing
                    // equal visual weight with the dialog's main action buttons.
                    TextButton(
                        onClick = {
                            performImport(merge = false)
                            showMergeDialog = false
                            pendingImportJson = null
                        },
                    ) { Text(stringResource(R.string.privacykit_restore_replace)) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        performImport(merge = true)
                        showMergeDialog = false
                        pendingImportJson = null
                    },
                ) { Text(stringResource(R.string.privacykit_restore_merge)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showMergeDialog = false
                        pendingImportJson = null
                    },
                ) { Text(stringResource(R.string.privacykit_cancel)) }
            },
        )
    }

    if (showRemoveImportedDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveImportedDialog = false },
            title = { Text(stringResource(R.string.privacykit_templates_remove_title)) },
            text = { Text(stringResource(R.string.privacykit_templates_remove_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveImportedDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                PrivacyKitImportedTemplates.clear(context)
                            }
                            templateStatus =
                                context.getString(R.string.privacykit_templates_removed)
                            templatesRefresh++
                        }
                    },
                ) { Text(stringResource(R.string.privacykit_templates_remove_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRemoveImportedDialog = false },
                ) { Text(stringResource(R.string.privacykit_cancel)) }
            },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // One drill-down row. Everything that used to be inline - the three
        // provider choices and their base URL / model / API key fields - now
        // lives in PrivacyKitAiProvidersScreen.
        PkCategory {
            PkPreference(
                title = stringResource(R.string.privacykit_ai_providers_title),
                summary = aiSummary,
                onClick = { showAiProviders = true },
            )
        }

        PkSection(title = stringResource(R.string.privacykit_templates_title)) {
            SettingsBody(body = stringResource(R.string.privacykit_templates_summary))
            SettingsBody(
                body = stringResource(
                    R.string.privacykit_templates_count,
                    PrivacyKitDeviceTemplates.TEMPLATES.size,
                    importedCount,
                ),
            )
        }
        PkCategory {
            PkPreference(
                title = stringResource(R.string.privacykit_templates_import),
                summary = stringResource(R.string.privacykit_templates_import_summary),
                onClick = { templateImportLauncher.launch(arrayOf("application/json")) },
            )
            PkPreference(
                title = stringResource(R.string.privacykit_templates_export),
                summary = stringResource(R.string.privacykit_templates_export_summary),
                onClick = {
                    templateExportLauncher.launch(
                        "privacykit-device-profiles-${System.currentTimeMillis()}.json")
                },
            )
            if (importedCount > 0) {
                PkPreference(
                    title = stringResource(R.string.privacykit_templates_remove),
                    onClick = { showRemoveImportedDialog = true },
                )
            }
        }
        val templateLine = templateStatus
        if (templateLine != null) {
            PkSection {
                SettingsBody(body = templateLine)
            }
        }

        PkSection(title = stringResource(R.string.privacykit_backup_title)) {
            SettingsBody(body = stringResource(R.string.privacykit_backup_summary))
        }
        PkCategory {
            PkPreference(
                title = stringResource(R.string.privacykit_backup_export),
                onClick = {
                    exportLauncher.launch("privacykit-backup-${System.currentTimeMillis()}.json")
                },
            )
            PkPreference(
                title = stringResource(R.string.privacykit_backup_import),
                onClick = { importLauncher.launch(arrayOf("application/json")) },
            )
        }
        val status = backupStatus
        if (status != null) {
            PkSection {
                SettingsBody(body = status)
            }
        }

        PkSection(title = "Developer") {
            // ADB control gate. Read-only `adb shell cmd privacykit`
            // subcommands always work; this toggle is what lets the shell
            // *mutate* profiles/rules (see PrivacyKitShellCommand). It can
            // only be flipped here, never from the shell itself.
            var adbControl by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) {
                adbControl = withContext(Dispatchers.IO) {
                    runCatching { privacyKitManager()?.isAdbControlEnabled() ?: false }
                        .getOrDefault(false)
                }
            }
            val rowChecked = adbControl
            SwitchPreference(
                model = object : SwitchPreferenceModel {
                    override val title: String = "ADB control"
                    override val summary: () -> CharSequence = {
                        "Let \"adb shell cmd privacykit\" create profiles and set " +
                            "identifier rules, for testing. Off by default; read-only " +
                            "commands work regardless."
                    }
                    override val checked: () -> Boolean? = { rowChecked }
                    override val changeable: () -> Boolean = { rowChecked != null }
                    override val onCheckedChange: ((Boolean) -> Unit)? = { value ->
                        adbControl = value
                        scope.launch(Dispatchers.IO) {
                            runCatching { privacyKitManager()?.setAdbControlEnabled(value) }
                        }
                    }
                },
            )
        }

        PkSection(title = stringResource(R.string.privacykit_about_title)) {
            SettingsBody(
                body = stringResource(
                    R.string.privacykit_about_body,
                    Build.VERSION.RELEASE,
                    Build.DISPLAY,
                ),
            )
        }

        PkCategory {
            val authorUrl = stringResource(R.string.privacykit_about_author_url)
            PkPreference(
                title = stringResource(R.string.privacykit_about_author),
                summary = stringResource(R.string.privacykit_about_author_github),
                onClick = { pkOpenUrl(context, authorUrl) },
            )
        }
    }
}

/** Outcome of one device-profile import: what landed, and what was rejected. */
class PkTemplateImportResult(val added: Int, val skipped: Int, val error: String?)

/*
 * User-imported device identity templates.
 *
 * Lives in this file rather than next to PrivacyKitDeviceTemplates.kt only
 * because the built-in catalog is owned elsewhere and must not be touched; it
 * is package-visible, so any caller in com.android.settings.privacykit can use
 * it, and it should be lifted into its own file the next time the catalog is
 * edited anyway.
 *
 * Storage is a JSON array in its own SharedPreferences file, deliberately
 * separate from the AI credentials so clearing one never disturbs the other.
 */
object PrivacyKitImportedTemplates {

    private const val PREFS = "privacykit_templates_prefs"
    private const val KEY_TEMPLATES = "imported_templates"

    /** Keeps the prefs blob (and the picker's dropdown) to a sane size. */
    private const val MAX_TEMPLATES = 200

    /** No legitimate Build.* value is anywhere near this long. */
    private const val MAX_VALUE_LENGTH = 512

    private const val MAX_NAME_LENGTH = 80

    /** Envelope keys [extractArray] will look inside, in order. */
    private val ARRAY_KEYS = listOf("templates", "deviceProfiles", "device_profiles", "profiles")

    /** Blocking (SharedPreferences + JSON parse) - call off the main thread. */
    fun imported(context: Context): List<PrivacyKitDeviceTemplate> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TEMPLATES, null)
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<PrivacyKitDeviceTemplate>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val template = parseTemplate(obj) ?: continue
                out.add(template)
            }
            out
        } catch (t: Throwable) {
            // A corrupt blob must never take the Settings tab down; the user can
            // re-import, and the stored value is replaced on the next import.
            Log.w(TAG, "Discarding unreadable imported device profiles", t)
            emptyList()
        }
    }

    fun importedCount(context: Context): Int = imported(context).size

    /**
     * Built-ins first, then anything the user imported. This is what a device
     * template picker should offer; display names are unique across the whole
     * list, which matters because the existing picker resolves a selection by
     * display name.
     */
    fun all(context: Context): List<PrivacyKitDeviceTemplate> =
        PrivacyKitDeviceTemplates.TEMPLATES + imported(context)

    /**
     * Parses [json] and adds every valid, not-already-present template to the
     * store. Malformed entries are skipped and counted rather than failing the
     * whole file. Blocking - call off the main thread.
     */
    fun importJson(context: Context, json: String): PkTemplateImportResult {
        val array = try {
            extractArray(json)
        } catch (e: JSONException) {
            return PkTemplateImportResult(0, 0, e.message ?: "invalid JSON")
        } catch (e: RuntimeException) {
            return PkTemplateImportResult(0, 0, e.message ?: "invalid JSON")
        }

        val existingImported = imported(context).toMutableList()
        val known = PrivacyKitDeviceTemplates.TEMPLATES + existingImported
        val takenNames = known.map { it.displayName }.toMutableSet()
        val knownIdentities = known.map { identityKey(it) }.toMutableSet()

        var added = 0
        var skipped = 0
        for (i in 0 until array.length()) {
            if (existingImported.size >= MAX_TEMPLATES) {
                skipped += array.length() - i
                break
            }
            val obj = array.optJSONObject(i)
            val parsed = if (obj == null) null else parseTemplate(obj)
            if (parsed == null) {
                skipped++
                continue
            }
            // Content-level dedupe, so re-importing a file that also contains the
            // built-ins (which is exactly what Export produces) is a no-op for
            // those instead of creating 17 near-duplicate entries.
            if (!knownIdentities.add(identityKey(parsed))) {
                skipped++
                continue
            }
            val named = parsed.copy(displayName = uniqueName(parsed.displayName, takenNames))
            takenNames.add(named.displayName)
            existingImported.add(named)
            added++
        }

        if (added > 0) {
            save(context, existingImported)
        }
        return PkTemplateImportResult(added, skipped, null)
    }

    /**
     * Serialises built-ins plus imported templates, so an export is a usable
     * starting point for hand-editing. Re-importing it adds nothing, because
     * every entry already exists. Blocking - call off the main thread.
     */
    fun exportJson(context: Context): String {
        val array = JSONArray()
        for (template in all(context)) {
            array.put(toJson(template))
        }
        return JSONObject()
            .put("version", 1)
            .put("templates", array)
            .toString(2)
    }

    /** Blocking - call off the main thread. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_TEMPLATES)
            .apply()
    }

    private fun save(context: Context, templates: List<PrivacyKitDeviceTemplate>) {
        val array = JSONArray()
        for (template in templates.take(MAX_TEMPLATES)) {
            array.put(toJson(template))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TEMPLATES, array.toString())
            .apply()
    }

    private fun toJson(template: PrivacyKitDeviceTemplate): JSONObject = JSONObject()
        .put("displayName", template.displayName)
        .put("fingerprint", template.fingerprint)
        .put("model", template.model)
        .put("manufacturer", template.manufacturer)
        .put("brand", template.brand)
        .put("device", template.device)
        .put("product", template.product)
        .put("board", template.board)
        .put("hardware", template.hardware)

    /**
     * Accepts a bare array, a wrapper object with a templates array (what
     * [exportJson] writes), or a single template object, so hand-written files
     * do not have to guess the envelope.
     */
    private fun extractArray(json: String): JSONArray {
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) return JSONArray(trimmed)
        val obj = JSONObject(trimmed)
        for (key in ARRAY_KEYS) {
            val array = obj.optJSONArray(key)
            if (array != null) return array
        }
        if (obj.has("fingerprint")) return JSONArray().put(obj)
        throw JSONException("expected a JSON array of device profiles")
    }

    /**
     * Turns one JSON object into a template, or null if it is not usable.
     *
     * brand/product/device may be omitted: they are literally the first three
     * fields of the fingerprint, so reading them back out of it is exact rather
     * than a guess. Everything else must be stated - inventing a board or
     * hardware value would produce exactly the kind of internally-inconsistent
     * identity the curated catalog exists to avoid.
     */
    private fun parseTemplate(obj: JSONObject): PrivacyKitDeviceTemplate? {
        val fingerprint = obj.stringField("fingerprint")
        if (!looksLikeFingerprint(fingerprint)) return null
        val head = fingerprint.substringBefore(':').split('/')
        val fromFingerprint: List<String> = if (head.size == 3) head else emptyList()

        val brand = obj.stringField("brand").ifBlank { fromFingerprint.getOrElse(0) { "" } }
        val product = obj.stringField("product").ifBlank { fromFingerprint.getOrElse(1) { "" } }
        val device = obj.stringField("device").ifBlank { fromFingerprint.getOrElse(2) { "" } }
        val model = obj.stringField("model")
        val manufacturer = obj.stringField("manufacturer")
        val board = obj.stringField("board")
        val hardware = obj.stringField("hardware")

        val values = listOf(
            fingerprint, model, manufacturer, brand, device, product, board, hardware)
        if (values.any { !isUsableValue(it) }) return null

        val name = listOf(
            obj.stringField("displayName"),
            obj.stringField("display_name"),
            obj.stringField("name"),
            "$manufacturer $model",
        ).firstOrNull { it.isNotBlank() && isUsableValue(it) } ?: model

        return PrivacyKitDeviceTemplate(
            displayName = name.take(MAX_NAME_LENGTH),
            fingerprint = fingerprint,
            model = model,
            manufacturer = manufacturer,
            brand = brand,
            device = device,
            product = product,
            board = board,
            hardware = hardware,
        )
    }

    private fun JSONObject.stringField(name: String): String = optString(name, "").trim()

    private fun isUsableValue(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_VALUE_LENGTH &&
            value.none { it.isISOControl() }

    /**
     * brand/product/device:release/id/incremental:type/tags - five slashes and
     * two colons in a real one. Kept loose (at least three slashes and a colon)
     * so unusual but genuine OEM fingerprints still import.
     */
    private fun looksLikeFingerprint(value: String): Boolean =
        value.length in 12..MAX_VALUE_LENGTH &&
            value.count { it == '/' } >= 3 &&
            value.contains(':')

    private fun identityKey(template: PrivacyKitDeviceTemplate): String = listOf(
        template.fingerprint,
        template.model,
        template.manufacturer,
        template.brand,
        template.device,
        template.product,
        template.board,
        template.hardware,
    ).joinToString(
        // NUL separator: no Build.* value can contain one, so two
        // different field splits can never collide into one key.
        separator = "\u0000",
    )

    private fun uniqueName(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var suffix = 2
        while ("$base ($suffix)" in taken && suffix < MAX_TEMPLATES + 2) {
            suffix++
        }
        return "$base ($suffix)"
    }
}

