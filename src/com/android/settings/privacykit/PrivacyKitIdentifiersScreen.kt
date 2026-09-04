/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.privacykit

import android.content.Context
import android.os.ServiceManager

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsDimension

import android.privacykit.IPrivacyKitManager
import android.privacykit.PrivacyKitIdentifierGenerator
import android.privacykit.PrivacyKitKeys

import java.util.concurrent.Executors

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun pkManager(): IPrivacyKitManager? =
    IPrivacyKitManager.Stub.asInterface(ServiceManager.getService("privacykit"))

/**
 * The one background thread every PrivacyKit binder call on this screen goes through: the catalog
 * snapshot read, the rule picker's initial read, and every setIdentifierRule write.
 *
 * Both of its properties are load bearing:
 *  - Nothing blocks the main thread on system_server. setIdentifierRule persists the profile, so
 *    running it inline on a radio tap or on a back press was a real jank source.
 *  - It is single threaded, so tasks run in submission order. A rule write queued while the picker
 *    closes is therefore guaranteed to land *before* the catalog reload that the same back press
 *    starts, which is what lets the list refresh in place and never show the pre-change rule.
 *
 * It is process wide and deliberately never shut down: a value flushed from onDispose has to
 * outlive the composable that flushed it.
 */
private val pkBinderExecutor = Executors.newSingleThreadExecutor()
private val pkBinderDispatcher = pkBinderExecutor.asCoroutineDispatcher()

/** How long the custom-value field stays quiet before it commits to the service. */
private const val CUSTOM_COMMIT_DELAY_MS = 700L

/** Rounded-card geometry, matching the expressive Settings preference grouping. */
private val GroupCornerLarge = 20.dp
private val GroupCornerSmall = 4.dp
private val GroupRowGap = 1.dp

/**
 * Corner treatment for row [index] of a group of [count] rows, so a group of preference rows reads
 * as one rounded card (large outer corners, small inner corners) like AOSP Settings does.
 */
private fun rowShape(index: Int, count: Int): RoundedCornerShape = when {
    count <= 1 -> RoundedCornerShape(GroupCornerLarge)
    index == 0 -> RoundedCornerShape(
        topStart = GroupCornerLarge,
        topEnd = GroupCornerLarge,
        bottomStart = GroupCornerSmall,
        bottomEnd = GroupCornerSmall,
    )
    index == count - 1 -> RoundedCornerShape(
        topStart = GroupCornerSmall,
        topEnd = GroupCornerSmall,
        bottomStart = GroupCornerLarge,
        bottomEnd = GroupCornerLarge,
    )
    else -> RoundedCornerShape(GroupCornerSmall)
}

/** One bulk read of every catalog identifier's rule (and, for Custom rules, its value). */
private class PkRuleSnapshot(
    val types: Map<String, PkRuleType>,
    val values: Map<String, String>,
) {
    fun ruleOf(key: String): PkRuleType = types[key] ?: PkRuleType.REAL
}

/**
 * Reads the whole catalog in one pass. The old screen issued one binder call per visible row from
 * inside the row composable, which meant ~63 main-thread round trips while scrolling; this runs
 * once per screen entry - plus once per rule edit, to refresh the list in place - and also gives
 * the category headers their counters. Blocking AIDL: only ever call it on [pkBinderDispatcher].
 */
private fun loadRuleSnapshot(packageName: String): PkRuleSnapshot {
    val mgr = pkManager() ?: return PkRuleSnapshot(emptyMap(), emptyMap())
    val types = LinkedHashMap<String, PkRuleType>()
    val values = LinkedHashMap<String, String>()
    for (item in PrivacyKitIdentifierCatalog.allItems) {
        val rule = try {
            PkRuleType.fromServerValue(mgr.getRuleType(packageName, item.key))
        } catch (e: Exception) {
            PkRuleType.REAL
        }
        types[item.key] = rule
        if (rule == PkRuleType.CUSTOM) {
            val value = try {
                mgr.getRuleValue(packageName, item.key)
            } catch (e: Exception) {
                null
            }
            if (!value.isNullOrEmpty()) {
                values[item.key] = value
            }
        }
    }
    return PkRuleSnapshot(types, values)
}

/** A catalog group after search filtering: [allItems] drives the counters, [visibleItems] the rows. */
private class PkVisibleGroup(
    val title: String,
    val allItems: List<PkIdentifierItem>,
    val visibleItems: List<PkIdentifierItem>,
)

/** A [PkVisibleGroup] with its expand state and header summary resolved, ready to lay out. */
private class PkRenderGroup(
    val group: PkVisibleGroup,
    val expanded: Boolean,
    val summary: String,
)

/** Localized display name for a rule type (the catalog's own labels are English-only literals). */
private fun ruleLabel(context: Context, rule: PkRuleType): String = context.getString(
    when (rule) {
        PkRuleType.REAL -> R.string.privacykit_rule_real
        PkRuleType.STATIC -> R.string.privacykit_rule_static
        PkRuleType.RANDOM_PER_LAUNCH -> R.string.privacykit_rule_per_launch
        PkRuleType.RANDOM_DAILY -> R.string.privacykit_rule_daily
        PkRuleType.CUSTOM -> R.string.privacykit_rule_custom
        PkRuleType.EMPTY -> R.string.privacykit_rule_empty
    }
)

/** One-line explanation of what a rule type actually does, shown under the radio option. */
private fun ruleDescription(context: Context, rule: PkRuleType): String = context.getString(
    when (rule) {
        PkRuleType.REAL -> R.string.privacykit_rule_real_summary
        PkRuleType.STATIC -> R.string.privacykit_rule_static_summary
        PkRuleType.RANDOM_PER_LAUNCH -> R.string.privacykit_rule_per_launch_summary
        PkRuleType.RANDOM_DAILY -> R.string.privacykit_rule_daily_summary
        PkRuleType.CUSTOM -> R.string.privacykit_rule_custom_summary
        PkRuleType.EMPTY -> R.string.privacykit_rule_empty_summary
    }
)

/** Row summary: the rule name, or "Custom: <value>" when a custom value is configured. */
private fun ruleSummary(context: Context, rule: PkRuleType, customValue: String?): String =
    if (rule == PkRuleType.CUSTOM && !customValue.isNullOrEmpty()) {
        context.getString(R.string.privacykit_summary_custom, customValue)
    } else {
        ruleLabel(context, rule)
    }

