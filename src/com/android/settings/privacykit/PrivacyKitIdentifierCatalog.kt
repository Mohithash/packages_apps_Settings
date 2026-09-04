/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */

// Reconstructed 2026-08-19, after the build box that held this file's commit was purged.
// Sources, in order of authority: the 2026-08-18 uncommitted patch hunks, which carry the
// final text of every region they touch verbatim; a whole-file snapshot mined from the
// Claude Code transcripts for the fifteen regions no hunk touches, each spliced in on a
// unique three-line context anchor at both ends; and the classes.dex of the r49
// Settings.apk, against which every identifier key, label, default rule, set, map and long
// string literal below was verified character-for-character. The line count matches the
// 1286 the patch hunk headers imply. Nothing here is guessed.

package com.android.settings.privacykit

import android.content.Context

import androidx.annotation.StringRes

import com.android.settings.R

/**
 * Rule types for a single identifier, matching the real PrivacyKit app's model exactly (ported
 * from Identifier.kt at github.com/Mohithash/Privacy_Kit). Resolution/enforcement lives in
 * PrivacyKitRuleResolver on the server side; RULE_EMPTY was added there to match.
 *
 * Only a subset of the identifiers below have a real hook site wired up server-side yet - see
 * [PrivacyKitIdentifierCatalog.enforcedKeys], which is the single source of truth the UI uses to
 * decide whether it may tell the user an identifier is genuinely being spoofed. Every other
 * identifier is still fully editable and its rule is stored and round-trips correctly through the
 * generic setIdentifierRule/getRuleType AIDL calls, but has no effect until a matching framework
 * hook site is added - a real, deliberate gap, not something being hidden.
 *
 * Coverage is a three-way answer, not a boolean: [PrivacyKitIdentifierCatalog.enforcementOf]
 * returns FULL, PARTIAL or NONE, and [PrivacyKitIdentifierCatalog.partialEnforcementCaveats] says
 * exactly what is missing for each PARTIAL key. PARTIAL and NONE must both render as
 * not-enforced.
 *
 * FULL is a necessary condition for telling the user an identifier is handled - it is NOT a
 * sufficient condition for the word "spoofed". Some FULL keys are *blocks*, not substitutions: the
 * app is refused the value rather than handed a plausible fake, which is a materially different
 * promise (a block is often detectable; a spoof is meant not to be). Those keys carry a mandatory
 * sentence saying so in [PrivacyKitIdentifierCatalog.restrictionNotes].
 * [PrivacyKitIdentifierCatalog.noteOf] is the single accessor the UI should use for that sentence,
 * whatever its source, so a newly added note source can never be silently dropped from a screen.
 *
 * A second, independent axis: [PrivacyKitIdentifierCatalog.supportedRuleTypesFor] is the set of
 * rule types the *backend* will actually act on for a key. Offering a rule type the backend
 * silently refuses is the same class of lie as claiming an unhooked identifier is spoofed - a
 * prior audit caught exactly that - so the picker must never widen this list.
 */
enum class PkRuleType(val label: String) {
    REAL("Real"),
    STATIC("Static"),
    RANDOM_PER_LAUNCH("Random Per Launch"),
    RANDOM_DAILY("Random Daily"),
    CUSTOM("Custom Value"),
    EMPTY("Empty / Restricted"),
    ;

    /** Maps to PrivacyKitRuleResolver.RULE_* on the server. */
    val serverValue: Int
        get() = when (this) {
            REAL -> 0
            STATIC -> 1
            RANDOM_PER_LAUNCH -> 2
            RANDOM_DAILY -> 3
            CUSTOM -> 4
            EMPTY -> 5
        }

    companion object {
        fun fromServerValue(value: Int): PkRuleType =
            entries.firstOrNull { it.serverValue == value } ?: REAL
    }
}

data class PkIdentifierItem(
    val key: String,
    val label: String,
    val defaultRule: PkRuleType,
)

data class PkIdentifierGroup(
    val title: String,
    val items: List<PkIdentifierItem>,
)

object PrivacyKitIdentifierCatalog {
    val groups = listOf(
        PkIdentifierGroup(
            title = "Device Identifiers",
            items = listOf(
                PkIdentifierItem("android_id", "Android ID", PkRuleType.STATIC),
                // Default kept at Real deliberately, even though PrivacyKitIdentifierGenerator now
                // has a gsf_id case (genGsfId emits a 19-digit decimal long that the gservices hook
                // accepts, so Static / Random / Custom now take effect - see
                // [partialEnforcementCaveats]). The GSF ID participates in Google Play / FCM device
                // registration, so it is opt-in per app rather than spoofed by default.
                PkIdentifierItem("gsf_id", "Google Services Framework ID", PkRuleType.REAL),
                PkIdentifierItem("firebase_installation_id", "Firebase Installation ID", PkRuleType.STATIC),
                PkIdentifierItem("firebase_app_instance_id", "Firebase App Instance ID", PkRuleType.STATIC),
                // advertising_id deliberately does NOT live here any more. The ad id is
                // enforced by a BLOCK, not by a substitution: the bind that fetches it is
                // refused. That makes it a restriction, so it sits in the "Privacy Restrictions"
                // group with the other block-style keys, where the group counter cannot imply a
                // value was substituted.
                //
                // Corrected 2026-08 (r31): the older wording here - "PrivacyKit cannot fake a
                // GAID, there is no framework API to intercept" - is no longer accurate as an
                // absolute. PrivacyKitGmsBinderFilter wraps the dedicated ad-id leaf binder
                // in LoadedApk.ServiceDispatcher#doConnected and rewrites the getId() reply, so a
                // substitution path does exist in this tree. It changes nothing about what this
                // catalog claims, for two independent reasons: it is gated behind
                // persist.sys.privacykit.adid_spoof, which defaults to false
                // (PrivacyKitGmsBinderFilter), and on-device verification found no
                // ad-id service on this build for it to wrap. So the block remains the only
                // enforcement that reaches an app here, the row keeps saying "blocked, not
                // faked", and only Real/Empty are offered. See [restrictionNotes].

                // app_set_id is NOT enforced on this device, and for a specific reason rather
                // than "no hook exists" - see [notEnforcedReasons]. r33 added a real hook to
                // android.adservices.appsetid.AppSetIdManager, but apps read the App Set ID
                // through com.google.android.gms.appset.AppSetIdClient, which reaches Google Play
                // services over its own connection and never enters that API; on-device testing
                // confirmed the value an app reads does not change. Do not promote this key on
                // the strength of the hook existing.
                PkIdentifierItem("app_set_id", "Google App Set ID", PkRuleType.STATIC),
                PkIdentifierItem("media_drm_id", "Media DRM ID (Widevine)", PkRuleType.STATIC),
                PkIdentifierItem("serial", "Hardware Serial", PkRuleType.STATIC),
            ),
        ),
        PkIdentifierGroup(
            title = "Device Information",
            items = listOf(
                // Every key in [coherentBuildIdentityKeys] defaults to Real, not Static.
                // Two reasons, both concrete: [supportedRuleTypesFor] offers these keys only
                // Real and Custom, so a Static default preselected a radio row the picker does
                // not draw; and Static on a fingerprint-linked field is the exact incoherence
                // this catalog exists to prevent - the backend refuses it
                // (PrivacyKitRuleResolver#isCoherentBuildIdentityKey) and the generator would
                // have minted 16 random hex characters for it. A coherent set arrives via
                // Custom, which is how a device template applies one.
                PkIdentifierItem("build_model", "Build Model", PkRuleType.REAL),
                PkIdentifierItem("build_brand", "Build Brand", PkRuleType.REAL),
                PkIdentifierItem("build_manufacturer", "Build Manufacturer", PkRuleType.REAL),
                PkIdentifierItem("build_fingerprint", "Build Fingerprint", PkRuleType.REAL),
                PkIdentifierItem("build_board", "Build Board", PkRuleType.REAL),
                PkIdentifierItem("build_device", "Build Device", PkRuleType.REAL),
                PkIdentifierItem("build_product", "Build Product", PkRuleType.REAL),
                PkIdentifierItem("build_hardware", "Build Hardware", PkRuleType.REAL),
                PkIdentifierItem("build_soc_manufacturer", "SoC Manufacturer", PkRuleType.REAL),
                PkIdentifierItem("build_soc_model", "SoC Model", PkRuleType.REAL),
                PkIdentifierItem("build_id", "Build ID", PkRuleType.REAL),
                PkIdentifierItem("build_type", "Build Type", PkRuleType.REAL),
                PkIdentifierItem("build_tags", "Build Tags", PkRuleType.REAL),
                PkIdentifierItem("build_display", "Build Display", PkRuleType.REAL),
                PkIdentifierItem("build_bootloader", "Build Bootloader", PkRuleType.REAL),
                PkIdentifierItem("build_host", "Build Host", PkRuleType.REAL),
                PkIdentifierItem("build_user", "Build User", PkRuleType.REAL),
                // Same reasoning as the coherent block above, via the other backend narrowing:
                // PrivacyKitRuleResolver#isAbiOrRadioKey offers these four only Real, Custom and
                // (where FieldSpec.rejectEmpty allows it) Empty, so a Static default preselected
                // a row the picker does not draw.
                PkIdentifierItem("build_radio_version", "Build Radio Version", PkRuleType.REAL),
                PkIdentifierItem("supported_abis", "Supported ABIs", PkRuleType.REAL),
                PkIdentifierItem("supported_32_bit_abis", "Supported 32-bit ABIs", PkRuleType.REAL),
                PkIdentifierItem("supported_64_bit_abis", "Supported 64-bit ABIs", PkRuleType.REAL),
            ),
        ),
        PkIdentifierGroup(
            title = "OS Version",
            items = listOf(
                PkIdentifierItem("os_version_release", "Android Version (RELEASE)", PkRuleType.REAL),
                PkIdentifierItem("os_version_incremental", "Build Incremental Version", PkRuleType.REAL),
                PkIdentifierItem("os_sdk_int", "SDK / API Level (SDK_INT)", PkRuleType.REAL),
                PkIdentifierItem("os_security_patch", "Security Patch Level", PkRuleType.REAL),
                PkIdentifierItem("os_codename", "Version Codename", PkRuleType.REAL),
                PkIdentifierItem("os_base_os", "Base OS", PkRuleType.REAL),
                PkIdentifierItem("kernel_version", "Kernel Version (Java os.version)", PkRuleType.REAL),
            ),
        ),
        PkIdentifierGroup(
            title = "Timestamps",
            items = listOf(
                PkIdentifierItem("first_install_time", "App Install Time", PkRuleType.REAL),
                PkIdentifierItem("last_boot_time", "Last Boot Time", PkRuleType.REAL),
            ),
        ),
        PkIdentifierGroup(
            title = "Device Trust Signals",
            items = listOf(
                // Real, not Static. These land in an int-valued Settings row, so
                // the only substitutes that mean anything are a Custom "0"/"1"
                // and Empty; [supportedRuleTypesFor] no longer offers Static for
                // them, and a default the picker does not draw is its own small
                // lie. Turning the flag off for an app is a Custom "0" - which
                // is exactly what the Device Trust Signals switches in
                // PrivacyKitAppControlsScreen write.
                PkIdentifierItem("adb_enabled", "ADB Debugging Flag", PkRuleType.REAL),
                PkIdentifierItem("developer_options_enabled", "Developer Options Flag", PkRuleType.REAL),
            ),
        ),
        PkIdentifierGroup(
            title = "Location Consistency",
            items = listOf(
                PkIdentifierItem("device_timezone", "Timezone", PkRuleType.REAL),
                PkIdentifierItem("device_locale", "Locale", PkRuleType.REAL),
            ),
        ),
        PkIdentifierGroup(
            title = "Network Identity",
            items = listOf(
                PkIdentifierItem("wifi_mac", "WiFi MAC", PkRuleType.STATIC),
                PkIdentifierItem("bluetooth_mac", "Bluetooth MAC", PkRuleType.STATIC),
                PkIdentifierItem("wifi_bssid", "WiFi BSSID", PkRuleType.STATIC),
                PkIdentifierItem("wifi_ssid", "WiFi SSID", PkRuleType.REAL),
                PkIdentifierItem("device_name", "Device name", PkRuleType.REAL),
            ),
        ),
        PkIdentifierGroup(
            title = "Telephony Identity",
            items = listOf(
                PkIdentifierItem("imei", "IMEI", PkRuleType.STATIC),
                PkIdentifierItem("meid", "MEID", PkRuleType.STATIC),
                PkIdentifierItem("imsi", "Subscriber ID", PkRuleType.STATIC),
                PkIdentifierItem("iccid", "ICCID", PkRuleType.STATIC),
                PkIdentifierItem("phone_number", "Phone Number", PkRuleType.STATIC),
                // The carrier family defaults to Real, not Static, for the same
                // reason the coherent build keys do: [supportedRuleTypesFor]
                // does not offer Static for any of them (no generator case, and
                // the hooks' shape checks discard 16 hex characters), so a
                // Static default preselected a radio row the picker does not
                // draw. A carrier identity arrives coherently via Custom.
                PkIdentifierItem("sim_operator", "SIM Operator (MCC+MNC)", PkRuleType.REAL),
                PkIdentifierItem("sim_operator_name", "SIM Operator Name", PkRuleType.REAL),
                PkIdentifierItem("sim_carrier_id", "SIM Carrier ID", PkRuleType.REAL),
                PkIdentifierItem("network_operator", "Network Operator (MCC+MNC)", PkRuleType.REAL),
                PkIdentifierItem("network_operator_name", "Network Operator Name", PkRuleType.REAL),
                PkIdentifierItem("sim_country_iso", "SIM Country ISO", PkRuleType.REAL),
                PkIdentifierItem("network_country_iso", "Network Country ISO", PkRuleType.REAL),
            ),
        ),
        PkIdentifierGroup(
            title = "Privacy Restrictions",
            items = listOf(
                PkIdentifierItem("accounts", "Device Accounts", PkRuleType.REAL),
                PkIdentifierItem("bluetooth_bonded_devices", "Bluetooth Bonded Devices", PkRuleType.REAL),
                PkIdentifierItem("wifi_scan_results", "WiFi Scan Results", PkRuleType.REAL),
                PkIdentifierItem("wifi_configured_networks", "WiFi Configured Networks", PkRuleType.REAL),
                PkIdentifierItem("cell_info", "Cell Info / Tower Location", PkRuleType.REAL),
                PkIdentifierItem("contacts", "Contacts", PkRuleType.REAL),
                PkIdentifierItem("call_log", "Call Log", PkRuleType.REAL),
                PkIdentifierItem("sms", "SMS / MMS Messages", PkRuleType.REAL),
                PkIdentifierItem("calendar", "Calendar", PkRuleType.REAL),
                // Block-only, and the label says so in the list itself rather than relying on the
                // user opening the row: no rule type can make an app see a *different* advertising
                // ID, only no advertising ID at all.
                PkIdentifierItem(
                    "advertising_id",
                    "Advertising ID (blocked, not faked)",
                    PkRuleType.REAL,
                ),
            ),
        ),
    )

