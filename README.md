# FaceAuthenticator

### Strong Face Unlock for BiometricPrompt Apps

[![Version](https://img.shields.io/badge/Version-v1.3--beta02-2ea44f?style=for-the-badge&logo=github)](https://github.com/mohamed-zaitoon/FaceAuthenticator/releases)
[![Android](https://img.shields.io/badge/Android-10%2B%20(API%2029%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com/)
[![Target](https://img.shields.io/badge/Target-Android%2017%20(API%2037)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com/)
[![Xposed](https://img.shields.io/badge/Xposed-LSPosed%20Module-orange?style=for-the-badge)](https://github.com/LSPosed/LSPosed)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Gradle](https://img.shields.io/badge/Gradle-9.6.1-02303A?style=for-the-badge&logo=gradle&logoColor=white)](https://gradle.org/)

**FaceAuthenticator** is an Xposed/LSPosed module that upgrades the real Android face biometric sensor registration to `BIOMETRIC_STRONG`, respects apps that request one biometric type, and silently requests face after a configurable fingerprint window when apps request both fingerprint and face.

The module runs only in the Android system framework scope. It does not add a launcher UI and it does not create a fake biometric provider.

[Download Latest APK](https://github.com/mohamed-zaitoon/FaceAuthenticator/releases) | [Website](https://faceauthenticator.mohamedzaitoon.com/) | [Report Issues](https://github.com/mohamed-zaitoon/FaceAuthenticator/issues)

---

## Why FaceAuthenticator?

| Feature | Description |
| :--- | :--- |
| Real system hook | Hooks Android framework biometric classes instead of injecting app-specific UI code. |
| Face strength upgrade | Marks real face sensors as `BIOMETRIC_STRONG` where the framework exposes a weaker value. |
| Default routing | Leaves fingerprint-only and face-only prompts on the biometric type requested by the app. |
| Delayed face priority | Starts fingerprint first when both fingerprint and face are eligible, then requests face after a configurable delay. Default: 5 seconds. |
| Hook settings | Lets rooted users configure delay from 0.001s to 10s, show or hide status messages, toggle delayed face, force instant face confirmation, and keep Keystore apps on fingerprint. |
| Fingerprint-only overrides | Reads fingerprint-only package names from `/data/local/tmp/faceauth_fingerprint_only_packages` for apps that crash, show unexpected errors, or verify hardware/Keystore face behavior. |
| App compatibility | Avoids app-process scope so protected apps such as QNB do not crash from LSPosed/root detection. |
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
2. It keeps single-biometric prompts unchanged: fingerprint-only stays fingerprint, and face-only stays face.
3. It starts fingerprint first for dual-biometric prompts, then starts eligible face sensors after the configured delay if authentication is still waiting.
4. It defaults to silent mode, so no countdown/help messages are shown unless enabled in hook settings.
5. It asks Android to skip passive face confirmation by setting the prompt confirmation hint to false.
6. It routes packages listed in `/data/local/tmp/faceauth_fingerprint_only_packages` and hardware-backed crypto prompts to fingerprint-only authentication when fingerprint is eligible.
7. It keeps the fingerprint scheduler moving by skipping a problematic reset-lockout HAL call on affected ROMs.

## Hook Settings

The module settings screen writes root-readable settings to `/data/local/tmp/faceauth_settings.properties`.

```properties
delayed_face_enabled=true
face_delay_ms=5000
show_status_messages=false
instant_face_confirmation=true
keystore_fingerprint_only=false
```

## Example

The simplified flow below mirrors the module's routing behavior:

```kotlin
val result = if (requiresFingerprintOnly(packageName)) {
    preferFingerprintOnly(preAuthInfo)
} else {
    preferFingerprintThenDelayedFace(preAuthInfo)
}
```

The routing is handled inside the framework without injecting into app processes.

## Build

Release signing is configured locally through `key.properties` or environment variables.
Do not commit signing keys or passwords.

Build a release APK:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew clean :app:assembleRelease
```

## License

This project is licensed under the terms in [LICENSE](LICENSE).

Made by [Mohamed Zaitoon](https://github.com/mohamed-zaitoon).