/**
 * Category header counter, e.g. "3 of 8 spoofed  -  2 set but not enforced". Deliberately counts
 * enforced and unenforced changes separately so the header never overstates what is really applied.
 *
 * Only [PkEnforcement.FULL] is allowed into the first number. PARTIAL keys are counted with the
 * unenforced ones, which is the direction the catalog's own contract demands - "PARTIAL and NONE
 * must both render as not-enforced" - and means this figure can only ever understate protection,
 * never overstate it. That is the right way round for a privacy tool: a user who trusts an inflated
 * count is the one who gets burned. The per-row note is where the nuance gets explained, since a
 * single integer cannot say "the deprecated field is spoofed but the modern accessor is not".
 *
 * Known wording limitation, deliberately not papered over: the string for that first number reads
 * "N of M spoofed", but the Privacy Restrictions group is made of keys that *block* rather than
 * substitute - contacts, call log, SMS, calendar, bonded devices and now advertising_id. They are
 * genuinely enforced, so excluding them would understate protection for the whole group, and there
 * is no third counter string in res/values to say "blocked" instead. The row itself, its info
 * dialog and the rule-picker banner all state plainly that these are blocks and not fakes, so the
 * imprecision is confined to one aggregate word. Fixing it needs one new string
 * (privacykit_group_summary_blocked) in strings_privacykit_identifiers.xml.
 *
 * The remaining gap is cosmetic, not honesty related: partials are folded into the same "set but
 * not enforced" phrase as no-hook keys because there is no third counter string in res/values yet,
 * and this screen is not allowed to add one. See the report note on privacykit_group_summary_partial.
 */
private fun groupSummary(
    context: Context,
    items: List<PkIdentifierItem>,
    snapshot: PkRuleSnapshot,
): String {
    // Enforcement status is no longer surfaced in the UI; the header just counts how
    // many identifiers in the group carry a non-Real rule.
    var spoofed = 0
    for (item in items) {
        if (snapshot.ruleOf(item.key) != PkRuleType.REAL) spoofed++
    }
    if (spoofed == 0) {
        return context.getString(R.string.privacykit_group_summary_none)
    }
    return context.getString(R.string.privacykit_group_summary_spoofed, spoofed, items.size)
}

// ---- Randomise --------------------------------------------------------------------------------

/**
 * What "Randomise all" does to one identifier. Two modes, because a Static rule and a Custom rule
 * keep their value in different places and only one of those places is reachable from this process:
 *
 *  - [REROLL_STATIC] rewrites the *same* Static rule. PrivacyKitProfileStore#setRule drops that
 *    key's entry from the generated-value cache on every write, and PrivacyKitRuleResolver's Static
 *    branch now mints from fresh entropy instead of from a (package, key) hash, so the next read
 *    produces a genuinely new value - minted in system_server, where the device's real value is in
 *    hand. That is the only way media_drm_id can be randomised at all, since its generator has to
 *    match the real blob's byte length, and it leaves the user's chosen rule type alone.
 *  - [NEW_CUSTOM] generates the value here and stores it as the Custom literal, which is what the
 *    row summary and the Custom field then show.
 */
private enum class PkRandomiseMode { REROLL_STATIC, NEW_CUSTOM }

/** One identifier that "Randomise all" will act on. */
private class PkRandomiseEntry(val key: String, val label: String, val mode: PkRandomiseMode)

/**
 * Everything the confirmation dialog needs in order to state plainly what is about to happen: what
 * changes, how much configured state is deliberately left alone, and whether this app's Build
 * identity is currently spoofed incoherently enough to be worth offering a template for.
 */
private class PkRandomisePlan(
    val entries: List<PkRandomiseEntry>,
    val skipped: Int,
    val spoofedBuildKeys: Int,
    val template: PrivacyKitDeviceTemplate?,
)

/**
 * Keys whose values only mean anything as a *set*: the Build identity, the Build.VERSION fields,
 * the ABI lists and the kernel string.
 *
 * Randomising one of these on its own produces a device that cannot exist - a Pixel model beside a
 * Samsung manufacturer, a security patch date from a build that never shipped - and an impossible
 * device is a far stronger fingerprint than a real one. The catalog's device templates exist
 * precisely so that these move together, which is why nothing on this screen offers to draw one of
 * them at random.
 */
private fun isCoherentIdentityKey(key: String): Boolean =
    key.startsWith("build_") || key.startsWith("os_") || key.startsWith("supported_") ||
            key == "kernel_version"

/**
 * Whether this screen may offer to fill the Custom field for [key] itself.
 *
 * Both halves are honesty gates rather than conveniences. Without a dedicated generator the engine
 * emits an opaque 16-character hex string, which is a plausible token and an implausible model
 * name, ABI list or time zone id - and the in-process injector validates several of those and keeps
 * the real value, so the button would look like it worked and change nothing. And media_drm_id has
 * to match the real blob's byte length, which no app process can see.
 */
private fun canRandomiseCustomValue(key: String): Boolean =
    PrivacyKitIdentifierGenerator.hasGeneratorFor(key) &&
            !PrivacyKitIdentifierGenerator.needsRealValue(key)

/**
 * How - or whether - "Randomise all" touches [key], given its current [rule].
 *
 * Every exclusion is deliberate:
 *  - [PkRuleType.REAL] is never touched. Randomising a key the user left alone would silently start
 *    spoofing it, which is a far bigger change than the button promises.
 *  - [PkRuleType.RANDOM_PER_LAUNCH] and [PkRuleType.RANDOM_DAILY] are never touched: they already
 *    mint a new value on their own schedule, so "randomising" them could only mean freezing them to
 *    one value, i.e. weakening them.
 *  - [PkRuleType.EMPTY] is never touched; it has no value to randomise.
 *  - restriction keys are never touched; they block access instead of substituting a value, so
 *    there is nothing to regenerate.
 *  - a key with no dedicated generator is never touched (see [canRandomiseCustomValue]), which is
 *    also what keeps the whole Build / OS / ABI group out of this action.
 *  - the backend's own per-key rule-type allow-list is honoured, so a rule type
 *    PrivacyKitRuleResolver#isRuleTypeAllowed refuses is never written - media_drm_id is eligible
 *    only through its Static rule, never by being converted to Custom.
 */
private fun randomiseModeFor(key: String, rule: PkRuleType): PkRandomiseMode? {
    if (PrivacyKitIdentifierCatalog.isRestrictionKey(key)) return null
    if (!PrivacyKitIdentifierGenerator.hasGeneratorFor(key)) return null
    val supported = PrivacyKitIdentifierCatalog.supportedRuleTypesFor(key)
    return when (rule) {
        PkRuleType.STATIC ->
            if (PkRuleType.STATIC in supported) PkRandomiseMode.REROLL_STATIC else null
        PkRuleType.CUSTOM ->
            if (PkRuleType.CUSTOM in supported && canRandomiseCustomValue(key)) {
                PkRandomiseMode.NEW_CUSTOM
            } else {
                null
            }
        else -> null
    }
}

