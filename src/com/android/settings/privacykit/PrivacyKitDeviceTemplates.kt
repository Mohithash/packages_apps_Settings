/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */

// Reconstructed 2026-08-19, after the build box that held this file's commit was purged.
// Rebuilt by applying the 2026-08-18 uncommitted patch to a whole-file snapshot mined from
// the Claude Code transcripts (git apply, exact, zero fuzz - so the snapshot was the base
// commit and the result is the final state), then verified field-for-field against the
// classes.dex of the r49 Settings.apk: all seventeen templates, in order, with the same
// eleven values each and the same two defaulted SoC fields. Nothing here is guessed.

package com.android.settings.privacykit

import android.privacykit.PrivacyKitKeys

/**
 * Curated, real, internally-coherent device identity bundles for the
 * "generate new profile" feature. Deliberately uses real shipped device
 * fingerprints rather than pure random/AI generation for the built-in
 * templates - a hallucinated or randomly-assembled fingerprint can be
 * internally inconsistent (wrong tag/security-patch/model combination) in
 * ways that read as more suspicious than the real device's own identity
 * hardening this ROM already does. AI generation is offered as a separate,
 * clearly-labeled advanced option (PrivacyKitAiGenerator) for users who want
 * a fully custom bundle instead of one of these.
 */