    val allItems = groups.flatMap { it.items }

    /**
     * Identifier keys the framework genuinely enforces on the API an app would actually call.
     *
     * This set drives whether the UI is allowed to tell the user an identifier is being spoofed,
     * so the bar for membership is deliberately high - and deliberately about *reach*, not about
     * whether a hook exists at all. A key may only appear here when both hold:
     *
     *  1. every rule type the UI offers takes effect for it, and
     *  2. the hook sits on the path the modern, documented, app-facing API actually takes - not
     *     merely on a deprecated sibling or on an internal binder method that nothing calls.
     *
     * When only (1) holds, the key belongs in [partialEnforcementCaveats] instead. Under-claiming
     * is the safe direction for a privacy tool: a key listed here that is not really covered is a
     * lie told to exactly the person who is relying on it.
     *
     * Requirement (2) is not hypothetical. Until the 2026-08 audit this set contained "imei" on
     * the strength of PhoneSubInfoController#getImeiForSubscriber - a method that is declared in
     * IPhoneSubInfo.aidl, is implemented, is hooked, and has no caller anywhere in the tree. The
     * UI therefore told the user the IMEI was hidden while TelephonyManager#getImei() handed back
     * the real one. Every entry below has since been re-verified by reading the hook source and
     * then walking the public API down to it, not by trusting a summary.
     *
     * VERIFIED EVIDENCE, per group:
     *
     *  - android_id: SettingsProvider#getSsaidSettingLocked -> mascaradeSsaidSetting() resolves
     *    "android_id" on every SSAID read (SettingsProvider.java:1889). Cross-process, covers
     *    Settings.Secure.ANDROID_ID, which is the only public way to read it.
     *
     *  - serial: DeviceIdentifiersPolicyService#getSerial (line 108) resolves KEY_SERIAL via
     *    LocalServices. Build#getSerial() is the only public reader and routes here.
     *
     *  - imei: ALL FIVE app-facing IMEI entry points in this tree resolve under "imei":
     *      TelephonyManager#getImei()/getImei(int)   -> ITelephony#getImeiForSlot
     *                                                  (PhoneInterfaceManager.java:3589)
     *      TelephonyManager#getPrimaryImei()         -> ITelephony#getPrimaryImei     (:3609)
     *      TelephonyManager#getDeviceId()            -> ITelephony#getDeviceIdWithFeature (:8026)
     *      TelephonyManager#getDeviceId(int)         -> IPhoneSubInfo#getDeviceIdForPhone
     *                                                  (PhoneSubInfoController.java:129)
     *      TelephonyManager#getTypeAllocationCode()  -> ITelephony#getTypeAllocationCodeForSlot
     *                                                  (:3632), which now derives the TAC from
     *                                                  the *resolved* IMEI instead of the real one
     *    PrivacyKitRuleResolver#genLuhnValidImei emits a 15-digit Luhn-valid IMEI, so the spoofed
     *    value survives both app-side checksum validation and the TAC substring(0, 8).
     *    getMeid()/getMeid(int) are hard `return null` stubs in this Android 16 tree (legacy CDMA
     *    removed), so there is no second device-id API left to leak through.
     *
     *  - imsi: TelephonyManager#getSubscriberId()/(int) -> IPhoneSubInfo
     *    #getSubscriberIdForSubscriber, hooked on both branches (PhoneSubInfoController.java:233
     *    phone.getSubscriberId(), :249 subInfo.getImsi()). Sole public reader.
     *
     *  - iccid: TelephonyManager#getSimSerialNumber()/(int) -> IPhoneSubInfo
     *    #getIccSerialNumberForSubscriber (PhoneSubInfoController.java:279). The other route,
     *    SubscriptionInfo#getIccId(), is redacted to null by SubscriptionManagerService
     *    #conditionallyRemoveIdentifiers (line 861) for any caller without device-identifier
     *    access, so it is not an unhooked leak.
     *
     *  - the build_* / os_* keys: PrivacyKitIdentityInjector, called from
     *    ActivityThread#handleBindApplication before any app code runs. Each has a FieldSpec in
     *    SPECS naming a real, non-constant static field of android.os.Build or Build.VERSION in
     *    this tree, written through the ART Field.accessFlags un-final path. Every String-typed
     *    spec is rejectEmpty=false, so applyOneField writes the value for every rule type the
     *    picker offers, RULE_EMPTY included - which is what lets them be claimed as fully
     *    enforced.
     *
     *    Re-verified 2026-08 against the r29 backend allow-list, which is the part of this
     *    bullet that had gone stale: PrivacyKitRuleResolver#isRuleTypeAllowed now REFUSES the
     *    random rule types for the twenty-one fingerprint-linked identity keys
     *    (isCoherentBuildIdentityKey -> Real/Custom only) and for the ABI/radio keys
     *    (isAbiOrRadioKey -> Real/Custom/Empty only), returning the real value instead.
     *    resolve() consults it as its very first statement, so those refusals are outputs, not
     *    advice. They do not disqualify the keys, they narrow what may be OFFERED for them, so
     *    [perKeyRuleTypes] now mirrors both refusals exactly. Offering a rule type the backend
     *    refuses is the same class of lie as claiming an unhooked key is spoofed, and until this
     *    pass the catalog was doing exactly that for all twenty-one coherent keys.
     *
     *  - supported_abis / supported_32_bit_abis / supported_64_bit_abis: same injector path, and
     *    PROMOTED into this set in this pass with zero backend change. Their only stated gap was
     *    a rule-type gap: the generator has no ABI case, so the random types produced 16 hex
     *    characters that PrivacyKitIdentityInjector#parseAbiList rejects, keeping the real list.
     *    The backend now refuses those same rule types itself, so the honest fix is to stop
     *    offering them rather than to invent a random ABI generator - a fake ABI list breaks
     *    native library loading, which is the whole reason parseAbiList rejects one. What is
     *    left genuinely takes effect: Real, and a Custom list of real ABI names (what a device
     *    template supplies). Empty is offered only for the 32/64-bit lists, whose FieldSpec has
     *    rejectEmpty=false so parseAbiList really does write an empty array; SUPPORTED_ABIS has
     *    rejectEmpty=true (no real device has an empty primary ABI list) so Empty is not offered
     *    for it. A Custom value that is not a list of real ABI names is discarded and the real
     *    list kept - the same plausibility narrowing device_timezone carries, stated in
     *    [perKeyRuleTypes]. build_radio_version, the fourth SPECS entry not listed here, stays
     *    out: see [partialEnforcementCaveats].
     *
     *  - contacts / call_log / sms / calendar / bluetooth_bonded_devices: PrivacyKitService
     *    #applyRestrictionRule() maps RULE_EMPTY onto an AppOps UID mode of MODE_IGNORED for
     *    OP_READ_CONTACTS / OP_READ_CALL_LOG / OP_READ_SMS / OP_READ_CALENDAR /
     *    OP_BLUETOOTH_CONNECT (restrictionOpForControl, PrivacyKitService.java:378-388). All five
     *    are their own switch op, and the platform's soft-denial path returns an empty result
     *    rather than throwing (ContentProvider.Transport#query hands back an empty MatrixCursor;
     *    AdapterServiceBinder#getBondedDevices returns Collections.emptyList()). The bridge is
     *    re-applied on profile create/activate/delete, on clearProfile, on backup restore, and at
     *    PHASE_ACTIVITY_MANAGER_READY, so restrictions follow the active profile and survive
     *    reboot. "accounts" is the sixth RESTRICTION_KEY but is NOT listed here - see
     *    [partialEnforcementCaveats].
     *
     *  - advertising_id: a BLOCK, not a spoof, and the only key in this set enforced by refusing a
     *    bind rather than by substituting a value. Verified 2026-08 by reading the whole chain,
     *    not a summary:
     *      PrivacyKitService.KEY_ADVERTISING_ID = "advertising_id" (:157) is a member of
     *      RESTRICTION_KEYS (:160) and restrictionControlForKey() maps it to
     *      CONTROL_BLOCK_ADVERTISING_ID (:672);
     *      setIdentifierRule() -> applyRestrictionRule() (:858) -> setBooleanControlInternal(),
     *      which persists to "privacykit_block_advertising_id_list" and refreshes the in-memory
     *      snapshot through setAdvertisingIdBlockCached();
     *      ActiveServices#retrieveServiceLocked (:5298) then returns null - deliberately null and
     *      not a ServiceLookupResult, which would surface as a SecurityException - for any bind
     *      whose action is "com.google.android.gms.ads.identifier.service.START", so
     *      Context#bindService() returns false and AdvertisingIdClient#getAdvertisingIdInfo()
     *      cannot fetch an id at all;
     *      the snapshot is re-primed at PHASE_ACTIVITY_MANAGER_READY, and the RULE_EMPTY bridge is
     *      re-derived on profile switch/delete/restore like every other RESTRICTION_KEY.
     *    Because the offered rule set is narrowed to Real/Empty (see [restrictionKeys]), both
     *    offered rule types genuinely take effect, which is what qualifies it for this set. The
     *    honest-wording obligation that comes with it lives in [restrictionNotes] and MUST be
     *    shown on the row: the app is refused the id, it is never handed a fake one, the refusal
     *    is detectable by an app whose other Play services calls keep working, and it does not
     *    retract an id the app cached before the block was switched on.
     *
     * Known narrower-than-the-label caveats that do NOT disqualify a key, but are worth knowing:
     *  - Every injector-backed key is a Java-side override inside the app's own process only:
     *    SystemProperties.get(), __system_property_get() from native/NDK code, /system/build.prop
     *    and the WebView user-agent string all still report the real device.
     *  - imei: TelephonyManager#getTypeAllocationCode() carries no callingPackage parameter, so
     *    PhoneInterfaceManager#getStrictCallingPackageName() derives the caller from its UID and
     *    deliberately gives up when the UID maps to more than one package. A shared-UID app (or
     *    any caller whose UID cannot be resolved) therefore still gets the *real* TAC - eight
     *    digits of the real IMEI, and a way to detect the spoof by diffing it against the spoofed
     *    getImei(). The 15-digit IMEI itself is still spoofed on every path for those callers.
     *  - imei / imsi / iccid: package-keyed spoofing means that if two packages share a UID, only
     *    the package name the app actually passes is spoofed. Inherent to the design.
     *  - bluetooth_bonded_devices is enforced by denying BLUETOOTH_CONNECT, which does empty the
     *    bonded-device list but also removes the app's other connect-class Bluetooth access
     *    (device names, createBond, profile connections).
     *
     * Deliberately NOT here, and why (so nobody "helpfully" adds them back):
     *  - phone_number: now hooked on BOTH the deprecated TelephonyManager.getLine1Number() and the
     *    modern SubscriptionManager.getPhoneNumber() (SubscriptionManagerService#getPhoneNumber and
     *    #getPhoneNumberFromFirstAvailableSource), but SubscriptionInfo#getNumber() is a second,
     *    still-live reader that returns the real MSISDN to any caller holding READ_PHONE_NUMBERS.
     *    See [partialEnforcementCaveats].
     *  - kernel_version: hooked, but Empty / Restricted is silently discarded. Same reference.
     *  - wifi_mac: PrivacyKitKeys.KEY_WIFI_MAC and PrivacyKitRuleResolver.KEY_WIFI_MAC both exist
     *    and the resolver even has a MAC generator, but there is NO resolveIdentifier() call site
     *    for it anywhere in the tree (the hook was reverted - mainline module API boundary). The
     *    constant existing is not evidence of enforcement.
     *  - meid: no hook, but TelephonyManager#getMeid() returns null unconditionally in this tree,
     *    so there is nothing to hide. The row is correctly shown as not enforced.
     *  - os_sdk_int: PrivacyKitIdentityInjector deliberately refuses to touch Build.VERSION#SDK_INT
     *    (see its "DELIBERATELY NOT SPOOFED" block) - spoofing it makes AndroidX compat shims call
     *    APIs that do not exist. Editable, stored, never applied, by design.
     *  - media_drm_id: gained a real resolveIdentifier() call site in 2026-08, but only on the
     *    Java/SDK path. r31 added the second hook that would close the stated gap
     *    (NdkMediaDrm.cpp#maybeSpoofDeviceUniqueId, on the NDK path), and it is NOT enough to
     *    promote the key: it is gated behind persist.sys.privacykit.drm_ndk, which defaults to
     *    FALSE (NdkMediaDrm.cpp:743) and has not been verified on device. A hook that is
     *    compiled in but switched off is not enforcement. The caveat now names the flag instead
     *    of claiming the NDK path is simply unhooked; see [partialEnforcementCaveats].
     *  - gsf_id: hooked on the gservices provider query AND now has a generator
     *    (PrivacyKitIdentifierGenerator#genGsfId emits a 19-digit decimal long the hook accepts), so
     *    Static / Random / Custom take effect on the provider path. It is PARTIAL, not FULL, because
     *    the IGmsServiceBroker transport, GmsCore's own in-process reads and the cross-user authority
     *    form are uncovered - see [partialEnforcementCaveats], to which it moved out of
     *    [notEnforcedReasons] once the generator landed.
     *  - device_timezone: injector-backed (PrivacyKitIdentityInjector#applyTimeZone), FULL, moved
     *    INTO enforcedKeys below. Was previously mislisted here as having no call site.
     *  - device_locale: injector-backed (PrivacyKitIdentityInjector#applyLocale) but PARTIAL - the
     *    resource Configuration keeps the real locale. See [partialEnforcementCaveats]. Was
     *    previously mislisted here as having no call site.
     *  - app_set_id: DOES have a live resolveIdentifier() call site as of r33
     *    (AppSetIdManager.java:142-168, reached by reflection from inside the AdServices mainline
     *    module), so the blanket "no call site" claim that used to cover it is stale - and it is
     *    still not enforced, for a sharper reason. The hook sits on
     *    android.adservices.appsetid.AppSetIdManager, while apps read the App Set ID through
     *    com.google.android.gms.appset.AppSetIdClient, which reaches Google Play services over
     *    its own connection and never enters that API. On-device verification found the value an
     *    app reads unchanged. (AdServices is also an updatable module, so a device carrying the
     *    Google-signed build does not even contain the hook.) Promoting it on the strength of the
     *    hook existing would repeat the 2026-08 IMEI failure described above - hooking a method
     *    with no real callers and then telling the user the identifier was hidden. It stays NONE,
     *    with a specific reason in [notEnforcedReasons] rather than the generic copy.
     *  - firebase_installation_id / firebase_app_instance_id: not "no hook yet" but "no hook is
     *    possible". The Firebase SDK mints both inside the app's own process and stores them in
     *    the app's own data directory; the value never crosses a binder, a ContentProvider or a
     *    system property on its way to being used, so a ROM-side framework has no interception
     *    point at all. See [notEnforcedReasons].
     *  - kernel_version, meid, last_boot_time, wifi_mac, bluetooth_mac, os_sdk_int: also NONE,
     *    and also for reasons the generic "no framework hook yet" copy would misrepresent - two
     *    of them (meid, wifi_mac) are cases where the platform already hands every ordinary app
     *    nothing or a fixed constant, i.e. a win being displayed as a failure. Each has an entry
     *    in [notEnforcedReasons].
     *  - wifi_bssid, wifi_ssid, hostname, first_install_time, adb_enabled,
     *    developer_options_enabled, the sim_* / network_* keys, wifi_scan_results,
     *    wifi_configured_networks, cell_info: no resolveIdentifier() call site and no
     *    RESTRICTION_KEYS entry. Confirmed by grepping every resolveIdentifier( caller in
     *    frameworks/base, frameworks/opt/telephony and packages/services/Telephony. These are the
     *    genuinely pending keys - a hook point is known for each and the work simply is not done
     *    - so the generic "not enforced yet" copy is the truthful thing to show for them, and
     *    they deliberately get no [notEnforcedReasons] entry.
     *
     * Key-string audit (a typo here silently matches no rule and enforces nothing): every string
     * in this set, in [partialEnforcementCaveats], in [notEnforcedReasons], in [restrictionNotes]
     * and in [restrictionKeys] was compared character-for-character against its backend constant -
     * android_id / serial / imei / imsi / iccid / phone_number in PrivacyKitKeys.java and
     * PrivacyKitRuleResolver.java; the build_* and os_* keys against PrivacyKitKeys.INJECTOR_KEYS
     * and the SPECS table in PrivacyKitIdentityInjector.java; kernel_version against the
     * KEY_KERNEL_VERSION passed to resolveIdentifier() in that file's applyKernelVersion(); and
     * contacts / call_log / sms / calendar / accounts / bluetooth_bonded_devices /
     * advertising_id against PrivacyKitService.RESTRICTION_KEYS.
     *
     * Re-audited 2026-08 for the three keys added this round, each against the constant the hook
     * actually passes rather than against a report:
     *   "media_drm_id"    == PrivacyKitKeys.KEY_MEDIA_DRM_ID (PrivacyKitKeys.java:73)
     *                     == PrivacyKitRuleResolver.KEY_MEDIA_DRM_ID (:40)
     *   "gsf_id"          == PrivacyKitKeys.KEY_GSF_ID (PrivacyKitKeys.java:104). The format case
     *                        keyed on that constant lives in PrivacyKitIdentifierGenerator#generate
     *                        (genGsfId); PrivacyKitRuleResolver delegates all generation there, so it
     *                        needs no matching constant of its own. gsf_id is PARTIAL - see
     *                        [partialEnforcementCaveats]
     *   "advertising_id"  == PrivacyKitService.KEY_ADVERTISING_ID (:157), the literal used in
     *                        RESTRICTION_KEYS and restrictionControlForKey()
     * All match, and every one of them is also a real PkIdentifierItem key in the catalog above.
     *
     * ---------------------------------------------------------------------------------------
     * THIS IS THE *DECLARED* SET, NOT THE ANSWER. Read [enforcedKeys] for that.
     *
     * Everything above is a hand-maintained claim, and the 2026-08 audit found that nine of the
     * claims below had been contradicted by another map in this same file without anyone noticing:
     * phone_number, build_radio_version, accounts, device_locale, sim_operator, sim_operator_name,
     * sim_country_iso and sim_carrier_id were listed here AND in [partialEnforcementCaveats] (whose
     * doc says the two sets are "guaranteed disjoint by construction"), and meid was listed here
     * AND in [notEnforcedReasons] / [notPossibleReasons] with the text "there is nothing left to
     * hide". Because [enforcementOf] tested this set first, all nine rendered as FULL - fully
     * enforced - while the very caveat this file stores for them says an app can still read the
     * real value. Six of the nine even have a "deliberately NOT here, and why" bullet above saying
     * they are absent from this set. They were not.
     *
     * That is the drift this area exists to catch, and it drifted anyway, because the invariant was
     * a sentence in a doc comment rather than a line of code. So it is a line of code now:
     * [enforcedKeys] SUBTRACTS the caveat maps from this declaration, which makes a contradiction
     * resolve to the conservative answer automatically and permanently. Adding a key here that has
     * a caveat entry no longer over-claims; it just does nothing, which is the correct failure
     * direction for a privacy tool.
     *
     * To promote a key for real, delete its entry from [partialEnforcementCaveats] or
     * [notEnforcedReasons] in the same commit that adds it here - i.e. state, in writing, that the
     * gap that caveat describes is closed. That is one visible edit instead of a silent one.
     * ---------------------------------------------------------------------------------------
     */
    private val declaredEnforcedKeys = setOf(
        // Cross-process hooks (SettingsProvider / DeviceIdentifiersPolicyService).
        "android_id", "serial",
        // Settings-table device name: Global.device_name + Secure.bluetooth_name,
        // both resolved through the one key so they cannot disagree.
        "device_name",
        // Telephony hooks - every app-facing API that can return the value is covered.
        // NOTE: phone_number is deliberately absent; see partialEnforcementCaveats.
        "imei", "imsi", "iccid",
        // In-process Build.* identity (PrivacyKitIdentityInjector) - the coherent template eight.
        "build_fingerprint", "build_model", "build_manufacturer", "build_brand",
        "build_device", "build_product", "build_board", "build_hardware",
        // In-process Build.* identity - the remaining String-typed fields.
        // NOTE: build_radio_version is deliberately absent; see partialEnforcementCaveats.
        "build_id", "build_type", "build_tags", "build_display", "build_bootloader",
        "build_host", "build_user", "build_soc_manufacturer", "build_soc_model",
        // In-process Build.* ABI lists. Promoted 2026-08 with no backend change: the only gap
        // was that the picker offered random rule types the backend refuses outright
        // (PrivacyKitRuleResolver#isAbiOrRadioKey) and the injector would have discarded anyway,
        // so [perKeyRuleTypes] narrows them to what genuinely applies - Real and a Custom ABI
        // list for all three, plus Empty for the 32/64-bit lists only, whose FieldSpec has
        // rejectEmpty=false. Every offered rule type then takes effect, which is what membership
        // of this set means.
        "supported_abis", "supported_32_bit_abis", "supported_64_bit_abis",
        // In-process Build.VERSION.* identity.
        // NOTE: kernel_version is deliberately absent; see partialEnforcementCaveats.
        "os_version_release", "os_version_incremental", "os_security_patch",
        "os_codename", "os_base_os",
        // In-process default time zone (PrivacyKitIdentityInjector#applyTimeZone). Only Real and
        // Custom are offered (perKeyRuleTypes) and both take effect: setDefault propagates to
        // TimeZone.getDefault, android.icu and java.time's ZoneId.systemDefault. The persist.sys.
        // timezone property is NOT rewritten, so a SystemProperties / TimeManager / native reader
        // still sees the real zone - the same one-process scope every injector key carries.
        // device_locale is deliberately NOT here (PARTIAL - resource config); see the caveats map.
        "device_timezone",
        // AppOps-backed privacy restrictions (RULE_EMPTY -> MODE_IGNORED).
        // NOTE: accounts is deliberately absent; see partialEnforcementCaveats.
        "contacts", "call_log", "sms", "calendar", "bluetooth_bonded_devices",
        // Bind-refusal restriction (RULE_EMPTY -> the dedicated GMS advertising-ID bind is
        // denied). Enforced, but a BLOCK and not a spoof - restrictionNotes carries the sentence
        // the UI is obliged to show, and restrictionKeys narrows the picker to Real/Empty so no
        // value-substitution rule type is ever offered for it.
        "advertising_id",
        // Promoted 2026-08 after each gained a verified framework delivery hook:
        // telephony (PhoneSubInfoController/SubscriptionManagerService), the six device-state
        // flags (SettingsProvider single-name + whole-table paths), first_install_time
        // (ComputerEngine), device_locale (LocaleManager per-app), build_radio_version (injector).
        // NOTE (2026-08 audit): of the keys in this "promoted" block, meid,
        // phone_number, accounts, device_locale, build_radio_version and the
        // carrier family are all subtracted again by [enforcedKeys], because
        // each also carries a caveat entry saying an app can still read the
        // real value. The declaration is left intact rather than edited away so
        // the disagreement stays visible: whoever closes one of those gaps
        // deletes the caveat and the key promotes itself.
        "meid", "phone_number",
        "adb_enabled", "developer_options_enabled", "wireless_debugging",
        "package_verifier", "usb_app_verification", "accessibility_services",
        "first_install_time", "device_locale", "build_radio_version",
        // Wired 2026-08 via the in-app TelephonyManager choke point (operators) and the
        // TelephonyRegistry onCellInfoChanged callback path (cell_info, RULE_EMPTY).
        "sim_operator", "sim_operator_name", "sim_country_iso",
        "network_operator", "network_operator_name", "cell_info",
        // Wired 2026-08 in packages/services/Telephony (PhoneInterfaceManager) and
        // AccountManagerService (accounts, RULE_EMPTY).
        "network_country_iso", "sim_carrier_id", "accounts",
    )

