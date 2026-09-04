/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.privacykit

import android.app.KeyguardManager
import android.app.appsearch.GenericDocument
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.os.ServiceManager
import android.privacykit.IPrivacyKitManager
import android.util.Log

import androidx.annotation.Keep

import com.android.extensions.appfunctions.AppFunctionException
import com.android.extensions.appfunctions.AppFunctionService
import com.android.extensions.appfunctions.ExecuteAppFunctionRequest
import com.android.extensions.appfunctions.ExecuteAppFunctionResponse

import com.android.settings.R

import java.util.Locale
import java.util.concurrent.Executors

private const val TAG = "PkAppFunctions"

/** Read-only functions. Available to any caller the AppFunctions service lets through. */
private const val FN_LIST_MANAGED_APPS = "privacyKitListManagedApps"
private const val FN_LIST_PROFILES = "privacyKitListProfiles"
private const val FN_GET_IDENTIFIER_RULE = "privacyKitGetIdentifierRule"

/** Mutating functions. Refused unless PrivacyKit's own agent gate is on - see [MUTATING]. */
private const val FN_SET_ACTIVE_PROFILE = "privacyKitSetActiveProfile"
private const val FN_SET_IDENTIFIER_RULE = "privacyKitSetIdentifierRule"

/**
 * The functions that change state. Every one of these is refused unless
 * PrivacyKit's persisted agent/adb-control gate is on, which is the same flag
 * `adb shell cmd privacykit` consults before it will accept a mutating
 * subcommand, and which can only be flipped by the Settings UI - never from
 * this surface, so an agent cannot grant itself write access.
 */
private val MUTATING = setOf(FN_SET_ACTIVE_PROFILE, FN_SET_IDENTIFIER_RULE)

/**
 * Reads one execution request's parameters.
 *
 * The AppFunctions SDK nests a function's arguments in a document named
 * "<functionId>Params"; the platform shell (`cmd app_function
 * execute-app-function --parameters ...`) instead turns the JSON object it is
 * handed into flat top-level properties. Both are accepted: [primary] is
 * whichever of the two the request actually used, [fallback] the other one.
 */
private class PkParams(
    private val primary: GenericDocument,
    private val fallback: GenericDocument?,
) {
    /** A non-blank string argument, or null. */
    fun str(name: String): String? {
        val raw = primary.getPropertyString(name) ?: fallback?.getPropertyString(name)
        val trimmed = raw?.trim()
        return if (trimmed.isNullOrEmpty()) null else trimmed
    }

    /**
     * A numeric argument, or null if absent. Distinguished from "present and 0"
     * by probing the property first: getPropertyLong() reports a missing
     * property as 0, which for a rule type would silently mean RULE_REAL.
     */
    fun long(name: String): Long? {
        if (primary.getProperty(name) != null) return primary.getPropertyLong(name)
        if (fallback != null && fallback.getProperty(name) != null) {
            return fallback.getPropertyLong(name)
        }
        return null
    }
}

/**
 * Exposes a small, deliberately narrow slice of PrivacyKit to Android's
 * on-device agent surface (AppFunctions).
 *
 * ## What is exposed, and what is not
 *
 * Three read-only functions (which apps PrivacyKit manages, what profiles a
 * package has, what rule is set for one identifier) and two mutating ones
 * (switch the active profile, set one identifier rule).
 *
 * **No function ever returns a real identifier value, or a spoofed one.**
 * getIdentifierRule answers with the rule *type*, the key, how much of that
 * key this build's hooks really cover, and a boolean saying whether a custom
 * literal is stored - never the literal itself, and never the underlying real
 * IMEI / serial / android_id. That is the whole point of the boundary: an
 * agent that could read back values would be a better fingerprinting oracle
 * than the apps PrivacyKit is defending against.
 *
 * ## Why the gate exists
 *
 * This service runs inside Settings, so its calls into PrivacyKit carry
 * Settings' WRITE_SECURE_SETTINGS. The caller on the other side has no such
 * permission - it borrows ours. Every mutating function is therefore refused
 * unless PrivacyKit's persisted agent gate is on *and* the device is unlocked,
 * and the refusal is fail-closed: if the gate cannot even be read, the answer
 * is no.
 *
 * ## Failure policy
 *
 * A PrivacyKit fault must never take Settings down with it. Everything runs on
 * a worker thread inside a catch-all that turns any throwable into a clean
 * AppFunctionException, so the worst a broken backend can do is fail one
 * function call.
 */
