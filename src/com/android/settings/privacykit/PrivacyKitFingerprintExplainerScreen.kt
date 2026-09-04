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
 * Confirmed FINAL: all 5 Compose trace line numbers in r49 match this file exactly.
 */
package com.android.settings.privacykit

import android.os.ServiceManager

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsDimension

import android.privacykit.IPrivacyKitManager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun pkManager(): IPrivacyKitManager? =
    IPrivacyKitManager.Stub.asInterface(ServiceManager.getService("privacykit"))

/** Card geometry, kept in step with the identifier catalog's rounded preference cards. */
private val ExplainerCorner = 20.dp

/**
 * How tall the expanded disclosure list may grow before it scrolls inside itself.
 *
 * Bounded on purpose: the disclosure sits outside the weighted state Box, so an unbounded list for
 * an app with forty configured identifiers would squeeze the coherence report it is meant to
 * annotate down to nothing.
 */
private val DisclosureMaxHeight = 220.dp

/**
 * Disclosure copy for [ExplainerPayloadDisclosure].
 *
 * Developer-facing English, deliberately NOT in a string resource: this screen's strings live in
 * res/values/strings_privacykit_identifiers.xml, which this change does not own. The text states a
 * privacy-relevant fact about what the device just did over the network, so it ships now rather
 * than waiting on a translation pass - the same trade PrivacyKitIdentifierCatalog makes for its
 * injectorScopeNote. Move it into the strings file next time that file is touched.
 */
private const val DISCLOSURE_TITLE = "What left this device"
private const val DISCLOSURE_NOTHING_SENT = "No AI provider is configured, so nothing was sent."
private const val DISCLOSURE_SENT_TO = "Sent to "
private const val DISCLOSURE_SENT_MIDDLE = " - "
private const val DISCLOSURE_SENT_TAIL =
        " field descriptions. The identifier values themselves never left the device. Tap to see " +
        "the exact text that did."

/**
 * Read-only "Explain my fingerprint" screen: gathers every identifier that has been given a
 * non-default (non-Real) rule on packageName's *active* profile, reduces each one to a
 * [PrivacyKitAiFieldShape], sends only those shapes to the user-configured AI endpoint via
 * [PrivacyKitAiGenerator.explainFingerprint] asking for a plain-English coherence critique, and
 * shows the free-text answer inside a Settings-style card. Never writes any rule - diagnostic
 * only, mirroring PrivacyKitIdentifiersScreen's read patterns (pkManager()/getRuleType/
 * getRuleValue) but with no rule-picker/setIdentifierRule path at all.
 *
 * The screen used to post the literal value of every Custom rule - the user's spoofed IMEI, ICCID,
 * serial and fingerprint - to a third-party cloud model. It no longer sends any identifier value
 * at all, and [ExplainerPayloadDisclosure] shows the user the exact bytes that did go out, named
 * against the exact host they went to, so the claim is checkable instead of a promise.
 *
 * Four honest states: loading (spinner plus what it is doing), nothing-configured, a result card,
 * and a failure state that says the AI provider did not answer and offers a retry. The disclosure
 * card sits below all of them whenever there was in fact something to send.
 */
@Composable
fun PrivacyKitFingerprintExplainerScreen(packageName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var configuredFields by remember {
        mutableStateOf<List<PrivacyKitAiFieldShape>>(emptyList())
    }
    var destination by remember { mutableStateOf<String?>(null) }
    var explanation by remember { mutableStateOf<String?>(null) }
    var retryToken by remember { mutableStateOf(0) }

    LaunchedEffect(packageName, retryToken) {
        loading = true
        explanation = null
        destination = PrivacyKitAiGenerator.describeDestination(context)
        val fields = withContext(Dispatchers.IO) { collectConfiguredFields(packageName) }
        configuredFields = fields
        if (fields.isNotEmpty()) {
            val answer = withContext(Dispatchers.IO) {
                PrivacyKitAiGenerator.explainFingerprint(context, fields)
            }
            explanation = if (answer.isNullOrBlank()) null else answer
        }
        loading = false
    }

    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(SettingsDimension.paddingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.privacykit_back),
                )
            }
            Text(
                text = stringResource(R.string.privacykit_explainer_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = SettingsDimension.paddingSmall),
            )
        }

        val answer = explanation
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                loading -> ExplainerLoading()
                configuredFields.isEmpty() -> ExplainerZeroState(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.privacykit_explainer_empty_title),
                    summary = stringResource(R.string.privacykit_explainer_empty_summary),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onRetry = null,
                )
                answer != null -> ExplainerResult(
                    fieldCount = configuredFields.size,
                    explanation = answer,
                )
                else -> ExplainerZeroState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = stringResource(R.string.privacykit_explainer_error_title),
                    summary = stringResource(R.string.privacykit_explainer_error_summary),
                    tint = MaterialTheme.colorScheme.error,
                    onRetry = { retryToken++ },
                )
            }
        }

        // Outside the state Box on purpose: the request goes out before the answer comes back and
        // is made whether or not it succeeds, so the disclosure has to be visible while loading
        // and after a failure too, not only next to a successful result. The one state it is
        // absent from is the one where nothing was sent.
        if (configuredFields.isNotEmpty()) {
            ExplainerPayloadDisclosure(destination = destination, fields = configuredFields)
        }
    }
}