    /**
     * Keys that have a real, working hook which nevertheless does not cover the whole identifier,
     * mapped to the precise reason. This is the single source of truth for [partiallyEnforcedKeys]
     * so the set and its justification can never drift apart.
     *
     * These keys are treated as NOT enforced everywhere in the UI today, which is the correct
     * conservative default - a partially covered identifier must never look fully covered. The map
     * exists so that a later pass can surface a distinct third state ("partly enforced" plus the
     * specific reason) instead of lumping them in with keys that have no hook at all.
     *
     * The strings below are developer-facing English, NOT localized: surfacing them verbatim in
     * the UI would ship untranslated text. A real UI treatment needs matching R.string entries;
     * these are the source text for them, and are what a bug report or `adb shell dumpsys` style
     * dump should quote.
     */
    val partialEnforcementCaveats = mapOf(
        // Verified 2026-08: the two deprecated TelephonyManager#getLine1Number(int) branches
        // (ITelephony#getLine1NumberForDisplay at PhoneInterfaceManager.java:7508 and
        // IPhoneSubInfo#getLine1NumberForSubscriber at PhoneSubInfoController.java:299) AND the
        // modern SubscriptionManager#getPhoneNumber(int[, source]) path are now hooked - the latter
        // in SubscriptionManagerService#getPhoneNumber and #getPhoneNumberFromFirstAvailableSource,
        // each wrapping getPhoneNumberFromSourceInternal's result through resolveTelephony(pkg,
        // KEY_PHONE_NUMBER, ...) exactly as PhoneSubInfoController already does. What is still open
        // is a THIRD reader: SubscriptionInfo#getNumber(), populated by
        // SubscriptionManagerService#conditionallyRemoveIdentifiers (:846), which returns the real
        // MSISDN unredacted to any caller that holds READ_PHONE_NUMBERS (it only nulls the number
        // for callers WITHOUT phone-number access). Closing that means substituting the number
        // inside conditionallyRemoveIdentifiers - a security-sensitive redaction path exercised by
        // every getActiveSubscriptionInfo* / getAllSubInfoList read - so it is deliberately left for
        // a change that can be built and tested, and phone_number stays PARTIAL until then.
        "phone_number" to
            "The deprecated TelephonyManager.getLine1Number() and the modern " +
            "SubscriptionManager.getPhoneNumber() are both spoofed. SubscriptionInfo.getNumber() " +
            "is a third reader that still returns the real number to apps holding the phone-number " +
            "permission, so a caller using it can read the real MSISDN and detect the spoof.",

        // Found by the 2026-08 re-verification, NOT by the original audit: kernel_version does not
        // go through the SPECS table (it is not a Build field) but through
        // PrivacyKitIdentityInjector#applyKernelVersion, which hardcodes
        //   if (resolved == null || resolved.equals(realValue) || resolved.trim().isEmpty()) return;
        // Unlike the SPECS path there is no rejectEmpty flag to turn off, so RULE_EMPTY - which
        // PrivacyKitRuleResolver.resolve() returns as "" for every key - is ALWAYS silently
        // discarded and the app keeps reading the real kernel release string. Five of the six rule
        // types (Real/Static/Random-daily/Random-per-launch/Custom) do apply and really do rewrite
        // System.getProperty("os.version"), so the hook is real; it just is not the "every rule
        // type applies" that enforcedKeys promises. Promoting this key requires either giving
        // applyKernelVersion a rejectEmpty=false equivalent or removing Empty from the UI for it.
        // kernel_version intentionally has NO caveat entry: it is not enforced at
        // all. The applyKernelVersion() hook was removed, and it could never have
        // worked - libcore registers "os.version" in unchangeableProps
        // (System.java:1090) and the live Properties table silently refuses the
        // write, so System.setProperty("os.version", ...) is a no-op. Listing it as
        // partially enforced told the user a spoof was taking effect when none was.
        "build_radio_version" to
            "Overrides the deprecated Build.RADIO field, which really is spoofed. " +
            "Build.getRadioVersion() recomputes from TelephonyProperties on every call and " +
            "still reports the real baseband.",

        // The three ABI lists USED to live here, with the caveat "only a Custom value that is
        // a genuine ABI list is applied; the random rule types generate hex, which parseAbiList
        // rejects". That was a rule-type gap rather than a coverage gap, and it was closed in
        // 2026-08 by removing the rule types that never worked instead of by inventing a random
        // ABI generator - a fake ABI list breaks native library loading, which is precisely why
        // parseAbiList rejects one. The backend refuses those rule types itself
        // (PrivacyKitRuleResolver#isAbiOrRadioKey), [perKeyRuleTypes] mirrors that, and the keys
        // moved to [enforcedKeys]. Nothing about what reaches an app changed; the claim stopped
        // being wider than the mechanism.

        // Verified 2026-08 by reading the hook, not a report. MediaDrm#getPropertyByteArray
        // (MediaDrm.java:2290) calls the real plugin FIRST through the renamed native method
        // getPropertyByteArrayNative - the JNI table now binds that Java name to the unchanged
        // implementation (android_media_MediaDrm.cpp:2209-2210) - so a plugin that rejects the
        // property still throws before any PrivacyKit code runs. Only for
        // PROPERTY_DEVICE_UNIQUE_ID does it then route through resolveDeviceUniqueId() ->
        // IPrivacyKitManager#resolveIdentifier(pkg, "media_drm_id", <real blob as lower-case hex>),
        // and the returned string is decoded by parseHexString() and genuinely returned to the
        // app. So this is a real substitution on the documented SDK API, reached by the ordinary
        // MediaDrm.getPropertyByteArray(PROPERTY_DEVICE_UNIQUE_ID) call apps actually make.
        //
        // The gap that keeps it out of enforcedKeys is an entire second app-facing API: the
        // NDK entry point AMediaDrm_getPropertyByteArray (frameworks/av/media/ndk/
        // NdkMediaDrm.cpp:725, exported at libmediandk.map.txt:304) reaches libmediadrm without
        // passing through Java at all.
        //
        // Re-verified 2026-08 (r31): that path now HAS a hook -
        // NdkMediaDrm.cpp#maybeSpoofDeviceUniqueId JNI up-calls
        // MediaDrm.resolveDeviceUniqueIdNative([B)[B - and the key still does not qualify for
        // enforcedKeys, for reasons that are about what reaches a device rather than what exists
        // in the tree:
        //   - it is gated on persist.sys.privacykit.drm_ndk, which defaults to false
        //     (NdkMediaDrm.cpp:743), so on a stock build of this ROM the NDK path returns the
        //     real Widevine id exactly as before;
        //   - the flag has not been verified on device, so even flipping it is not evidence;
        //   - and with the flag on there is a residual, smaller gap: a pure-native or detached
        //     thread has no JNIEnv, so the up-call is skipped (:757) and that caller reads the
        //     real blob.
        // A native caller can therefore still read the real id and detect the spoof by comparing
        // what the two APIs return. If the flag is ever defaulted on and verified, this key can
        // move to enforcedKeys with the JNIEnv caveat kept - not before.
        //
        // Two smaller, deliberate narrowings, stated so nobody assumes more than is true: a caller
        // whose UID is below Process.FIRST_APPLICATION_UID keeps the real id (the same boundary
        // PrivacyKitIdentityInjector uses), and a substitute that is not hexadecimal of exactly
        // the real blob's byte length is discarded in favour of the real value, because apps
        // size-check this blob.
        //
        // Rule types: PrivacyKitRuleResolver#isRuleTypeAllowed (:74) refuses RULE_PER_LAUNCH,
        // RULE_DAILY and RULE_EMPTY for this key, and resolve() consults it as its very first
        // statement (:88), returning the real value on refusal. That is the real enforcement
        // point; [supportedRuleTypesFor] mirrors it so the picker does not offer what the backend
        // throws away.
        "media_drm_id" to
            "Spoofed for apps that read it through the Java MediaDrm API, using Real, Static or " +
            "Custom. The NDK path AMediaDrm_getPropertyByteArray bypasses Java entirely; this " +
            "build contains a hook for it too, but that hook is switched off by default " +
            "(persist.sys.privacykit.drm_ndk) and is skipped on threads with no JNI environment, " +
            "so a native caller normally still reads the real Widevine ID - and can detect the " +
            "spoof by comparing what the two APIs return. Per-launch, Daily and Empty are " +
            "refused for this key: a rotating ID burns streaming device-registration slots, and " +
            "an empty one breaks apps that size-check the value.",

        // Verified 2026-08: "accounts" is a real RESTRICTION_KEYS entry and PrivacyKitService
        // denies OP_GET_ACCOUNTS for the UID, but AccountManagerService only consults that op in
        // resolveAccountVisibility()'s VISIBILITY_UNDEFINED fallback, and only for pre-O callers.
        // A stored per-package visibility entry, a signature match or profile-owner status all
        // short-circuit it first, and getTypesVisibleToCaller() ignores app ops entirely - so a
        // modern app's account list is not reliably emptied.
        "accounts" to
            "The GET_ACCOUNTS app-op is denied, but AccountManager only consults it in a " +
            "legacy fallback path, so a modern app's account list is not reliably emptied.",

        // Verified 2026-08 by reading PrivacyKitIdentityInjector#applyLocale: LocaleList#setDefault
        // moves Locale.getDefault, LocaleList.getDefault and the ICU default, which covers what an
        // app reads with Locale.getDefault() and every locale-sensitive formatter. It is PARTIAL by
        // construction, and - stated plainly because it matters - by CHOICE: applyLocale's own
        // comment records that rewriting the app's resource Configuration would switch the app's
        // entire UI language and that any mistake in that plumbing corrupts resource loading
        // rather than merely leaking an identifier. So Resources.getConfiguration().getLocales() -
        // a very common, permission-free app-facing API - still reports the real locale, and an
        // app that reads it can detect the spoof. That is a real, stated limit, not a defect
        // waiting to be fixed; do not "close" it without solving the resource problem first.
        //
        // Rule types updated 2026-08 (r29): PrivacyKitIdentifierGenerator now has a real
        // KEY_DEVICE_LOCALE case backed by LOCALE_POOL (26 plausible BCP-47 tags), so Static,
        // Random-daily and Random-per-launch produce tags that parseLocaleTag accepts and now
        // genuinely take effect. The old note here - "the resolver has no locale generator, so
        // the random rule types produce hex that the injector rejects" - was true when it was
        // written and is now stale. Empty is still not offered: applyLocale early-returns on an
        // empty candidate, and there is no such thing as an empty locale.
        "device_locale" to
            "Moves Locale.getDefault() and the ICU/formatter locale, so formatting, dates and " +
            "Locale.getDefault() all follow the spoofed locale. The app's resource configuration " +
            "is deliberately left alone so apps keep loading the translations they ship, which " +
            "means Resources.getConfiguration().getLocales() still reports the real locale and " +
            "an app that reads it can tell.",

        // Verified 2026-08 end to end: PrivacyKitIdentifierGenerator#genGsfId now emits a 19-digit
        // decimal long, and the gservices hook (ContentProviderProxy#query/#call, guarded by
        // isDecimalLong) accepts it, so Static / Random-daily / Random-per-launch / Custom all
        // substitute the GSF ID on the provider path (Empty is inert - "" fails the decimal guard -
        // and is not offered; see perKeyRuleTypes). It is PARTIAL because a provider-layer hook
        // cannot cover the IGmsServiceBroker bound-service transport, GmsCore's own in-process reads
        // (they never cross the proxy), or the cross-user content://<userId>@... authority form.
        // Re-checked 2026-08 against r31: the LoadedApk#doConnected binder-wrap technique that
        // PrivacyKitGmsBinderFilter uses would in principle reach the broker transport, and
        // is deliberately NOT applied here - the ad-id leaf binder is a single-purpose service,
        // whereas IGmsServiceBroker is the shared GMS binder that also carries Auth, Maps and
        // Play Integrity, so wrapping it risks far more than this key is worth. gsf_id stays
        // PARTIAL by choice, not for want of a technique.
        "gsf_id" to
            "The Google Services Framework ID is substituted for apps that read it through the " +
            "gservices provider query, using Real, Static, Random or a plain-decimal Custom value. " +
            "Apps that read it through Google Play services directly, rather than the query, still " +
            "get the real ID.",
        // THE CARRIER IDENTITY FAMILY. Re-verified 2026-08 by grepping every KEY_SIM_* /
        // KEY_NETWORK_* call site in the tree, and the old text here was not merely stale, it was
        // BACKWARDS. It said the SubscriptionInfo path was covered and TelephonyManager was not.
        // The truth in this tree is the reverse: the only live hook for sim_operator,
        // sim_operator_name, sim_country_iso, network_operator and network_operator_name is
        // TelephonyManager#resolvePrivacyKitCarrierIdentifier - an in-process choke point on
        // getSimOperator()/getSimOperatorName()/getSimCountryIso()/getNetworkOperator()/
        // getNetworkOperatorName() - and there is no PrivacyKit reference anywhere in
        // frameworks/opt/telephony at all, so SubscriptionManagerService is currently unhooked.
        //
        // The conclusion the old text reached (PARTIAL) survives its reasoning being wrong,
        // because each key still has a second live reader that returns the real carrier:
        //   sim_* -> SubscriptionInfo.getMccString()/getMncString()/getCarrierName()/
        //            getCountryIso()/getCarrierId(), via SubscriptionManager.getActiveSubscriptionInfo()
        //   network_* -> ServiceState.getOperatorNumeric()/getOperatorAlphaLong(), via
        //            TelephonyManager.getServiceState() and the service-state broadcast
        // network_operator and network_operator_name gained an entry here in this pass for exactly
        // that reason: they were being claimed FULL while sitting on the identical mechanism as
        // their sim_* twins, which is not a defensible pair of answers.
        //
        // These promote to FULL when the SubscriptionManagerService / ServiceState readers are
        // hooked too - not before, and not by editing this text.
        "sim_operator" to
            "The SIM MCC+MNC is substituted for apps that read it with " +
            "TelephonyManager.getSimOperator(). An app that reads it from SubscriptionInfo " +
            "instead still gets the real value, and an app that reads both can tell they " +
            "disagree.",
        "sim_operator_name" to
            "The SIM carrier name is substituted for apps that read it with " +
            "TelephonyManager.getSimOperatorName(). An app that reads it from SubscriptionInfo " +
            "instead still gets the real name.",
        "sim_country_iso" to
            "The SIM country is substituted for apps that read it with " +
            "TelephonyManager.getSimCountryIso(). An app that reads it from SubscriptionInfo " +
            "instead still gets the real country.",
        "sim_carrier_id" to
            "The carrier id is substituted for apps that read it with " +
            "TelephonyManager.getSimCarrierId(). An app that reads SubscriptionInfo.getCarrierId() " +
            "instead still gets the real id.",
        "network_operator" to
            "The network MCC+MNC is substituted for apps that read it with " +
            "TelephonyManager.getNetworkOperator(). The same value is also carried in the " +
            "service state, which is not filtered, so an app that reads it from there still " +
            "gets the real operator.",
        "network_operator_name" to
            "The network operator name is substituted for apps that read it with " +
            "TelephonyManager.getNetworkOperatorName(). The same name is also carried in the " +
            "service state, which is not filtered, so an app that reads it from there still " +
            "gets the real name.",

        // Added by the 2026-08 audit, and it is the same shape as the 2026-08 IMEI failure
        // described in [declaredEnforcedKeys]' doc, so it is worth naming plainly. cell_info was
        // listed as fully enforced on the strength of TelephonyRegistry#maybePrivacyKitCellInfo,
        // which is real and does empty the list - but it sits on the CALLBACK path only
        // (onCellInfoChanged, delivered to a registered PhoneStateListener/TelephonyCallback).
        // The two documented, ordinary ways an app reads serving cells -
        // TelephonyManager#getAllCellInfo() and #requestCellInfoUpdate() - land in
        // PhoneInterfaceManager#getAllCellInfo / #requestCellInfoUpdateInternal, and grepping
        // KEY_CELL_INFO across frameworks/base, frameworks/opt/telephony and
        // packages/services/Telephony finds exactly one call site: the registry one. Neither
        // PhoneInterfaceManager path is hooked, so an app that polls instead of registering reads
        // the real serving cells - and an app that does both can see one path empty and the other
        // full, which is a spoof-detector rather than a spoof.
        "cell_info" to
            "The serving-cell list is emptied for apps that receive it through a registered " +
            "telephony callback. An app that asks for it directly with getAllCellInfo() or " +
            "requestCellInfoUpdate() still gets the real cell list, and an app that uses both " +
            "can tell the two disagree.",
    )

