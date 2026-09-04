/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.privacykit

import android.content.Context
import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation

import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.widget.ui.SettingsBody

/**
 * The "AI providers" screen: pick which backend PrivacyKit's optional AI
 * features talk to, and enter that backend's credentials.
 *
 * Split out of the Settings tab so the tab shows one summary row instead of a
 * provider list plus three text fields stacked in the main scroll. Nothing about
 * the storage model changed: this screen is a pure front-end for
 * [PrivacyKitAiGenerator]'s existing per-provider SharedPreferences keys, and
 * both consumers ([PrivacyKitAiGenerator.generate] and
 * [PrivacyKitAiGenerator.explainFingerprint]) keep reading whichever provider is
 * selected here.
 *
 * Only the selected provider's fields are shown. Every provider owns its own
 * pref keys, so switching the selection re-reads that provider's stored values
 * and never touches the other providers' - the field state is keyed on
 * `provider` so Compose discards and re-initialises it on every switch, which is
 * also why an unsaved edit is intentionally dropped when you change provider
 * rather than being written to the newly-selected one.
 *
 * Nested-screen pattern: swapped into the tab body (not pushed onto the SPA nav
 * graph), so it carries its own [BackHandler] and [PkScreenHeader].
 */
@Composable
fun PrivacyKitAiProvidersScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var provider by remember {
        mutableStateOf(PrivacyKitAiGenerator.getSelectedProvider(context))
    }
    // Keyed on the selection: switching providers reloads that provider's own
    // stored settings instead of leaking the previous one's into the fields.
    var baseUrl by remember(provider) {
        mutableStateOf(PrivacyKitAiGenerator.getBaseUrlForDisplay(context, provider))
    }
    var model by remember(provider) {
        mutableStateOf(PrivacyKitAiGenerator.getModel(context, provider))
    }
    var apiKey by remember(provider) { mutableStateOf("") }
    var hasStoredKey by remember(provider) {
        mutableStateOf(PrivacyKitAiGenerator.hasApiKey(context, provider))
    }

    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        PkScreenHeader(
            title = stringResource(R.string.privacykit_ai_providers_title),
            onBack = onBack,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            PkSection {
                SettingsBody(body = stringResource(R.string.privacykit_ai_providers_summary))
            }

            PkCategory(title = stringResource(R.string.privacykit_ai_provider_choose)) {
                for (candidate in PrivacyKitAiProvider.values()) {
                    PkPreference(
                        title = pkAiProviderLabel(candidate),
                        summary = pkAiProviderSummary(candidate),
                        onClick = {
                            if (candidate != provider) {
                                PrivacyKitAiGenerator.setSelectedProvider(context, candidate)
                                provider = candidate
                            }
                        },
                        trailing = {
                            RadioButton(selected = candidate == provider, onClick = null)
                        },
                    )
                }
            }

            // Titled with the provider name so it is never ambiguous whose
            // credentials are on screen - the fields below belong to the
            // selected provider only.
            PkSection(title = pkAiProviderLabel(provider)) {
                // Plain OutlinedTextFields on purpose: SpaLib's SettingsTextFieldPassword
                // hard-codes its own horizontal padding, which would leave the API key
                // field indented relative to the other two inside this section.
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.privacykit_ai_endpoint)) },
                    placeholder = { Text(provider.defaultBaseUrl) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = SettingsSpace.extraSmall6),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.privacykit_ai_model)) },
                    placeholder = { Text(provider.defaultModel) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = SettingsSpace.extraSmall6),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.privacykit_ai_api_key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = SettingsSpace.extraSmall6,
                            bottom = SettingsSpace.extraSmall2,
                        ),
                )
                SettingsBody(
                    body = if (hasStoredKey) {
                        stringResource(R.string.privacykit_ai_key_saved_hint)
                    } else {
                        stringResource(R.string.privacykit_ai_key_missing_hint)
                    },
                )
                if (provider == PrivacyKitAiProvider.GEMINI) {
                    SettingsBody(body = stringResource(R.string.privacykit_ai_gemini_note))
                }
                Row(Modifier.padding(top = SettingsSpace.extraSmall6)) {
                    Button(
                        onClick = {
                            // A blank key means "keep the stored one" - saveProviderConfig
                            // enforces that, so the masked field never has to be retyped
                            // just to change a model name.
                            PrivacyKitAiGenerator.saveProviderConfig(
                                context, provider, baseUrl, apiKey, model)
                            apiKey = ""
                            hasStoredKey = PrivacyKitAiGenerator.hasApiKey(context, provider)
                            Toast.makeText(
                                context,
                                R.string.privacykit_ai_saved,
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    ) {
                        Text(stringResource(R.string.privacykit_save))
                    }
                    if (hasStoredKey) {
                        Spacer(Modifier.width(SettingsSpace.extraSmall4))
                        TextButton(
                            onClick = {
                                PrivacyKitAiGenerator.clearApiKey(context, provider)
                                apiKey = ""
                                hasStoredKey = false
                                Toast.makeText(
                                    context,
                                    R.string.privacykit_ai_key_cleared,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        ) {
                            Text(stringResource(R.string.privacykit_ai_clear_key))
                        }
                    }
                }
            }
        }
    }
}