data class PrivacyKitDeviceTemplate(
    val displayName: String,
    val fingerprint: String,
    val model: String,
    val manufacturer: String,
    val brand: String,
    val device: String,
    val product: String,
    val board: String,
    val hardware: String,
    val socManufacturer: String = "",
    val socModel: String = "",
    /**
     * Build.DISPLAY. Blank means "derive it": AOSP's own build system defaults
     * BUILD_DISPLAY_ID to BUILD_ID, which is what Pixel, Samsung, Sony, Nothing
     * and OnePlus ship, so the build id parsed out of [fingerprint] is the
     * correct derivation. An OEM that ships something else (Xiaomi prints the
     * MIUI/HyperOS incremental here) can state it explicitly.
     */
    val display: String = "",
    /**
     * Build.VERSION.SECURITY_PATCH, when the template states a verified one.
     * Blank means "leave the device's real patch level alone" - see
     * [effectiveSecurityPatch] for why this one field is NOT derived from the
     * fingerprint the way the other six are.
     */
    val securityPatch: String = "",
) {
    /**
     * The parsed halves of [fingerprint], or null when it does not have the
     * platform's shape.
     *
     * The grammar is fixed by build/make/core/Makefile and has not changed in
     * a decade:
     *   $(BRAND)/$(PRODUCT)/$(DEVICE):$(RELEASE)/$(ID)/$(INCREMENTAL):$(TYPE)/$(TAGS)
     * The incremental is the one component that may itself contain '/' or ':'
     * on some OEM builds, so the split is anchored on the FIRST and LAST ':'
     * and the incremental takes whatever is left in the middle, rather than a
     * naive split that would mis-slice those builds.
     *
     * Never throws. A fingerprint this cannot parse yields null and every
     * derived key is simply omitted from [toFieldMap] - the same "omit rather
     * than invent" rule the SoC pair already follows.
     */
    private val parsed: Parsed? by lazy(LazyThreadSafetyMode.PUBLICATION) { parse(fingerprint) }

    private data class Parsed(
        val release: String,
        val id: String,
        val incremental: String,
        val type: String,
        val tags: String,
    )

    /** Build.VERSION.RELEASE as printed inside [fingerprint], or "". */
    val versionRelease: String get() = parsed?.release ?: ""

    /** Build.ID as printed inside [fingerprint], or "". */
    val buildId: String get() = parsed?.id ?: ""

    /** Build.VERSION.INCREMENTAL as printed inside [fingerprint], or "". */
    val versionIncremental: String get() = parsed?.incremental ?: ""

    /** Build.TYPE as printed inside [fingerprint], or "". */
    val buildType: String get() = parsed?.type ?: ""

    /** Build.TAGS as printed inside [fingerprint], or "". */
    val buildTags: String get() = parsed?.tags ?: ""

    /** [display] when the template states one, else Build.ID (the AOSP default). */
    val displayId: String get() = if (display.isNotBlank()) display else buildId

    /**
     * [securityPatch] verbatim, or "" - deliberately NOT derived.
     *
     * The obvious derivation is the build id's own date stamp: `AP2A.240805.005`
     * is a build cut on 2024-08-05, so read the month off it. That derivation is
     * wrong on real devices, and wrong in the direction that matters. A phone's
     * patch level advances with every monthly OTA while its Build.ID very often
     * does not - the OnePlus 12R template below genuinely ships Android 15 on
     * build id `TP1A.220905.001`, and its real SECURITY_PATCH is two years
     * newer than that stamp. Deriving would have written `2022-09-01` next to
     * `:15/`, which is not a patch level any shipped Android 15 device has ever
     * reported: the derivation would have MANUFACTURED the incoherence this
     * whole class exists to avoid.
     *
     * Leaving the field blank leaves the device's real patch level in place,
     * and a recent patch level beside an older build id is the normal state of
     * every phone that has taken an update - so blank is the coherent answer
     * here, not the lazy one. A template that knows the verified value for its
     * device states [securityPatch] and gets that instead.
     */
    val effectiveSecurityPatch: String get() = securityPatch

    /**
     * Every Build.* / Build.VERSION.* field this template can state, as the
     * identifier keys the rule engine takes.
     *
     * The eight always-present fields, the SoC pair when the template states
     * one, and - since 2026-08 - the six identity fields parsed straight out of
     * the fingerprint (Build.ID, DISPLAY, TYPE, TAGS, VERSION.RELEASE,
     * VERSION.INCREMENTAL) plus SECURITY_PATCH when the template states a
     * verified one. Those were being left REAL while the fingerprint above them
     * was replaced. Leaving them real was
     * not the safe half-measure it looked like: a device whose FINGERPRINT says
     * `google/husky/husky:14/AP2A.240805.005/...` while its Build.ID still says
     * `BP2A.250605.031.A3` and its RELEASE still says `16` is self-contradicting
     * on a one-line string compare, which is a LOUDER signal than either
     * identity would have been on its own. All six come out of the fingerprint
     * string itself, so no new template data was needed to fix it;
     * SECURITY_PATCH is the one field that genuinely cannot be derived and is
     * left real unless a template states it (see [effectiveSecurityPatch]).
     *
     * Blank means "leave the real value alone" for every optional key, so a
     * fingerprint this cannot parse degrades to exactly the old eight-field
     * behaviour instead of writing something invented.
     *
     * Two limits stated rather than hidden:
     *  - Java-field-level only. No ro.build.* / ro.soc.* / ro.board.platform /
     *    ro.hardware system property is written, so native readers (and
     *    eglGetDisplay) keep seeing the real values.
     *  - Build.VERSION.SDK_INT is deliberately never spoofed (a fake API level
     *    makes AndroidX shims call methods that do not exist), so a template
     *    whose RELEASE is not this device's own leaves RELEASE and SDK_INT
     *    disagreeing. That is still strictly better than before this change -
     *    the fingerprint already carried the template's release, so the pair
     *    (FINGERPRINT, RELEASE) stopped disagreeing and only (RELEASE, SDK_INT)
     *    remains - but it is a real residual tell, and the way to avoid it is
     *    to pick a template whose release matches this build's.
     */
    fun toFieldMap(): Map<String, String> = buildMap {
        put(PrivacyKitKeys.KEY_BUILD_FINGERPRINT, fingerprint)
        put(PrivacyKitKeys.KEY_BUILD_MODEL, model)
        put(PrivacyKitKeys.KEY_BUILD_MANUFACTURER, manufacturer)
        put(PrivacyKitKeys.KEY_BUILD_BRAND, brand)
        put(PrivacyKitKeys.KEY_BUILD_DEVICE, device)
        put(PrivacyKitKeys.KEY_BUILD_PRODUCT, product)
        put(PrivacyKitKeys.KEY_BUILD_BOARD, board)
        put(PrivacyKitKeys.KEY_BUILD_HARDWARE, hardware)
        if (socManufacturer.isNotBlank()) {
            put(PrivacyKitKeys.KEY_BUILD_SOC_MANUFACTURER, socManufacturer)
        }
        if (socModel.isNotBlank()) {
            put(PrivacyKitKeys.KEY_BUILD_SOC_MODEL, socModel)
        }
        putIfNotBlank(PrivacyKitKeys.KEY_BUILD_ID, buildId)
        putIfNotBlank(PrivacyKitKeys.KEY_BUILD_DISPLAY, displayId)
        putIfNotBlank(PrivacyKitKeys.KEY_BUILD_TYPE, buildType)
        putIfNotBlank(PrivacyKitKeys.KEY_BUILD_TAGS, buildTags)
        putIfNotBlank(PrivacyKitKeys.KEY_OS_VERSION_RELEASE, versionRelease)
        putIfNotBlank(PrivacyKitKeys.KEY_OS_VERSION_INCREMENTAL, versionIncremental)
        putIfNotBlank(PrivacyKitKeys.KEY_OS_SECURITY_PATCH, effectiveSecurityPatch)
    }

    private fun MutableMap<String, String>.putIfNotBlank(key: String, value: String) {
        if (value.isNotBlank()) put(key, value)
    }

    private companion object {
        fun parse(fp: String): Parsed? {
            val first = fp.indexOf(':')
            val last = fp.lastIndexOf(':')
            if (first < 0 || last <= first) return null
            val middle = fp.substring(first + 1, last)
            val trailer = fp.substring(last + 1)

            val relEnd = middle.indexOf('/')
            if (relEnd <= 0) return null
            val idEnd = middle.indexOf('/', relEnd + 1)
            if (idEnd <= relEnd + 1) return null
            val release = middle.substring(0, relEnd)
            val id = middle.substring(relEnd + 1, idEnd)
            val incremental = middle.substring(idEnd + 1)

            val typeEnd = trailer.indexOf('/')
            if (typeEnd <= 0 || typeEnd == trailer.length - 1) return null
            val type = trailer.substring(0, typeEnd)
            val tags = trailer.substring(typeEnd + 1)

            if (release.isBlank() || id.isBlank() || incremental.isBlank()
                    || type.isBlank() || tags.isBlank()) {
                return null
            }
            return Parsed(release, id, incremental, type, tags)
        }
    }
}