    /**
     * Keys that stay [PkEnforcement.NONE] but for which the generic "this build has no framework
     * hook for it yet" explanation would itself be false, mapped to what is really going on.
     *
     * Membership here changes nothing about [enforcementOf]: these keys stay NONE, stay out of
     * every "protected" counter, and keep the de-emphasized treatment. It only replaces generic UI
     * copy with the specific truth.
     *
     * A key belongs here rather than in [partialEnforcementCaveats] when claiming even PARTIAL
     * would overstate what the user actually gets. Three different situations qualify, and the
     * difference matters to the person reading the row:
     *
     *  1. STRUCTURALLY IMPOSSIBLE for a ROM-side framework ([notPossibleReasons]) - the value
     *     never crosses a system boundary (the Firebase IDs), or the platform refuses the write
     *     (kernel_version), or the identifier does not exist on this device (meid), or the
     *     platform already hides it from every app (wifi_mac, bluetooth_mac), or faking it would
     *     break the app (last_boot_time). No amount of future work moves these; a different
     *     product would be needed (in-process injection), which is the standing Tier-1/Tier-2
     *     line. Showing "not enforced yet" for them promises work that will never come.
     *  2. DELIBERATELY LEFT REAL ([leftRealReasons]) - os_sdk_int, which the injector refuses to
     *     touch because a fake API level makes support libraries call methods that do not exist.
     *     A design decision, not a gap.
     *  3. HOOKED BUT ON THE WRONG DELIVERY PATH ([wrongDeliveryPathReasons]) - app_set_id, where
     *     a real hook exists and simply is not where apps read the value, confirmed on device.
     *
     * Keys with no entry here are the honest "pending" ones: a hook point is known, the work is
     * not done, and the generic copy is true for them.
     *
     * The strings below are the developer-facing source text, NOT localized - the same trade as
     * [partialEnforcementCaveats] - and every one of them has a matching string resource in
     * [notEnforcedReasonRes], which is what a UI should render. Keep the two key sets equal.
     */
    val notEnforcedReasons = mapOf<String, String>(
        // Both Firebase IDs: firebase-installations mints the FID inside the app process and
        // persists it in the app's own data dir before ever registering it over HTTPS, and the
        // analytics App Instance ID works the same way. There is no binder, no ContentProvider
        // and no system property in the path, so there is nothing for a framework hook to sit on.
        // The only lever the platform has is clearing the app's data, which regenerates the value
        // - a wipe, not a spoof. Enforceable only by in-process code injection, i.e. by a
        // different product (PrivacyKit-LSPosed), which is exactly the Tier-1/Tier-2 boundary.
        "firebase_installation_id" to
            "This ID is created by the app itself, inside its own process, and is never asked " +
            "of the system, so there is nothing for PrivacyKit to intercept or replace. " +
            "Clearing the app's data makes the app create a new one.",
        "firebase_app_instance_id" to
            "This ID is created by the app itself, inside its own process, and is never asked " +
            "of the system, so there is nothing for PrivacyKit to intercept or replace. " +
            "Clearing the app's data makes the app create a new one.",

        // Documented at length in PrivacyKitKeys.java: libcore seeds "os.version" into the
        // unchangeable property table (System.java:1090) and the live
        // PropertiesWithNonOverrideableDefaults.put() silently discards the write, so the dead
        // System.setProperty call was removed rather than kept as decoration. Unrescuable
        // elsewhere too: android.system.Os.uname().release is public, permission-free SDK that
        // reads the kernel directly, and /proc/version is one read away - a spoofed Java value
        // that disagreed with either is a one-string-compare tell.
        "kernel_version" to
            "The kernel reports this value directly and Android refuses to overwrite it. A fake " +
            "value would also disagree with /proc/version and with a system call any app can " +
            "make, which is easier to spot than the real value.",

        // Nothing to hide, so nothing to fix: TelephonyManager#getMeid() and #getMeid(int) are
        // hard `return null` stubs in this Android 16 tree (legacy CDMA support removed). The row
        // used to read as an apology for a non-problem.
        "meid" to
            "This phone has no MEID - it is a CDMA-only identifier that this Android version no " +
            "longer provides. Every app that asks already gets nothing back, so there is nothing " +
            "left to hide.",

        // Apps derive it as currentTimeMillis() - SystemClock.elapsedRealtime().
        // elapsedRealtime() is a vDSO CLOCK_BOOTTIME read: no binder, no Java choke point, and
        // faking it would desynchronize Choreographer, ANR timers and every animation in the
        // process. Actively harmful, not merely unimplemented. Note that
        // /proc/sys/kernel/random/boot_id is a DIFFERENT identifier and is spoofable natively by
        // bind-mount; it belongs in its own Advanced row and must not be conflated with this key.
        "last_boot_time" to
            "Apps work this out from the system clock and the time since the device booted, not " +
            "from a value the system hands them. Changing it would break timers, animations and " +
            "app-not-responding detection inside the app.",

        // Already neutralized by the platform: without the signature-level LOCAL_MAC_ADDRESS
        // permission, WifiInfo#getMacAddress() returns 02:00:00:00:00:00 to every third-party app
        // (WifiInfo.java:100, :613-614) and BluetoothAdapter#getAddress() does the same. There is
        // nothing left to substitute on the Java side. The residual leak is
        // /sys/class/net/wlan0/address, a native sysfs redirect - the Tier-2 boundary, not a Java
        // hook. The reverted wifi_mac hook (task #13) was never the thing that mattered.
        "wifi_mac" to
            "Android already hides this. Every app without a system signature reads " +
            "02:00:00:00:00:00 instead of the real address, so there is nothing left to replace. " +
            "The real address can still be read by native code straight from the kernel, which " +
            "is outside what PrivacyKit changes on the Java side.",
        "bluetooth_mac" to
            "Android already hides this. Every app without a system signature reads " +
            "02:00:00:00:00:00 instead of the real address, so there is nothing left to replace. " +
            "The real address can still be read by native code straight from the kernel, which " +
            "is outside what PrivacyKit changes on the Java side.",

        // Writable, deliberately refused: PrivacyKitIdentityInjector has an explicit
        // "DELIBERATELY NOT SPOOFED" block for Build.VERSION#SDK_INT because a fake API level
        // makes AndroidX compat shims call APIs that do not exist in the real OS. Stored,
        // round-trips, never applied - by design.
        "os_sdk_int" to
            "Left real on purpose. Apps use the API level to decide which system features to " +
            "call, so a fake one makes their support libraries call methods that do not exist " +
            "and the app crashes.",

        // A live hook on the wrong door. See the app_set_id bullet in [enforcedKeys]' doc for the
        // full chain; the short version is that the hook is on
        // android.adservices.appsetid.AppSetIdManager and apps use
        // com.google.android.gms.appset.AppSetIdClient, verified on device.
        "app_set_id" to
            "PrivacyKit can replace this ID when an app asks Android's AdServices for it, but " +
            "apps get it from Google Play services instead, over a connection that never passes " +
            "through that interface. Testing on this device confirmed the value an app reads " +
            "does not change, so this is listed as not enforced.",
    )

