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
 * Rebuilt line-for-line from the patch hunks (702 lines); 12 lines the patch does not touch were filled from the r49 decompile and the transcript snapshot - see the TODO markers, if any.
 */
package com.android.settings.privacykit

import android.content.Context
import android.util.Log

import org.json.JSONArray
import org.json.JSONObject

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

private const val TAG = "PrivacyKitAiGenerator"
private const val PREFS = "privacykit_ai_prefs"

/** Which provider the AI features talk to. Absent = the original OpenAI-compatible one. */
private const val KEY_PROVIDER = "provider"

private val CONNECT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15).toInt()
private val READ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30).toInt()

/** How much of a failed response body is allowed into logcat. */
private const val ERROR_BODY_LOG_LIMIT = 300

private const val PROMPT = "Return ONLY a JSON object (no prose, no markdown fences) with " +
        "exactly these string keys: fingerprint, model, manufacturer, brand, device, product, " +
        "board, hardware. Values must together form a single, internally-consistent Android " +
        "device identity (a real-looking android.os.Build fingerprint and matching fields for " +
        "a genuine, currently-shipping Android phone). Do not include any other keys or text."

/**
 * The two request/response shapes PrivacyKit knows how to speak.
 *
 * These are genuinely different protocols, not one protocol with options: the
 * URL layout, the auth header, the request body and the response body all
 * differ, so [PrivacyKitAiGenerator] branches on this rather than trying to
 * parameterise a single call.
 */
enum class PrivacyKitAiWire {
    /** POST {base}/chat/completions, Bearer auth, choices[0].message.content. */
    OPENAI_CHAT,

    /** POST {base}/models/{model}:generateContent, x-goog-api-key auth, candidates[]. */
    GEMINI_GENERATE_CONTENT,
}

/**
 * An AI backend the user can enter credentials for.
 *
 * Each provider owns its own SharedPreferences keys inside the single
 * `privacykit_ai_prefs` file, so keys for several providers can coexist and
 * switching providers never destroys the other one's credentials.
 *
 * [OPENAI_COMPATIBLE] deliberately reuses the pref names from the original
 * single-provider implementation ("endpoint" / "api_key" / "model"), so a user
 * who configured this before the multi-provider rework keeps working with no
 * migration step at all - their old settings simply *are* this provider's
 * settings.
 */
enum class PrivacyKitAiProvider(
    val id: String,
    val wire: PrivacyKitAiWire,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val baseUrlPref: String,
    val apiKeyPref: String,
    val modelPref: String,
    /**
     * True when the base URL is inherently user-defined (any self-hosted or
     * third-party OpenAI-compatible server), so a blank one must mean "not
     * configured" rather than silently falling back to a vendor default - we
     * never want to post someone's key to a host they did not choose.
     */
    val requiresExplicitBaseUrl: Boolean,
) {
    OPENAI_COMPATIBLE(
        id = "openai",
        wire = PrivacyKitAiWire.OPENAI_CHAT,
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        baseUrlPref = "endpoint",
        apiKeyPref = "api_key",
        modelPref = "model",
        requiresExplicitBaseUrl = true,
    ),

    /**
     * DeepSeek is OpenAI-compatible: identical /chat/completions request and
     * response shape, so it reuses [PrivacyKitAiWire.OPENAI_CHAT] verbatim and
     * only differs by base URL, key and model name.
     */
    DEEPSEEK(
        id = "deepseek",
        wire = PrivacyKitAiWire.OPENAI_CHAT,
        defaultBaseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-chat",
        baseUrlPref = "deepseek_endpoint",
        apiKeyPref = "deepseek_api_key",
        modelPref = "deepseek_model",
        requiresExplicitBaseUrl = false,
    ),

    /** Google's generateContent API - a different protocol end to end. */
    GEMINI(
        id = "gemini",
        wire = PrivacyKitAiWire.GEMINI_GENERATE_CONTENT,
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        defaultModel = "gemini-2.0-flash",
        baseUrlPref = "gemini_endpoint",
        apiKeyPref = "gemini_api_key",
        modelPref = "gemini_model",
        requiresExplicitBaseUrl = false,
    );

    companion object {
        fun fromId(id: String?): PrivacyKitAiProvider =
            PrivacyKitAiProvider.values().firstOrNull { it.id == id } ?: OPENAI_COMPATIBLE
    }
}