object PrivacyKitDeviceTemplates {

    /**
     * Every identifier key a template can write, and therefore every key a
     * "clear the spoofed identity" action has to clear.
     *
     * Applying a template and clearing it used to be written twice, against two
     * different lists: apply iterated [PrivacyKitDeviceTemplate.toFieldMap] (up
     * to ten keys) while all five clear sites iterated PrivacyKitKeys.BUILD_KEYS
     * (eight). The SoC pair therefore survived a "None (real identity)" reset
     * and stayed stuck on the old template's SoC - and the moment toFieldMap
     * grew to cover Build.ID / DISPLAY / TYPE / TAGS / RELEASE / INCREMENTAL,
     * the same asymmetry would have left SIX more fields welded to a device the
     * user had already switched away from, which is the worst possible state:
     * a fingerprint from one phone and a build id from another.
     *
     * So there is now one list, named after what it means, and every clear site
     * iterates it. It is the full SUPERSET of what toFieldMap() can emit rather
     * than a union over [TEMPLATES], because a template does not have to come
     * from that list - PrivacyKitAiGenerator mints one at runtime and may state
     * a SoC or a security patch that no built-in template does. Clearing a key
     * that was never set is a no-op, so over-covering here is free; under-
     * covering is the bug this replaced.
     *
     * INVARIANT: every key put() by [PrivacyKitDeviceTemplate.toFieldMap] must
     * appear here. Adding one there without adding it here reintroduces exactly
     * the stuck-field defect described above.
     */
    val IDENTITY_KEYS: Set<String> = setOf(
        // The eight coherent Build.* fields (== PrivacyKitKeys.BUILD_KEYS).
        PrivacyKitKeys.KEY_BUILD_FINGERPRINT,
        PrivacyKitKeys.KEY_BUILD_MODEL,
        PrivacyKitKeys.KEY_BUILD_MANUFACTURER,
        PrivacyKitKeys.KEY_BUILD_BRAND,
        PrivacyKitKeys.KEY_BUILD_DEVICE,
        PrivacyKitKeys.KEY_BUILD_PRODUCT,
        PrivacyKitKeys.KEY_BUILD_BOARD,
        PrivacyKitKeys.KEY_BUILD_HARDWARE,
        // Optional SoC pair.
        PrivacyKitKeys.KEY_BUILD_SOC_MANUFACTURER,
        PrivacyKitKeys.KEY_BUILD_SOC_MODEL,
        // Parsed out of the fingerprint string itself.
        PrivacyKitKeys.KEY_BUILD_ID,
        PrivacyKitKeys.KEY_BUILD_DISPLAY,
        PrivacyKitKeys.KEY_BUILD_TYPE,
        PrivacyKitKeys.KEY_BUILD_TAGS,
        PrivacyKitKeys.KEY_OS_VERSION_RELEASE,
        PrivacyKitKeys.KEY_OS_VERSION_INCREMENTAL,
        // Only written when a template states a verified value.
        PrivacyKitKeys.KEY_OS_SECURITY_PATCH,
    )

