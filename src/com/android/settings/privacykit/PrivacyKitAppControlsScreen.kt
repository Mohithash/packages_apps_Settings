/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.privacykit

import android.content.Context
import android.content.pm.PackageManager
import android.os.ServiceManager
import android.os.UserHandle
import android.provider.Settings
import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

import com.android.internal.util.voltage.HideAppListUtils
import com.android.internal.util.voltage.HideDeveloperStatusUtils
import com.android.internal.util.voltage.PrivacyKitBooleanListUtils

import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel

import android.privacykit.IPrivacyKitManager
import android.privacykit.PrivacyKitKeys

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun privacyKitManager(): IPrivacyKitManager? =
    IPrivacyKitManager.Stub.asInterface(ServiceManager.getService("privacykit"))

/** PrivacyKitProfileStore.RULE_CUSTOM - a literal value the user (or a template) supplied. */
private const val RULE_CUSTOM = 4

/** PrivacyKitProfileStore.RULE_REAL - no substitution at all. */
private const val RULE_REAL = 0
private const val RULE_EMPTY = 5

/**
 * Resolves one PrivacyKitKeys.CONTROL_* constant by name, at runtime.
 *
 * A direct `PrivacyKitKeys.CONTROL_X` reference is a compile-time constant the
 * compiler inlines, so it fails the *build* in a tree that does not yet carry
 * the constant - which is no use to a screen that has to survive the backend
 * landing separately. Reading the field reflectively uses the real constant
 * whenever PrivacyKitKeys has it, and falls back to the wire value
 * PrivacyKitService matches on when it does not. In that second case the
 * control does not exist server-side either, so the write is a no-op rather
 * than a wrong one, and [readBooleanControl] keeps reporting off.
 */
private fun pkControlKey(field: String, fallback: String): String = try {
    PrivacyKitKeys::class.java.getField(field).get(null) as? String ?: fallback
} catch (e: Throwable) {
    // Throwable, not Exception: this runs from a top-level initializer, where
    // an Error would poison the whole file class and take the screen with it.
    fallback
}

private val CONTROL_BLOCK_CLIPBOARD_READ =
    pkControlKey("CONTROL_BLOCK_CLIPBOARD_READ", "block_clipboard_read")
private val CONTROL_BLOCK_BACKGROUND_START =
    pkControlKey("CONTROL_BLOCK_BACKGROUND_START", "block_background_start")
private val CONTROL_AUTO_REVOKE_ON_EXIT =
    pkControlKey("CONTROL_AUTO_REVOKE_ON_EXIT", "auto_revoke_on_exit")

/** Everything one pass over the backends can tell us about this package. */
/**
 * The "Spoof settings" rows, in display order. [emptyRule] marks the one whose
 * honest substitute is an empty list rather than the string "0".
 */
private data class PkSpoofFlag(val key: String, val emptyRule: Boolean)

private val PK_SPOOF_FLAGS = listOf(
    PkSpoofFlag(PrivacyKitKeys.KEY_ADB_ENABLED, false),
    PkSpoofFlag(PrivacyKitKeys.KEY_DEVELOPER_OPTIONS, false),
    PkSpoofFlag(PrivacyKitKeys.KEY_WIRELESS_DEBUGGING, false),
    PkSpoofFlag(PrivacyKitKeys.KEY_PACKAGE_VERIFIER, false),
    PkSpoofFlag(PrivacyKitKeys.KEY_USB_APP_VERIFICATION, false),
    PkSpoofFlag(PrivacyKitKeys.KEY_ACCESSIBILITY_SERVICES, true),
)