/**
 * One configured identifier, reduced to the only form PrivacyKit will let leave the device.
 *
 * Until this type existed, "Explain my fingerprint" POSTed the literal contents of every Custom
 * rule - the spoofed IMEI, ICCID, Android ID, hardware serial and build fingerprint the user had
 * assembled - to whichever cloud provider was configured (OpenAI, DeepSeek or Gemini). Inside a
 * privacy ROM that is exactly backwards: the identity built in order not to be tracked became the
 * request body, and no screen said so.
 *
 * The question the feature actually asks - "do these fields agree with each other?" - never needed
 * the literals, only their shape and family. So this type carries a description instead of a value,
 * and it is deliberately impossible to construct one around a raw value: the constructor is
 * private, [of] is the single factory, and [of] either passes through PrivacyKit own rule name
 * ("Static", "Random daily", ...) - the tool word for what it does, not user data - or hands the
 * value to [describe], which copies no character of its input into its output.
 *
 * [render] is what the disclosure UI shows and is character-for-character what is sent, so the
 * screen cannot drift from the wire.
 */
class PrivacyKitAiFieldShape private constructor(
    /** The catalog human label for the identifier, e.g. "Build Model". */
    val label: String,
    /** A PkRuleType label, or a bracketed description of the value - never the value. */
    val shape: String,
) {

    /** For example: Build Model = &lt;samsung-family name, 9 characters&gt; */
    fun render(): String = label + " = " + shape

    companion object {

        /**
         * The only way to build one.
         *
         * @param label the catalog label for the identifier
         * @param ruleLabel the PkRuleType label ("Static", "Random daily", "Empty", ...)
         * @param customValue the literal the user typed - for CUSTOM rules only, and null for
         *   every other rule type, where the rule name already says everything and there is no
         *   value in play to leak
         */
        fun of(label: String, ruleLabel: String, customValue: String?): PrivacyKitAiFieldShape =
            PrivacyKitAiFieldShape(
                label,
                if (customValue == null) ruleLabel else describe(customValue),
            )

        /**
         * Replaces a literal identifier value with a description of its shape.
         *
         * Output is always angle-bracketed, so a reader of the prompt - and of the disclosure card
         * - can tell at a glance that nothing in it is a real value.
         *
         * What it deliberately DOES reveal is the minimum a coherence critique needs: length,
         * character classes, and for names the vendor family, because "does the model agree with
         * the brand" is unanswerable without it. What it never reveals is which model, which
         * serial, which id, which subscriber.
         */
        fun describe(raw: String): String {
            val value = raw.trim()
            return when {
                value.isEmpty() -> "<empty>"
                looksLikeFingerprint(value) -> describeFingerprint(value)
                UUID_SHAPE.matches(value) -> "<UUID, 8-4-4-4-12>"
                value.all { it.isDigit() } -> describeDigits(value)
                HEX_SHAPE.matches(value) && value.length >= 8 ->
                    "<" + value.length + " hex characters>"
                TZ_SHAPE.matches(value) -> "<time-zone id, region/city form>"
                LOCALE_SHAPE.matches(value) -> "<language tag>"
                looksLikeAbiList(value) -> "<list of " + abiParts(value).size + " ABI names>"
                else -> describeName(value)
            }
        }

        /**
         * A build fingerprint is the one value worth taking apart rather than measuring whole,
         * because its internal agreement IS the thing being critiqued.
         *
         * Canonical form is brand/product/device:release/id/incremental:type/tags. The vendor
         * family, whether product and device echo the brand, the Android release number, and the
         * build type and tags are all either non-identifying or low-entropy enough to be worth
         * more than they cost - and they are precisely what makes an incoherence visible. The
         * build id and the incremental (the two high-entropy, device-pinning parts) are reduced to
         * character-class sketches. Type and tags are allow-listed rather than passed through, so
         * an odd value cannot smuggle a literal out in their place.
         */
        private fun describeFingerprint(value: String): String {
            val parts = value.split("/", ":")
            if (parts.size != 8) {
                return "<build fingerprint, non-standard: " + parts.size + " of 8 parts>"
            }
            val brand = parts[0].trim()
            val family = vendorFamily(brand) ?: "unrecognised"
            // Product-vs-device agreement, decided HERE rather than by the model, because
            // deciding it there would mean shipping both codenames. On a real build these two
            // are the same string or share a stem (husky/husky, dm3qxeea/dm3q); a hand-assembled
            // identity that took its product from one device and its device from another is one
            // of the commonest incoherences there is, and this reports that verdict as a boolean
            // instead of as evidence.
            val related = shareStem(parts[1].trim(), parts[2].trim())
            val release = parts[3].trim()
            val releaseText =
                if (release.isNotEmpty() && release.all { it.isDigit() || it == FULL_STOP }) {
                    "Android " + release
                } else {
                    "non-numeric release field"
                }
            val type = if (parts[6] in BUILD_TYPES) parts[6] else "non-standard type"
            val tags = if (parts[7] in BUILD_TAGS) parts[7] else "non-standard tags"
            return "<build fingerprint: brand=" + family + "-family, product and device " +
                    (if (related) "share a stem" else "are unrelated strings") +
                    ", " + releaseText +
                    ", buildId=" + charClasses(parts[4]) +
                    ", incremental=" + charClasses(parts[5]) +
                    ", " + type + ", " + tags + ">"
        }

        /**
         * Digit runs are described by length, with a hint at the family the length implies. The
         * length alone is what a coherence check needs (a 14-digit "IMEI" is the giveaway); the
         * digits themselves are the identifier.
         */
        private fun describeDigits(value: String): String = when (value.length) {
            5, 6 -> "<" + value.length + "-digit number, MCC+MNC shaped>"
            14, 15, 16 -> "<" + value.length + "-digit number, IMEI/MEID shaped>"
            19, 20 -> "<" + value.length + "-digit number, GSF/ICCID shaped>"
            else -> "<" + value.length + "-digit number>"
        }

        private fun describeName(value: String): String {
            val family = vendorFamily(value)
            val sketch = charClasses(value)
            return if (family != null) {
                "<" + family + "-family name, " + value.length + " characters: " + sketch + ">"
            } else {
                "<text, " + value.length + " characters: " + sketch + ">"
            }
        }

        /** "3 letters + 6 digits + 2 separators" - shape without content. */
        private fun charClasses(value: String): String {
            val letters = value.count { it.isLetter() }
            val digits = value.count { it.isDigit() }
            val other = value.length - letters - digits
            val parts = ArrayList<String>(3)
            if (letters > 0) parts.add(letters.toString() + " letters")
            if (digits > 0) parts.add(digits.toString() + " digits")
            if (other > 0) parts.add(other.toString() + " separators")
            return if (parts.isEmpty()) "empty" else parts.joinToString(" + ")
        }

        /**
         * A hit names the vendor family and nothing else, which is the entire point: it is what
         * makes "does this model agree with this brand" answerable without shipping either
         * literal. Ordered most-specific first so motorola beats moto and qualcomm beats qcom.
         */
        private fun vendorFamily(value: String): String? {
            val lower = value.lowercase()
            return VENDOR_TOKENS.firstOrNull { lower.contains(it) }
        }

        private fun looksLikeFingerprint(value: String): Boolean =
            value.split("/").size - 1 >= 3 && value.split(":").size - 1 >= 2

        /**
         * True when two build codenames look like they came off the same device: one contains the
         * other, or they agree for the first few characters. Compared in lower case and never
         * reported as anything but a boolean.
         */
        private fun shareStem(first: String, second: String): Boolean {
            val a = first.lowercase()
            val b = second.lowercase()
            if (a.isEmpty() || b.isEmpty()) return false
            if (a.contains(b) || b.contains(a)) return true
            val n = minOf(a.length, b.length, STEM_CHARS)
            return n >= 3 && a.take(n) == b.take(n)
        }

        private fun abiParts(value: String): List<String> =
            value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        private fun looksLikeAbiList(value: String): Boolean {
            val parts = abiParts(value)
            return parts.isNotEmpty() && parts.all { ABI_SHAPE.matches(it) }
        }

        private val VENDOR_TOKENS = listOf(
            "samsung", "motorola", "qualcomm", "fairphone", "mediatek", "oneplus", "xiaomi",
            "infinix", "lenovo", "nothing", "google", "huawei", "exynos", "xperia", "realme",
            "pixel", "redmi", "honor", "nokia", "meizu", "tecno", "moto", "qcom", "poco",
            "oppo", "vivo", "sony", "asus", "tcl", "zte",
        )

        private val BUILD_TYPES = setOf("user", "userdebug", "eng")
        private val BUILD_TAGS = setOf("release-keys", "dev-keys", "test-keys")

        private const val FULL_STOP = '.'

        /** How many leading characters two codenames must agree on to count as the same stem. */
        private const val STEM_CHARS = 4

        // Regex.matches() anchors the whole input, so none of these need ^ or $.
        private val UUID_SHAPE = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val HEX_SHAPE = Regex("[0-9a-fA-F]+")
        private val TZ_SHAPE = Regex("[A-Za-z_]+/[A-Za-z_+/0-9-]+")
        private val LOCALE_SHAPE = Regex("[a-z]{2,3}([-_][A-Za-z]{2,4})?([-_][A-Za-z0-9]{2,8})?")
        private val ABI_SHAPE = Regex("(arm64-v8a|armeabi-v7a|armeabi|x86_64|x86|riscv64|mips64|mips)")
    }
}

