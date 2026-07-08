package examples

private fun requiresFingerprintOnly(
    packageName: String,
    fingerprintOnlyPackages: Set<String>
): Boolean {
    return packageName in fingerprintOnlyPackages
}

fun chooseBiometricRoute(
    packageName: String,
    hasFace: Boolean,
    hasFingerprint: Boolean,
    fingerprintOnlyPackages: Set<String> = emptySet()
): String {
    if (requiresFingerprintOnly(packageName, fingerprintOnlyPackages) && hasFingerprint) {
        return "fingerprint-only"
    }

    return when {
        hasFace && hasFingerprint -> "fingerprint-first-face-after-configured-delay"
        hasFace -> "face-only"
        hasFingerprint -> "fingerprint-only"
        else -> "no-eligible-biometric"
    }
}