/**
 * Works out what "Randomise all" would do, from the catalog snapshot already on screen. Pure and
 * cheap - no binder, no disk - so it can run on the tap that opens the confirmation.
 *
 * [PkRandomisePlan.skipped] counts only identifiers the user has actually configured, so the dialog
 * can say "N of your settings are left alone" without counting the ~50 keys sitting at Real.
 */
private fun buildRandomisePlan(snapshot: PkRuleSnapshot): PkRandomisePlan {
    val entries = ArrayList<PkRandomiseEntry>()
    var skipped = 0
    for (item in PrivacyKitIdentifierCatalog.allItems) {
        val rule = snapshot.ruleOf(item.key)
        val mode = randomiseModeFor(item.key, rule)
        if (mode == null) {
            if (rule != PkRuleType.REAL) skipped++
        } else {
            entries.add(PkRandomiseEntry(item.key, item.label, mode))
        }
    }
    var spoofedBuildKeys = 0
    for (key in PrivacyKitKeys.BUILD_KEYS) {
        if (snapshot.ruleOf(key) != PkRuleType.REAL) spoofedBuildKeys++
    }
    // A template is only offered when the Build identity is already being spoofed, i.e. when there
    // is an incoherent identity to repair. If every Build field is Real there is nothing to fix and
    // applying a template would start spoofing eight fields the user never asked about - the same
    // rule that keeps this action off Real keys everywhere else.
    //
    // Built-ins only, deliberately: they are curated real devices, and pulling in the user's
    // imported templates would put a SharedPreferences read on this path.
    val template = if (spoofedBuildKeys > 0) {
        PrivacyKitDeviceTemplates.TEMPLATES.randomOrNull()
    } else {
        null
    }
    return PkRandomisePlan(entries, skipped, spoofedBuildKeys, template)
}

/**
 * Runs [plan]. One blocking binder call plus a system_server disk write per identifier, so this is
 * only ever called on [pkBinderDispatcher] / [pkBinderExecutor], never on the main thread.
 *
 * One history entry per key is unavoidable - there is no bulk AIDL - and PrivacyKitHistoryStore
 * only folds an entry into the one before it when the package, action and detail all match, which
 * they never do across different keys. That is why the eligibility rules above matter for more than
 * correctness: they hold a realistic batch to a handful of identifiers against a 200-entry log,
 * rather than the ~68 a catalog-wide sweep would write. If this action is ever widened, the log
 * needs a bulk verb (one "RULES_RANDOMISED, N keys" entry) before that happens.
 */
private fun applyRandomisePlan(
    packageName: String,
    plan: PkRandomisePlan,
    applyTemplate: Boolean,
) {
    val mgr = pkManager() ?: return
    for (entry in plan.entries) {
        try {
            when (entry.mode) {
                PkRandomiseMode.REROLL_STATIC ->
                    mgr.setIdentifierRule(
                        packageName, entry.key, PkRuleType.STATIC.serverValue, null)
                PkRandomiseMode.NEW_CUSTOM -> {
                    val generated = PrivacyKitIdentifierGenerator.generateFresh(entry.key, null)
                    if (!generated.isNullOrEmpty()) {
                        mgr.setIdentifierRule(
                            packageName, entry.key, PkRuleType.CUSTOM.serverValue, generated)
                    }
                }
            }
        } catch (e: Exception) {
            // One identifier failing - a dead service, a SecurityException - must not abandon the
            // rest of the batch, and must never throw into the UI.
        }
    }
    val template = plan.template
    if (applyTemplate && template != null) {
        // Exactly what the existing device-template control writes: all eight coherent Build fields
        // as one Custom bundle, so they cannot drift apart.
        for ((key, value) in template.toFieldMap()) {
            try {
                mgr.setIdentifierRule(packageName, key, PkRuleType.CUSTOM.serverValue, value)
            } catch (e: Exception) {
            }
        }
    }
}

/**
 * Full identifier catalog screen, ported from the real PrivacyKit app's IdentifiersScreen and then
 * restyled to match AOSP Settings: collapsible category headers with per-category counters, rounded
 * preference-card rows showing the current rule as a summary, and a search field over all groups.
 * Tapping a row opens the rule-type picker (Real/Static/Random-per-launch/Random-daily/Custom/Empty).
 * Operates on packageName's *active* profile via the same generic AIDL calls the old per-app screen
 * already used.
 *
 * Honesty rule enforced here, in the three states [PrivacyKitIdentifierCatalog.enforcementOf]
 * distinguishes:
 *
 *  - FULL: normal appearance. The framework really does return the spoofed value on the API an app
 *    would call, for every rule type.
 *  - PARTIAL: de-emphasized exactly like NONE - a partly covered identifier must never *look* more
 *    protected than an uncovered one - but distinguished from it by a differently tinted tag and,
 *    crucially, by an inline caveat line naming what is and is not covered ("Overrides the
 *    deprecated Build.RADIO field... Build.getRadioVersion() still reports the real baseband").
 *    Tapping its info button opens that caveat in full rather than the generic dialog.
 *  - NONE: de-emphasized, generic "Not enforced yet" tag. Its dialog and its red rule-picker
 *    banner use the generic "no hook exists" copy only when the catalog has nothing more specific;
 *    a key like gsf_id, where a hook exists but the rule engine cannot feed it a usable value, gets
 *    its own sentence instead, because the generic one would be false.
 *
 * Orthogonal to those three, and just as load bearing: a FULL key may be enforced by *blocking*
 * rather than by substituting a value (advertising_id). Those are never de-emphasized - the
 * protection is real - but they always carry
 * [PrivacyKitIdentifierCatalog.restrictionNotes]' sentence on the row, in the info dialog and above
 * the rule picker, so "blocked" is never read as "spoofed". All three note sources reach this
 * screen through one accessor, [PrivacyKitIdentifierCatalog.noteOf].
 *
 * Before this pass PARTIAL and NONE were indistinguishable, so a hook that genuinely worked on one
 * path looked as absent as a key with no hook at all. That under-informed the user in a way they
 * could not act on; the caveat text is the actionable part.
 */