@Keep
class PrivacyKitAppFunctionService : AppFunctionService() {

    /**
     * onExecuteFunction is documented to run on the main thread; every call
     * below is a binder round-trip into system_server, so none of them happen
     * there. The callback the system hands us is safe to complete from any
     * thread (it is a SafeOneTimeExecuteAppFunctionCallback on the far side).
     */
    private val worker = Executors.newSingleThreadExecutor()

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun onExecuteFunction(
        request: ExecuteAppFunctionRequest,
        callingPackage: String,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException>,
    ) {
        worker.execute {
            val response = try {
                dispatch(request)
            } catch (e: AppFunctionException) {
                callback.onError(e)
                return@execute
            } catch (t: Throwable) {
                // Deliberately Throwable: a fault in PrivacyKit or in AppSearch
                // document assembly must come back as a function error, never as
                // a dead Settings process.
                Log.e(TAG, "Unhandled failure executing " + request.functionIdentifier, t)
                callback.onError(
                    AppFunctionException(
                        AppFunctionException.ERROR_APP_UNKNOWN_ERROR,
                        getString(R.string.privacykit_appfn_error_internal),
                    )
                )
                return@execute
            }
            callback.onResult(response)
        }
    }

    private fun dispatch(request: ExecuteAppFunctionRequest): ExecuteAppFunctionResponse {
        val functionId = request.functionIdentifier
        val params = paramsOf(request)

        // Gate first, so a mutating call is refused before any argument it
        // carries is acted on.
        if (functionId in MUTATING) {
            requireMutationAllowed()
        }

        return when (functionId) {
            FN_LIST_MANAGED_APPS -> listManagedApps()
            FN_LIST_PROFILES -> listProfiles(params)
            FN_GET_IDENTIFIER_RULE -> getIdentifierRule(params)
            FN_SET_ACTIVE_PROFILE -> setActiveProfile(params)
            FN_SET_IDENTIFIER_RULE -> setIdentifierRule(params)
            else -> throw AppFunctionException(
                AppFunctionException.ERROR_FUNCTION_NOT_FOUND,
                getString(R.string.privacykit_appfn_error_unknown_function, functionId),
            )
        }
    }

    private fun paramsOf(request: ExecuteAppFunctionRequest): PkParams {
        val top = request.parameters
        val nested = try {
            top.getPropertyDocument(request.functionIdentifier + "Params")
        } catch (e: Exception) {
            null
        }
        return if (nested != null) PkParams(nested, top) else PkParams(top, null)
    }

    // ---- security --------------------------------------------------------------