    /**
     * The user-facing rendering of every note this catalog can attach to a row, as string
     * resource ids.
     *
     * The four source-text maps above ([partialEnforcementCaveats], [restrictionNotes],
     * [notEnforcedReasons]) are documented as developer-facing English, which is fine for a bug
     * report or a dumpsys-style dump and is NOT fine on a user's screen - shipping them verbatim
     * ships untranslated text. These maps are the localized half: same facts, same limits, said
     * in the user's language. [noteOf] with a Context is the accessor a screen should use;
     * [noteOf] without one stays the source-text accessor.
     *
     * Nothing here changes [enforcementOf]. A note never makes a key look more covered than it is.
     */
    /**
     * KNOWN GAP, stated rather than left to be discovered: the seven carrier-family and cell_info
     * caveats have no entry here, so [noteOf] falls back to their developer-facing English source
     * text on a user's screen. That is the documented fallback and it keeps the facts, but it ships
     * untranslated. Closing it needs new R.string entries in res/values/strings.xml, which this
     * change does not own; [auditInconsistencies] reports every one of them by name so the gap
     * cannot go quiet.
     */
    private val partialCaveatRes: Map<String, Int> = mapOf(
        "phone_number" to R.string.privacykit_note_phone_number,
        "build_radio_version" to R.string.privacykit_note_build_radio_version,
        "media_drm_id" to R.string.privacykit_note_media_drm_id,
        "accounts" to R.string.privacykit_note_accounts,
        "device_locale" to R.string.privacykit_note_device_locale,
        "gsf_id" to R.string.privacykit_note_gsf_id,
    )

