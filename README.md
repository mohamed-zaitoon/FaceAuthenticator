# FaceAuthenticator

### Strong Face Unlock for BiometricPrompt Apps

[![Version](https://img.shields.io/badge/Version-v1.0-2ea44f?style=for-the-badge&logo=github)](https://github.com/mohamed-zaitoon/FaceAuthenticator/releases)
[![Android](https://img.shields.io/badge/Android-10%2B%20(API%2029%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com/)
[![Target](https://img.shields.io/badge/Target-Android%2017%20(API%2037)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com/)
[![Xposed](https://img.shields.io/badge/Xposed-LSPosed%20Module-orange?style=for-the-badge)](https://github.com/LSPosed/LSPosed)

**FaceAuthenticator** is an Xposed/LSPosed module that upgrades the real Android face biometric sensor registration to `BIOMETRIC_STRONG` and controls BiometricPrompt sensor priority for selected apps.

The module runs only in the Android system framework scope. It does not add a launcher UI and it does not create a fake biometric provider.

[Download Latest APK](https://github.com/mohamed-zaitoon/FaceAuthenticator/releases) | [Report Issues](https://github.com/mohamed-zaitoon/FaceAuthenticator/issues)

---

## Why FaceAuthenticator?

| Feature | Description |
| :--- | :--- |
| Real system hook | Hooks Android framework biometric classes instead of injecting app-specific UI code. |
| Face strength upgrade | Marks real face sensors as `BIOMETRIC_STRONG` where the framework exposes a weaker value. |
| App-specific routing | Keeps face-first behavior by default while forcing fingerprint-only prompts for keystore-bound apps that reject face auth. |
| Thndr support | Routes `com.axismarkets.thndr` to fingerprint-only authentication to avoid the app asking for its password after face unlock. |
| QNB support | Keeps the existing fingerprint-only behavior for `com.qnbalahli.bebasata`. |
| Release hardened | Release builds are minified with R8/ProGuard while keeping the Xposed entry point intact. |

## Requirements

* Rooted Android device.
* LSPosed or another compatible Xposed framework.
* Android 10 (API 29) or newer.
* System Framework selected in the module scope.

## Installation

1. Download the APK from [GitHub Releases](https://github.com/mohamed-zaitoon/FaceAuthenticator/releases).
2. Install the APK on the device.
3. Enable **FaceAuthenticator** in LSPosed.
4. Select the **System Framework** scope.
5. Reboot the device.

## How It Works

FaceAuthenticator hooks Android's biometric service classes inside the `android` package:

1. It upgrades face biometric sensor strength to `BIOMETRIC_STRONG`.
2. It reorders eligible sensors so face can be preferred before fingerprint in normal apps.
3. It removes face from the eligible sensor list for apps that need fingerprint-only keystore authentication.
4. It keeps the fingerprint scheduler moving by skipping a problematic reset-lockout HAL call on affected ROMs.

## Example

The simplified flow below mirrors the module's per-app routing behavior:

```kotlin
val packageName = findLikelyPackageName(args)
val result = if (requiresFingerprintOnly(packageName)) {
    preferFingerprintOnly(preAuthInfo)
} else {
    preferFaceThenFingerprint(preAuthInfo)
}
```

For apps such as Thndr, this makes Android show a fingerprint prompt only, which allows the app's keystore-backed authentication to complete successfully.

## Build

Release signing is configured through `key.properties`:

```properties
store_file=release-key.jks
store_password=123456789
key_alias=fatceauthentication
key_password=123456789
```

Build a release APK:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew clean :app:assembleRelease
```

## License

This project is licensed under the terms in [LICENSE](LICENSE).

Made by [Mohamed Zaitoon](https://github.com/mohamed-zaitoon).