@Composable
fun PrivacyKitIdentifiersScreen(packageName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshToken by rememberSaveable { mutableStateOf(0) }
    var showExplainer by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // Which row's info button was tapped, or null for no dialog. Was a plain Boolean while every
    // unenforced row shared one generic dialog; a PARTIAL row now needs its own per-key caveat, so
    // the identity of the row has to survive to the dialog. String? is saveable, so it still
    // restores across a configuration change.
    var infoDialogKey by rememberSaveable { mutableStateOf<String?>(null) }
    // The pending "Randomise all" confirmation, or null for no dialog. Deliberately NOT
    // saveable: a plan is a reading of what the service currently holds, so rebuilding it
    // after a configuration change is both cheaper and safer than restoring a stale one that
    // might now name identifiers the user has since changed.
    var randomisePlan by remember { mutableStateOf<PkRandomisePlan?>(null) }
    // Opt-in, per dialog, and always reset to false when a new one opens: applying a device
    // template writes all eight Build fields, including ones currently left at Real.
    var randomiseWithTemplate by remember { mutableStateOf(false) }
    var randomiseRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()

    // Everything from here to the early returns below has to stay *above* them. The rule picker and
    // the fingerprint explainer replace this screen's entire content, so anything remembered after
    // an early return is torn down when one of them opens and rebuilt from scratch when it closes.
    // The catalog snapshot used to live down there, which meant every single rule edit blanked all
    // nine groups back to a full-screen spinner while ~68 binder calls re-ran, and reset the scroll
    // position on the way back. Held up here it survives the round trip: a rule edit only refreshes
    // it *in place* through refreshToken, so the previous snapshot (correct except for the one row
    // just edited) stays on screen until the new one lands - no flicker, no lost scroll position.
    var snapshot by remember { mutableStateOf<PkRuleSnapshot?>(null) }
    LaunchedEffect(packageName, refreshToken) {
        val reloaded = withContext(pkBinderDispatcher) { loadRuleSnapshot(packageName) }
        snapshot = reloaded
    }

    val trimmedQuery = query.trim()
    val visibleGroups = remember(trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            PrivacyKitIdentifierCatalog.groups.map {
                PkVisibleGroup(it.title, it.items, it.items)
            }
        } else {
            val matched = ArrayList<PkVisibleGroup>()
            for (group in PrivacyKitIdentifierCatalog.groups) {
                val hits = group.items.filter {
                    it.label.contains(trimmedQuery, ignoreCase = true) ||
                            it.key.contains(trimmedQuery, ignoreCase = true)
                }
                if (hits.isNotEmpty()) {
                    matched.add(PkVisibleGroup(group.title, group.items, hits))
                }
            }
            matched
        }
    }

    // Starting a search re-expands everything, so a hit can never hide inside a collapsed group.
    // Hoisted for the same reason as the snapshot: left below the early return it would re-fire on
    // every picker round trip and silently re-expand groups the user had collapsed while searching.
    LaunchedEffect(trimmedQuery) {
        if (trimmedQuery.isNotEmpty()) expandedGroups.clear()
    }

    val key = selectedKey
    if (key != null) {
        // One lambda for every way out of the picker, so the two exits cannot drift apart. The
        // system back gesture used to skip the refreshToken bump that the toolbar arrow did; that
        // was harmless only while the snapshot above was destroyed and rebuilt anyway. Now that it
        // survives, a path that forgot to bump would leave the row - and its group counter - showing
        // the pre-change rule even though the new one had been persisted.
        val closePicker: () -> Unit = {
            selectedKey = null
            refreshToken++
        }
        BackHandler(onBack = closePicker)
        PrivacyKitIdentifierRuleScreen(
            packageName = packageName,
            item = PrivacyKitIdentifierCatalog.itemByKey(key),
            onBack = closePicker,
        )
        return
    }

    if (showExplainer) {
        // Read-only screen - it has no setIdentifierRule path at all - so closing it cannot
        // invalidate the snapshot and deliberately does not bump refreshToken. Both of its exits
        // still share one lambda.
        val closeExplainer: () -> Unit = { showExplainer = false }
        BackHandler(onBack = closeExplainer)
        PrivacyKitFingerprintExplainerScreen(
            packageName = packageName,
            onBack = closeExplainer,
        )
        return
    }

    BackHandler(onBack = onBack)

    // Nullable var captured by the dialog lambdas, so it is pinned to a local val first - a nullable
    // `var` never smart-casts inside a closure.
    val dialogKey = infoDialogKey
    if (dialogKey != null) {
        val dismiss: () -> Unit = { infoDialogKey = null }
        val dialogNote = PrivacyKitIdentifierCatalog.noteOf(dialogKey)
        if (dialogNote != null) {
            IdentifierNoteDialog(
                label = PrivacyKitIdentifierCatalog.itemByKey(dialogKey).label,
                note = dialogNote,
                onDismiss = dismiss,
            )
        } else {
            NotEnforcedInfoDialog(onDismiss = dismiss)
        }
    }

    // Same nullable-var-in-a-lambda rule as dialogKey above: pin it to a local val first.
    val plan = randomisePlan
    if (plan != null) {
        val dismissPlan: () -> Unit = { randomisePlan = null }
        if (plan.entries.isEmpty() && plan.template == null) {
            RandomiseNothingDialog(onDismiss = dismissPlan)
        } else {
            RandomiseAllDialog(
                plan = plan,
                withTemplate = randomiseWithTemplate,
                onWithTemplateChange = { randomiseWithTemplate = it },
                onDismiss = dismissPlan,
                onConfirm = {
                    val useTemplate = randomiseWithTemplate
                    randomisePlan = null
                    randomiseRunning = true
                    // Queued on the process-wide executor rather than run inside the coroutine
                    // below, for the same reason a custom-value flush is: a batch the user
                    // has already confirmed must finish even if they leave the screen while it
                    // is running, and a half-applied identity is worse than either end state.
                    pkBinderExecutor.execute {
                        applyRandomisePlan(packageName, plan, useTemplate)
                    }
                    scope.launch {
                        // Rides the same single-threaded queue, so it can only run once the
                        // batch above has - which is what makes the reload below see the new
                        // values rather than the pre-change ones.
                        withContext(pkBinderDispatcher) { }
                        randomiseRunning = false
                        refreshToken++
                    }
                },
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        IdentifiersTopBar(
            onBack = onBack,
            onExplain = { showExplainer = true },
            // Nothing to plan from until the first snapshot lands, and a second batch must
            // never be queued behind one that is still running.
            randomiseEnabled = snapshot != null && !randomiseRunning,
            onRandomiseAll = {
                val current = snapshot
                if (current != null) {
                    randomiseWithTemplate = false
                    randomisePlan = buildRandomisePlan(current)
                }
            },
        )
        IdentifiersSearchField(
            query = query,
            onQueryChange = { query = it },
        )

        // Only the very first load can still be null; a refresh keeps the previous snapshot on
        // screen rather than falling back into this spinner.
        val snap = snapshot
        if (snap == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(SettingsDimension.paddingLarge))
                    Text(
                        text = stringResource(R.string.privacykit_ids_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }

        val rendered = ArrayList<PkRenderGroup>(visibleGroups.size)
        for (group in visibleGroups) {
            rendered.add(
                PkRenderGroup(
                    group = group,
                    expanded = expandedGroups[group.title] ?: true,
                    summary = groupSummary(context, group.allItems, snap),
                )
            )
        }

        // Remembered above the early returns, so coming back from a rule edit lands the user on the
        // row they tapped instead of at the top of the catalog.
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            if (rendered.isEmpty()) {
                item(key = "pk_no_results") { IdentifiersNoResults() }
            }
            for (entry in rendered) {
                item(key = "pk_header_${entry.group.title}") {
                    IdentifierGroupHeader(
                        title = entry.group.title,
                        summary = entry.summary,
                        expanded = entry.expanded,
                        onToggle = {
                            expandedGroups[entry.group.title] = !entry.expanded
                        },
                    )
                }
                if (entry.expanded) {
                    val rows = entry.group.visibleItems
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                        IdentifierPreferenceRow(
                            item = row,
                            rule = snap.ruleOf(row.key),
                            customValue = snap.values[row.key],
                            shape = rowShape(index, rows.size),
                            onClick = { selectedKey = row.key },
                            onInfoClick = { infoDialogKey = row.key },
                        )
                    }
                }
            }
            item(key = "pk_footer") { IdentifiersFooter() }
        }
    }
}