    private val restrictionNoteRes: Map<String, Int> = mapOf(
        "advertising_id" to R.string.privacykit_note_advertising_id,
    )

    /**
     * NONE keys that no future hook can move, mapped to the user-facing reason.
     *
     * These are not pending work. A screen should say so plainly - and should not offer a rule
     * picker that stores a choice which provably does nothing - but it MUST show the reason in
     * its place: a greyed row with no explanation reads as "broken", which is the problem this
     * map exists to fix, wearing a different colour.
     */
    val notPossibleReasons: Map<String, Int> = mapOf(
        "firebase_installation_id" to R.string.privacykit_note_firebase_id,
        "firebase_app_instance_id" to R.string.privacykit_note_firebase_id,
        "kernel_version" to R.string.privacykit_note_kernel_version,
        "meid" to R.string.privacykit_note_meid,
        "last_boot_time" to R.string.privacykit_note_last_boot_time,
        "wifi_mac" to R.string.privacykit_note_mac_already_hidden,
        "bluetooth_mac" to R.string.privacykit_note_mac_already_hidden,
    )

    /** NONE keys the framework could spoof and deliberately does not, with the reason. */
    val leftRealReasons: Map<String, Int> = mapOf(
        "os_sdk_int" to R.string.privacykit_note_os_sdk_int,
    )

    /**
     * NONE keys that have a real, live hook which is simply not on the path apps use, with the
     * reason. Distinct from [notPossibleReasons]: the hook could start mattering if the delivery
     * path changed, so this is neither "impossible" nor "not built yet".
     */
    val wrongDeliveryPathReasons: Map<String, Int> = mapOf(
        "app_set_id" to R.string.privacykit_note_app_set_id,
    )

    /**
     * Localized counterpart of [notEnforcedReasons]. Keep the key sets equal: a key with source
     * text but no resource silently falls back to untranslated English, and a key with a resource
     * but no source text loses its dump/bug-report copy.
     */
    val notEnforcedReasonRes: Map<String, Int> =
        notPossibleReasons + leftRealReasons + wrongDeliveryPathReasons

    /**
     * Keys enforced by *refusing access* rather than by substituting a value, mapped to the
     * sentence the UI is obliged to show alongside them.
     *
     * This is a standing honesty obligation, not decoration. "Blocked" and "spoofed" are different
     * promises: a blocked identifier means the app is told it cannot have the value, and can often
     * tell it is being filtered; a spoofed one means the app is handed a plausible fake and cannot
     * tell. Presenting a block as a spoof would let a user believe they are hidden in a crowd when
     * in fact they are visibly refusing to answer.
     *
     * Unlike [notEnforcedReasons] these keys ARE enforced, so they are free to appear in
     * [enforcedKeys]; the note is about the *kind* of protection, not its absence.
     *
     * Developer-facing English, NOT localized; same trade as [partialEnforcementCaveats].
     */
    val restrictionNotes = mapOf(
        "advertising_id" to
            "Blocks the advertising ID - it does not fake one. The ID is created inside Google " +
            "Play services, so no fake value can be substituted; instead the app's request for it " +
            "is refused and the app sees the ID as unavailable, as on a phone with no Google " +
            "services. This is detectable: an app whose other Play services calls keep working " +
            "can tell it is being filtered. It does not retract an ID the app already stored " +
            "before you turned this on, and it does not affect Google's own use of the ID inside " +
            "Play services. This build also contains an experimental path that could hand out a " +
            "fake ID instead of blocking, but it is switched off by default and no advertising " +
            "ID service was found on this device for it to act on, so blocking is what actually " +
            "happens.",
    )

    /**
     * The standing scope limit shared by every identifier PrivacyKitIdentityInjector rewrites
     * inside the app's own process.
     *
     * These keys are genuinely, fully enforced for the API an app calls - they are in
     * [enforcedKeys] and belong there - but "fully enforced" here is scoped to one process and one
     * API surface, and someone deciding whether to rely on a spoofed build fingerprint deserves to
     * know that boundary before they pick a rule rather than after. Verified in this tree and
     * already recorded in [enforcedKeys]' own "known narrower-than-the-label caveats" block: the
     * injector writes the static fields of android.os.Build and Build.VERSION through the ART
     * un-final path, which is invisible to SystemProperties.get(), to __system_property_get()
     * called from native or NDK code, to anything that parses /system/build.prop, and to the
     * WebView user-agent string.
     *
     * Deliberately NOT part of [noteOf]. This is not a coverage defect in a particular key, it is a
     * property of the mechanism they all share, so it must not de-emphasize a row, must not change
     * [enforcementOf], and must not move a key out of the enforced counter. The UI shows it once,
     * on the screen where the user chooses a rule.
     *
     * kernel_version and build_radio_version are injector-backed too but are absent from
     * [injectorScopeKeys] on purpose: each already has a larger limitation of its own stated
     * elsewhere (build_radio_version in [partialEnforcementCaveats], kernel_version in
     * [notEnforcedReasons], whose text already names /proc and native readers), and two stacked
     * banners saying overlapping things is how people learn to skip both.
     *
     * The three ABI lists moved INTO [injectorScopeKeys] in 2026-08 when they were promoted to
     * [enforcedKeys]: they no longer carry a caveat of their own, so this is now the one scope
     * limit that applies to them, and dropping it would leave them looking wider than they are.
     *
     * Developer-facing English, NOT localized; same trade as [partialEnforcementCaveats].
     */
    val injectorScopeNote =
        "This is applied inside the app's own process, on the Java android.os.Build fields. " +
        "Native code that reads the same value with __system_property_get(), anything that parses " +
        "/system/build.prop, and the WebView user-agent string all still report the real device."

    /** The [enforcedKeys] members the injector writes in-process; see [injectorScopeNote]. */
    private val injectorScopeKeys = setOf(
        "build_fingerprint", "build_model", "build_manufacturer", "build_brand",
        "build_device", "build_product", "build_board", "build_hardware",
        "build_id", "build_type", "build_tags", "build_display", "build_bootloader",
        "build_host", "build_user", "build_soc_manufacturer", "build_soc_model",
        "supported_abis", "supported_32_bit_abis", "supported_64_bit_abis",
        "os_version_release", "os_version_incremental", "os_security_patch",
        "os_codename", "os_base_os",
    )

    /**
     * [injectorScopeNote] when [key] is one of the in-process Build / Build.VERSION overrides, else
     * null. Kept separate from [noteOf] on purpose - see [injectorScopeNote].
     */
    fun scopeNoteOf(key: String): String? =
        if (key in injectorScopeKeys) injectorScopeNote else null

    /** [scopeNoteOf] as a string resource - the localized form a screen should render. */
    @StringRes
    fun scopeNoteResOf(key: String): Int? =
        if (key in injectorScopeKeys) R.string.privacykit_note_injector_scope else null

    /**
     * [scopeNoteOf] resolved against [context], falling back to the untranslated source text if
     * the resource cannot be read. Never throws: a broken resource must not take a Settings
     * screen down.
     */
    fun scopeNoteOf(context: Context, key: String): String? {
        val res = scopeNoteResOf(key)
        if (res != null) {
            try {
                return context.getString(res)
            } catch (t: Throwable) {
                // Fall through to the source text below - worse copy, identical facts.
            }
        }
        return scopeNoteOf(key)
    }

    /**
     * Keys with a real hook whose coverage is incomplete - the domain of
     * [partialEnforcementCaveats].
     *
     * Derived, never hand-maintained, so a key can never appear here without a stated reason.
     * Guaranteed disjoint from [enforcedKeys] by construction of that set (each omission is called
     * out by name in its doc comment).
     */
    /**
     * The keys the UI is allowed to present as fully enforced.
     *
     * DERIVED, never hand-maintained. [declaredEnforcedKeys] is the claim; this is the claim minus
     * every key that some other map in this file simultaneously says is not fully covered:
     *
     *  - a key with a [partialEnforcementCaveats] entry has a stated gap an app can read the real
     *    value through. That is the definition of PARTIAL, so it cannot also be FULL.
     *  - a key with a [notEnforcedReasons] entry has a stated reason it is NONE - impossible,
     *    deliberately left real, or hooked on a path apps do not use. That cannot be FULL either.
     *
     * Before this was derived, nine keys were in both places at once and [enforcementOf] - which
     * tested the declaration first - reported every one of them as FULL. See the block at the end
     * of [declaredEnforcedKeys]' doc for the list and for how to genuinely promote a key.
     *
     * The subtraction is deliberately silent rather than an exception: a Settings screen must not
     * crash because a catalog entry is inconsistent, and the conservative answer (under-claim) is
     * always safe to render. [auditInconsistencies] is the loud half - it reports every
     * contradiction it can see, for a dumpsys-style dump or a test, without being able to take a
     * screen down.
     */
    val enforcedKeys: Set<String> =
        declaredEnforcedKeys - partialEnforcementCaveats.keys - notEnforcedReasons.keys

    val partiallyEnforcedKeys: Set<String> = partialEnforcementCaveats.keys

    /** How much of an identifier the framework actually covers. See [enforcementOf]. */
    fun enforcementOf(key: String): PkEnforcement = when (key) {
        in enforcedKeys -> PkEnforcement.FULL
        in partiallyEnforcedKeys -> PkEnforcement.PARTIAL
        else -> PkEnforcement.NONE
    }

    /**
     * The developer-facing reason a partially-enforced key is only partial, or null for keys that
     * are either fully enforced or have no hook at all. Not localized - see
     * [partialEnforcementCaveats].
     */
    fun caveatOf(key: String): String? = partialEnforcementCaveats[key]

