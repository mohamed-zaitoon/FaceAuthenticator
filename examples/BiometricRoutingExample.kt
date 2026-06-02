package examples

private const val QNB_PACKAGE_NAME = "com.qnbalahli.bebasata"
private const val THNDR_PACKAGE_NAME = "com.axismarkets.thndr"

private fun requiresFingerprintOnly(packageName: String): Boolean {
    return packageName == QNB_PACKAGE_NAME || packageName == THNDR_PACKAGE_NAME
}

fun chooseBiometricRoute(packageName: String): String {
    return if (requiresFingerprintOnly(packageName)) {
        "fingerprint-only"
    } else {
        "face-first-with-fingerprint-fallback"
    }
}