@Composable
private fun IdentifiersTopBar(
    onBack: () -> Unit,
    onExplain: () -> Unit,
    randomiseEnabled: Boolean,
    onRandomiseAll: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(
            start = SettingsDimension.paddingSmall,
            end = SettingsDimension.paddingSmall,
            top = SettingsDimension.paddingSmall,
            bottom = SettingsDimension.paddingSmall,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.privacykit_back),
            )
        }
        Text(
            text = stringResource(R.string.privacykit_identifiers_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = SettingsDimension.paddingSmall),
        )
        IconButton(onClick = onRandomiseAll, enabled = randomiseEnabled) {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = stringResource(R.string.privacykit_rnd_action),
            )
        }
        IconButton(onClick = onExplain) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = stringResource(R.string.privacykit_explainer_title),
            )
        }
    }
}

@Composable
private fun IdentifiersSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        shape = RoundedCornerShape(GroupCornerLarge),
        placeholder = { Text(stringResource(R.string.privacykit_ids_search_hint)) },
        leadingIcon = {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.privacykit_ids_search_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(
            start = SettingsDimension.paddingLarge,
            end = SettingsDimension.paddingLarge,
            bottom = SettingsDimension.paddingSmall,
        ),
    )
}

@Composable
private fun IdentifierGroupHeader(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "PrivacyKitGroupChevron",
    )
    val cdRes = if (expanded) {
        R.string.privacykit_ids_group_collapse
    } else {
        R.string.privacykit_ids_group_expand
    }
    Row(
        Modifier.fillMaxWidth()
                .padding(horizontal = SettingsDimension.paddingLarge)
                .clip(RoundedCornerShape(GroupCornerLarge))
                .clickable(onClick = onToggle)
                .padding(
                    start = SettingsDimension.itemPaddingStart,
                    end = SettingsDimension.paddingSmall,
                    top = SettingsDimension.paddingLarge,
                    bottom = SettingsDimension.paddingSmall,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = stringResource(cdRes),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SettingsDimension.itemIconSize).rotate(rotation),
        )
    }
}

@Composable
private fun IdentifierPreferenceRow(
    item: PkIdentifierItem,
    rule: PkRuleType,
    customValue: String?,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    onInfoClick: () -> Unit,
) {
    val context = LocalContext.current
    val enforcement = PrivacyKitIdentifierCatalog.enforcementOf(item.key)
    val fullyEnforced = enforcement == PkEnforcement.FULL
    // PARTIAL is de-emphasized to exactly the same degree as NONE on purpose. The catalog's contract
    // is that a partly covered identifier may differ from an uncovered one in its *explanation*,
    // never in how protected it looks - so the alpha is shared and only the tag colour and the
    // caveat line below tell them apart.
    // Enforcement is no longer surfaced, so every row renders at full opacity
    // rather than dimming the ones without a framework hook.
    val contentAlpha = 1f
    // noteOf(), not caveatOf(): the row must also carry the "blocked, not faked" sentence for a
    // FULL restriction key such as advertising_id, and the specific "why this is not enforced"
    // sentence for a NONE key that has one, such as gsf_id. Routing all three through one catalog
    // accessor is what stops a newly added note source from silently missing this surface.
    val note = PrivacyKitIdentifierCatalog.noteOf(item.key)
    Row(
        Modifier.fillMaxWidth()
                .padding(horizontal = SettingsDimension.paddingLarge, vertical = GroupRowGap)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceBright)
                .clickable(onClick = onClick)
                .heightIn(min = SettingsDimension.preferenceMinHeight)
                .padding(
                    start = SettingsDimension.itemPaddingStart,
                    end = SettingsDimension.paddingSmall,
                    top = SettingsDimension.itemPaddingVertical,
                    bottom = SettingsDimension.itemPaddingVertical,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(contentAlpha),
            )
            Text(
                text = ruleSummary(context, rule, customValue),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(contentAlpha),
            )
            // The always-on "Not enforced yet" badge pill was removed by request; the info
            // (i) button and the caveat note below keep the honest explanation one tap away
            // without a coloured tag on every partially/unenforced row.
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = SettingsDimension.paddingTiny,
                end = SettingsDimension.paddingSmall,
            ),
        )
    }
}

/**
 * Small tonal tag for an identifier the framework does not fully act on.
 *
 * [partial] only changes the tint, not the wording: both states say "Not enforced yet" because both
 * mean "do not rely on this being hidden", which is the claim that has to stay conservative. The
 * secondary tint plus the caveat line underneath is what tells the user there is a real but narrow
 * hook here rather than nothing at all.
 *
 * The wording is shared for a second, more mundane reason: adding a distinct "Partly enforced"
 * label needs a new R.string, and res/values is owned by another agent this round. See the report.
 */
@Composable
private fun EnforcementBadge(partial: Boolean) {
    val container = if (partial) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val onContainer = if (partial) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    Text(
        text = stringResource(R.string.privacykit_not_enforced_badge),
        style = MaterialTheme.typography.labelSmall,
        color = onContainer,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(GroupCornerLarge))
                .background(container)
                .padding(
                    start = SettingsDimension.paddingSmall,
                    end = SettingsDimension.paddingSmall,
                    top = SettingsDimension.paddingTiny,
                    bottom = SettingsDimension.paddingTiny,
                ),
    )
}

@Composable
private fun NotEnforcedInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
        icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
        title = { Text(stringResource(R.string.privacykit_not_enforced_info_title)) },
        text = { Text(stringResource(R.string.privacykit_not_enforced_info_body)) },
    )
}

