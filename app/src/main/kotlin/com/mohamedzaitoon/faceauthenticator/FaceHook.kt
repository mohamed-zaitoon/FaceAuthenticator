package com.mohamedzaitoon.faceauthenticator

import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File
import java.lang.reflect.Field
import java.util.Locale

class FaceHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == null ||
            lpparam.packageName == "com.mohamedzaitoon.faceauthenticator"
        ) {
            return
        }

        if (lpparam.packageName == "com.android.systemui" || lpparam.processName == "com.android.systemui") {
            if (isDisableFilePresent()) return
            XposedBridge.log(TAG + "Loaded in SystemUI. Enabling instant face confirmation hooks.")
            hookSystemUiFaceConfirmation(lpparam.classLoader)
            return
        }

        if (!isSystemFrameworkPackage(lpparam.packageName, lpparam.processName)) {
            return
        }

        XposedBridge.log(TAG + "Loaded in system framework package: " + lpparam.packageName)

        if (isDisableFilePresent()) {
            XposedBridge.log(TAG + "Disabled by " + DISABLE_FILE)
            return
        }

        if (!ENABLE_SYSTEM_FACE_REGISTRATION_UPGRADE) {
            XposedBridge.log(TAG + "System face registration upgrade hook is disabled.")
            return
        }

        XposedBridge.log(TAG + "System framework loaded. Enabling real face strong hooks.")

        hookPromptInfoClass(lpparam.classLoader)
        hookAuthSessionConfirmation(lpparam.classLoader)

        if (ENABLE_SYSTEM_CONFIG_AND_PROVIDER_UPGRADE) {
            hookBiometricConfig(lpparam.classLoader)
            hookFaceProviders(lpparam.classLoader)
            hookFaceServiceRegistry(lpparam.classLoader)
            hookFaceService(lpparam.classLoader)
        }
        hookActiveMarker(lpparam.classLoader)
        hookBiometricSensorStrength(lpparam.classLoader)
        hookBiometricService(lpparam.classLoader)
        hookBiometricPromptSensorPriority(lpparam.classLoader)
        if (ENABLE_AUTH_SESSION_FALLBACK) {
            hookAuthSessionFaceFallback(lpparam.classLoader)
        }
        hookFingerprintResetLockout(lpparam.classLoader)
    }

    private fun hookActiveMarker(classLoader: ClassLoader) {
        try {
            val biometricServiceClass = XposedHelpers.findClass(
                "com.android.server.biometrics.BiometricService",
                classLoader
            )

            XposedBridge.hookAllConstructors(
                biometricServiceClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        markModuleActive(getContextFromService(param.thisObject))
                    }
                }
            )

            XposedBridge.log(TAG + "Hooked BiometricService constructors for active marker.")
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "Error hooking active marker: " + t.message)
        }
    }

    private fun isSystemFrameworkPackage(packageName: String?, processName: String?): Boolean {
        return packageName == "android" ||
            packageName == "system" ||
            processName == "android" ||
            processName == "system_server"
    }

    private fun hookFingerprintResetLockout(classLoader: ClassLoader) {
        try {
            val clientClass = XposedHelpers.findClass(
                "com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintResetLockoutClient",
                classLoader
            )

            XposedHelpers.findAndHookMethod(
                clientClass,
                "start",
                "com.android.server.biometrics.sensors.ClientMonitorCallback",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        XposedBridge.log(
                            TAG + "Skipping FingerprintResetLockoutClient HAL call " +
                                "to keep fingerprint scheduler moving."
                        )
                        val callback = param.args[0]
                        if (callback != null) {
                            XposedHelpers.callMethod(
                                callback,
                                "onClientFinished",
                                param.thisObject,
                                true
                            )
                        }
                        param.setResult(null)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                clientClass,
                "startHalOperation",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.setResult(null)
                    }
                }
            )

            XposedBridge.log(TAG + "Hooked FingerprintResetLockoutClient to prevent queue stalls.")
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "Error hooking FingerprintResetLockoutClient: " + t.message)
        }
    }

    private fun hookBiometricSensorStrength(classLoader: ClassLoader) {
        try {
            val sensorClass = XposedHelpers.findClass(
                "com.android.server.biometrics.BiometricSensor",
                classLoader
            )

            XposedBridge.hookAllConstructors(
                sensorClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        patchBiometricSensorConstructorArgs(param.args)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        patchFaceBiometricSensor(param.thisObject, "constructor")
                    }
                }
            )

            var updateStrengthHooks = 0
            var currentStrengthHooks = 0
            for (method in sensorClass.declaredMethods) {
                if (method.name == "updateStrength") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isFaceBiometricSensor(param.thisObject) &&
                                    isIntegerAt(param.args, 0) &&
                                    param.args[0] as Int != BIOMETRIC_STRONG
                                ) {
                                    XposedBridge.log(
                                        TAG + "BiometricSensor.updateStrength: face sensorId=" +
                                            getSensorId(param.thisObject) +
                                            " strength " + param.args[0] + " -> " +
                                            BIOMETRIC_STRONG
                                    )
                                    param.args[0] = BIOMETRIC_STRONG
                                }
                            }

                            override fun afterHookedMethod(param: MethodHookParam) {
                                patchFaceBiometricSensor(param.thisObject, "updateStrength")
                            }
                        }
                    )
                    updateStrengthHooks++
                } else if (method.name == "getCurrentStrength") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isFaceBiometricSensor(param.thisObject)) {
                                    param.setResult(BIOMETRIC_STRONG)
                                }
                            }
                        }
                    )
                    currentStrengthHooks++
                }
            }

            XposedBridge.log(
                TAG + "Hooked BiometricSensor strength methods for real face sensors: " +
                    "constructors=all, updateStrength=" + updateStrengthHooks +
                    ", getCurrentStrength=" + currentStrengthHooks
            )
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "Error hooking BiometricSensor strength methods: " + t.message)
        }
    }

    private fun hookBiometricPromptSensorPriority(classLoader: ClassLoader) {
        try {
            val biometricServiceClass = XposedHelpers.findClass(
                "com.android.server.biometrics.BiometricService",
                classLoader
            )

            var hooked = 0
            for (method in biometricServiceClass.declaredMethods) {
                if (method.name != "authenticateInternal") {
                    continue
                }

                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            markModuleActive(getContextFromService(param.thisObject))
                            val packageName = findLikelyPackageName(param.args, param.thisObject)
                            val preAuthInfo = findPreAuthInfoArgument(param.args, param.thisObject)
                            if (preAuthInfo == null) {
                                XposedBridge.log(
                                    TAG + "Biometric prompt for " + packageName +
                                        " reached without a PreAuthInfo object."
                                )
                                return
                            }

                            XposedBridge.log(
                                TAG + "Intercepted biometric prompt for " + packageName
                            )

                            val settings = getHookSettings()
                            if (settings.instantFaceConfirmation) {
                                patchPromptInfoForInstantFaceConfirmation(param.args)
                            }

                            val result = when {
                                requiresFingerprintOnly(packageName) -> {
                                    preferFingerprintOnly(
                                        preAuthInfo,
                                        "fingerprint only for configured hardware/Keystore app"
                                    )
                                }
                                isFaceBlockedByLowLight() -> {
                                    preferFingerprintOnly(
                                        preAuthInfo,
                                        "fingerprint only while face is blocked"
                                    )
                                }
                                settings.keystoreFingerprintOnly &&
                                    hasHardwareBackedCryptoOperation(method, param.args) -> {
                                    preferFingerprintOnly(
                                        preAuthInfo,
                                        "fingerprint only for hardware-backed crypto prompt"
                                    )
                                }
                                !settings.delayedFaceEnabled -> {
                                    SensorPriorityResult.UNCHANGED
                                }
                                else -> {
                                    preferFingerprintThenDelayedFace(preAuthInfo)
                                }
                            }
                            if (result.changed) {
                                XposedBridge.log(
                                    TAG + "Biometric prompt from " + packageName +
                                        ": ordered " + result.selectedModality +
                                        ", eligibleSensors " + result.originalCount +
                                        " -> " + result.finalCount
                                )
                            }
                        }
                    }
                )
                hooked++
            }

            XposedBridge.log(
                TAG + "Hooked BiometricPrompt face-first priority overloads: " + hooked
            )
        } catch (t: Throwable) {
            XposedBridge.log(
                TAG + "Error hooking BiometricPrompt face-first priority: " + t.message
            )
        }
    }

    private fun hookAuthSessionFaceFallback(classLoader: ClassLoader) {
        val classNames = arrayOf(
            "com.android.server.biometrics.AuthSession",
            "com.android.server.biometrics.BiometricService\$AuthSession"
        )

        for (className in classNames) {
            try {
                val authSessionClass = XposedHelpers.findClass(className, classLoader)
                var acquiredHooks = 0
                var resultHooks = 0

                for (method in authSessionClass.declaredMethods) {
                    if (method.name == "onAcquired" || method.name == "onAcquiredReceived") {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    handleFaceAcquired(param.thisObject, param.args)
                                }
                            }
                        )
                        acquiredHooks++
                    }
                }

                for (method in authSessionClass.declaredMethods) {
                    if (method.name == "startAllPreparedSensors") {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val settings = getHookSettings()
                                    if (!settings.delayedFaceEnabled) {
                                        return
                                    }
                                    val authSession = param.thisObject ?: return
                                    if (isDelayedFaceStartInProgress(authSession)) {
                                        return
                                    }
                                    if (!isAuthSessionStillWaiting(authSession)) {
                                        return
                                    }
                                    if (!hasEligibleFingerprintAndFace(authSession)) {
                                        return
                                    }
                                    scheduleDelayedFaceStart(authSession, settings)
                                    if (param.args.isNotEmpty()) {
                                        param.args[0] =
                                            java.util.function.Function<Any, Boolean> { sensor ->
                                                isFingerprintBiometricSensor(sensor)
                                            }
                                    }
                                }
                            }
                        )
                    }
                }

                for (method in authSessionClass.declaredMethods) {
                    if (method.name == "onAuthenticationSucceeded" ||
                        method.name == "onAuthenticationRejected" ||
                        method.name == "onErrorReceived"
                    ) {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    handleFaceAuthResult(method.name, param.thisObject, param.args)
                                }
                            }
                        )
                        resultHooks++
                    }
                }

                XposedBridge.log(
                    TAG + "Hooked " + className +
                        " for fingerprint-first delayed face fallback: acquiredHooks=" + acquiredHooks +
                        ", resultHooks=" + resultHooks
                )
                return
            } catch (e: XposedHelpers.ClassNotFoundError) {
                XposedBridge.log(TAG + className + " not found in this ROM.")
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "Error hooking " + className + ": " + t.message)
            }
        }
    }

    private fun hookBiometricConfig(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.content.res.Resources",
                classLoader,
                "getStringArray",
                Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val res = param.thisObject as Resources
                        try {
                            val resEntryName = res.getResourceEntryName(param.args[0] as Int)
                            if (resEntryName != "config_biometric_sensors") {
                                return
                            }

                            @Suppress("UNCHECKED_CAST")
                            val originalConfig = param.getResult() as? Array<String>
                            val upgradedConfig = upgradeExistingFaceConfigs(originalConfig)
                            if (upgradedConfig !== originalConfig) {
                                param.setResult(upgradedConfig)
                            }
                        } catch (_: Resources.NotFoundException) {
                        }
                    }
                }
            )
            XposedBridge.log(TAG + "Hooked config_biometric_sensors in real-sensor-only mode.")
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "Error hooking biometric config: " + t.message)
        }
    }

    private fun hookFaceProviders(classLoader: ClassLoader) {
        val providerClassNames = arrayOf(
            "com.android.server.biometrics.sensors.face.aidl.FaceProvider",
            "com.android.server.biometrics.sensors.face.hidl.FaceProvider"
        )

        for (className in providerClassNames) {
            try {
                val providerClass = XposedHelpers.findClass(className, classLoader)
                XposedBridge.log(TAG + "Found real FaceProvider: " + className)

                XposedHelpers.findAndHookMethod(
                    providerClass,
                    "getSensorProperties",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val original = param.getResult() as? List<*> ?: return
                            val upgraded = upgradeFacePropertyList(
                                original,
                                className + ".getSensorProperties()"
                            )
                            if (upgraded !== original) {
                                param.setResult(upgraded)
                            }
                        }
                    }
                )

                XposedHelpers.findAndHookMethod(
                    providerClass,
                    "getSensorProperties",
                    Int::class.javaPrimitiveType!!,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val original = param.getResult() ?: return
                            val upgraded = upgradeFaceProperty(
                                original,
                                className + ".getSensorProperties(id)"
                            )
                            if (upgraded !== original) {
                                param.setResult(upgraded)
                            }
                        }
                    }
                )
            } catch (e: XposedHelpers.ClassNotFoundError) {
                XposedBridge.log(TAG + className + " not found in this ROM.")
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "Error hooking FaceProvider " + className + ": " + t.message)
            }
        }
    }

    private fun hookFaceServiceRegistry(classLoader: ClassLoader) {
        try {
            val registryClass = XposedHelpers.findClass(
                "com.android.server.biometrics.sensors.face.FaceServiceRegistry",
                classLoader
            )

            for (method in registryClass.declaredMethods) {
                if (method.name == "registerService" || method.name == "registerAuthenticator") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                XposedBridge.log(
                                    TAG + "FaceServiceRegistry." + method.name +
                                        " called for real face service."
                                )
                            }
                        }
                    )
                }
            }

            XposedBridge.log(TAG + "Hooked FaceServiceRegistry logging.")
        } catch (e: XposedHelpers.ClassNotFoundError) {
            XposedBridge.log(TAG + "FaceServiceRegistry class not found.")
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "Error hooking FaceServiceRegistry: " + t.message)
        }
    }

    private fun hookFaceService(classLoader: ClassLoader) {
        try {
            val faceServiceClass = XposedHelpers.findClass(
                "com.android.server.biometrics.sensors.face.FaceService",
                classLoader
            )

            for (method in faceServiceClass.declaredMethods) {
                if (method.name == "registerAuthenticators") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                XposedBridge.log(TAG + "FaceService.registerAuthenticators called.")
                            }
                        }
                    )
                }
            }

            XposedBridge.log(TAG + "Hooked FaceService logging.")
        } catch (e: XposedHelpers.ClassNotFoundError) {
            XposedBridge.log(TAG + "FaceService class not found.")
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "Error hooking FaceService: " + t.message)
        }
    }

    private fun hookBiometricService(classLoader: ClassLoader) {
        try {
            val biometricServiceClass = XposedHelpers.findClass(
                "com.android.server.biometrics.BiometricService",
                classLoader
            )

            var hooked = 0
            for (method in biometricServiceClass.declaredMethods) {
                if (method.name != "registerAuthenticator") {
                    continue
                }

                XposedBridge.hookMethod(
                    method,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            markModuleActive(getContextFromService(param.thisObject))
                            if (!isIntegerAt(param.args, 1) || !isIntegerAt(param.args, 2)) {
                                return
                            }

                            val sensorId = if (isIntegerAt(param.args, 0)) param.args[0] as Int else -1
                            val modality = param.args[1] as Int
                            val strength = param.args[2] as Int

                            if (modality != MODALITY_FACE) {
                                return
                            }

                            if (strength != BIOMETRIC_STRONG) {
                                XposedBridge.log(
                                    TAG + "Upgrading real face authenticator registration: " +
                                        "sensorId=" + sensorId + ", strength=" + strength +
                                        " -> " + BIOMETRIC_STRONG
                                )
                                param.args[2] = BIOMETRIC_STRONG
                            } else {
                                XposedBridge.log(
                                    TAG + "Real face authenticator already registered as strong: " +
                                        "sensorId=" + sensorId
                                )
                            }
                        }
                    }
                )
                hooked++
            }

            XposedBridge.log(
                TAG + "Hooked BiometricService.registerAuthenticator overloads for real face: " +
                    hooked
            )
        } catch (e: XposedHelpers.ClassNotFoundError) {
            XposedBridge.log(TAG + "BiometricService class not found.")
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "Error hooking BiometricService: " + t.message)
        }
    }

    private data class SensorPriorityResult(
        val changed: Boolean,
        val selectedModality: String,
        val originalCount: Int,
        val finalCount: Int
    ) {
        companion object {
            val UNCHANGED = SensorPriorityResult(false, "unchanged", 0, 0)
        }
    }

    private companion object {
        private const val TAG = "FaceAuthHook: "
        private const val MODALITY_FINGERPRINT = 2
        private const val MODALITY_FACE = 8
        private const val BIOMETRIC_STRONG = 15
        private const val FACE_ACQUIRED_GOOD = 0
        private const val FACE_ACQUIRED_TOO_DARK = 3
        private const val DEFAULT_FINGERPRINT_FACE_DELAY_MS = 5000L
        private const val MIN_FINGERPRINT_FACE_DELAY_MS = 1L
        private const val MAX_FINGERPRINT_FACE_DELAY_MS = 10000L
        private const val FACE_LOW_LIGHT_BLOCK_MS = 120000L
        private const val DISABLE_FILE = "/data/local/tmp/disable_faceauth"
        private const val SETTINGS_FILE = "/data/local/tmp/faceauth_settings.properties"
        private const val FINGERPRINT_ONLY_PACKAGES_FILE =
            "/data/local/tmp/faceauth_fingerprint_only_packages"
        private const val ACTIVE_BOOT_KEY = "faceauthenticator_active_boot"
        private const val ACTIVE_TIME_KEY = "faceauthenticator_active_time"
        private const val ENABLE_SYSTEM_FACE_REGISTRATION_UPGRADE = true
        private const val ENABLE_SYSTEM_CONFIG_AND_PROVIDER_UPGRADE = false
        private const val ENABLE_AUTH_SESSION_FALLBACK = true
        @Volatile
        private var activeMarkerWritten = false
        @Volatile
        private var cachedHookSettings = HookSettings()
        @Volatile
        private var cachedHookSettingsModifiedAt = Long.MIN_VALUE
        private val delayedFaceScheduledSessions: MutableSet<Any> =
            java.util.Collections.synchronizedSet(
                java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())
            )
        private val delayedFaceStartInProgressSessions: MutableSet<Any> =
            java.util.Collections.synchronizedSet(
                java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())
            )
        @Volatile
        private var faceLowLightBlockedUntil = 0L

        private data class HookSettings(
            val delayedFaceEnabled: Boolean = true,
            val faceDelayMs: Long = DEFAULT_FINGERPRINT_FACE_DELAY_MS,
            val showStatusMessages: Boolean = false,
            val instantFaceConfirmation: Boolean = true,
            val keystoreFingerprintOnly: Boolean = false
        )

        private fun getHookSettings(): HookSettings {
            val file = File(SETTINGS_FILE)
            val modifiedAt = try {
                if (file.exists()) file.lastModified() else -1L
            } catch (_: Throwable) {
                -1L
            }

            if (modifiedAt == cachedHookSettingsModifiedAt) {
                return cachedHookSettings
            }

            val settings = readHookSettings(file)
            cachedHookSettings = settings
            cachedHookSettingsModifiedAt = modifiedAt
            return settings
        }

        private fun readHookSettings(file: File): HookSettings {
            if (!file.exists()) {
                return HookSettings()
            }

            return try {
                val values = HashMap<String, String>()
                file.readLines().forEach { line ->
                    val clean = line.substringBefore('#').trim()
                    val separator = clean.indexOf('=')
                    if (separator > 0) {
                        values[clean.substring(0, separator).trim()] =
                            clean.substring(separator + 1).trim()
                    }
                }

                HookSettings(
                    delayedFaceEnabled = parseBooleanSetting(
                        values["delayed_face_enabled"],
                        true
                    ),
                    faceDelayMs = parseDelayMs(values["face_delay_ms"]),
                    showStatusMessages = parseBooleanSetting(
                        values["show_status_messages"],
                        false
                    ),
                    instantFaceConfirmation = parseBooleanSetting(
                        values["instant_face_confirmation"],
                        true
                    ),
                    keystoreFingerprintOnly = parseBooleanSetting(
                        values["keystore_fingerprint_only"],
                        false
                    )
                )
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "Could not read hook settings: " + t.message)
                HookSettings()
            }
        }

        private fun parseDelayMs(rawValue: String?): Long {
            val value = rawValue?.toLongOrNull() ?: DEFAULT_FINGERPRINT_FACE_DELAY_MS
            return value.coerceIn(MIN_FINGERPRINT_FACE_DELAY_MS, MAX_FINGERPRINT_FACE_DELAY_MS)
        }

        private fun parseBooleanSetting(rawValue: String?, fallback: Boolean): Boolean {
            return when (rawValue?.trim()?.lowercase(Locale.US)) {
                "1", "true", "yes", "on", "enabled" -> true
                "0", "false", "no", "off", "disabled" -> false
                else -> fallback
            }
        }

        private fun requiresFingerprintOnly(packageName: String): Boolean {
            return isPackageInFingerprintOnlyFile(packageName)
        }

        private fun isPackageInFingerprintOnlyFile(packageName: String): Boolean {
            if (packageName.isBlank() || packageName == "unknown package") {
                return false
            }

            return try {
                val file = File(FINGERPRINT_ONLY_PACKAGES_FILE)
                file.exists() && file.readLines().any { line ->
                    val entry = line.substringBefore('#').trim()
                    entry == packageName
                }
            } catch (t: Throwable) {
                XposedBridge.log(
                    TAG + "Could not read fingerprint-only package list: " + t.message
                )
                false
            }
        }

        private fun isFaceBlockedByLowLight(): Boolean {
            val now = System.currentTimeMillis()
            val blockedUntil = faceLowLightBlockedUntil
            if (blockedUntil <= now) {
                if (blockedUntil != 0L) {
                    faceLowLightBlockedUntil = 0L
                }
                return false
            }
            return true
        }

        private fun blockFaceForLowLight(reason: String) {
            val blockedUntil = System.currentTimeMillis() + FACE_LOW_LIGHT_BLOCK_MS
            faceLowLightBlockedUntil = blockedUntil
            XposedBridge.log(
                TAG + "Low light detected; using fingerprint only for " +
                    FACE_LOW_LIGHT_BLOCK_MS + "ms. reason=" + reason
            )
        }

        private fun clearFaceLowLightBlock(reason: String) {
            if (faceLowLightBlockedUntil != 0L) {
                faceLowLightBlockedUntil = 0L
                XposedBridge.log(TAG + "Cleared low-light fingerprint-only mode: " + reason)
            }
        }

        private fun markModuleActive(context: android.content.Context?) {
            if (context == null || activeMarkerWritten) {
                return
            }

            try {
                val resolver = context.contentResolver
                val bootCount = android.provider.Settings.Global.getInt(
                    resolver,
                    android.provider.Settings.Global.BOOT_COUNT,
                    -1
                )
                android.provider.Settings.Global.putInt(resolver, ACTIVE_BOOT_KEY, bootCount)
                android.provider.Settings.Global.putLong(
                    resolver,
                    ACTIVE_TIME_KEY,
                    System.currentTimeMillis()
                )
                activeMarkerWritten = true
                XposedBridge.log(TAG + "Active marker written for boot=" + bootCount)
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "Could not write active marker: " + t.message)
            }
        }

        private fun getContextFromService(service: Any?): android.content.Context? {
            if (service == null) {
                return null
            }

            return try {
                XposedHelpers.callMethod(service, "getContext") as? android.content.Context
            } catch (_: Throwable) {
                try {
                    getField(service, "mContext") as? android.content.Context
                } catch (_: Throwable) {
                    null
                }
            }
        }

        private fun isDisableFilePresent(): Boolean {
            return try {
                File(DISABLE_FILE).exists()
            } catch (_: Throwable) {
                false
            }
        }

        private fun patchBiometricSensorConstructorArgs(args: Array<Any?>?) {
            val constructorArgs = args ?: return
            if (!isIntegerAt(constructorArgs, 1) ||
                !isIntegerAt(constructorArgs, 2) ||
                !isIntegerAt(constructorArgs, 3)
            ) {
                return
            }

            val sensorId = constructorArgs[1] as Int
            val modality = constructorArgs[2] as Int
            val strength = constructorArgs[3] as Int

            if (modality == MODALITY_FACE && strength != BIOMETRIC_STRONG) {
                XposedBridge.log(
                    TAG + "BiometricSensor constructor: face sensorId=" +
                        sensorId + " strength " + strength + " -> " + BIOMETRIC_STRONG
                )
                constructorArgs[3] = BIOMETRIC_STRONG
            }
        }

        private fun isFaceBiometricSensor(sensor: Any?): Boolean {
            return sensor != null && getSensorModality(sensor) == MODALITY_FACE
        }

        private fun isFingerprintBiometricSensor(sensor: Any?): Boolean {
            return sensor != null && getSensorModality(sensor) == MODALITY_FINGERPRINT
        }

        private fun patchFaceBiometricSensor(sensor: Any?, source: String) {
            if (!isFaceBiometricSensor(sensor)) {
                return
            }

            val sensorId = getSensorId(sensor!!)
            val oemStrength = getIntField(sensor, "oemStrength", -1)
            val updatedStrength = getIntField(sensor, "mUpdatedStrength", -1)
            var changed = false

            if (oemStrength != BIOMETRIC_STRONG) {
                changed = changed or setIntField(sensor, "oemStrength", BIOMETRIC_STRONG)
            }
            if (updatedStrength != BIOMETRIC_STRONG) {
                changed = changed or setIntField(sensor, "mUpdatedStrength", BIOMETRIC_STRONG)
            }

            if (changed) {
                XposedBridge.log(
                    TAG + "Patched real face BiometricSensor from " + source +
                        ": sensorId=" + sensorId + ", oemStrength=" + oemStrength +
                        ", updatedStrength=" + updatedStrength + " -> " + BIOMETRIC_STRONG
                )
            }
        }

        private fun findFaceSensorsInPreAuthInfo(
            preAuthInfo: Any?,
            includeIneligible: Boolean = true
        ): List<Any?> {
            if (preAuthInfo == null) {
                return emptyList()
            }

            val faceSensors = ArrayList<Any?>()
            val visited = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<Any, Boolean>()
            )
            val fieldNames = if (includeIneligible) {
                arrayOf(
                    "eligibleSensors",
                    "mEligibleSensors",
                    "ineligibleSensors",
                    "mIneligibleSensors"
                )
            } else {
                arrayOf("eligibleSensors", "mEligibleSensors")
            }

            for (fieldName in fieldNames) {
                collectFaceSensors(
                    getFieldOrNull(preAuthInfo, fieldName),
                    faceSensors,
                    visited,
                    0
                )
            }

            return faceSensors
        }

        private fun collectFaceSensors(
            value: Any?,
            faceSensors: ArrayList<Any?>,
            visited: MutableSet<Any>,
            depth: Int
        ) {
            if (value == null || depth > 4) {
                return
            }

            if (isFaceBiometricSensor(value)) {
                addUniqueSensor(faceSensors, value)
                return
            }

            if (value is Array<*>) {
                for (item in value) {
                    collectFaceSensors(item, faceSensors, visited, depth + 1)
                }
                return
            }

            if (value is Iterable<*>) {
                for (item in value) {
                    collectFaceSensors(item, faceSensors, visited, depth + 1)
                }
                return
            }

            if (value is Map<*, *>) {
                for (entry in value.entries) {
                    collectFaceSensors(entry.key, faceSensors, visited, depth + 1)
                    collectFaceSensors(entry.value, faceSensors, visited, depth + 1)
                }
                return
            }

            if (value.javaClass.isPrimitive ||
                value is Number ||
                value is Boolean ||
                value is String ||
                !visited.add(value)
            ) {
                return
            }

            val nestedFieldNames = arrayOf(
                "first",
                "second",
                "sensor",
                "mSensor",
                "biometricSensor",
                "mBiometricSensor"
            )
            for (fieldName in nestedFieldNames) {
                collectFaceSensors(
                    getFieldOrNull(value, fieldName),
                    faceSensors,
                    visited,
                    depth + 1
                )
            }
        }

        private fun addUniqueSensor(sensors: ArrayList<Any?>, sensor: Any?) {
            if (!containsSameSensor(sensors, sensor)) {
                sensors.add(sensor)
            }
        }

        private fun containsSameSensor(sensors: List<Any?>, sensor: Any?): Boolean {
            for (existing in sensors) {
                if (existing === sensor) {
                    return true
                }
                if (existing != null &&
                    sensor != null &&
                    getSensorId(existing) == getSensorId(sensor) &&
                    getSensorModality(existing) == getSensorModality(sensor)
                ) {
                    return true
                }
            }
            return false
        }

        private fun handleFaceAcquired(authSession: Any?, args: Array<Any?>?) {
            val sensorId = getIntArg(args, 0, -1)
            val acquiredInfo = getIntArg(args, 1, -1)
            if (!isFaceSensorIdForAuthSession(authSession, sensorId)) {
                return
            }

            if (acquiredInfo == FACE_ACQUIRED_GOOD) {
                clearFaceLowLightBlock("face acquired good")
                return
            }

            if (acquiredInfo == FACE_ACQUIRED_TOO_DARK) {
                blockFaceForLowLight("face acquired too dark")
                stopFaceSensorsForCurrentSession(authSession)
                if (getHookSettings().showStatusMessages) {
                    showBiometricHelp(
                        authSession,
                        MODALITY_FINGERPRINT,
                        lowLightFingerprintOnlyMessage()
                    )
                }
            }
        }

        private fun handleFaceAuthResult(
            methodName: String,
            authSession: Any?,
            args: Array<Any?>?
        ) {
            val sensorId = getIntArg(args, 0, -1)
            if (!isFaceSensorIdForAuthSession(authSession, sensorId)) {
                return
            }

            if (methodName == "onAuthenticationSucceeded") {
                clearFaceLowLightBlock("face authentication succeeded")
            }
        }

        private fun isFaceSensorIdForAuthSession(authSession: Any?, sensorId: Int): Boolean {
            if (sensorId < 0) {
                return false
            }

            for (sensor in getFaceSensorsFromAuthSession(authSession)) {
                if (getSensorId(sensor!!) == sensorId) {
                    return true
                }
            }
            return false
        }

        private fun stopFaceSensorsForCurrentSession(authSession: Any?) {
            var stopped = 0
            for (sensor in getFaceSensorsFromAuthSession(authSession)) {
                if (stopFaceSensor(sensor)) {
                    stopped++
                }
            }

            if (stopped > 0) {
                XposedBridge.log(TAG + "Stopped face sensors for low-light fallback: " + stopped)
            }
        }

        private fun stopFaceSensor(sensor: Any?): Boolean {
            if (sensor == null) {
                return false
            }

            val methodNames = arrayOf("stopSensor", "goToStateStopped")
            for (methodName in methodNames) {
                try {
                    XposedHelpers.callMethod(sensor, methodName)
                    return true
                } catch (_: Throwable) {
                }
            }
            return false
        }

        private fun preferFingerprintThenDelayedFace(preAuthInfo: Any?): SensorPriorityResult {
            if (preAuthInfo == null) {
                return SensorPriorityResult.UNCHANGED
            }

            val eligibleObject = try {
                XposedHelpers.getObjectField(preAuthInfo, "eligibleSensors")
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "Unable to read PreAuthInfo.eligibleSensors: " + t.message)
                return SensorPriorityResult.UNCHANGED
            }

            if (eligibleObject !is List<*>) {
                return SensorPriorityResult.UNCHANGED
            }

            val eligibleSensors = eligibleObject
            if (eligibleSensors.isEmpty()) {
                return SensorPriorityResult.UNCHANGED
            }

            val fingerprintSensors = ArrayList<Any?>()
            val faceSensors = ArrayList<Any?>()
            val otherSensors = ArrayList<Any?>()
            for (sensor in eligibleSensors) {
                if (isFingerprintBiometricSensor(sensor)) {
                    fingerprintSensors.add(sensor)
                } else if (isFaceBiometricSensor(sensor)) {
                    patchFaceBiometricSensor(sensor, "eligibleSensors")
                    faceSensors.add(sensor)
                } else {
                    otherSensors.add(sensor)
                }
            }

            if (faceSensors.isEmpty() || fingerprintSensors.isEmpty()) {
                return SensorPriorityResult.UNCHANGED
            }

            val selectedSensors = ArrayList<Any?>(
                faceSensors.size + fingerprintSensors.size + otherSensors.size
            )
            selectedSensors.addAll(fingerprintSensors)
            selectedSensors.addAll(faceSensors)
            selectedSensors.addAll(otherSensors)

            val originalCount = eligibleSensors.size
            if (isSameSensorList(eligibleSensors, selectedSensors)) {
                return SensorPriorityResult.UNCHANGED
            }

            if (!replaceEligibleSensors(preAuthInfo, eligibleSensors, selectedSensors)) {
                return SensorPriorityResult.UNCHANGED
            }

            return SensorPriorityResult(
                true,
                "fingerprint first, delayed face fallback",
                originalCount,
                selectedSensors.size
            )
        }

        private fun hookPromptInfoClass(classLoader: ClassLoader) {
            try {
                val promptInfoClass = XposedHelpers.findClass(
                    "android.hardware.biometrics.PromptInfo",
                    classLoader
                )
                val methodsToOverride = arrayOf(
                    "isConfirmationRequired",
                    "isConfirmationRequested",
                    "requireConfirmation",
                    "isRequireConfirmation"
                )
                for (methodName in methodsToOverride) {
                    for (method in promptInfoClass.declaredMethods) {
                        if (method.name == methodName && method.parameterTypes.isEmpty()) {
                            XposedBridge.hookMethod(
                                method,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        val settings = getHookSettings()
                                        if (settings.instantFaceConfirmation) {
                                            param.setResult(false)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
                XposedBridge.log(TAG + "Hooked PromptInfo confirmation methods.")
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "Error hooking PromptInfo class: " + t.message)
            }
        }

        private fun hookAuthSessionConfirmation(classLoader: ClassLoader) {
            val classNames = arrayOf(
                "com.android.server.biometrics.AuthSession",
                "com.android.server.biometrics.BiometricService\$AuthSession"
            )
            for (className in classNames) {
                try {
                    val clazz = XposedHelpers.findClass(className, classLoader)
                    for (method in clazz.declaredMethods) {
                        if ((method.name == "isConfirmationRequired" || method.name == "requireConfirmation") &&
                            method.returnType == Boolean::class.javaPrimitiveType
                        ) {
                            XposedBridge.hookMethod(
                                method,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        val settings = getHookSettings()
                                        if (settings.instantFaceConfirmation) {
                                            param.setResult(false)
                                        }
                                    }
                                }
                            )
                        }
                    }
                    XposedBridge.log(TAG + "Hooked " + className + " confirmation methods.")
                } catch (_: Throwable) {
                }
            }
        }

        private fun hookSystemUiFaceConfirmation(classLoader: ClassLoader) {
            XposedBridge.log(TAG + "Loading SystemUI face confirmation bypass hooks.")

            val targetClasses = arrayOf(
                "com.android.systemui.biometrics.AuthBiometricFaceView",
                "com.android.systemui.biometrics.AuthBiometricView",
                "com.android.systemui.biometrics.AuthBiometricFingerprintAndFaceView",
                "com.android.systemui.biometrics.AuthContainerView",
                "com.android.systemui.biometrics.AuthController"
            )

            for (className in targetClasses) {
                try {
                    val clazz = XposedHelpers.findClass(className, classLoader)
                    val methodNames = arrayOf(
                        "requireConfirmation",
                        "isConfirmationRequired",
                        "needConfirmation",
                        "getRequireConfirmation"
                    )

                    for (method in clazz.declaredMethods) {
                        if (methodNames.contains(method.name) && method.returnType == Boolean::class.javaPrimitiveType) {
                            XposedBridge.hookMethod(
                                method,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        val settings = getHookSettings()
                                        if (settings.instantFaceConfirmation) {
                                            param.setResult(false)
                                        }
                                    }
                                }
                            )
                        }
                    }

                    XposedBridge.hookAllConstructors(
                        clazz,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val settings = getHookSettings()
                                if (settings.instantFaceConfirmation) {
                                    val target = param.thisObject ?: return
                                    setBooleanField(target, "mRequireConfirmation", false)
                                    setBooleanField(target, "mConfirmationRequired", false)
                                    setBooleanField(target, "mIsConfirmationRequired", false)
                                    setBooleanField(target, "requireConfirmation", false)
                                    setBooleanField(target, "confirmationRequired", false)
                                }
                            }
                        }
                    )

                    XposedBridge.log(TAG + "Hooked SystemUI class: " + className)
                } catch (_: XposedHelpers.ClassNotFoundError) {
                } catch (t: Throwable) {
                    XposedBridge.log(TAG + "Error hooking SystemUI class " + className + ": " + t.message)
                }
            }
        }

        private fun patchPromptInfoForInstantFaceConfirmation(args: Array<Any?>?) {
            if (args == null) {
                return
            }

            for (arg in args) {
                if (!isPromptInfoObject(arg)) {
                    continue
                }

                var changed = false
                try {
                    XposedHelpers.callMethod(arg, "setIsConfirmationRequired", false)
                    changed = true
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.callMethod(arg, "setConfirmationRequired", false)
                    changed = true
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.callMethod(arg, "setConfirmationRequested", false)
                    changed = true
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.callMethod(arg, "setRequireConfirmation", false)
                    changed = true
                } catch (_: Throwable) {
                }

                changed = setBooleanField(arg!!, "mIsConfirmationRequired", false) || changed
                changed = setBooleanField(arg, "mRequireConfirmation", false) || changed
                changed = setBooleanField(arg, "mConfirmationRequired", false) || changed
                changed = setBooleanField(arg, "mConfirmationRequested", false) || changed
                changed = setBooleanField(arg, "mIsConfirmationRequested", false) || changed
                changed = setBooleanField(arg, "isConfirmationRequired", false) || changed
                changed = setBooleanField(arg, "requireConfirmation", false) || changed
                changed = setBooleanField(arg, "confirmationRequired", false) || changed
                changed = setBooleanField(arg, "confirmationRequested", false) || changed

                if (changed) {
                    XposedBridge.log(TAG + "Requested instant face confirmation for PromptInfo.")
                }
                return
            }
        }

        private fun isPromptInfoObject(value: Any?): Boolean {
            if (value == null) {
                return false
            }
            val className = value.javaClass.name
            return className == "android.hardware.biometrics.PromptInfo" ||
                className.endsWith(".PromptInfo") ||
                className.contains("PromptInfo") ||
                hasField(value, "mConfirmationRequested") ||
                hasField(value, "confirmationRequested") ||
                hasField(value, "mIsConfirmationRequired") ||
                hasField(value, "mRequireConfirmation")
        }

        private fun hasHardwareBackedCryptoOperation(
            method: java.lang.reflect.Method,
            args: Array<Any?>?
        ): Boolean {
            val operationId = findCryptoOperationId(method, args)
            if (operationId > 0L) {
                XposedBridge.log(
                    TAG + "Detected hardware-backed crypto operationId=" + operationId
                )
                return true
            }
            return false
        }

        private fun findCryptoOperationId(
            method: java.lang.reflect.Method,
            args: Array<Any?>?
        ): Long {
            if (args == null) {
                return 0L
            }

            val directOperationId = findDirectAuthenticateInternalOperationId(method, args)
            if (directOperationId > 0L) {
                return directOperationId
            }

            val visited = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<Any, Boolean>()
            )
            for (arg in args) {
                val operationId = findCryptoOperationIdInObject(arg, 0, visited)
                if (operationId > 0L) {
                    return operationId
                }
            }
            return 0L
        }

        private fun findDirectAuthenticateInternalOperationId(
            method: java.lang.reflect.Method,
            args: Array<Any?>
        ): Long {
            val types = method.parameterTypes
            if (args.size > 3 &&
                isLongParameter(types, 1) &&
                isLongParameter(types, 2) &&
                isIntParameter(types, 3)
            ) {
                return args[2] as? Long ?: 0L
            }

            if (args.size > 2 &&
                isLongParameter(types, 1) &&
                isIntParameter(types, 2)
            ) {
                return args[1] as? Long ?: 0L
            }

            val directLongIndices = ArrayList<Int>()
            for (i in args.indices) {
                if (isLongParameter(types, i) && args[i] is Long) {
                    directLongIndices.add(i)
                }
            }

            if (directLongIndices.size == 1) {
                return args[directLongIndices[0]] as? Long ?: 0L
            }

            return 0L
        }

        private fun isLongParameter(types: Array<Class<*>>, index: Int): Boolean {
            if (index >= types.size) {
                return false
            }
            val type = types[index]
            return type == java.lang.Long.TYPE || type == java.lang.Long::class.java
        }

        private fun isIntParameter(types: Array<Class<*>>, index: Int): Boolean {
            if (index >= types.size) {
                return false
            }
            val type = types[index]
            return type == java.lang.Integer.TYPE || type == java.lang.Integer::class.java
        }

        private fun findCryptoOperationIdInObject(
            value: Any?,
            depth: Int,
            visited: MutableSet<Any>
        ): Long {
            if (value == null || depth > 2) {
                return 0L
            }

            if (value is Array<*>) {
                for (item in value) {
                    val operationId = findCryptoOperationIdInObject(item, depth + 1, visited)
                    if (operationId > 0L) {
                        return operationId
                    }
                }
                return 0L
            }

            if (value is Iterable<*>) {
                for (item in value) {
                    val operationId = findCryptoOperationIdInObject(item, depth + 1, visited)
                    if (operationId > 0L) {
                        return operationId
                    }
                }
                return 0L
            }

            if (value is Map<*, *>) {
                for (item in value.values) {
                    val operationId = findCryptoOperationIdInObject(item, depth + 1, visited)
                    if (operationId > 0L) {
                        return operationId
                    }
                }
                return 0L
            }

            if (value.javaClass.isPrimitive ||
                value is Number ||
                value is Boolean ||
                value is String ||
                !visited.add(value)
            ) {
                return 0L
            }

            val methodNames = arrayOf("getOperationId", "getCryptoOperationId")
            for (methodName in methodNames) {
                try {
                    val result = XposedHelpers.callMethod(value, methodName)
                    if (result is Long && result > 0L) {
                        return result
                    }
                } catch (_: Throwable) {
                }
            }

            val fieldNames = arrayOf(
                "operationId",
                "mOperationId",
                "cryptoOperationId",
                "mCryptoOperationId",
                "operationHandle",
                "mOperationHandle"
            )
            for (fieldName in fieldNames) {
                val result = getFieldOrNull(value, fieldName)
                if (result is Long && result > 0L) {
                    return result
                }
            }

            val nestedFieldNames = arrayOf(
                "authenticateOptions",
                "mAuthenticateOptions",
                "options",
                "mOptions",
                "promptInfo",
                "mPromptInfo"
            )
            for (fieldName in nestedFieldNames) {
                val operationId = findCryptoOperationIdInObject(
                    getFieldOrNull(value, fieldName),
                    depth + 1,
                    visited
                )
                if (operationId > 0L) {
                    return operationId
                }
            }

            return 0L
        }

        private fun preferFingerprintOnly(
            preAuthInfo: Any?,
            selectedModality: String = "fingerprint only"
        ): SensorPriorityResult {
            if (preAuthInfo == null) {
                return SensorPriorityResult.UNCHANGED
            }

            val eligibleObject = try {
                XposedHelpers.getObjectField(preAuthInfo, "eligibleSensors")
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "Unable to read PreAuthInfo.eligibleSensors: " + t.message)
                return SensorPriorityResult.UNCHANGED
            }

            if (eligibleObject !is List<*>) {
                return SensorPriorityResult.UNCHANGED
            }

            val eligibleSensors = eligibleObject
            if (eligibleSensors.isEmpty()) {
                return SensorPriorityResult.UNCHANGED
            }

            val selectedSensors = ArrayList<Any?>()
            var removedFace = false
            var hasFingerprint = false
            for (sensor in eligibleSensors) {
                if (isFingerprintBiometricSensor(sensor)) {
                    selectedSensors.add(sensor)
                    hasFingerprint = true
                } else if (isFaceBiometricSensor(sensor)) {
                    removedFace = true
                } else {
                    selectedSensors.add(sensor)
                }
            }

            if (!removedFace || !hasFingerprint || selectedSensors.isEmpty()) {
                return SensorPriorityResult.UNCHANGED
            }

            val originalCount = eligibleSensors.size
            if (!replaceEligibleSensors(preAuthInfo, eligibleSensors, selectedSensors)) {
                return SensorPriorityResult.UNCHANGED
            }

            return SensorPriorityResult(
                true,
                selectedModality,
                originalCount,
                selectedSensors.size
            )
        }

        private fun scheduleDelayedFaceStart(authSession: Any?, settings: HookSettings) {
            if (authSession == null) {
                return
            }

            if (!hasEligibleFingerprintAndFace(authSession)) {
                XposedBridge.log(
                    TAG + "Skipped delayed face scheduling because this session does not expose " +
                        "both fingerprint and face as eligible sensors."
                )
                return
            }

            synchronized(delayedFaceScheduledSessions) {
                if (!delayedFaceScheduledSessions.add(authSession)) {
                    return
                }
            }

            XposedBridge.log(
                TAG + "Scheduled face fallback after " + settings.faceDelayMs + "ms."
            )

            val handler = Handler(Looper.getMainLooper())
            if (settings.showStatusMessages) {
                handler.post(
                    {
                        showBiometricHelp(
                            authSession,
                            MODALITY_FINGERPRINT,
                            fingerprintDelayMessage(settings.faceDelayMs)
                        )
                    }
                )
            }
            handler.postDelayed(
                {
                    startDelayedFaceSensors(authSession, settings)
                },
                settings.faceDelayMs
            )
        }

        private fun startDelayedFaceSensors(authSession: Any?, settings: HookSettings) {
            if (authSession == null || !isAuthSessionStillWaiting(authSession)) {
                return
            }

            val faceSensors = getFaceSensorsFromAuthSession(authSession)
            if (faceSensors.isEmpty()) {
                if (settings.showStatusMessages) {
                    showBiometricHelp(
                        authSession,
                        MODALITY_FACE,
                        faceUnavailableMessage()
                    )
                }
                XposedBridge.log(
                    TAG + "Cannot start delayed face fallback: no real face sensor is " +
                        "registered on this device/session."
                )
                return
            }

            var started = startPreparedFaceSensorsThroughAuthSession(authSession, faceSensors)
            if (started == 0) {
                started = startPreparedFaceSensorsDirectly(faceSensors)
            }

            if (started > 0) {
                if (settings.showStatusMessages) {
                    showBiometricHelp(
                        authSession,
                        MODALITY_FACE,
                        faceFallbackStartedMessage()
                    )
                }
                XposedBridge.log(
                    TAG + "Requested delayed face fallback after " +
                        settings.faceDelayMs + "ms, sensors=" + started
                )
            }
        }

        private fun startPreparedFaceSensorsThroughAuthSession(
            authSession: Any,
            faceSensors: List<Any?>
        ): Int {
            synchronized(delayedFaceStartInProgressSessions) {
                delayedFaceStartInProgressSessions.add(authSession)
            }
            try {
                val faceFilter = java.util.function.Function<Any, Boolean> { sensor ->
                    isFaceBiometricSensor(sensor)
                }
                XposedHelpers.callMethod(authSession, "startAllPreparedSensors", faceFilter)
                return faceSensors.size
            } catch (t: Throwable) {
                XposedBridge.log(
                    TAG + "Unable to start face via AuthSession.startAllPreparedSensors: " +
                        t.message
                )
            } finally {
                synchronized(delayedFaceStartInProgressSessions) {
                    delayedFaceStartInProgressSessions.remove(authSession)
                }
            }

            synchronized(delayedFaceStartInProgressSessions) {
                delayedFaceStartInProgressSessions.add(authSession)
            }
            try {
                XposedHelpers.callMethod(authSession, "startAllPreparedSensorsExceptFingerprint")
                return faceSensors.size
            } catch (t: Throwable) {
                XposedBridge.log(
                    TAG + "Unable to start face via AuthSession non-fingerprint path: " +
                        t.message
                )
            } finally {
                synchronized(delayedFaceStartInProgressSessions) {
                    delayedFaceStartInProgressSessions.remove(authSession)
                }
            }

            return 0
        }

        private fun startPreparedFaceSensorsDirectly(faceSensors: List<Any?>): Int {
            var started = 0
            for (sensor in faceSensors) {
                try {
                    XposedHelpers.callMethod(sensor, "startSensor")
                    started++
                } catch (t: Throwable) {
                    XposedBridge.log(
                        TAG + "Unable to start delayed face sensor " +
                            getSensorId(sensor!!) + ": " + t.message
                    )
                }
            }
            return started
        }

        private fun isDelayedFaceStartInProgress(authSession: Any?): Boolean {
            if (authSession == null) {
                return false
            }
            synchronized(delayedFaceStartInProgressSessions) {
                return delayedFaceStartInProgressSessions.contains(authSession)
            }
        }

        private fun showBiometricHelp(authSession: Any?, modality: Int, message: String) {
            if (authSession == null || !isAuthSessionStillWaiting(authSession)) {
                return
            }

            try {
                val statusBarService = getField(authSession, "mStatusBarService") ?: return
                XposedHelpers.callMethod(
                    statusBarService,
                    "onBiometricHelp",
                    modality,
                    message
                )
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "Unable to show biometric delay message: " + t.message)
            }
        }

        private fun hasEligibleFingerprintAndFace(authSession: Any?): Boolean {
            val eligibleSensors = getEligibleSensorsFromAuthSession(authSession) ?: return false
            var hasFingerprint = false
            var hasFace = false
            for (sensor in eligibleSensors) {
                if (isFingerprintBiometricSensor(sensor)) {
                    hasFingerprint = true
                } else if (isFaceBiometricSensor(sensor)) {
                    hasFace = true
                }

                if (hasFingerprint && hasFace) {
                    return true
                }
            }
            return false
        }

        private fun hasEligibleFingerprintSensor(authSession: Any?): Boolean {
            val eligibleSensors = getEligibleSensorsFromAuthSession(authSession) ?: return false
            for (sensor in eligibleSensors) {
                if (isFingerprintBiometricSensor(sensor)) {
                    return true
                }
            }
            return false
        }

        private fun hasAnyFaceSensorForAuthSession(authSession: Any?): Boolean {
            return getFaceSensorsFromAuthSession(authSession).isNotEmpty()
        }

        private fun getFaceSensorsFromAuthSession(authSession: Any?): List<Any?> {
            val preAuthInfo = getPreAuthInfoFromAuthSession(authSession) ?: return emptyList()
            return findFaceSensorsInPreAuthInfo(preAuthInfo, includeIneligible = false)
        }

        private fun getEligibleSensorsFromAuthSession(authSession: Any?): List<*>? {
            val preAuthInfo = getPreAuthInfoFromAuthSession(authSession) ?: return null
            return getFieldOrNull(preAuthInfo, "eligibleSensors") as? List<*>
                ?: getFieldOrNull(preAuthInfo, "mEligibleSensors") as? List<*>
        }

        private fun getPreAuthInfoFromAuthSession(authSession: Any?): Any? {
            if (authSession == null) {
                return null
            }
            return getFieldOrNull(authSession, "mPreAuthInfo")
                ?: getFieldOrNull(authSession, "preAuthInfo")
        }

        private fun isAuthSessionStillWaiting(authSession: Any): Boolean {
            val authenticatedSensorId = getIntField(authSession, "mAuthenticatedSensorId", -1)
            if (authenticatedSensorId != -1) {
                return false
            }
            if (getBooleanField(authSession, "mCancelled", false)) {
                return false
            }
            return true
        }

        private fun fingerprintDelayMessage(delayMs: Long): String {
            val seconds = formatDelaySeconds(delayMs)
            return if (isArabicLocale()) {
                "استخدم بصمة الإصبع أولاً. إذا لم توجد استجابة خلال " +
                    seconds + " ثانية، سيتم طلب التعرف على الوجه."
            } else {
                "Use fingerprint first. If there is no response within $seconds seconds, face unlock will be requested."
            }
        }

        private fun formatDelaySeconds(delayMs: Long): String {
            val seconds = delayMs / 1000.0
            return if (seconds >= 1.0) {
                String.format(Locale.US, "%.3f", seconds).trimEnd('0').trimEnd('.')
            } else {
                String.format(Locale.US, "%.3f", seconds)
            }
        }

        private fun faceFallbackStartedMessage(): String {
            return if (isArabicLocale()) {
                "انتهت مهلة بصمة الإصبع. يتم الآن طلب التعرف على الوجه."
            } else {
                "Fingerprint timeout reached. Face unlock is now being requested."
            }
        }

        private fun faceUnavailableMessage(): String {
            return if (isArabicLocale()) {
                "انتهت مهلة بصمة الإصبع، لكن لا يوجد مستشعر وجه حقيقي متاح على هذا الجهاز."
            } else {
                "Fingerprint timeout reached, but no real face sensor is available on this device."
            }
        }

        private fun lowLightFingerprintOnlyMessage(): String {
            return if (isArabicLocale()) {
                "الإضاءة منخفضة جدا للتعرف على الوجه. استخدم بصمة الإصبع."
            } else {
                "Light is too low for face unlock. Use fingerprint."
            }
        }

        private fun isArabicLocale(): Boolean {
            return Locale.getDefault().language.equals("ar", ignoreCase = true)
        }

        private fun findAuthSessionArgument(args: Array<Any?>?): Any? {
            if (args == null) {
                return null
            }

            for (arg in args) {
                if (arg != null) {
                    val className = arg.javaClass.name
                    if (className == "com.android.server.biometrics.AuthSession" ||
                        className == "com.android.server.biometrics.BiometricService\$AuthSession"
                    ) {
                        return arg
                    }
                }
            }
            return null
        }

        private fun findPreAuthInfoArgument(args: Array<Any?>?, service: Any? = null): Any? {
            val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            if (args != null) {
                for (arg in args) {
                    val found = findPreAuthInfoInObject(arg, 0, visited)
                    if (found != null) {
                        return found
                    }
                }
            }
            if (service != null) {
                val found = findPreAuthInfoInObject(service, 0, visited)
                if (found != null) {
                    return found
                }
            }
            return null
        }

        private fun findPreAuthInfoInObject(
            value: Any?,
            depth: Int,
            visited: MutableSet<Any>
        ): Any? {
            if (value == null || depth > 5 || !visited.add(value)) {
                return null
            }

            val className = value.javaClass.name
            if (className == "com.android.server.biometrics.PreAuthInfo" ||
                className.contains("PreAuthInfo") ||
                hasField(value, "eligibleSensors") ||
                hasField(value, "mEligibleSensors") ||
                hasField(value, "preAuthInfo") ||
                hasField(value, "mPreAuthInfo")
            ) {
                val nestedPreAuthInfo = getFieldOrNull(value, "preAuthInfo")
                    ?: getFieldOrNull(value, "mPreAuthInfo")
                if (nestedPreAuthInfo != null) {
                    return nestedPreAuthInfo
                }
                if (className == "com.android.server.biometrics.PreAuthInfo" ||
                    className.contains("PreAuthInfo") ||
                    hasField(value, "eligibleSensors") ||
                    hasField(value, "mEligibleSensors")
                ) {
                    return value
                }
            }

            if (value is Array<*>) {
                for (item in value) {
                    val found = findPreAuthInfoInObject(item, depth + 1, visited)
                    if (found != null) {
                        return found
                    }
                }
            } else if (value is List<*>) {
                for (item in value) {
                    val found = findPreAuthInfoInObject(item, depth + 1, visited)
                    if (found != null) {
                        return found
                    }
                }
            } else if (value is Map<*, *>) {
                for (item in value.values) {
                    val found = findPreAuthInfoInObject(item, depth + 1, visited)
                    if (found != null) {
                        return found
                    }
                }
            }

            return null
        }

        private fun findLikelyPackageName(args: Array<Any?>?, service: Any? = null): String {
            if (args == null) {
                return "unknown package"
            }

            findPackageNameFromUid(args, service)?.let { return it }

            val fallbackVisited = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<Any, Boolean>()
            )
            for (arg in args) {
                findPackageNameInObject(arg, false, 0, fallbackVisited)?.let { return it }
            }

            return "unknown package"
        }

        private fun findPackageNameInObject(
            value: Any?,
            knownOnly: Boolean,
            depth: Int,
            visited: MutableSet<Any>
        ): String? {
            if (value == null || depth > 3) {
                return null
            }

            if (value is String) {
                return packageNameOrNull(value, knownOnly)
            }

            if (value is Array<*>) {
                for (item in value) {
                    findPackageNameInObject(item, knownOnly, depth + 1, visited)?.let { return it }
                }
                return null
            }

            if (value is Iterable<*>) {
                for (item in value) {
                    findPackageNameInObject(item, knownOnly, depth + 1, visited)?.let { return it }
                }
                return null
            }

            val javaClass = value.javaClass
            if (javaClass.isPrimitive ||
                value is Number ||
                value is Boolean ||
                !visited.add(value)
            ) {
                return null
            }

            val methodNames = arrayOf(
                "getOpPackageName",
                "getPackageName",
                "getCallingPackage",
                "getAttributionSource",
                "getNext",
                "getOwnerString"
            )
            for (methodName in methodNames) {
                try {
                    val result = XposedHelpers.callMethod(value, methodName)
                    findPackageNameInObject(result, knownOnly, depth + 1, visited)?.let {
                        return it
                    }
                } catch (_: Throwable) {
                }
            }

            val fieldNames = arrayOf(
                "opPackageName",
                "mOpPackageName",
                "packageName",
                "mPackageName",
                "callingPackage",
                "mCallingPackage",
                "attributionSource",
                "mAttributionSource",
                "authenticateOptions",
                "mAuthenticateOptions",
                "options",
                "mOptions",
                "owner",
                "mOwner"
            )
            for (fieldName in fieldNames) {
                try {
                    val result = getField(value, fieldName)
                    findPackageNameInObject(result, knownOnly, depth + 1, visited)?.let {
                        return it
                    }
                } catch (_: Throwable) {
                }
            }

            return null
        }

        private fun packageNameOrNull(value: String, knownOnly: Boolean): String? {
            if (knownOnly || value.indexOf('.') <= 0 || value.indexOf('/') >= 0) {
                return null
            }
            if (value.startsWith("com.android.server.") || value.startsWith("android.")) {
                return null
            }
            return value
        }

        private fun findPackageNameFromUid(args: Array<Any?>, service: Any?): String? {
            val context = try {
                getField(service ?: return null, "mContext") as? android.content.Context
            } catch (_: Throwable) {
                null
            } ?: return null

            for (arg in args) {
                val uid = findLikelyUid(arg, 0)
                if (uid > 0) {
                    try {
                        val packages = context.packageManager.getPackagesForUid(uid)
                        if (packages != null) {
                            for (packageName in packages) {
                                if (packageNameOrNull(packageName, false) != null) {
                                    return packageName
                                }
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
            return null
        }

        private fun findLikelyUid(value: Any?, depth: Int): Int {
            if (value == null || depth > 2) {
                return -1
            }

            if (value is Int) {
                return if (value >= 10000) value else -1
            }

            if (value is Array<*>) {
                for (item in value) {
                    val uid = findLikelyUid(item, depth + 1)
                    if (uid > 0) {
                        return uid
                    }
                }
                return -1
            }

            val fieldNames = arrayOf("uid", "mUid", "callingUid", "mCallingUid")
            for (fieldName in fieldNames) {
                try {
                    val uid = getIntField(value, fieldName, -1)
                    if (uid >= 10000) {
                        return uid
                    }
                } catch (_: Throwable) {
                }
            }

            return -1
        }

        private fun isSameSensorList(currentSensors: List<*>, selectedSensors: List<*>): Boolean {
            if (currentSensors.size != selectedSensors.size) {
                return false
            }

            for (i in currentSensors.indices) {
                if (currentSensors[i] !== selectedSensors[i]) {
                    return false
                }
            }

            return true
        }

        private fun replaceEligibleSensors(
            preAuthInfo: Any,
            currentSensors: List<*>,
            selectedSensors: ArrayList<Any?>
        ): Boolean {
            try {
                @Suppress("UNCHECKED_CAST")
                val mutableSensors = currentSensors as MutableList<Any?>
                mutableSensors.clear()
                mutableSensors.addAll(selectedSensors)
                return true
            } catch (_: Throwable) {
                try {
                    XposedHelpers.setObjectField(
                        preAuthInfo,
                        "eligibleSensors",
                        ArrayList(selectedSensors)
                    )
                    return true
                } catch (fieldError: Throwable) {
                    XposedBridge.log(
                        TAG + "Unable to replace PreAuthInfo.eligibleSensors: " +
                            fieldError.message
                    )
                    return false
                }
            }
        }

        private fun upgradeExistingFaceConfigs(originalConfig: Array<String>?): Array<String>? {
            if (originalConfig == null || originalConfig.isEmpty()) {
                XposedBridge.log(TAG + "No biometric config entries found; nothing to add.")
                return originalConfig
            }

            var upgradedConfig: Array<String>? = null
            var foundFace = false

            for (i in originalConfig.indices) {
                val config = originalConfig[i]
                val upgraded = upgradeFaceConfigEntry(config) ?: config
                if (upgraded !== config) {
                    if (upgradedConfig == null) {
                        upgradedConfig = originalConfig.clone()
                    }
                    upgradedConfig[i] = upgraded
                    foundFace = true
                } else if (isFaceConfig(config)) {
                    foundFace = true
                }
            }

            if (!foundFace) {
                XposedBridge.log(
                    TAG + "No real face sensor in config_biometric_sensors; leaving config unchanged."
                )
                return originalConfig
            }

            if (upgradedConfig != null) {
                XposedBridge.log(TAG + "Upgraded existing face config strength to BIOMETRIC_STRONG.")
                return upgradedConfig
            }

            XposedBridge.log(TAG + "Existing face config already strong.")
            return originalConfig
        }

        private fun upgradeFaceConfigEntry(config: String?): String? {
            if (config == null) {
                return config
            }

            val parts = splitColon(config)
            if (parts.size < 3) {
                return config
            }

            return try {
                val modality = parts[1].toInt()
                val strength = parts[2].toInt()
                if (modality != MODALITY_FACE || strength == BIOMETRIC_STRONG) {
                    return config
                }

                parts[2] = BIOMETRIC_STRONG.toString()
                joinWithColon(parts)
            } catch (_: NumberFormatException) {
                config
            }
        }

        private fun isFaceConfig(config: String?): Boolean {
            if (config == null) {
                return false
            }

            val parts = splitColon(config)
            if (parts.size < 2) {
                return false
            }

            return try {
                parts[1].toInt() == MODALITY_FACE
            } catch (_: NumberFormatException) {
                false
            }
        }

        private fun upgradeFacePropertyList(original: List<*>?, source: String): List<*>? {
            if (original == null || original.isEmpty()) {
                XposedBridge.log(TAG + source + " returned no real face sensors; leaving unchanged.")
                return original
            }

            var upgraded: ArrayList<Any?>? = null
            for (i in original.indices) {
                val prop = original[i]
                val upgradedProp = upgradeFaceProperty(prop, source)
                if (upgradedProp !== prop) {
                    if (upgraded == null) {
                        upgraded = ArrayList(original)
                    }
                    upgraded[i] = upgradedProp
                }
            }

            return upgraded ?: original
        }

        private fun upgradeFaceProperty(prop: Any?, source: String): Any? {
            if (prop == null) {
                return null
            }

            val strength = getIntField(prop, "sensorStrength", BIOMETRIC_STRONG)
            val sensorId = getIntField(prop, "sensorId", -1)
            if (strength == BIOMETRIC_STRONG) {
                XposedBridge.log(TAG + source + " sensorId=" + sensorId + " already strong.")
                return prop
            }

            val copy = copyFacePropertyWithStrength(prop, BIOMETRIC_STRONG)
            if (copy !== prop) {
                XposedBridge.log(
                    TAG + source + " upgraded real face sensor properties: " +
                        "sensorId=" + sensorId + ", strength=" + strength + " -> " +
                        BIOMETRIC_STRONG
                )
                return copy
            }

            if (setIntField(prop, "sensorStrength", BIOMETRIC_STRONG)) {
                XposedBridge.log(
                    TAG + source + " patched real face sensor field in place: " +
                        "sensorId=" + sensorId + ", strength=" + strength + " -> " +
                        BIOMETRIC_STRONG
                )
            }
            return prop
        }

        private fun copyFacePropertyWithStrength(prop: Any, strength: Int): Any {
            return try {
                val constructor = prop.javaClass.getDeclaredConstructor(
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    java.util.List::class.java,
                    Int::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!
                )
                constructor.isAccessible = true
                constructor.newInstance(
                    getIntField(prop, "sensorId", -1),
                    strength,
                    getIntField(prop, "maxEnrollmentsPerUser", 1),
                    getField(prop, "componentInfo"),
                    getIntField(prop, "sensorType", 0),
                    getBooleanField(prop, "supportsFaceDetection", false),
                    getBooleanField(prop, "supportsSelfIllumination", false),
                    getBooleanField(prop, "resetLockoutRequiresChallenge", false)
                )
            } catch (t: Throwable) {
                XposedBridge.log(
                    TAG + "Could not copy FaceSensorPropertiesInternal; trying in-place patch: " +
                        t.message
                )
                prop
            }
        }

        private fun getSensorModality(sensor: Any): Int {
            val modality = getIntField(sensor, "modality", -1)
            if (modality != -1) {
                return modality
            }
            return getIntField(sensor, "mModality", -1)
        }

        private fun getSensorId(sensor: Any): Int {
            val id = getIntField(sensor, "id", -1)
            if (id != -1) {
                return id
            }
            return getIntField(sensor, "sensorId", -1)
        }

        private fun isIntegerAt(args: Array<Any?>?, index: Int): Boolean {
            return args != null && args.size > index && args[index] is Int
        }

        private fun getIntArg(args: Array<Any?>?, index: Int, fallback: Int): Int {
            return if (args != null && args.size > index) {
                args[index] as? Int ?: fallback
            } else {
                fallback
            }
        }

        private fun hasField(target: Any, fieldName: String): Boolean {
            return try {
                findField(target.javaClass, fieldName)
                true
            } catch (_: Throwable) {
                false
            }
        }

        private fun getField(target: Any, fieldName: String): Any? {
            val field = findField(target.javaClass, fieldName)
            field.isAccessible = true
            return field.get(target)
        }

        private fun getFieldOrNull(target: Any, fieldName: String): Any? {
            return try {
                getField(target, fieldName)
            } catch (_: Throwable) {
                null
            }
        }

        private fun getIntField(target: Any, fieldName: String, fallback: Int): Int {
            return try {
                val value = getField(target, fieldName)
                value as? Int ?: fallback
            } catch (_: Throwable) {
                fallback
            }
        }

        private fun getBooleanField(target: Any, fieldName: String, fallback: Boolean): Boolean {
            return try {
                val value = getField(target, fieldName)
                value as? Boolean ?: fallback
            } catch (_: Throwable) {
                fallback
            }
        }

        private fun setIntField(target: Any, fieldName: String, value: Int): Boolean {
            return try {
                val field = findField(target.javaClass, fieldName)
                field.isAccessible = true
                field.setInt(target, value)
                true
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "Could not set " + fieldName + ": " + t.message)
                false
            }
        }

        private fun setBooleanField(target: Any, fieldName: String, value: Boolean): Boolean {
            return try {
                val field = findField(target.javaClass, fieldName)
                field.isAccessible = true
                field.setBoolean(target, value)
                true
            } catch (_: Throwable) {
                false
            }
        }

        private fun findField(clazz: Class<*>, fieldName: String): Field {
            var current: Class<*>? = clazz
            while (current != null) {
                try {
                    return current.getDeclaredField(fieldName)
                } catch (_: NoSuchFieldException) {
                    current = current.superclass
                }
            }
            throw NoSuchFieldException(fieldName)
        }

        private fun splitColon(value: String): Array<String> {
            val parts = ArrayList<String>()
            var start = 0
            while (true) {
                val index = value.indexOf(':', start)
                if (index < 0) {
                    parts.add(value.substring(start))
                    return parts.toTypedArray()
                }
                parts.add(value.substring(start, index))
                start = index + 1
            }
        }

        private fun joinWithColon(parts: Array<String>): String {
            val builder = StringBuilder()
            for (i in parts.indices) {
                if (i > 0) {
                    builder.append(':')
                }
                builder.append(parts[i])
            }
            return builder.toString()
        }
    }
}
