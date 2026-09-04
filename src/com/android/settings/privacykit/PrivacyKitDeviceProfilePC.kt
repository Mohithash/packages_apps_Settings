/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.privacykit

import android.app.AlertDialog
import android.content.Context
import android.os.ServiceManager
import android.privacykit.IPrivacyKitManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen

import com.android.settingslib.core.AbstractPreferenceController

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KEY = "privacykit_device_profile"
private const val VALUE_NONE = "none"
private const val VALUE_AI = "ai_generate"
private const val VALUE_AI_CONFIG = "ai_configure"
private const val RULE_CUSTOM = 4

/**
 * "Generate new profile" - lets the user apply a curated, real, coherent
 * device-identity template in one tap, or generate one via an optional
 * user-configured OpenAI-compatible AI endpoint. Both paths write the
 * template's whole field map as RULE_CUSTOM through the same identity-rule
 * engine the plain per-field toggles use - the eight coherent Build.* fields,
 * the SoC pair when the template states one, and the Build.ID / DISPLAY / TYPE
 * / TAGS / VERSION.RELEASE / VERSION.INCREMENTAL values parsed out of the
 * template's own fingerprint string, so the applied identity does not
 * contradict itself.
 *
 * RULE_CUSTOM is not an implementation detail here: PrivacyKitRuleResolver
 * refuses every other non-Real rule type for these keys, because there is no
 * such thing as a coherent random draw for a fingerprint-linked field.
 */
class PrivacyKitDeviceProfilePC(
    private val context: Context,
    private val packageName: String,
    private val coroutineScope: CoroutineScope
) : AbstractPreferenceController(context) {

    override fun isAvailable() = true

    override fun getPreferenceKey() = KEY

    override fun displayPreference(screen: PreferenceScreen) {
        super.displayPreference(screen)
        val pref = screen.findPreference<ListPreference>(preferenceKey) ?: return
        val values = mutableListOf(VALUE_NONE)
        val entries = mutableListOf("None (real identity)")
        for (t in PrivacyKitDeviceTemplates.TEMPLATES) {
            values.add(t.displayName)
            entries.add(t.displayName)
        }
        values.add(VALUE_AI)
        entries.add("Generate with AI…")
        values.add(VALUE_AI_CONFIG)
        entries.add("Configure AI settings…")
        pref.entries = entries.toTypedArray()
        pref.entryValues = values.toTypedArray()
        pref.value = VALUE_NONE
        pref.setOnPreferenceChangeListener { _, newValue ->
            when (newValue as String) {
                VALUE_NONE -> {
                    clearBuildIdentity()
                    true
                }
                VALUE_AI_CONFIG -> {
                    showAiConfigDialog()
                    false // don't persist this as the selected value
                }
                VALUE_AI -> {
                    generateWithAi()
                    false // value updates only if generation succeeds; see generateWithAi
                }
                else -> {
                    val template = PrivacyKitDeviceTemplates.TEMPLATES.find { it.displayName == newValue }
                    if (template != null) applyTemplate(template)
                    true
                }
            }
        }
    }

    private fun getManager(): IPrivacyKitManager? =
        IPrivacyKitManager.Stub.asInterface(ServiceManager.getService("privacykit"))

    private fun applyTemplate(template: PrivacyKitDeviceTemplate) {
        coroutineScope.launch(Dispatchers.IO) {
            val manager = getManager() ?: return@launch
            for ((key, value) in template.toFieldMap()) {
                manager.setIdentifierRule(packageName, key, RULE_CUSTOM, value)
            }
        }
    }

    /**
     * "None (real identity)".
     *
     * Clears PrivacyKitDeviceTemplates.IDENTITY_KEYS, not PrivacyKitKeys.BUILD_KEYS:
     * applyTemplate writes every key the template's field map contains, which is
     * more than the eight in BUILD_KEYS, and a clear that covers less than the
     * apply leaves the difference welded to the template the user just switched
     * away from. Before this, the SoC pair survived "None" - and once the field
     * map grew to carry Build.ID / DISPLAY / TYPE / TAGS / RELEASE / INCREMENTAL,
     * so would those, leaving a build id from one device beside this device's
     * own real fingerprint.
     */
    private fun clearBuildIdentity() {
        coroutineScope.launch(Dispatchers.IO) {
            val manager = getManager() ?: return@launch
            for (key in PrivacyKitDeviceTemplates.IDENTITY_KEYS) {
                manager.clearIdentifierRule(packageName, key)
            }
        }
    }

    private fun generateWithAi() {
        coroutineScope.launch {
            val template = withContext(Dispatchers.IO) { PrivacyKitAiGenerator.generate(context) }
            if (template == null) {
                Toast.makeText(context, "AI generation unavailable, check AI settings",
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            applyTemplate(template)
            Toast.makeText(context, "Applied AI-generated identity", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAiConfigDialog() {
        val endpointInput = EditText(context).apply {
            hint = "https://api.openai.com/v1"
            setText(PrivacyKitAiGenerator.getEndpoint(context))
        }
        val keyInput = EditText(context).apply { hint = "API key" }
        val modelInput = EditText(context).apply {
            hint = "Model"
            setText(PrivacyKitAiGenerator.getModel(context))
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            addView(endpointInput)
            addView(keyInput)
            addView(modelInput)
        }

        AlertDialog.Builder(context)
            .setTitle("AI profile generation")
            .setMessage("Any OpenAI-compatible chat-completions endpoint works.")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                PrivacyKitAiGenerator.saveConfig(
                    context,
                    endpointInput.text.toString(),
                    keyInput.text.toString(),
                    modelInput.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
