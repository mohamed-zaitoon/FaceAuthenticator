package com.mohamedzaitoon.faceauthenticator

import android.content.res.Resources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File
import java.lang.reflect.Field

class FaceHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == null ||
            lpparam.packageName == "com.mohamedzaitoon.faceauthenticator"
        ) {
            return
        }

        if (lpparam.packageName != "android") {
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

        if (ENABLE_SYSTEM_CONFIG_AND_PROVIDER_UPGRADE) {
            hookBiometricConfig(lpparam.classLoader)
            hookFaceProviders(lpparam.classLoader)
            hookFaceServiceRegistry(lpparam.classLoader)
            hookFaceService(lpparam.classLoader)
        }
        hookBiometricSensorStrength(lpparam.classLoader)
        hookBiometricService(lpparam.classLoader)
        hookBiometricPromptSensorPriority(lpparam.classLoader)
        hookFingerprintResetLockout(lpparam.classLoader)
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
                            val preAuthInfo = findPreAuthInfoArgument(param.args) ?: return
                            val packageName = findLikelyPackageName(param.args)

                            val result = if (requiresFingerprintOnly(packageName)) {
                                preferFingerprintOnly(preAuthInfo)
                            } else {
                                preferFaceThenFingerprint(preAuthInfo)
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

            XposedBridge.log(TAG + "Hooked BiometricPrompt face-first priority overloads: " + hooked)
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "Error hooking BiometricPrompt face-first priority: " + t.message)
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
        private const val DISABLE_FILE = "/data/local/tmp/disable_faceauth"
        private const val QNB_PACKAGE_NAME = "com.qnbalahli.bebasata"
        private const val THNDR_PACKAGE_NAME = "com.axismarkets.thndr"

        private const val ENABLE_SYSTEM_FACE_REGISTRATION_UPGRADE = true
        private const val ENABLE_SYSTEM_CONFIG_AND_PROVIDER_UPGRADE = false

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

        private fun preferFaceThenFingerprint(preAuthInfo: Any?): SensorPriorityResult {
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

            val faceSensors = ArrayList<Any?>()
            val fingerprintSensors = ArrayList<Any?>()
            val otherSensors = ArrayList<Any?>()
            for (sensor in eligibleSensors) {
                if (isFaceBiometricSensor(sensor)) {
                    patchFaceBiometricSensor(sensor, "eligibleSensors")
                    faceSensors.add(sensor)
                } else if (isFingerprintBiometricSensor(sensor)) {
                    fingerprintSensors.add(sensor)
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
            selectedSensors.addAll(faceSensors)
            selectedSensors.addAll(fingerprintSensors)
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
                "face first, fingerprint fallback",
                originalCount,
                selectedSensors.size
            )
        }

        private fun requiresFingerprintOnly(packageName: String): Boolean {
            return packageName == QNB_PACKAGE_NAME || packageName == THNDR_PACKAGE_NAME
        }

        private fun preferFingerprintOnly(preAuthInfo: Any?): SensorPriorityResult {
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
            for (sensor in eligibleSensors) {
                if (isFingerprintBiometricSensor(sensor)) {
                    selectedSensors.add(sensor)
                } else if (isFaceBiometricSensor(sensor)) {
                    removedFace = true
                } else {
                    selectedSensors.add(sensor)
                }
            }

            if (!removedFace || selectedSensors.isEmpty()) {
                return SensorPriorityResult.UNCHANGED
            }

            val originalCount = eligibleSensors.size
            if (!replaceEligibleSensors(preAuthInfo, eligibleSensors, selectedSensors)) {
                return SensorPriorityResult.UNCHANGED
            }

            return SensorPriorityResult(
                true,
                "fingerprint only for keystore-bound app",
                originalCount,
                selectedSensors.size
            )
        }

        private fun findPreAuthInfoArgument(args: Array<Any?>?): Any? {
            if (args == null) {
                return null
            }

            for (arg in args) {
                if (arg != null && arg.javaClass.name == "com.android.server.biometrics.PreAuthInfo") {
                    return arg
                }
            }

            for (arg in args) {
                if (arg != null && hasField(arg, "eligibleSensors")) {
                    return arg
                }
            }

            return null
        }

        private fun findLikelyPackageName(args: Array<Any?>?): String {
            if (args == null) {
                return "unknown package"
            }

            for (arg in args) {
                if (arg is String && arg.indexOf('.') > 0) {
                    return arg
                }
            }

            return "unknown package"
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