    /**
     * The one line of explanatory text the UI should show for [key], from whichever source applies,
     * or null when the key needs no special explanation.
     *
     * The three maps hold disjoint key sets today, and the ?: chain fixes the precedence if that
     * ever stops being true: a PARTIAL coverage caveat first ([partialEnforcementCaveats], which is
     * what *makes* a key PARTIAL, so it must win), then the blocked-not-spoofed obligation
     * ([restrictionNotes]), then a specific reason a NONE key is NONE ([notEnforcedReasons]).
     *
     * Returning a note never changes [enforcementOf]. It only replaces generic UI copy with the
     * truth for that key, which is why every screen should read this instead of reaching into an
     * individual map: adding a fourth note source then cannot silently miss a surface.
     */
    fun noteOf(key: String): String? =
        partialEnforcementCaveats[key] ?: restrictionNotes[key] ?: notEnforcedReasons[key]

    /**
     * [noteOf] as a string resource, or null when this key needs no explanation.
     *
     * Same precedence as [noteOf], for the same reason: the PARTIAL caveat is what MAKES a key
     * partial so it must win, then the blocked-not-spoofed obligation, then the specific reason a
     * NONE key is NONE.
     */
    @StringRes
    fun noteResOf(key: String): Int? =
        partialCaveatRes[key] ?: restrictionNoteRes[key] ?: notEnforcedReasonRes[key]

    /**
     * [noteOf] resolved against [context] - the accessor a screen should use, so the sentence a
     * user reads is translated rather than developer English.
     *
     * Falls back to the source text when a key has no resource yet or the resource cannot be
     * read, because a note in the wrong language is still far better than a row that silently
     * loses its only honest explanation. Never throws.
     */
    fun noteOf(context: Context, key: String): String? {
        val res = noteResOf(key)
        if (res != null) {
            try {
                return context.getString(res)
            } catch (t: Throwable) {
                // Fall through to the source text below - worse copy, identical facts.
            }
        }
        return noteOf(key)
    }

    /** True when no future hook can cover [key]; the reason is in [notPossibleReasons]. */
    fun isNotPossible(key: String): Boolean = key in notPossibleReasons

    /** True when [key] is left real on purpose; the reason is in [leftRealReasons]. */
    fun isLeftReal(key: String): Boolean = key in leftRealReasons

    /**
     * True when [key] really is just "not built yet" - NONE, with no specific reason recorded.
     * These are the only keys for which the generic "not enforced yet" copy is honest.
     */
    fun isPending(key: String): Boolean =
        enforcementOf(key) == PkEnforcement.NONE && key !in notEnforcedReasonRes

    /**
     * Catalog keys whose rule is enforced by *restricting access* instead of by substituting a
     * value. Mirrors PrivacyKitService.RESTRICTION_KEYS exactly - the literals must stay in sync,
     * same duplication caveat as PrivacyKitKeys/PrivacyKitRuleResolver.
     *
     * Only RULE_REAL and RULE_EMPTY mean anything for these: the service restricts on RULE_EMPTY
     * and treats every other type as "do not restrict". Offering the value-substitution types here
     * would let the UI show a spoof that the backend silently turns into "unrestricted" - telling
     * the user the opposite of what actually happened.
     *
     * Two different enforcement mechanisms sit behind this one set, which is fine because the
     * Real/Empty contract is identical for both:
     *  - the first six deny a runtime-permission-backed AppOps op for the package's UID and let the
     *    platform's soft-denial path hand back an empty result;
     *  - advertising_id has no AppOps op at all; PrivacyKitService keeps a package list that
     *    ActiveServices consults on the bindService path, refusing the dedicated Google
     *    advertising-ID service bind.
     * Anything in this set is a block rather than a spoof, and anything the user could mistake for
     * a spoof needs an entry in [restrictionNotes].
     */
    val restrictionKeys = setOf(
        "contacts", "call_log", "sms", "calendar", "accounts",
        "bluetooth_bonded_devices",
        "advertising_id",
    )

    /** True when [key] is a restriction toggle rather than a value spoof. */
    fun isRestrictionKey(key: String): Boolean = key in restrictionKeys

    /**
     * Per-key rule-type allow-lists for keys whose *backend* refuses some rule types.
     *
     * This mirrors PrivacyKitRuleResolver#isRuleTypeAllowed, which is the real enforcement point:
     * resolve() consults it as its very first statement and returns the real value for a refused
     * type, so a refused rule can never become an output no matter what this UI offers, what a
     * restored backup contains, or what any other client stores. That class lives in
     * com.android.server.privacykit and is not on the Settings app's classpath, so it cannot be
     * queried from here and has to be mirrored by hand. Keep the two in sync: a rule type offered
     * here but refused there is precisely the class of lie this catalog exists to prevent.
     *
     * Not every entry is backed by isRuleTypeAllowed. The test is "does the rule type genuinely take
     * effect", and some keys are narrowed further downstream: device_timezone and device_locale by
     * PrivacyKitIdentityInjector's plausibility checks (a non-tz-id / non-BCP-47 string is discarded
     * and the real value kept), and gsf_id by the gservices hook's isDecimalLong guard (a non-decimal
     * substitute is refused). Those narrowings are just as real as an isRuleTypeAllowed refusal, so
     * they belong here too - the map is the union of both, never wider than what actually applies.
     *
     * Re-audited 2026-08 against PrivacyKitRuleResolver#isRuleTypeAllowed after r29 widened it
     * from one key to three families. It now refuses rule types for media_drm_id (below), for the
     * twenty-one coherent Build/SoC/VERSION identity keys, and for the ABI/radio keys - and resolve()
 * returns
     * the real value for a refused type. Every one of those refusals is mirrored below. If that
     * method changes again, this map must change with it in the same commit: a rule type offered
     * here but refused there is a spoof the user is told about and never gets.
     *
     * media_drm_id (verified against PrivacyKitRuleResolver:74, 2026-08): RULE_PER_LAUNCH and
     * RULE_DAILY are refused because streaming services bind the Widevine device id to a
     * "max N registered devices" entitlement, so a rotating id burns through the user's
     * registration slots and can get the account flagged - actively worse for the user than no
     * spoof. RULE_EMPTY is refused because MediaDrm#getPropertyByteArray is declared @NonNull and
     * real app code indexes into the array it returns. Real, Static and Custom are allowed, and
     * all three genuinely reach the app.
     */
    private val coherentBuildIdentityKeys = setOf(
        "build_fingerprint", "build_model", "build_manufacturer", "build_brand",
        "build_device", "build_product", "build_board", "build_hardware",
        "build_id", "build_soc_manufacturer", "build_soc_model",
        // Added 2026-08 alongside the matching widening of
        // PrivacyKitRuleResolver#isCoherentBuildIdentityKey. Every one of these six is either
        // printed inside the fingerprint string itself
        // (brand/product/device:release/id/incremental:type/tags carries build_id, build_type
        // and build_tags verbatim) or is read straight off the same build that produced it, so
        // an independently drawn value contradicts the fingerprint exactly the way a random
        // build_model does. Worse: PrivacyKitIdentifierGenerator has no case for any of the six,
        // so Static / Random-daily / Random-per-launch resolved to 16 random hex characters in
        // fields where every real device carries "user", "release-keys", a bootloader string, a
        // build-server hostname or a build account name. One parse of Build.TAGS and the spoof
        // is the tell it was supposed to hide. Real, or a coherent Custom bundle from a device
        // template - nothing else.
        "build_type", "build_tags", "build_display", "build_bootloader",
        "build_host", "build_user",
        // Added 2026-08, alongside the matching widening of
        // PrivacyKitRuleResolver#isCoherentBuildIdentityKey, and found the same
        // way the six above were: these four were in [enforcedKeys] but had no
        // entry in [perKeyRuleTypes], so they fell through to "every rule type"
        // and the picker offered Static / Random-daily / Random-per-launch for
        // them.
        //
        // Three of the four are printed inside the fingerprint string verbatim
        // (brand/product/device:RELEASE/id/INCREMENTAL:type/tags), and
        // Build.VERSION.CODENAME / BASE_OS come off the same build, so an
        // independent draw contradicts the fingerprint on one parse. And, as
        // with build_type and build_tags, PrivacyKitIdentifierGenerator has no
        // case for any of the four, so those three rule types resolved to 16
        // random hex characters: Build.VERSION.RELEASE would have read
        // "3f2ac1..." where every Android device reports "16", and CODENAME
        // would have stopped being "REL" - the single string that distinguishes
        // a shipping build from a developer preview. Real, or a coherent Custom
        // bundle from a device template, only.
        //
        // os_security_patch is deliberately NOT in this set: it is not part of
        // the fingerprint string, and the generator DOES have a case for it that
        // emits a real YYYY-MM-DD patch level, so the random rule types produce
        // a plausible value there and continue to be offered.
        "os_version_release", "os_version_incremental", "os_codename", "os_base_os",
    )