private data class PkAppControlsState(
    val label: String,
    val hideApp: Boolean,
    val hideDevStatus: Boolean,
    val hideLauncher: Boolean,
    val restrictInternet: Boolean,
    val restrictStorage: Boolean,
    val forceDataIsolation: Boolean,
    val blockClipboardRead: Boolean,
    val blockBackgroundStart: Boolean,
    val autoRevokeOnExit: Boolean,
    /** True when the active profile has any non-REAL rule on Build.FINGERPRINT. */
    val buildSpoofed: Boolean,
    /** Display name of the built-in template the current fingerprint came from, if any. */
    val buildTemplateName: String?,
    /** Whether an AI provider is set up well enough for [PrivacyKitAiGenerator.generate]. */
    val aiConfigured: Boolean,
    /** Device-state flags currently reported as disabled, keyed by PrivacyKit key. */
    val spoofFlags: Map<String, Boolean> = emptyMap(),
)

/**
 * Per-app controls that are *not* identifier rules.
 *
 * This screen replaces the legacy AndroidX-Preference bridge
 * ([PrivacyKitPackageConfigFragment]) that the profile row's "More controls"
 * action used to open. It deliberately carries only the controls that have no
 * equivalent on the Identifiers screen:
 *
 *  - app visibility & status (hide from other apps / hide developer & security
 *    status / hide app icon),
 *  - access restrictions (internet / shared storage / forced data isolation),
 *  - the one-tap coherent device-identity template picker.
 *
 * The legacy screen's per-identifier spoof switches (android_id, serial, imei,
 * imsi, iccid, phone number, Build.* identity) are deliberately NOT ported.
 * They duplicated the Identifiers screen, and a plain on/off switch cannot
 * express the six rule types or the honest "not enforced yet" labelling that
 * screen carries - so the two screens could, and did, disagree about the same
 * key. There is now exactly one place a rule is chosen.
 *
 * Scope differs between the two halves of this screen and the footer says so:
 * the six switches are per-*package* (Settings.Secure CSV membership lists and
 * PrivacyKitService's boolean controls, neither of which is profile-aware),
 * while the identity template is written through setIdentifierRule and so lands
 * on whichever profile is active - which is the profile the user tapped to get
 * here.
 *
 * Nested-screen pattern: swapped into the tab body rather than pushed onto the
 * SPA nav graph, so it carries its own [BackHandler] and [PkScreenHeader].
 */