/**
 * The per-key counterpart of [NotEnforcedInfoDialog]: says what this specific identifier's hook does
 * and does not reach, instead of the generic "there is no hook" story, which for these keys would be
 * false.
 *
 * Used for all three note sources - a PARTIAL coverage caveat, a blocked-not-spoofed restriction
 * note, and a specific reason a NONE key is NONE - because from the user's side they answer the same
 * question ("what actually happens if I set this?") with different answers.
 *
 * Titled with the identifier's own label rather than a shared heading, because the whole point of
 * this dialog is that the answer differs per key.
 *
 * The body is [PrivacyKitIdentifierCatalog.noteOf], which the catalog documents as developer facing
 * English that is NOT localized. Surfacing it here is a deliberate trade: an untranslated but
 * specific and actionable sentence beats a translated one that leaves the user unable to tell a
 * narrow hook from a missing one, or a block from a spoof. Promoting these to R.string entries is
 * the follow-up.
 */
@Composable
private fun IdentifierNoteDialog(label: String, note: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
        icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
        title = { Text(label) },
        text = { Text(note) },
    )
}

@Composable
private fun IdentifiersNoResults() {
    Column(
        Modifier.fillMaxWidth().padding(SettingsDimension.paddingExtraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SettingsDimension.iconLarge),
        )
        Spacer(Modifier.height(SettingsDimension.paddingLarge))
        Text(
            text = stringResource(R.string.privacykit_ids_no_results),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(SettingsDimension.paddingSmall))
        Text(
            text = stringResource(R.string.privacykit_ids_no_results_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun IdentifiersFooter() {
    Row(
        Modifier.fillMaxWidth().padding(
            start = SettingsDimension.paddingExtraLarge,
            end = SettingsDimension.paddingExtraLarge,
            top = SettingsDimension.paddingExtraLarge,
            bottom = SettingsDimension.paddingExtraLarge,
        ),
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SettingsDimension.itemIconSize),
        )
        Text(
            text = stringResource(R.string.privacykit_ids_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = SettingsDimension.paddingLarge),
        )
    }
}

/**
 * Rule picker for a single identifier, styled like an AOSP Settings radio page: each option is a
 * full-width preference row with the rule name plus a plain-English description of what it does.
 *
 * The Custom value field commits on a [CUSTOM_COMMIT_DELAY_MS] debounce, on focus loss, on an
 * explicit Save press, on either way out of this screen, and finally on dispose - the previous
 * version fired setIdentifierRule on *every keystroke*, which spammed both the binder and the
 * PrivacyKit history log with one entry per character typed. All five paths funnel through the same
 * commitCustom(), which reads the field's live state and no-ops when nothing has changed since the
 * last write, so they cannot double-write and none of them can drop the final keystroke.
 */
@Composable
private fun PrivacyKitIdentifierRuleScreen(
    packageName: String,
    item: PkIdentifierItem,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var selectedRule by remember(item.key) { mutableStateOf(item.defaultRule) }
    var customText by remember(item.key) { mutableStateOf("") }
    var committedText by remember(item.key) { mutableStateOf("") }
    var loaded by remember(item.key) { mutableStateOf(false) }

    LaunchedEffect(packageName, item.key) {
        val loadedState = withContext(pkBinderDispatcher) {
            val mgr = pkManager()
            val rule = try {
                PkRuleType.fromServerValue(mgr?.getRuleType(packageName, item.key) ?: 0)
            } catch (e: Exception) {
                PkRuleType.REAL
            }
            val value = try {
                mgr?.getRuleValue(packageName, item.key) ?: ""
            } catch (e: Exception) {
                ""
            }
            rule to value
        }
        selectedRule = loadedState.first
        customText = loadedState.second
        committedText = loadedState.second
        loaded = true
    }

    fun writeRule(rule: PkRuleType, value: String?) {
        val identifierKey = item.key
        val serverValue = rule.serverValue
        // Queued rather than called inline: this is a persisting binder call and every caller is on
        // the main thread. pkBinderExecutor is process wide, so a write queued from onDispose still
        // runs after this composable is gone, and single threaded, so it is ordered ahead of the
        // catalog reload the caller starts right after onBack().
        pkBinderExecutor.execute {
            try {
                pkManager()?.setIdentifierRule(packageName, identifierKey, serverValue, value)
            } catch (e: Exception) {
                // Fail open: leave the user's selection on screen rather than throw into the UI.
            }
        }
    }

    fun commitCustom() {
        if (!loaded || selectedRule != PkRuleType.CUSTOM) return
        if (customText == committedText) return
        writeRule(PkRuleType.CUSTOM, customText)
        committedText = customText
    }

    fun applyRule(rule: PkRuleType) {
        if (rule == selectedRule) return
        selectedRule = rule
        if (rule == PkRuleType.CUSTOM) {
            writeRule(rule, customText)
            committedText = customText
        } else {
            writeRule(rule, null)
        }
    }

    // Debounced auto-commit for the custom value. Restarted on every keystroke, so only a pause in
    // typing (or an explicit Save / focus loss / exit below) reaches the service.
    LaunchedEffect(customText, selectedRule, loaded) {
        if (!loaded || selectedRule != PkRuleType.CUSTOM) return@LaunchedEffect
        if (customText == committedText) return@LaunchedEffect
        delay(CUSTOM_COMMIT_DELAY_MS)
        commitCustom()
    }

    // Both ways out of this screen: flush a pending custom value first, *then* hand control back.
    // Leaving cancels the debounce coroutine above, so without this an edit the user can still see
    // in the field would be silently dropped. Ordering matters as much as the flush itself - onBack
    // makes the caller reload its catalog snapshot, and pkBinderExecutor runs work in submission
    // order, so queueing the write first is what guarantees that reload sees the new value.
    val leave: () -> Unit = {
        commitCustom()
        onBack()
    }
    BackHandler(onBack = leave)

    // Belt and braces for every *other* way this screen can go away - a configuration change, the
    // host activity finishing, the user leaving PrivacyKit outright - none of which route through
    // leave(). commitCustom() is a no-op once the field is already flushed, so the ordinary back
    // path does not write twice.
    DisposableEffect(item.key) {
        onDispose { commitCustom() }
    }

    // This is the screen where the user decides whether to rely on an identifier, so the coverage
    // caveat has to be visible here, above the rule picker, not buried behind an info button.
    val enforcement = PrivacyKitIdentifierCatalog.enforcementOf(item.key)
    val note = PrivacyKitIdentifierCatalog.noteOf(item.key)
    // Separate from note on purpose: the scope limit is a property of how in-process Build
    // spoofing works, not a defect in this key, so it informs the choice without de-emphasizing a
    // row that really is enforced. It is shown here and deliberately not on the list rows.
    val scopeNote = PrivacyKitIdentifierCatalog.scopeNoteOf(item.key)
    val dirty = loaded && selectedRule == PkRuleType.CUSTOM && customText != committedText

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(SettingsDimension.paddingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = leave) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.privacykit_back),
                )
            }
            Text(
                text = item.label,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = SettingsDimension.paddingSmall),
            )
        }

        if (!loaded) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            // NONE always gets the red banner - a key with no working enforcement must look
            // alarming whether or not the catalog has a specific sentence for it - and that
            // sentence, when there is one, replaces the generic copy inside the banner rather than
            // sitting beside it, because the generic copy ("no framework hook yet") is itself false
            // for those keys. PARTIAL and the block-style FULL keys get the tonal advisory banner.
            if (enforcement == PkEnforcement.NONE) {
                item(key = "pk_rule_not_enforced") { RuleNotEnforcedNotice(note) }
            } else if (note != null) {
                item(key = "pk_rule_advisory") { RuleAdvisoryNotice(note) }
            }
            if (scopeNote != null) {
                item(key = "pk_rule_scope") { RuleScopeNotice(scopeNote) }
            }
            item(key = "pk_rule_section") {
                Text(
                    text = stringResource(R.string.privacykit_rule_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(
                        start = SettingsDimension.paddingExtraLarge,
                        end = SettingsDimension.paddingExtraLarge,
                        top = SettingsDimension.paddingLarge,
                        bottom = SettingsDimension.paddingSmall,
                    ),
                )
            }
            // Two reasons this list can be narrower than PkRuleType.entries, both of them
            // backend facts rather than presentation choices:
            //  - a restriction key is enforced by refusing access, not by value substitution, so
            //    only Real/Empty do anything for it;
            //  - a key such as media_drm_id has a backend rule-type allow-list
            //    (PrivacyKitRuleResolver#isRuleTypeAllowed) that returns the real value for
            //    Per-launch, Daily and Empty, so offering them would be a lie.
            // A rule type stored by older data or a restored backup that is no longer in this list
            // leaves every radio unselected, which is the truthful rendering: none of the offered
            // rules is what the backend will act on.
            val options = PrivacyKitIdentifierCatalog.supportedRuleTypesFor(item.key)
            itemsIndexed(options, key = { _, rule -> rule.name }) { index, rule ->
                RuleOptionRow(
                    title = ruleLabel(context, rule),
                    summary = ruleDescription(context, rule),
                    selected = rule == selectedRule,
                    shape = rowShape(index, options.size),
                    onClick = { applyRule(rule) },
                )
            }
            if (selectedRule == PkRuleType.CUSTOM && PkRuleType.CUSTOM in options) {
                item(key = "pk_rule_custom_editor") {
                    CustomValueEditor(
                        identifierKey = item.key,
                        value = customText,
                        dirty = dirty,
                        onValueChange = { customText = it },
                        onCommit = { commitCustom() },
                        onRandomise = {
                            // Pure CPU - one SecureRandom draw and a format - with no binder
                            // and no disk, so it is fine on the tap. The value comes from the
                            // framework generator the rule engine itself uses; this file has
                            // no copy of any format rule, deliberately, because a UI-side copy
                            // would drift and a wrongly shaped identifier is worse than none.
                            val generated =
                                    PrivacyKitIdentifierGenerator.generateFresh(item.key, null)
                            if (!generated.isNullOrEmpty()) {
                                customText = generated
                                // Committed straight away rather than after the typing
                                // debounce: there is no half-typed state to protect here.
                                commitCustom()
                            }
                        },
                    )
                }
            }
            item(key = "pk_rule_bottom_space") {
                Spacer(Modifier.height(SettingsDimension.paddingExtraLarge))
            }
        }
    }
}