    val TEMPLATES: List<PrivacyKitDeviceTemplate> = listOf(
        PrivacyKitDeviceTemplate(
            displayName = "Google Pixel 8 Pro",
            fingerprint = "google/husky/husky:14/AP2A.240805.005/12025142:user/release-keys",
            model = "Pixel 8 Pro", manufacturer = "Google", brand = "google",
            device = "husky", product = "husky", board = "husky", hardware = "husky",
            socManufacturer = "Google", socModel = "Tensor G3",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "Samsung Galaxy S23",
            fingerprint = "samsung/dm3qxeea/dm3q:14/UP1A.231005.007/S911BXXU3BWK1:user/release-keys",
            model = "SM-S911B", manufacturer = "samsung", brand = "samsung",
            device = "dm3q", product = "dm3qxeea", board = "kalama", hardware = "qcom",
            socManufacturer = "QTI", socModel = "SM8550",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "Xiaomi 13T",
            fingerprint = "Xiaomi/corot/corot:14/UKQ1.230917.001/V816.0.5.0.UMZMIXM:user/release-keys",
            model = "23078RKD5C", manufacturer = "Xiaomi", brand = "Xiaomi",
            device = "corot", product = "corot", board = "taro", hardware = "qcom",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "Generic AOSP (no OEM branding)",
            fingerprint = "generic/aosp_arm64/generic_arm64:14/UPB2.230823.017/12026509:userdebug/test-keys",
            model = "AOSP on ARM64", manufacturer = "Google", brand = "generic",
            device = "generic_arm64", product = "aosp_arm64", board = "generic_arm64",
            hardware = "ranchu",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "OnePlus 10T 5G",
            fingerprint = "OnePlus/CPH2413/OP5552L1:13/SKQ1.221119.001/S.123ec2a_6b801_6ff30:user/release-keys",
            model = "CPH2413", manufacturer = "OnePlus", brand = "OnePlus",
            device = "OP5552L1", product = "CPH2413", board = "taro", hardware = "qcom",
            socManufacturer = "QTI", socModel = "SM8475",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "OnePlus 12R (Ace 3, CN)",
            fingerprint = "OnePlus/PJE110/OP5CF9L1:15/TP1A.220905.001/U.1c823f0-3e38-1:user/release-keys",
            model = "PJE110", manufacturer = "OnePlus", brand = "OnePlus",
            device = "OP5CF9L1", product = "PJE110", board = "kalama", hardware = "qcom",
            socManufacturer = "QTI", socModel = "SM8550",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "Sony Xperia 1 VI",
            fingerprint = "Sony/pdx245/pdx245:14/UKQ1.231121.002/1:user/release-keys",
            model = "XQ-EC72", manufacturer = "Sony", brand = "Sony",
            device = "pdx245", product = "pdx245", board = "pineapple", hardware = "qcom",
            socManufacturer = "QTI", socModel = "SM8650",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "Asus Zenfone 10",
            fingerprint = "asus/EU_AI2302/ASUS_AI2302:13/TKQ1.220928.001/33.0204.0204.36:user/release-keys",
            model = "ASUS_AI2302", manufacturer = "asus", brand = "asus",
            device = "ASUS_AI2302", product = "EU_AI2302", board = "kalama", hardware = "qcom",
            socManufacturer = "QTI", socModel = "SM8550",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "Redmi Note 13 Pro 5G",
            fingerprint = "Redmi/garnet_global/garnet:14/UKQ1.231003.002/V816.0.9.0.UNRMIXM:user/release-keys",
            model = "2312DRA50G", manufacturer = "Xiaomi", brand = "Redmi",
            device = "garnet", product = "garnet_global", board = "parrot", hardware = "qcom",
            socManufacturer = "QTI", socModel = "SM7435",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "Nothing Phone (2)",
            fingerprint = "Nothing/Pong/Pong:14/UP1A.231005.007/2310072102:user/release-keys",
            model = "A065", manufacturer = "Nothing", brand = "Nothing",
            device = "Pong", product = "Pong", board = "taro", hardware = "qcom",
            socManufacturer = "QTI", socModel = "SM8475",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "Motorola Edge 50 Pro",
            fingerprint = "motorola/eqe_g/eqe:13/V1UM35H.10-38-1/f42a9:user/release-keys",
            model = "Motorola Edge 50 Pro", manufacturer = "motorola", brand = "motorola",
            device = "eqe", product = "eqe_g", board = "parrot", hardware = "qcom",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "Samsung Galaxy A54 5G",
            fingerprint = "samsung/a54xnaxx/a54x:14/UP1A.231005.007/A546BXXSACXI3:user/release-keys",
            model = "SM-A546B", manufacturer = "samsung", brand = "samsung",
            device = "a54x", product = "a54xnaxx", board = "s5e8835", hardware = "s5e8835",
            socManufacturer = "Samsung", socModel = "s5e8835",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "Samsung Galaxy S24 Ultra",
            fingerprint = "samsung/e3qxeea/e3q:14/UP1A.231005.007/S928BXXU1AWM9:user/release-keys",
            model = "SM-S928B", manufacturer = "samsung", brand = "samsung",
            device = "e3q", product = "e3qxeea", board = "pineapple", hardware = "qcom",
            socManufacturer = "QTI", socModel = "SM8650",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "Google Pixel 8",
            fingerprint = "google/shiba/shiba:15/AP3A.241105.007/12470370:user/release-keys",
            model = "Pixel 8", manufacturer = "Google", brand = "google",
            device = "shiba", product = "shiba", board = "shiba", hardware = "shiba",
            socManufacturer = "Google", socModel = "Tensor G3",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "Google Pixel 6 Pro",
            fingerprint = "google/raven/raven:13/TQ1A.230105.002/9325679:user/release-keys",
            model = "Pixel 6 Pro", manufacturer = "Google", brand = "google",
            device = "raven", product = "raven", board = "raven", hardware = "raven",
            socManufacturer = "Google", socModel = "gs101",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "Samsung Galaxy Z Fold5",
            fingerprint = "samsung/q5qxeea/q5q:14/UP1A.231005.007/F946BXXS5EYI1:user/release-keys",
            model = "SM-F946B", manufacturer = "samsung", brand = "samsung",
            device = "q5q", product = "q5qxeea", board = "kalama", hardware = "qcom",
            socManufacturer = "QTI", socModel = "SM8550",
        ),
        PrivacyKitDeviceTemplate(
            displayName = "Google Pixel 6a",
            fingerprint = "google/bluejay/bluejay:16/BP4A.251205.006/14401865:user/release-keys",
            model = "Pixel 6a", manufacturer = "Google", brand = "google",
            device = "bluejay", product = "bluejay", board = "bluejay", hardware = "bluejay",
            socManufacturer = "Google", socModel = "gs101",
        ),
    )
}