/**
 * The privacy disclosure for this screen: names the host the explainer actually talked to and
 * shows, verbatim, every line that went into the prompt.
 *
 * It exists because the honest answer to "what does this feature send?" used to be "every
 * identifier value you configured", and nothing on screen said so. The feature now sends
 * [PrivacyKitAiFieldShape] descriptions only, and this card renders the same
 * [PrivacyKitAiFieldShape.render] strings that were put on the wire - so a suspicious user can
 * check the claim rather than trust it. Collapsed by default so it informs without shouting.
 */
@Composable
private fun ExplainerPayloadDisclosure(
    destination: String?,
    fields: List<PrivacyKitAiFieldShape>,
) {
    var expanded by remember { mutableStateOf(false) }
    val summary = if (destination == null) {
        DISCLOSURE_NOTHING_SENT
    } else {
        DISCLOSURE_SENT_TO + destination + DISCLOSURE_SENT_MIDDLE + fields.size +
                DISCLOSURE_SENT_TAIL
    }
    Column(
        Modifier.fillMaxWidth()
                .padding(
                    start = SettingsDimension.paddingLarge,
                    end = SettingsDimension.paddingLarge,
                    bottom = SettingsDimension.paddingLarge,
                )
                .clip(RoundedCornerShape(ExplainerCorner))
                .background(MaterialTheme.colorScheme.surfaceBright),
    ) {
        Row(
            Modifier.fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(SettingsDimension.paddingLarge),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Share,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SettingsDimension.itemIconSize),
            )
            Column(Modifier.weight(1f).padding(start = SettingsDimension.paddingLarge)) {
                Text(
                    text = DISCLOSURE_TITLE,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SettingsDimension.itemIconSize)
                        .rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth()
                        .heightIn(max = DisclosureMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = SettingsDimension.paddingLarge,
                            end = SettingsDimension.paddingLarge,
                            bottom = SettingsDimension.paddingLarge,
                        ),
            ) {
                for (field in fields) {
                    Text(
                        text = field.render(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExplainerLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(SettingsDimension.paddingLarge))
            Text(
                text = stringResource(R.string.privacykit_explainer_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Shared empty/error presentation: a large tinted icon, a title, an explanation, and - for the
 * failure case only - a retry button.
 */
@Composable
private fun ExplainerZeroState(
    icon: ImageVector,
    title: String,
    summary: String,
    tint: Color,
    onRetry: (() -> Unit)?,
) {
    Box(
        Modifier.fillMaxSize().padding(SettingsDimension.paddingExtraLarge),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(SettingsDimension.iconLarge),
            )
            Spacer(Modifier.height(SettingsDimension.paddingLarge))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(SettingsDimension.paddingSmall))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(SettingsDimension.paddingExtraLarge))
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.privacykit_explainer_retry))
                }
            }
        }
    }
}

@Composable
private fun ExplainerResult(fieldCount: Int, explanation: String) {
    Column(
        Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = SettingsDimension.paddingLarge,
                    end = SettingsDimension.paddingLarge,
                    top = SettingsDimension.paddingSmall,
                    bottom = SettingsDimension.paddingExtraLarge,
                ),
    ) {
        Column(
            Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(ExplainerCorner))
                    .background(MaterialTheme.colorScheme.surfaceBright)
                    .padding(SettingsDimension.paddingLarge),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(SettingsDimension.itemIconSize),
                )
                Column(Modifier.weight(1f).padding(start = SettingsDimension.paddingLarge)) {
                    Text(
                        text = stringResource(R.string.privacykit_explainer_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.privacykit_explainer_card_summary, fieldCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(
                Modifier.padding(
                    top = SettingsDimension.paddingLarge,
                    bottom = SettingsDimension.paddingLarge,
                )
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(
                start = SettingsDimension.paddingSmall,
                end = SettingsDimension.paddingSmall,
                top = SettingsDimension.paddingExtraLarge,
            ),
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SettingsDimension.itemIconSize),
            )
            Text(
                text = stringResource(R.string.privacykit_explainer_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = SettingsDimension.paddingLarge),
            )
        }
    }
}

/**
 * Reads every catalog identifier's current rule for packageName's active profile and returns only
 * the ones that have been touched (rule type != Real), each already reduced to a
 * [PrivacyKitAiFieldShape].
 *
 * The reduction happens HERE, where the value is read, rather than later where it is sent. A
 * Custom rule's value - a spoofed IMEI, ICCID, serial or build fingerprint - is handed straight to
 * [PrivacyKitAiFieldShape.of] and is never assigned to anything that outlives this loop: not a
 * local, not composable state, not the prompt. Non-Custom rules have no value to leak in the first
 * place; the rule name is PrivacyKit's own word for what it does.
 *
 * This used to return the literal value for every Custom rule, which the caller then posted to a
 * cloud LLM.
 *
 * Blocking AIDL calls; caller must invoke off the main thread.
 */
private fun collectConfiguredFields(packageName: String): List<PrivacyKitAiFieldShape> {
    val mgr = pkManager() ?: return emptyList()
    val result = ArrayList<PrivacyKitAiFieldShape>()
    for (item in PrivacyKitIdentifierCatalog.allItems) {
        val ruleType = try {
            mgr.getRuleType(packageName, item.key)
        } catch (e: Exception) {
            PkRuleType.REAL.serverValue
        }
        if (ruleType == PkRuleType.REAL.serverValue) continue

        val rule = PkRuleType.fromServerValue(ruleType)
        val customValue = if (rule == PkRuleType.CUSTOM) {
            try {
                mgr.getRuleValue(packageName, item.key)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        result.add(PrivacyKitAiFieldShape.of(item.label, rule.label, customValue))
    }
    return result
}