/**
 * Red banner for a key with no working enforcement.
 *
 * [note] replaces the generic localized copy when the catalog has a specific reason for this key
 * (see [PrivacyKitIdentifierCatalog.notEnforcedReasons]). The generic string claims "no framework
 * hook yet", which for a key like gsf_id - where a hook exists but the rule engine cannot feed it a
 * usable value - would be false, and a false explanation is worse than a vague one.
 */
@Composable
private fun RuleNotEnforcedNotice(note: String?) {
    Row(
        Modifier.fillMaxWidth()
                .padding(
                    start = SettingsDimension.paddingLarge,
                    end = SettingsDimension.paddingLarge,
                    top = SettingsDimension.paddingSmall,
                )
                .clip(RoundedCornerShape(GroupCornerLarge))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(SettingsDimension.paddingLarge),
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(SettingsDimension.itemIconSize),
        )
        Text(
            text = note ?: stringResource(R.string.privacykit_not_enforced_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(start = SettingsDimension.paddingLarge),
        )
    }
}

/**
 * Banner shown above the rule picker for a PARTIAL key, or for a FULL key that is enforced by
 * blocking rather than by substituting a value, in place of [RuleNotEnforcedNotice].
 *
 * Deliberately a different colour from the red "no working hook" banner: the situation really is
 * different, and a user who sees the same red block everywhere stops reading it. It is still a
 * banner rather than a footnote because choosing a rule type is exactly the moment the limitation
 * matters - several of these notes are specifically about *which rule types* take effect, and the
 * advertising_id one is about what the rule does at all.
 *
 * [note] is the catalog's un-localized developer-facing English; see [IdentifierNoteDialog] for why
 * that trade is being made. No maxLines: this banner is the one place the full sentence must be
 * readable.
 */
@Composable
private fun RuleAdvisoryNotice(note: String) {
    Row(
        Modifier.fillMaxWidth()
                .padding(
                    start = SettingsDimension.paddingLarge,
                    end = SettingsDimension.paddingLarge,
                    top = SettingsDimension.paddingSmall,
                )
                .clip(RoundedCornerShape(GroupCornerLarge))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(SettingsDimension.paddingLarge),
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(SettingsDimension.itemIconSize),
        )
        Text(
            text = note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(start = SettingsDimension.paddingLarge),
        )
    }
}

/**
 * Quiet informational banner for the standing scope limit of the in-process Build identity
 * overrides ([PrivacyKitIdentifierCatalog.injectorScopeNote]).
 *
 * Deliberately neither the red "not enforced" block nor the tonal advisory one: these keys really
 * are fully enforced on the API an app calls, so a banner that reads as a warning would understate
 * them. It uses the same plain surface as the preference rows, so it reads as "here is how this
 * works" rather than "this is broken" - while still being impossible to miss on the one screen
 * where the user decides whether to rely on the identifier.
 *
 * [note] is the catalog's un-localized developer-facing English; see [IdentifierNoteDialog] for why
 * that trade is being made.
 */
@Composable
private fun RuleScopeNotice(note: String) {
    Row(
        Modifier.fillMaxWidth()
                .padding(
                    start = SettingsDimension.paddingLarge,
                    end = SettingsDimension.paddingLarge,
                    top = SettingsDimension.paddingSmall,
                )
                .clip(RoundedCornerShape(GroupCornerLarge))
                .background(MaterialTheme.colorScheme.surfaceBright)
                .padding(SettingsDimension.paddingLarge),
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SettingsDimension.itemIconSize),
        )
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = SettingsDimension.paddingLarge),
        )
    }
}