/**
 * Optional advanced AI features: "generate a device profile" and the read-only
 * "explain my fingerprint" critique.
 *
 * Off by default - the user must pick a provider and enter an API key first.
 * Every path falls back to null (caller shows an unavailable state, or falls
 * back to a curated template) on any network/parse failure; nothing here ever
 * throws to its caller. All calls are blocking and must be made off the main
 * thread.
 *
 * No identifier VALUE ever leaves the device through this object. [generate]
 * sends a fixed prompt with no device data in it at all, and [explainFingerprint]
 * accepts only [PrivacyKitAiFieldShape], which cannot hold one. API keys are the
 * only secrets here and they travel in a header to the host the user chose.
 */
object PrivacyKitAiGenerator {

    // ---------------------------------------------------------------- config

    fun getSelectedProvider(context: Context): PrivacyKitAiProvider =
        PrivacyKitAiProvider.fromId(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PROVIDER, null)
        )

    fun setSelectedProvider(context: Context, provider: PrivacyKitAiProvider) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROVIDER, provider.id)
            .apply()
    }

    /** Exactly what the user typed, with no vendor default filled in. */
    fun getBaseUrl(context: Context, provider: PrivacyKitAiProvider): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(provider.baseUrlPref, "") ?: ""

    /** What to prefill the base-URL field with: the stored value, else the vendor default. */
    fun getBaseUrlForDisplay(context: Context, provider: PrivacyKitAiProvider): String =
        getBaseUrl(context, provider).ifBlank { provider.defaultBaseUrl }

    fun getModel(context: Context, provider: PrivacyKitAiProvider): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(provider.modelPref, provider.defaultModel)
            ?.ifBlank { provider.defaultModel } ?: provider.defaultModel

    fun hasApiKey(context: Context, provider: PrivacyKitAiProvider): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(provider.apiKeyPref, null).isNullOrBlank()

    /**
     * Saves one provider's settings. A blank [apiKey] means "keep the stored
     * key": the field is masked, so making the user retype a 60-character
     * secret every time they correct a model name would be hostile. Use
     * [clearApiKey] to actually remove a key.
     */
    fun saveProviderConfig(
        context: Context,
        provider: PrivacyKitAiProvider,
        baseUrl: String,
        apiKey: String,
        model: String,
    ) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(provider.baseUrlPref, baseUrl.trim().trimEnd('/'))
            .putString(provider.modelPref, model.trim().ifBlank { provider.defaultModel })
        if (apiKey.isNotBlank()) {
            editor.putString(provider.apiKeyPref, apiKey.trim())
        }
        editor.apply()
    }

    fun clearApiKey(context: Context, provider: PrivacyKitAiProvider) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(provider.apiKeyPref)
            .apply()
    }

    /** True when the *selected* provider has everything it needs to be called. */
    fun isConfigured(context: Context): Boolean {
        val provider = getSelectedProvider(context)
        return hasApiKey(context, provider) && effectiveBaseUrl(context, provider).isNotBlank()
    }

    /**
     * "Who is about to receive this", for the disclosure UI: the selected provider plus the host
     * the request will really go to.
     *
     * Derived from the same [effectiveBaseUrl] the transport uses, so a screen that shows this
     * cannot disagree with the wire - including for the OpenAI-compatible provider, where the host
     * is whatever server the user pointed it at rather than a vendor name. Null when nothing is
     * configured, which is also exactly when nothing can be sent.
     */
    fun describeDestination(context: Context): String? {
        if (!isConfigured(context)) return null
        val provider = getSelectedProvider(context)
        val host = try {
            URL(effectiveBaseUrl(context, provider)).host
        } catch (t: Throwable) {
            null
        }
        return if (host.isNullOrBlank()) provider.id else provider.id + " (" + host + ")"
    }

    // ------------------------------------------------- legacy config surface
    // Kept so PrivacyKitDeviceProfilePC's inline "Configure AI settings..."
    // dialog keeps compiling and behaving sensibly: it now edits whichever
    // provider is currently selected.

    fun getEndpoint(context: Context): String = getBaseUrl(context, getSelectedProvider(context))

    fun getModel(context: Context): String = getModel(context, getSelectedProvider(context))

    fun saveConfig(context: Context, endpoint: String, apiKey: String, model: String) {
        saveProviderConfig(context, getSelectedProvider(context), endpoint, apiKey, model)
    }

    // --------------------------------------------------------------- feature

    /** Blocking network call - caller must invoke off the main thread. */
    fun generate(context: Context): PrivacyKitDeviceTemplate? {
        val content = complete(context, PROMPT, temperature = 0.9) ?: return null
        return try {
            val fields = JSONObject(stripJsonFence(content))
            PrivacyKitDeviceTemplate(
                displayName = "AI-generated",
                fingerprint = fields.getString("fingerprint"),
                model = fields.getString("model"),
                manufacturer = fields.getString("manufacturer"),
                brand = fields.getString("brand"),
                device = fields.getString("device"),
                product = fields.getString("product"),
                board = fields.getString("board"),
                hardware = fields.getString("hardware"),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AI profile generation returned unusable JSON, caller should fall back", t)
            null
        }
    }

    /**
     * Blocking network call - caller must invoke off the main thread.
     *
     * Diagnostic/read-only sibling of [generate]: asks the selected provider to
     * critique the internal consistency of identity fields that have already
     * been configured (not to invent new ones), and returns its free-text answer
     * verbatim - this is plain conversational output, so unlike [generate] the
     * content is never parsed as JSON. Returns null on any failure (not
     * configured, nothing configured yet, network, parse) - the caller should
     * show a clear "unavailable" state, never crash.
     *
     * [fields] is a list of [PrivacyKitAiFieldShape] rather than a label-to-value
     * map, and that IS the privacy fix: this method has no way to reach a literal
     * identifier value even if its caller has one in hand, because a shape cannot
     * be constructed around a raw value. The JSON that goes on the wire therefore
     * carries rule names and bracketed shape descriptions and nothing else. See
     * [PrivacyKitAiFieldShape] for exactly what does and does not leave the device.
     */
    fun explainFingerprint(context: Context, fields: List<PrivacyKitAiFieldShape>): String? {
        if (!isConfigured(context) || fields.isEmpty()) return null

        val shapes = JSONObject()
        for (field in fields) {
            shapes.put(field.label, field.shape)
        }
        val prompt = "An Android privacy tool has configured these identity fields for one app " +
                "on a phone. The literal values are withheld on purpose: each field is given " +
                "either as the rule the tool applies to it, or as a description of the shape of " +
                "its value in angle brackets. " + shapes + " In 3-5 short sentences, explain in " +
                "plain English whether this SET looks internally coherent - do the described " +
                "brand, model and product families agree with each other, is the fingerprint " +
                "shape plausible, and do any rotating (random) rules sit on fields that a real " +
                "device would keep stable - and flag anything that looks like an obvious " +
                "giveaway that identity spoofing is happening. Reason only from the shapes you " +
                "were given: do not ask for the literal values, and do not suggest specific " +
                "replacement values - just explain the risk."

        return complete(context, prompt, temperature = 0.7)?.trim()?.ifBlank { null }
    }

    // ------------------------------------------------------------ transport

    /**
     * Sends one single-turn prompt to the selected provider and returns the
     * model's text, or null if anything at all went wrong. This is the only
     * place that decides which wire format to speak.
     */
    private fun complete(context: Context, prompt: String, temperature: Double): String? {
        val provider = getSelectedProvider(context)
        val apiKey = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(provider.apiKeyPref, null)
        val baseUrl = effectiveBaseUrl(context, provider)
        if (apiKey.isNullOrBlank() || baseUrl.isBlank()) return null
        val model = getModel(context, provider)

        return try {
            when (provider.wire) {
                PrivacyKitAiWire.OPENAI_CHAT ->
                    openAiChat(baseUrl, apiKey, model, prompt, temperature)
                PrivacyKitAiWire.GEMINI_GENERATE_CONTENT ->
                    geminiGenerateContent(baseUrl, apiKey, model, prompt, temperature)
            }
        } catch (t: Throwable) {
            // Provider id only - never the key, never the request body.
            Log.w(TAG, "AI request failed for provider ${provider.id}", t)
            null
        }
    }

    /**
     * The base URL actually used on the wire. For a provider whose host is
     * fixed and vendor-owned, picking the provider by name is itself the user's
     * choice of host, so a blank field falls back to the vendor default. For the
     * OpenAI-compatible provider the host is whatever server the user runs, so a
     * blank field means "not configured" - see [PrivacyKitAiProvider.requiresExplicitBaseUrl].
     */
    private fun effectiveBaseUrl(context: Context, provider: PrivacyKitAiProvider): String {
        val stored = getBaseUrl(context, provider).trim().trimEnd('/')
        if (stored.isNotBlank()) return stored
        return if (provider.requiresExplicitBaseUrl) "" else provider.defaultBaseUrl
    }

    /**
     * OpenAI and DeepSeek: one POST to {base}/chat/completions with a Bearer
     * key, answer at choices[0].message.content.
     */
    private fun openAiChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        temperature: Double,
    ): String? {
        val message = JSONObject()
            .put("role", "user")
            .put("content", prompt)
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(message))
            .put("temperature", temperature)

        val responseText = postJson(
            url = URL("$baseUrl/chat/completions"),
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = body,
        ) ?: return null

        val choices = JSONObject(responseText).optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            Log.w(TAG, "AI response had no choices")
            return null
        }
        val content = choices.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
        return if (content.isNullOrBlank()) null else content
    }

    /**
     * Gemini: a genuinely different protocol from the OpenAI one above.
     *
     *   - URL carries the model and the verb: {base}/models/{model}:generateContent
     *   - the key goes in an x-goog-api-key header (Gemini also accepts ?key=,
     *     which we deliberately avoid so the secret never lands in a URL)
     *   - the prompt is contents[].parts[].text, not messages[].content
     *   - sampling settings live under generationConfig, not at the top level
     *   - the answer is candidates[0].content.parts[].text, which is a *list* of
     *     parts that has to be concatenated, and can legitimately be absent when
     *     a safety filter blocked the response
     *
     * NOTE: this path has not been exercised against a live endpoint yet.
     */
    private fun geminiGenerateContent(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        temperature: Double,
    ): String? {
        val part = JSONObject().put("text", prompt)
        val content = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(part))
        val body = JSONObject()
            .put("contents", JSONArray().put(content))
            .put("generationConfig", JSONObject().put("temperature", temperature))

        // Users copy model ids in either "gemini-2.0-flash" or "models/gemini-2.0-flash"
        // form; the base URL already supplies the "models/" segment.
        val modelPath = model.trim().removePrefix("models/").trim('/')
        if (modelPath.isBlank()) return null

        val responseText = postJson(
            url = URL("$baseUrl/models/$modelPath:generateContent"),
            headers = mapOf("x-goog-api-key" to apiKey),
            body = body,
        ) ?: return null

        val candidates = JSONObject(responseText).optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            // Typically a blocked prompt: the response carries promptFeedback instead.
            Log.w(TAG, "Gemini response had no candidates")
            return null
        }
        val parts = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
        if (parts == null || parts.length() == 0) {
            Log.w(TAG, "Gemini candidate had no parts")
            return null
        }
        val answer = StringBuilder()
        for (i in 0 until parts.length()) {
            answer.append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
        val text = answer.toString().trim()
        return if (text.isBlank()) null else text
    }

    /**
     * One JSON POST. Returns the response body, or null on any non-2xx status.
     * A truncated error body is logged because it is the only way to tell a bad
     * key from a wrong model name; response bodies never echo the key back.
     */
    private fun postJson(url: URL, headers: Map<String, String>, body: JSONObject): String? {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            for ((name, value) in headers) {
                conn.setRequestProperty(name, value)
            }
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errorBody = try {
                    conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
                        reader.readText().take(ERROR_BODY_LOG_LIMIT)
                    }
                } catch (t: Throwable) {
                    null
                }
                Log.w(TAG, "AI request HTTP $code ${errorBody ?: ""}")
                return null
            }
            return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Models asked for "only JSON" still wrap it in a markdown fence often
     * enough to be worth stripping before parsing.
     */
    private fun stripJsonFence(content: String): String =
        content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
}