    private val perKeyRuleTypes: Map<String, List<PkRuleType>> =
        // The twenty-one fingerprint-linked identity keys, mirroring
        // PrivacyKitRuleResolver#isCoherentBuildIdentityKey: a random model, brand, SoC or build
        // id contradicts the fingerprint string and is a one-parse tell, so the backend refuses
        // every rule type except Real and Custom and returns the real value for the rest. The
        // catalog offered all six until 2026-08, which meant a user could pick "Random daily" for
        // Build Model, see it stored, and read the real model in every app - the exact class of
        // lie this file exists to prevent. Custom is how a device template applies a coherent set.
        coherentBuildIdentityKeys.associateWith {
            listOf(PkRuleType.REAL, PkRuleType.CUSTOM)
        } + mapOf(
        "media_drm_id" to listOf(PkRuleType.REAL, PkRuleType.STATIC, PkRuleType.CUSTOM),
        // ABI lists and the baseband version, mirroring
        // PrivacyKitRuleResolver#isAbiOrRadioKey (Real / Custom / Empty). A random ABI list
        // breaks native library loading and PrivacyKitIdentityInjector#parseAbiList would reject
        // it anyway; a random baseband string is a fresh tell. Empty differs per key by
        // FieldSpec.rejectEmpty: SUPPORTED_ABIS refuses to be blanked (no real device has an
        // empty primary ABI list, and an app that saw one could not choose a native library at
        // all), while the 32/64-bit lists and Build.RADIO accept it, so Empty is offered only
        // where it genuinely applies.
        "supported_abis" to listOf(PkRuleType.REAL, PkRuleType.CUSTOM),
        "supported_32_bit_abis" to
                listOf(PkRuleType.REAL, PkRuleType.CUSTOM, PkRuleType.EMPTY),
        "supported_64_bit_abis" to
                listOf(PkRuleType.REAL, PkRuleType.CUSTOM, PkRuleType.EMPTY),
        "build_radio_version" to
                listOf(PkRuleType.REAL, PkRuleType.CUSTOM, PkRuleType.EMPTY),
        // device_timezone / device_locale: widened in 2026-08 because the reason they were
        // narrow went away. PrivacyKitIdentifierGenerator gained real cases for both in r29 -
        // TIMEZONE_POOL (22 real Olson ids) and LOCALE_POOL (26 real BCP-47 tags) - so Static,
        // Random-daily and Random-per-launch now produce values that
        // PrivacyKitIdentityInjector#applyTimeZone / #applyLocale accept instead of the 16 hex
        // characters they used to discard. The old comment here ("the resolver has no generator")
        // was true when written and is now stale.
        //
        // Empty is still not offered for either: both apply methods early-return on an empty
        // candidate, and there is no such thing as an empty time zone or locale. The plausibility
        // check remains the real boundary for Custom - a value that is not a known tz id or a
        // parseable language tag is discarded and the real one kept - which is why these two stay
        // out of the "every rule type" default rather than being dropped from this map.
        "device_timezone" to listOf(PkRuleType.REAL, PkRuleType.STATIC,
                PkRuleType.RANDOM_PER_LAUNCH, PkRuleType.RANDOM_DAILY, PkRuleType.CUSTOM),
        "device_locale" to listOf(PkRuleType.REAL, PkRuleType.STATIC,
                PkRuleType.RANDOM_PER_LAUNCH, PkRuleType.RANDOM_DAILY, PkRuleType.CUSTOM),
        // gsf_id: every rule type EXCEPT Empty takes effect. PrivacyKitIdentifierGenerator#genGsfId
        // gives Static / Random-daily / Random-per-launch a 19-digit decimal long, and Custom takes
        // any plain decimal; the gservices hook accepts all of those. Empty resolves to "", which
        // that hook's isDecimalLong guard rejects (keeping the real id), so offering it would show a
        // spoof that does nothing.
        "gsf_id" to listOf(PkRuleType.REAL, PkRuleType.STATIC,
                PkRuleType.RANDOM_PER_LAUNCH, PkRuleType.RANDOM_DAILY, PkRuleType.CUSTOM),

        // ------------------------------------------------------------------
        // Added by the 2026-08 mirror re-audit. Each of these was a key the
        // BACKEND already narrowed (or a hook already discarded) while this map
        // had no entry for it, so supportedRuleTypesFor fell through to "every
        // rule type" and the picker offered rules that were thrown away. Same
        // defect as the coherent build keys, found the same way: by walking
        // isRuleTypeAllowed and every hook, key by key, instead of trusting the
        // map.
        // ------------------------------------------------------------------

        // android_id: RULE_EMPTY is refused by PrivacyKitRuleResolver
        // #isRuleTypeAllowed as of this pass. An empty SSAID is not a state a
        // real device can be in, and app code hashes / substrings / length-
        // checks the value, so "" crashes callers instead of hiding anything.
        // The SettingsProvider SSAID hook is dropping its isEmpty() acceptance
        // in the same change, so both ends refuse it.
        "android_id" to listOf(PkRuleType.REAL, PkRuleType.STATIC,
                PkRuleType.RANDOM_PER_LAUNCH, PkRuleType.RANDOM_DAILY, PkRuleType.CUSTOM),

        // device_name: PrivacyKitRuleResolver#isRuleTypeAllowed has refused
        // everything but Real and Custom for this key since r29 (the name is
        // seeded from Build.MODEL, so a 16-hex draw lands a hash where a product
        // name belongs, and a name that disagrees with a spoofed build_model is
        // a one-compare tell). This map never mirrored it.
        "device_name" to listOf(PkRuleType.REAL, PkRuleType.CUSTOM),

        // first_install_time: the resolver refuses Per-launch and Daily (a
        // spoofed install date the app can write down and re-check must not move
        // under it) and Empty ("" is not a timestamp). Real / Static / Custom
        // all apply, and the generator has a real case for this key.
        "first_install_time" to listOf(PkRuleType.REAL, PkRuleType.STATIC, PkRuleType.CUSTOM),

        // The carrier identity family. Not an isRuleTypeAllowed refusal - these
        // are narrowed by the hooks' own shape checks, which are just as real an
        // output boundary. TelephonyManager#isPlausiblePrivacyKitCarrierValue
        // requires 5-6 decimal digits for an MCC+MNC and a lower-case 2-letter
        // code for a country ISO, and PrivacyKitIdentifierGenerator has a case
        // for NONE of these keys, so Static / Random-daily / Random-per-launch
        // produce 16 hex characters that every one of those checks rejects,
        // falling open to the real carrier. Empty is refused by the same shape
        // checks for the numeric and ISO keys.
        //
        // The two operator NAME keys are the exception in both directions: they
        // are free-form SPN text, so the shape check accepts anything - which
        // means a 16-hex "carrier name" really would reach the app. That is the
        // build_tags argument exactly: a value no real device could report is a
        // tell, not a disguise. Real, a Custom name, or Empty (a phone with no
        // SIM reports no operator name, so blank is a state that really occurs).
        "sim_operator" to listOf(PkRuleType.REAL, PkRuleType.CUSTOM),
        "network_operator" to listOf(PkRuleType.REAL, PkRuleType.CUSTOM),
        "sim_country_iso" to listOf(PkRuleType.REAL, PkRuleType.CUSTOM),
        "sim_operator_name" to
                listOf(PkRuleType.REAL, PkRuleType.CUSTOM, PkRuleType.EMPTY),
        "network_operator_name" to
                listOf(PkRuleType.REAL, PkRuleType.CUSTOM, PkRuleType.EMPTY),

        // sim_carrier_id: PhoneInterfaceManager#getSubscriptionCarrierId parses
        // the resolved string with Integer.parseInt and falls open to the real
        // carrier id on any NumberFormatException. 16 hex characters do not
        // parse, and neither does "", so only Real and a numeric Custom value
        // ever reach an app. (The preferred fix would be a carrier-id generator
        // case, which would make Static/Random work; that lives in
        // PrivacyKitIdentifierGenerator, and until it exists offering those rule
        // types here would be advertising a spoof that never happens.)
        "sim_carrier_id" to listOf(PkRuleType.REAL, PkRuleType.CUSTOM),

        // network_country_iso: PhoneInterfaceManager#getNetworkCountryIsoForPhone
        // accepts the resolved value only when it is empty or matches [a-z]{2}.
        // Empty is a real state (no network registration), so it is offered; 16
        // hex characters are not, so the random types are not. Same "add an ISO
        // generator like TIMEZONE_POOL / LOCALE_POOL" note as sim_carrier_id.
        "network_country_iso" to
                listOf(PkRuleType.REAL, PkRuleType.CUSTOM, PkRuleType.EMPTY),

        // cell_info: TelephonyRegistry#maybePrivacyKitCellInfo acts on RULE_EMPTY
        // alone - it delivers an empty list - and its own comment says every
        // other rule type falls open "because a serving-cell list has no
        // meaningful fabricated form". A block, not a spoof.
        "cell_info" to listOf(PkRuleType.REAL, PkRuleType.EMPTY),

        // The device-state flags. SettingsProvider#maybeSpoofDeviceStateSettingLocked
        // returns the resolved string verbatim, with no shape check, into a
        // setting that every caller reads with Settings.*.getInt(). A 16-hex
        // value from Static / Random therefore does not spoof the flag - it
        // makes getInt() throw NumberFormatException internally and hand back
        // the caller's own default, i.e. an outcome PrivacyKit neither chose nor
        // controls, while getString() hands back hex where a real device always
        // has "0" or "1". Real, a Custom "0"/"1", or Empty (which getInt() also
        // treats as absent) are the three that mean anything, and they are what
        // PrivacyKitAppControlsScreen already writes.
        "adb_enabled" to listOf(PkRuleType.REAL, PkRuleType.CUSTOM, PkRuleType.EMPTY),
        "developer_options_enabled" to
                listOf(PkRuleType.REAL, PkRuleType.CUSTOM, PkRuleType.EMPTY),
        "wireless_debugging" to
                listOf(PkRuleType.REAL, PkRuleType.CUSTOM, PkRuleType.EMPTY),
        "package_verifier" to
                listOf(PkRuleType.REAL, PkRuleType.CUSTOM, PkRuleType.EMPTY),
        "usb_app_verification" to
                listOf(PkRuleType.REAL, PkRuleType.CUSTOM, PkRuleType.EMPTY),
    )

    /**
     * Rule types that genuinely take effect for [key], in display order.
     *
     * Deliberately an allow-list rather than a filter-by-exclusion: if a new PkRuleType is ever
     * added it will not silently start appearing on restriction rows, where it would be inert, nor
     * on a key whose backend refuses it.
     *
     * Restriction keys are checked first: for them the Real/Empty contract comes from
     * PrivacyKitService.RESTRICTION_KEYS and overrides everything else.
     */
    fun supportedRuleTypesFor(key: String): List<PkRuleType> = when {
        isRestrictionKey(key) -> listOf(PkRuleType.REAL, PkRuleType.EMPTY)
        else -> perKeyRuleTypes[key] ?: PkRuleType.entries.toList()
    }

    fun itemByKey(key: String): PkIdentifierItem =
        allItems.firstOrNull { it.key == key } ?: PkIdentifierItem(key, key, PkRuleType.REAL)

    /**
     * The rule a picker should preselect for [key] - [PkIdentifierItem.defaultRule], but never a
     * rule type [supportedRuleTypesFor] does not offer.
     *
     * A default outside the offered set is a small version of the same lie: the row draws with
     * nothing selected, or with a selection the user cannot re-choose after moving off it, and in
     * the worst case a caller that reads defaultRule without consulting the picker writes a rule
     * the backend refuses. This has already happened twice here - the coherent build keys carried a
     * Static default after the backend narrowed them to Real/Custom, and the whole carrier family
     * did the same - so the clamp lives in code rather than in a review habit.
     *
     * REAL is the fallback because it is the one rule type every key offers and the only one that
     * cannot misrepresent anything.
     */
    fun defaultRuleFor(key: String): PkRuleType {
        val declared = itemByKey(key).defaultRule
        return if (declared in supportedRuleTypesFor(key)) declared else PkRuleType.REAL
    }

    /**
     * Every internal contradiction this catalog can see in itself, as developer-facing lines.
     * Empty means the file is self-consistent.
     *
     * The point of this function is that the invariants in this file used to be prose - "guaranteed
     * disjoint by construction", "keep the two key sets equal", "[perKeyRuleTypes] mirrors both
     * refusals exactly" - and prose does not fail. Every one of those three sentences was false by
     * the time the 2026-08 audit read them. This turns them into something a dump, a test or a
     * reviewer can execute.
     *
     * It reports rather than throws, and nothing renders from it: an inconsistent catalog must
     * still draw a screen, and [enforcedKeys] already resolves the two dangerous contradictions in
     * the conservative direction on its own. This is the part that says so out loud.
     */
    fun auditInconsistencies(): List<String> = buildList {
        for (key in declaredEnforcedKeys) {
            if (key in partialEnforcementCaveats) {
                add("$key: claimed in declaredEnforcedKeys but has a partialEnforcementCaveats " +
                    "entry; treated as PARTIAL")
            }
            if (key in notEnforcedReasons) {
                add("$key: claimed in declaredEnforcedKeys but has a notEnforcedReasons entry; " +
                    "treated as NONE")
            }
        }
        for (key in notEnforcedReasons.keys) {
            if (key !in notEnforcedReasonRes) {
                add("$key: has notEnforcedReasons source text but no string resource; the row " +
                    "would show untranslated English")
            }
        }
        for (key in notEnforcedReasonRes.keys) {
            if (key !in notEnforcedReasons) {
                add("$key: has a notEnforcedReasons string resource but no source text; a dump " +
                    "would lose its explanation")
            }
        }
        for (key in partialEnforcementCaveats.keys) {
            if (key !in partialCaveatRes) {
                add("$key: has a partial caveat but no string resource")
            }
        }
        for (key in restrictionKeys) {
            if (key !in allItems.map { it.key }) {
                add("$key: in restrictionKeys but is not a catalog item")
            }
        }
        for (item in allItems) {
            val offered = supportedRuleTypesFor(item.key)
            if (item.defaultRule !in offered) {
                add("${item.key}: defaultRule ${item.defaultRule.label} is not offered by " +
                    "supportedRuleTypesFor; defaultRuleFor() clamps it to Real")
            }
            if (offered.isEmpty()) {
                add("${item.key}: supportedRuleTypesFor offers nothing at all")
            }
        }
    }
}

/**
 * How much of an identifier this build's framework hooks really cover.
 *
 * [FULL] is a necessary condition for telling the user an identifier is handled - and NOT a
 * sufficient one for the word "spoofed", because some FULL keys are blocks rather than
 * substitutions. Check [PrivacyKitIdentifierCatalog.restrictionNotes] before choosing wording.
 * [PARTIAL] and [NONE] must both render as not-enforced; they may differ in the *explanation*
 * offered, never in whether the identifier looks protected.
 */
enum class PkEnforcement {
    /**
     * Every rule type the UI offers for this key applies, on the API an app would really call.
     * That may mean a substituted value or a refused request - see
     * [PrivacyKitIdentifierCatalog.restrictionNotes] for which, and note that the offered set can
     * itself be narrower than PkRuleType.entries (see
     * [PrivacyKitIdentifierCatalog.supportedRuleTypesFor]).
     */
    FULL,
    /** A real hook exists but does not cover the whole identifier - see caveat text. */
    PARTIAL,
    /**
     * Nothing the user picks takes effect. Usually that means no framework hook at all; for a few
     * keys a hook exists but the rule engine cannot feed it a value the hook will accept, in which
     * case [PrivacyKitIdentifierCatalog.notEnforcedReasons] carries the real story instead of the
     * generic one. Either way the rule is stored, round-trips, and changes nothing.
     */
    NONE,
}