    /**
     * Fail-closed precondition for every mutating function.
     *
     * Two independent checks, both of which must pass:
     *  1. PrivacyKit's persisted agent gate is on. If it cannot be read at all,
     *     the answer is no - an unreadable gate is a closed gate.
     *  2. The device is unlocked. An agent must not be able to rewrite privacy
     *     settings from the lock screen; this mirrors what Settings' own
     *     device-state app functions already do.
     */
    private fun requireMutationAllowed() {
        val enabled = try {
            manager().isAdbControlEnabled()
        } catch (e: AppFunctionException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the PrivacyKit agent gate; refusing the mutation.", e)
            throw denied(R.string.privacykit_appfn_error_gate_unreadable)
        }
        if (!enabled) {
            throw denied(R.string.privacykit_appfn_error_gate_off)
        }

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || keyguard.isDeviceLocked) {
            throw denied(R.string.privacykit_appfn_error_device_locked)
        }
    }

    // ---- functions -------------------------------------------------------------

    private fun listManagedApps(): ExecuteAppFunctionResponse {
        val managed: List<String> = pk<List<String>?> { it.getManagedPackages() } ?: emptyList()
        val configured: List<String> =
            pk<List<String>?> { it.getConfiguredPackages() } ?: emptyList()
        return respond { doc ->
            putStrings(doc, "packageNames", managed)
            doc.setPropertyLong("packageCount", managed.size.toLong())
            putStrings(doc, "configuredPackageNames", configured)
            doc.setPropertyLong("configuredPackageCount", configured.size.toLong())
        }
    }

    private fun listProfiles(params: PkParams): ExecuteAppFunctionResponse {
        val pkg = requirePackageName(params)
        val ids: List<String> = pk<List<String>?> { it.listProfileIds(pkg) } ?: emptyList()
        val activeId: String? = pk<String?> { it.getActiveProfileId(pkg) }
        val names = ids.map { id -> pk<String?> { m -> m.getProfileName(pkg, id) } ?: id }
        val modes = ids.map { id -> pk<String?> { m -> m.getProfileMode(pkg, id) } ?: "" }
        val activeName =
            if (activeId != null) pk<String?> { m -> m.getProfileName(pkg, activeId) } else null

        return respond { doc ->
            doc.setPropertyString("packageName", pkg)
            doc.setPropertyBoolean("managed", ids.isNotEmpty())
            doc.setPropertyLong("profileCount", ids.size.toLong())
            putStrings(doc, "profileIds", ids)
            putStrings(doc, "profileNames", names)
            putStrings(doc, "profileModes", modes)
            if (activeId != null) doc.setPropertyString("activeProfileId", activeId)
            if (activeName != null) doc.setPropertyString("activeProfileName", activeName)
        }
    }

    /**
     * Describes one (package, identifier) rule.
     *
     * Returns the rule *type*, not the value. "customValueSet" says whether a
     * literal is stored for a CUSTOM rule; the literal itself, and the real
     * identifier it replaces, never leave this process.
     */
    private fun getIdentifierRule(params: PkParams): ExecuteAppFunctionResponse {
        val pkg = requirePackageName(params)
        val key = requireKey(params)
        val code = pk<Int> { it.getRuleType(pkg, key) }
        val rule = PkRuleType.fromServerValue(code)
        val customValueSet = rule == PkRuleType.CUSTOM &&
            !pk<String?> { it.getRuleValue(pkg, key) }.isNullOrEmpty()

        return respond { doc ->
            describeRule(doc, pkg, key, code, rule, customValueSet)
        }
    }

    private fun setActiveProfile(params: PkParams): ExecuteAppFunctionResponse {
        val pkg = requireInstalledPackageName(params)
        val profileId = params.str("profileId")
            ?: throw invalid(R.string.privacykit_appfn_error_missing_profile)

        val ids: List<String> = pk<List<String>?> { it.listProfileIds(pkg) } ?: emptyList()
        if (ids.isEmpty()) {
            throw invalid(R.string.privacykit_appfn_error_not_managed, pkg)
        }
        // setActiveProfile() is documented to be a silent no-op for an unknown
        // id. Check first so the caller gets a real error instead of a
        // success response that changed nothing.
        if (!ids.contains(profileId)) {
            throw invalid(R.string.privacykit_appfn_error_unknown_profile, profileId, pkg)
        }

        pk<Unit> { it.setActiveProfile(pkg, profileId) }

        val nowActive: String? = pk<String?> { it.getActiveProfileId(pkg) }
        val nowActiveName =
            if (nowActive != null) pk<String?> { m -> m.getProfileName(pkg, nowActive) } else null

        return respond { doc ->
            doc.setPropertyString("packageName", pkg)
            doc.setPropertyBoolean("changed", nowActive == profileId)
            if (nowActive != null) doc.setPropertyString("activeProfileId", nowActive)
            if (nowActiveName != null) doc.setPropertyString("activeProfileName", nowActiveName)
        }
    }

    private fun setIdentifierRule(params: PkParams): ExecuteAppFunctionResponse {
        val pkg = requireInstalledPackageName(params)
        val key = requireKey(params)
        val requested = parseRuleType(params)

        // The catalog is the single source of truth for which rule types the
        // backend will actually act on for a key. Accepting one it silently
        // drops would report a protection that is not there.
        val supported = PrivacyKitIdentifierCatalog.supportedRuleTypesFor(key)
        if (!supported.contains(requested)) {
            throw invalid(
                R.string.privacykit_appfn_error_rule_type_unsupported,
                requested.name,
                key,
                supported.joinToString(", ") { it.name },
            )
        }

        val custom = if (requested == PkRuleType.CUSTOM) {
            params.str("value") ?: throw invalid(R.string.privacykit_appfn_error_missing_value)
        } else {
            null
        }

        pk<Unit> { it.setIdentifierRule(pkg, key, requested.serverValue, custom) }

        // Read back rather than echo: the caller learns what PrivacyKit
        // actually stored, and the response shape stays identical to
        // getIdentifierRule.
        val code = pk<Int> { it.getRuleType(pkg, key) }
        val stored = PkRuleType.fromServerValue(code)
        val customValueSet = stored == PkRuleType.CUSTOM &&
            !pk<String?> { it.getRuleValue(pkg, key) }.isNullOrEmpty()

        return respond { doc ->
            describeRule(doc, pkg, key, code, stored, customValueSet)
        }
    }

    // ---- argument parsing ------------------------------------------------------

    private fun requirePackageName(params: PkParams): String =
        params.str("packageName")
            ?: throw invalid(R.string.privacykit_appfn_error_missing_package)

    /**
     * Same as [requirePackageName] plus an installed-package check. Only the
     * mutating functions use it: a read about a package that has since been
     * uninstalled is still a fair question, but writing a rule for a package
     * name that does not exist is just litter in the profile store.
     */
    private fun requireInstalledPackageName(params: PkParams): String {
        val pkg = requirePackageName(params)
        try {
            packageManager.getPackageInfo(
                pkg,
                PackageInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } catch (e: PackageManager.NameNotFoundException) {
            throw invalid(R.string.privacykit_appfn_error_unknown_package, pkg)
        }
        return pkg
    }

    private fun requireKey(params: PkParams): String {
        val key = params.str("key")
            ?: throw invalid(R.string.privacykit_appfn_error_missing_key)
        if (PrivacyKitIdentifierCatalog.allItems.none { it.key == key }) {
            throw invalid(R.string.privacykit_appfn_error_unknown_key, key)
        }
        return key
    }

    /** Accepts either a rule-type name ("CUSTOM", "per_launch", ...) or its numeric code. */
    private fun parseRuleType(params: PkParams): PkRuleType {
        val name = params.str("ruleType")
        if (name != null) {
            return ruleTypeByName(name)
                ?: throw invalid(R.string.privacykit_appfn_error_unknown_rule_type, name)
        }
        val code = params.long("ruleType")
            ?: throw invalid(R.string.privacykit_appfn_error_missing_rule_type)
        return PkRuleType.entries.firstOrNull { it.serverValue.toLong() == code }
            ?: throw invalid(R.string.privacykit_appfn_error_unknown_rule_type, code.toString())
    }

    private fun ruleTypeByName(raw: String): PkRuleType? =
        when (raw.trim().uppercase(Locale.US).replace('-', '_').replace(' ', '_')) {
            "REAL", "OFF", "NONE" -> PkRuleType.REAL
            "STATIC", "RANDOM_STATIC" -> PkRuleType.STATIC
            "PER_LAUNCH", "RANDOM_PER_LAUNCH" -> PkRuleType.RANDOM_PER_LAUNCH
            "DAILY", "RANDOM_DAILY" -> PkRuleType.RANDOM_DAILY
            "CUSTOM", "CUSTOM_VALUE" -> PkRuleType.CUSTOM
            "EMPTY", "RESTRICTED" -> PkRuleType.EMPTY
            else -> null
        }

    // ---- plumbing --------------------------------------------------------------

    private fun manager(): IPrivacyKitManager =
        IPrivacyKitManager.Stub.asInterface(ServiceManager.getService("privacykit"))
            ?: throw AppFunctionException(
                AppFunctionException.ERROR_SYSTEM_ERROR,
                getString(R.string.privacykit_appfn_error_unavailable),
            )

    /**
     * Runs one PrivacyKit binder call, turning a dead or angry service into a
     * clean function error. Never lets a RemoteException escape to the caller
     * as a crash.
     */
    private fun <T> pk(block: (IPrivacyKitManager) -> T): T {
        val mgr = manager()
        return try {
            block(mgr)
        } catch (e: AppFunctionException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "PrivacyKit call failed", e)
            throw AppFunctionException(
                AppFunctionException.ERROR_APP_UNKNOWN_ERROR,
                getString(R.string.privacykit_appfn_error_privacykit_failed),
            )
        }
    }

    private fun denied(resId: Int): AppFunctionException =
        AppFunctionException(AppFunctionException.ERROR_DENIED, getString(resId))

    private fun invalid(resId: Int, vararg args: Any): AppFunctionException =
        AppFunctionException(
            AppFunctionException.ERROR_INVALID_ARGUMENT,
            if (args.isEmpty()) getString(resId) else getString(resId, *args),
        )

    /**
     * The one place a rule is described, shared by the getter and the setter so
     * the two can never drift into disagreeing about what is safe to disclose.
     */
    private fun describeRule(
        doc: GenericDocument.Builder<GenericDocument.Builder<*>>,
        pkg: String,
        key: String,
        code: Int,
        rule: PkRuleType,
        customValueSet: Boolean,
    ) {
        doc.setPropertyString("packageName", pkg)
        doc.setPropertyString("key", key)
        doc.setPropertyString("label", PrivacyKitIdentifierCatalog.itemByKey(key).label)
        doc.setPropertyString("ruleType", rule.name)
        doc.setPropertyLong("ruleTypeCode", code.toLong())
        // Whether a literal is stored - deliberately not the literal.
        doc.setPropertyBoolean("customValueSet", customValueSet)
        // How much of this identifier the build's hooks really cover, so an
        // agent cannot claim an unhooked key is being spoofed.
        doc.setPropertyString("enforcement", PrivacyKitIdentifierCatalog.enforcementOf(key).name)
        putStrings(
            doc,
            "supportedRuleTypes",
            PrivacyKitIdentifierCatalog.supportedRuleTypesFor(key).map { it.name },
        )
    }

    private fun respond(
        fill: (GenericDocument.Builder<GenericDocument.Builder<*>>) -> Unit
    ): ExecuteAppFunctionResponse {
        val payload = GenericDocument.Builder<GenericDocument.Builder<*>>("", "", "")
        fill(payload)
        val result = GenericDocument.Builder<GenericDocument.Builder<*>>("", "", "")
        result.setPropertyDocument(
            ExecuteAppFunctionResponse.PROPERTY_RETURN_VALUE,
            payload.build(),
        )
        return ExecuteAppFunctionResponse(result.build())
    }

    /**
     * Sets a repeated string property, skipping it entirely when empty - an
     * absent property plus the matching count is unambiguous, a zero-length
     * repeated property is not.
     */
    private fun putStrings(
        doc: GenericDocument.Builder<GenericDocument.Builder<*>>,
        name: String,
        values: List<String>,
    ) {
        if (values.isEmpty()) return
        doc.setPropertyString(name, *values.toTypedArray())
    }
}