@Composable
fun PrivacyKitAppControlsScreen(packageName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshToken by remember { mutableStateOf(0) }
    var showTemplatePicker by remember { mutableStateOf(false) }
    var aiRunning by remember { mutableStateOf(false) }

    // Deliberately a plain state + LaunchedEffect rather than produceState: a
    // refresh keeps the previously loaded values on screen while the reload
    // runs, so applying a template doesn't blank every switch for a frame.
    var state by remember { mutableStateOf<PkAppControlsState?>(null) }
    LaunchedEffect(packageName, refreshToken) {
        state = withContext(Dispatchers.IO) { loadAppControls(context, packageName) }
    }

    BackHandler(onBack = onBack)
    // Registered after the real handler so it takes priority while an AI
    // request is in flight (the dispatcher gives the most recently added
    // enabled handler precedence), matching the profile-duplicate guard.
    BackHandler(enabled = aiRunning) {}

    val current = state

    if (aiRunning) {
        // Modal and un-dismissable: the generate call is a network round-trip
        // of up to 30s and applying its result rewrites eight Build.* rules.
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.privacykit_device_profile_ai_working)) },
            text = {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = SettingsDimension.paddingLarge),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            },
            confirmButton = {},
        )
    }

    if (showTemplatePicker && current != null) {
        DeviceIdentityPickerDialog(
            currentTemplateName = current.buildTemplateName,
            usingRealIdentity = !current.buildSpoofed,
            aiConfigured = current.aiConfigured,
            onDismiss = { showTemplatePicker = false },
            onUseReal = {
                showTemplatePicker = false
                scope.launch {
                    withContext(Dispatchers.IO) { clearBuildIdentity(packageName) }
                    refreshToken++
                    Toast.makeText(
                        context,
                        R.string.privacykit_device_profile_reverted,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onTemplatePicked = { template ->
                showTemplatePicker = false
                scope.launch {
                    withContext(Dispatchers.IO) { applyTemplate(packageName, template) }
                    refreshToken++
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.privacykit_device_profile_applied, template.displayName),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onGenerateWithAi = {
                showTemplatePicker = false
                aiRunning = true
                scope.launch {
                    val generated = withContext(Dispatchers.IO) {
                        val template = PrivacyKitAiGenerator.generate(context)
                        if (template != null) applyTemplate(packageName, template)
                        template
                    }
                    aiRunning = false
                    refreshToken++
                    if (generated == null) {
                        Toast.makeText(
                            context,
                            R.string.privacykit_device_profile_ai_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.privacykit_device_profile_applied,
                                generated.displayName),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        PkScreenHeader(
            title = current?.label ?: packageName,
            onBack = onBack,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            PkCategory(title = stringResource(R.string.privacykit_category_obscura)) {
                PkControlSwitch(
                    title = stringResource(R.string.privacykit_hide_app_title),
                    summary = stringResource(R.string.privacykit_hide_app_summary),
                    checked = current?.hideApp,
                    onCheckedChange = { checked ->
                        state = current?.copy(hideApp = checked)
                        scope.launch(Dispatchers.IO) {
                            setHiddenFromOtherApps(context, packageName, checked)
                        }
                    },
                )
                PkControlSwitch(
                    title = stringResource(R.string.privacykit_hide_dev_status_title),
                    summary = stringResource(R.string.privacykit_hide_dev_status_summary),
                    checked = current?.hideDevStatus,
                    onCheckedChange = { checked ->
                        state = current?.copy(hideDevStatus = checked)
                        scope.launch(Dispatchers.IO) {
                            setDeveloperStatusHidden(context, packageName, checked)
                        }
                    },
                )
                PkControlSwitch(
                    title = stringResource(R.string.privacykit_hide_launcher_title),
                    summary = stringResource(R.string.privacykit_hide_launcher_summary),
                    checked = current?.hideLauncher,
                    onCheckedChange = { checked ->
                        state = current?.copy(hideLauncher = checked)
                        scope.launch(Dispatchers.IO) {
                            setLauncherIconHidden(context, packageName, checked)
                        }
                    },
                )
            }

            PkCategory(title = stringResource(R.string.privacykit_category_spoof)) {
                val spoofTitles = listOf(
                    R.string.privacykit_spoof_adb_title,
                    R.string.privacykit_spoof_devopts_title,
                    R.string.privacykit_spoof_wireless_adb_title,
                    R.string.privacykit_spoof_pkg_verifier_title,
                    R.string.privacykit_spoof_usb_verify_title,
                    R.string.privacykit_spoof_a11y_title,
                )
                val spoofSummaries = listOf(
                    R.string.privacykit_spoof_adb_summary,
                    R.string.privacykit_spoof_devopts_summary,
                    R.string.privacykit_spoof_wireless_adb_summary,
                    R.string.privacykit_spoof_pkg_verifier_summary,
                    R.string.privacykit_spoof_usb_verify_summary,
                    R.string.privacykit_spoof_a11y_summary,
                )
                PK_SPOOF_FLAGS.forEachIndexed { i, flag ->
                    PkControlSwitch(
                        title = stringResource(spoofTitles[i]),
                        summary = stringResource(spoofSummaries[i]),
                        checked = current?.spoofFlags?.get(flag.key),
                        onCheckedChange = { checked ->
                            state = current?.let {
                                it.copy(spoofFlags = it.spoofFlags + (flag.key to checked))
                            }
                            scope.launch(Dispatchers.IO) {
                                setSpoofFlag(packageName, flag, checked)
                            }
                        },
                    )
                }
            }
            PkFooter(text = stringResource(R.string.privacykit_spoof_footer))

            PkCategory(title = stringResource(R.string.privacykit_category_restrict)) {
                PkControlSwitch(
                    title = stringResource(R.string.privacykit_restrict_internet_title),
                    summary = stringResource(R.string.privacykit_restrict_internet_summary),
                    checked = current?.restrictInternet,
                    onCheckedChange = { checked ->
                        state = current?.copy(restrictInternet = checked)
                        scope.launch(Dispatchers.IO) {
                            setBooleanControl(
                                packageName, PrivacyKitKeys.CONTROL_RESTRICT_INTERNET, checked)
                        }
                    },
                )
                PkControlSwitch(
                    title = stringResource(R.string.privacykit_restrict_storage_title),
                    summary = stringResource(R.string.privacykit_restrict_storage_summary),
                    checked = current?.restrictStorage,
                    onCheckedChange = { checked ->
                        state = current?.copy(restrictStorage = checked)
                        scope.launch(Dispatchers.IO) {
                            setBooleanControl(
                                packageName, PrivacyKitKeys.CONTROL_RESTRICT_STORAGE, checked)
                        }
                    },
                )
                PkControlSwitch(
                    title = stringResource(R.string.privacykit_force_data_isolation_title),
                    summary = stringResource(R.string.privacykit_force_data_isolation_summary),
                    checked = current?.forceDataIsolation,
                    onCheckedChange = { checked ->
                        state = current?.copy(forceDataIsolation = checked)
                        scope.launch(Dispatchers.IO) {
                            setBooleanControl(
                                packageName,
                                PrivacyKitKeys.CONTROL_FORCE_DATA_ISOLATION,
                                checked)
                        }
                    },
                )
            }

            PkCategory(
                title = stringResource(R.string.privacykit_controls_category_behaviour),
            ) {
                PkControlSwitch(
                    title = stringResource(R.string.privacykit_block_clipboard_title),
                    summary = stringResource(R.string.privacykit_block_clipboard_summary),
                    checked = current?.blockClipboardRead,
                    onCheckedChange = { checked ->
                        state = current?.copy(blockClipboardRead = checked)
                        scope.launch(Dispatchers.IO) {
                            setBooleanControl(
                                packageName, CONTROL_BLOCK_CLIPBOARD_READ, checked)
                        }
                    },
                )
                PkControlSwitch(
                    title = stringResource(
                        R.string.privacykit_block_background_start_title),
                    summary = stringResource(
                        R.string.privacykit_block_background_start_summary),
                    checked = current?.blockBackgroundStart,
                    onCheckedChange = { checked ->
                        state = current?.copy(blockBackgroundStart = checked)
                        scope.launch(Dispatchers.IO) {
                            setBooleanControl(
                                packageName, CONTROL_BLOCK_BACKGROUND_START, checked)
                        }
                    },
                )
                PkControlSwitch(
                    title = stringResource(R.string.privacykit_auto_revoke_exit_title),
                    summary = stringResource(R.string.privacykit_auto_revoke_exit_summary),
                    checked = current?.autoRevokeOnExit,
                    onCheckedChange = { checked ->
                        state = current?.copy(autoRevokeOnExit = checked)
                        scope.launch(Dispatchers.IO) {
                            setBooleanControl(
                                packageName, CONTROL_AUTO_REVOKE_ON_EXIT, checked)
                        }
                    },
                )
            }
            PkFooter(text = stringResource(R.string.privacykit_controls_behaviour_footer))

            PkCategory(title = stringResource(R.string.privacykit_controls_category_identity)) {
                PkPreference(
                    title = stringResource(R.string.privacykit_device_profile_title),
                    summary = deviceIdentitySummary(current),
                    enabled = current != null,
                    onClick = { showTemplatePicker = true },
                )
            }

            PkFooter(text = stringResource(R.string.privacykit_controls_footer))
        }
    }
}

/** The one-line state of this profile's Build.* identity, or the loading placeholder. */
@Composable
private fun deviceIdentitySummary(state: PkAppControlsState?): String {
    if (state == null) {
        return stringResource(R.string.privacykit_device_profile_summary)
    }
    if (!state.buildSpoofed) {
        return stringResource(R.string.privacykit_device_profile_current_real)
    }
    // Local val first so the null check narrows the type for the elvis below.
    val templateName = state.buildTemplateName
    return templateName ?: stringResource(R.string.privacykit_device_profile_current_custom)
}

/**
 * A real SettingsLib SPA switch row. [checked] is null until the backends have
 * been read, which is exactly what SwitchPreferenceModel's nullable `checked`
 * means - the row renders its loading state instead of asserting "off" for a
 * control that might well be on.
 */
@Composable
private fun PkControlSwitch(
    title: String,
    summary: String,
    checked: Boolean?,
    onCheckedChange: (Boolean) -> Unit,
) {
    // Locals first: referencing the parameters from inside the anonymous
    // object's initializers would resolve to the object's own (not yet
    // initialized) properties. Same reason PkPreference does this.
    val rowTitle = title
    val rowSummary = summary
    val rowChecked = checked
    val rowOnCheckedChange = onCheckedChange
    SwitchPreference(
        model = object : SwitchPreferenceModel {
            override val title: String = rowTitle
            override val summary: () -> CharSequence = { rowSummary }
            override val checked: () -> Boolean? = { rowChecked }
            override val changeable: () -> Boolean = { rowChecked != null }
            override val onCheckedChange: ((Boolean) -> Unit)? = rowOnCheckedChange
        },
    )
}

/**
 * The "Generate new profile" picker: the real identity, every curated template,
 * and the optional AI generator.
 *
 * The AI row is shown but disabled when no provider is configured, with the
 * reason underneath. Hiding it would make the feature undiscoverable; enabling
 * it would produce a spinner that always fails. Its configuration lives on the
 * Settings tab's "AI provider" screen and is deliberately not duplicated here.
 */
@Composable
private fun DeviceIdentityPickerDialog(
    currentTemplateName: String?,
    usingRealIdentity: Boolean,
    aiConfigured: Boolean,
    onDismiss: () -> Unit,
    onUseReal: () -> Unit,
    onTemplatePicked: (PrivacyKitDeviceTemplate) -> Unit,
    onGenerateWithAi: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.privacykit_device_profile_pick_title)) },
        text = {
            LazyColumn(Modifier.height(360.dp)) {
                item(key = "real") {
                    IdentityChoiceRow(
                        title = stringResource(R.string.privacykit_device_profile_none),
                        selected = usingRealIdentity,
                        onClick = onUseReal,
                    )
                }
                items(PrivacyKitDeviceTemplates.TEMPLATES, key = { it.displayName }) { template ->
                    val isCurrent = !usingRealIdentity &&
                            template.displayName == currentTemplateName
                    IdentityChoiceRow(
                        title = template.displayName,
                        selected = isCurrent,
                        onClick = { onTemplatePicked(template) },
                    )
                }
                item(key = "ai") {
                    AiChoiceRow(enabled = aiConfigured, onClick = onGenerateWithAi)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.privacykit_cancel)) }
        },
    )
}

@Composable
private fun IdentityChoiceRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(title, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun AiChoiceRow(enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.privacykit_device_profile_ai),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (!enabled) {
            Text(
                text = stringResource(R.string.privacykit_device_profile_ai_not_set_up),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Reads every backend this screen shows, in one pass.
 *
 * Deliberately blocking - callers run it on [Dispatchers.IO] - because it mixes
 * SettingsProvider reads, binder round-trips, package-label resolution and a
 * SharedPreferences read. Each source is caught independently so one
 * unavailable backend cannot blank the rest of the screen.
 */
private fun loadAppControls(context: Context, packageName: String): PkAppControlsState {
    val pm = context.packageManager
    val label = try {
        pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            .loadLabel(pm).toString()
    } catch (e: Exception) {
        packageName
    }
    // Exactly the reads the legacy preference controllers did.
    val hideApp = try {
        HideAppListUtils.getApps(context).contains(packageName)
    } catch (e: Exception) {
        false
    }
    val hideDevStatus = try {
        val apps = Settings.Secure.getString(
            context.contentResolver, "hide_developer_status")
        apps != null && apps.split(",").contains(packageName)
    } catch (e: Exception) {
        false
    }
    val hideLauncher = try {
        PrivacyKitBooleanListUtils.contains(
            context.contentResolver, "privacykit_hide_launcher_list", packageName)
    } catch (e: Exception) {
        false
    }
    val mgr = try {
        privacyKitManager()
    } catch (e: Exception) {
        null
    }
    val restrictInternet =
        readBooleanControl(mgr, packageName, PrivacyKitKeys.CONTROL_RESTRICT_INTERNET)
    val restrictStorage =
        readBooleanControl(mgr, packageName, PrivacyKitKeys.CONTROL_RESTRICT_STORAGE)
    val forceDataIsolation =
        readBooleanControl(mgr, packageName, PrivacyKitKeys.CONTROL_FORCE_DATA_ISOLATION)
    // Same guarded read as the three above: readBooleanControl swallows the
    // RemoteException a system_server that predates these controls throws, and
    // reports off, so an unlanded backend shows as three switches that are off.
    val blockClipboardRead =
        readBooleanControl(mgr, packageName, CONTROL_BLOCK_CLIPBOARD_READ)
    val blockBackgroundStart =
        readBooleanControl(mgr, packageName, CONTROL_BLOCK_BACKGROUND_START)
    val autoRevokeOnExit =
        readBooleanControl(mgr, packageName, CONTROL_AUTO_REVOKE_ON_EXIT)
    // Build.FINGERPRINT stands in for the whole coherent bundle: every path that
    // writes these eight fields (this screen's templates, the Identifiers
    // screen, a restored backup) writes them together.
    var buildSpoofed = false
    var buildTemplateName: String? = null
    if (mgr != null) {
        try {
            buildSpoofed = mgr.getRuleType(packageName, PrivacyKitKeys.KEY_BUILD_FINGERPRINT) !=
                    RULE_REAL
            if (buildSpoofed) {
                val fingerprint = mgr.getRuleValue(
                    packageName, PrivacyKitKeys.KEY_BUILD_FINGERPRINT)
                buildTemplateName = PrivacyKitDeviceTemplates.TEMPLATES
                    .firstOrNull { it.fingerprint == fingerprint }
                    ?.displayName
            }
        } catch (e: Exception) {
            buildSpoofed = false
            buildTemplateName = null
        }
    }
    val aiConfigured = try {
        PrivacyKitAiGenerator.isConfigured(context)
    } catch (e: Exception) {
        false
    }
    // A flag is "spoofed" whenever its rule is anything other than REAL.
    val spoofFlags = HashMap<String, Boolean>()
    if (mgr != null) {
        for (flag in PK_SPOOF_FLAGS) {
            spoofFlags[flag.key] = try {
                mgr.getRuleType(packageName, flag.key) != RULE_REAL
            } catch (e: Exception) {
                false
            }
        }
    }
    return PkAppControlsState(
        label = label,
        hideApp = hideApp,
        hideDevStatus = hideDevStatus,
        hideLauncher = hideLauncher,
        restrictInternet = restrictInternet,
        restrictStorage = restrictStorage,
        forceDataIsolation = forceDataIsolation,
        blockClipboardRead = blockClipboardRead,
        blockBackgroundStart = blockBackgroundStart,
        autoRevokeOnExit = autoRevokeOnExit,
        buildSpoofed = buildSpoofed,
        buildTemplateName = buildTemplateName,
        aiConfigured = aiConfigured,
        spoofFlags = spoofFlags,
    )
}

private fun readBooleanControl(
    mgr: IPrivacyKitManager?,
    packageName: String,
    control: String,
): Boolean = try {
    mgr?.getBooleanControl(packageName, control) ?: false
} catch (e: Exception) {
    // Fail open: getBooleanControl can throw RemoteException as well as
    // SecurityException (a RuntimeException) from enforceCallingPermission.
    false
}

// ---- writes: identical mechanisms to the legacy preference controllers ------

private fun setHiddenFromOtherApps(context: Context, packageName: String, hidden: Boolean) {
    try {
        val utils = HideAppListUtils()
        val userId = UserHandle.myUserId()
        if (hidden) {
            utils.addApp(context, packageName, userId)
        } else {
            utils.removeApp(context, packageName, userId)
        }
    } catch (e: Exception) {
    }
}

private fun setDeveloperStatusHidden(context: Context, packageName: String, hidden: Boolean) {
    try {
        val utils = HideDeveloperStatusUtils()
        val userId = UserHandle.myUserId()
        if (hidden) {
            utils.addApp(context, packageName, userId)
        } else {
            utils.removeApp(context, packageName, userId)
        }
    } catch (e: Exception) {
    }
}

private fun setLauncherIconHidden(context: Context, packageName: String, hidden: Boolean) {
    try {
        val userId = UserHandle.myUserId()
        if (hidden) {
            PrivacyKitBooleanListUtils.add(
                context, "privacykit_hide_launcher_list", packageName, userId)
        } else {
            PrivacyKitBooleanListUtils.remove(
                context, "privacykit_hide_launcher_list", packageName, userId)
        }
    } catch (e: Exception) {
    }
}

/**
 * Turns one device-state flag spoof on or off for [packageName]. ON stores the
 * rule the SettingsProvider hook looks for; OFF restores the real value.
 */
private fun setSpoofFlag(packageName: String, flag: PkSpoofFlag, on: Boolean) {
    try {
        val mgr = privacyKitManager() ?: return
        when {
            !on -> mgr.setIdentifierRule(packageName, flag.key, RULE_REAL, null)
            flag.emptyRule -> mgr.setIdentifierRule(packageName, flag.key, RULE_EMPTY, null)
            else -> mgr.setIdentifierRule(packageName, flag.key, RULE_CUSTOM, "0")
        }
    } catch (e: Exception) {
    }
}

private fun setBooleanControl(packageName: String, control: String, value: Boolean) {
    try {
        privacyKitManager()?.setBooleanControl(packageName, control, value)
    } catch (e: Exception) {
    }
}

/**
 * Writes a template's eight Build.* fields as RULE_CUSTOM, exactly as
 * PrivacyKitDeviceProfilePC.applyTemplate did. These land on the package's
 * *active* profile, because setIdentifierRule takes no profile id.
 */
private fun applyTemplate(packageName: String, template: PrivacyKitDeviceTemplate) {
    try {
        val mgr = privacyKitManager() ?: return
        for ((key, value) in template.toFieldMap()) {
            mgr.setIdentifierRule(packageName, key, RULE_CUSTOM, value)
        }
    } catch (e: Exception) {
    }
}

/**
 * Clears every rule a template can write - a half-cleared bundle is incoherent.
 *
 * PrivacyKitDeviceTemplates.IDENTITY_KEYS, not PrivacyKitKeys.BUILD_KEYS: applyTemplate
 * above writes the template's whole field map, which is wider than those eight, and a
 * clear narrower than the apply leaves the difference welded to the device the user just
 * switched away from.
 */
private fun clearBuildIdentity(packageName: String) {
    try {
        val mgr = privacyKitManager() ?: return
        for (key in PrivacyKitDeviceTemplates.IDENTITY_KEYS) {
            mgr.clearIdentifierRule(packageName, key)
        }
    } catch (e: Exception) {
    }
}