/** Display name for [provider]. */
@Composable
fun pkAiProviderLabel(provider: PrivacyKitAiProvider): String =
    stringResource(pkAiProviderLabelRes(provider))

/** One-line description of what [provider] talks to. */
@Composable
fun pkAiProviderSummary(provider: PrivacyKitAiProvider): String = stringResource(
    when (provider) {
        PrivacyKitAiProvider.OPENAI_COMPATIBLE -> R.string.privacykit_ai_provider_openai_summary
        PrivacyKitAiProvider.DEEPSEEK -> R.string.privacykit_ai_provider_deepseek_summary
        PrivacyKitAiProvider.GEMINI -> R.string.privacykit_ai_provider_gemini_summary
    }
)

/**
 * String resource id of [provider]'s display name, for the callers that need the
 * label outside a composable (the Settings tab's summary row).
 */
fun pkAiProviderLabelRes(provider: PrivacyKitAiProvider): Int = when (provider) {
    PrivacyKitAiProvider.OPENAI_COMPATIBLE -> R.string.privacykit_ai_provider_openai
    PrivacyKitAiProvider.DEEPSEEK -> R.string.privacykit_ai_provider_deepseek
    PrivacyKitAiProvider.GEMINI -> R.string.privacykit_ai_provider_gemini
}

/**
 * Summary line for the Settings tab's "AI providers" row.
 *
 * Deliberately reports what the AI features will actually be able to do, not
 * just what has been typed in:
 *   - no key for the selected provider -> "None configured"
 *   - key present and [PrivacyKitAiGenerator.isConfigured] -> "<Provider> - key saved"
 *   - key present but no usable base URL -> "<Provider> - base URL needed"
 *
 * That last state is only reachable for the OpenAI-compatible provider, whose
 * base URL is never defaulted (we do not post a user's key to a host they did
 * not choose); claiming "key saved" there would promise a working setup that
 * would silently do nothing.
 *
 * Touches SharedPreferences - call off the main thread.
 */
fun pkAiProvidersRowSummary(context: Context): String {
    val provider = PrivacyKitAiGenerator.getSelectedProvider(context)
    if (!PrivacyKitAiGenerator.hasApiKey(context, provider)) {
        return context.getString(R.string.privacykit_ai_providers_row_none)
    }
    val label = context.getString(pkAiProviderLabelRes(provider))
    return if (PrivacyKitAiGenerator.isConfigured(context)) {
        context.getString(R.string.privacykit_ai_providers_row_ready, label)
    } else {
        context.getString(R.string.privacykit_ai_providers_row_needs_url, label)
    }
}