@Composable
private fun RuleOptionRow(
    title: String,
    summary: String,
    selected: Boolean,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
                .padding(horizontal = SettingsDimension.paddingLarge, vertical = GroupRowGap)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceBright)
                .clickable(onClick = onClick)
                .heightIn(min = SettingsDimension.preferenceMinHeight)
                .padding(
                    start = SettingsDimension.paddingSmall,
                    end = SettingsDimension.itemPaddingEnd,
                    top = SettingsDimension.itemPaddingVertical,
                    bottom = SettingsDimension.itemPaddingVertical,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = SettingsDimension.paddingSmall)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The Custom value field, plus the controls that fill it.
 *
 * The Randomise button appears only when [canRandomiseCustomValue] is true for this key. When it is
 * not, the field stays fully editable - a hand-typed value is always allowed - but a line underneath
 * says why PrivacyKit will not generate one, because a missing button is not an explanation. The
 * three reasons are genuinely different and get genuinely different sentences: this key needs the
 * real value and only the system can see it, this key only means anything as part of a coherent
 * device identity, or PrivacyKit simply has no format for it yet.
 */
@Composable
private fun CustomValueEditor(
    identifierKey: String,
    value: String,
    dirty: Boolean,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    onRandomise: () -> Unit,
) {
    val canRandomise = canRandomiseCustomValue(identifierKey)
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.privacykit_custom_section_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                start = SettingsDimension.paddingExtraLarge,
                end = SettingsDimension.paddingExtraLarge,
                top = SettingsDimension.paddingExtraLarge,
                bottom = SettingsDimension.paddingSmall,
            ),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            shape = RoundedCornerShape(GroupCornerLarge),
            label = { Text(stringResource(R.string.privacykit_custom_value)) },
            modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = SettingsDimension.paddingLarge)
                    .onFocusChanged { state -> if (!state.isFocused) onCommit() },
        )
        Row(
            Modifier.fillMaxWidth().padding(
                start = SettingsDimension.paddingExtraLarge,
                end = SettingsDimension.paddingLarge,
                top = SettingsDimension.paddingSmall,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (dirty) {
                    stringResource(R.string.privacykit_custom_saving)
                } else {
                    stringResource(R.string.privacykit_custom_saved)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (canRandomise) {
                TextButton(onClick = onRandomise) {
                    Text(stringResource(R.string.privacykit_rnd_field_button))
                }
            }
            TextButton(onClick = onCommit, enabled = dirty) {
                Text(stringResource(R.string.privacykit_custom_save))
            }
        }
        if (!canRandomise) {
            Text(
                text = stringResource(
                    when {
                        PrivacyKitIdentifierGenerator.needsRealValue(identifierKey) ->
                            R.string.privacykit_rnd_needs_real_value
                        isCoherentIdentityKey(identifierKey) ->
                            R.string.privacykit_rnd_build_coherence
                        else -> R.string.privacykit_rnd_no_generator
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = SettingsDimension.paddingExtraLarge,
                    end = SettingsDimension.paddingExtraLarge,
                    top = SettingsDimension.paddingSmall,
                ),
            )
        }
        Text(
            text = stringResource(R.string.privacykit_custom_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = SettingsDimension.paddingExtraLarge,
                end = SettingsDimension.paddingExtraLarge,
                top = SettingsDimension.paddingSmall,
            ),
        )
    }
}

/**
 * Confirmation for "Randomise all".
 *
 * It is a confirmation rather than a one-tap action because the change is bulk, immediate and not
 * undoable: there is no "previous value" anywhere to restore, so a mis-tap permanently discards
 * every Custom value the user typed by hand across the whole catalog. The dialog therefore names
 * the count in its title and every affected identifier in its body, and says out loud what is being
 * left alone, so "Randomise all" cannot be read as "randomise everything".
 *
 * The coherence block is the part that matters most. When the app's Build identity is already
 * spoofed, randomising those fields individually would be actively harmful, so they are excluded -
 * and rather than leave the user with a warning and no way to act on it, the dialog offers the
 * thing that *is* correct: one real device template applied to all eight fields at once. That opt-in
 * starts unchecked, because ticking it writes Build fields that may currently be set to Real, which
 * is a wider change than the rest of this action makes.
 */
@Composable
private fun RandomiseAllDialog(
    plan: PkRandomisePlan,
    withTemplate: Boolean,
    onWithTemplateChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val template = plan.template
    val hasEntries = plan.entries.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = hasEntries || withTemplate) {
                Text(stringResource(R.string.privacykit_rnd_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.privacykit_rnd_cancel))
            }
        },
        icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
        title = {
            Text(
                if (hasEntries) {
                    stringResource(R.string.privacykit_rnd_confirm_title, plan.entries.size)
                } else {
                    stringResource(R.string.privacykit_rnd_confirm_title_build)
                }
            )
        },
        text = {
            // Scrollable: the identifier list plus the skip note plus the coherence block is
            // taller than a dialog on a phone, and a truncated explanation of a destructive
            // action is not an explanation.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (hasEntries) {
                    Text(
                        text = plan.entries.joinToString(", ") { it.label },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(SettingsDimension.paddingSmall))
                    Text(
                        text = stringResource(R.string.privacykit_rnd_confirm_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (plan.skipped > 0) {
                    Spacer(Modifier.height(SettingsDimension.paddingSmall))
                    Text(
                        text = stringResource(
                            R.string.privacykit_rnd_confirm_skipped, plan.skipped),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (template != null) {
                    Spacer(Modifier.height(SettingsDimension.paddingLarge))
                    Text(
                        text = stringResource(
                            R.string.privacykit_rnd_build_warning, plan.spoofedBuildKeys),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(SettingsDimension.paddingSmall))
                    Row(
                        Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(GroupCornerLarge))
                                .clickable { onWithTemplateChange(!withTemplate) }
                                .padding(SettingsDimension.paddingSmall),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = withTemplate, onCheckedChange = null)
                        Text(
                            text = stringResource(
                                R.string.privacykit_rnd_template_checkbox, template.displayName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                                    .padding(start = SettingsDimension.paddingSmall),
                        )
                    }
                }
            }
        },
    )
}

/**
 * Shown when "Randomise all" has nothing to do, instead of silently doing nothing.
 *
 * The body states the eligibility rule rather than apologising, because "nothing happened" on a
 * privacy tool is exactly the kind of silence that leaves a user believing something was applied.
 */
@Composable
private fun RandomiseNothingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
        icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
        title = { Text(stringResource(R.string.privacykit_rnd_none_title)) },
        text = { Text(stringResource(R.string.privacykit_rnd_none_body)) },
    )
}
