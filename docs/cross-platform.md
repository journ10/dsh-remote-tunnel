# Cross-platform client

`mobile/` is the only maintained mobile implementation. The standalone `android/` project was removed during the KMP consolidation.

## Source sets and platform support

| Target | Transport / persistence | Current limits |
|---|---|---|
| Android | OkHttp / SharedPreferences | Primary app; real deployment regression still requires a device and your own relay |
| iOS | Ktor Darwin / NSUserDefaults | Xcode signing and runtime validation are local; some pickers/previews are placeholders |
| HarmonyOS ARM64 | Placeholder transport / memory | Shared code compilation does not imply a working remote client or signed HAP |
| JVM desktop | OkHttp / memory | Offline tests and development host, not a persistence-ready desktop release |

Shared code owns pairing, per-origin credentials, instance switching, sessions, streaming, approval/question UI and the workbench. Platform adapters provide transport, scanning, file pickers, preview, browser and back handling.

## Toolchain

The authoritative versions are in `mobile/gradle/libs.versions.toml` and the Gradle wrapper:

- JDK 17, Gradle 8.9, Android SDK 35, min SDK 26.
- Kotlin `2.2.21-1.1.0`, Compose Multiplatform `1.9.2-1.1.0` (Harmony fork).
- Android/JVM Compose dependencies are remapped to upstream `1.9.2`, Material 3 `1.9.0`.
- iOS builds require macOS and an Xcode installation with the selected platform SDK.
- Harmony host builds require DevEco Studio 6 and the corresponding SDK/toolchain.

The fork repository in `mobile/settings.gradle.kts` is a public dependency repository, not an application relay. See [Harmony dependency notes](../mobile/docs/harmony-cmp-on-official-kmp.md) before changing the version routing.

## Build commands

From `mobile/`:

```sh
./gradlew :composeApp:jvmTest
./gradlew :androidApp:assembleDebug
./gradlew :composeApp:compileKotlinIosSimulatorArm64
./gradlew :composeApp:compileKotlinOhosArm64
```

Set `JAVA_HOME` and `ANDROID_HOME` to your local installations, or provide an ignored `mobile/local.properties` with `sdk.dir`. No developer-specific paths belong in tracked build files. Optional local tooling may be placed under the ignored `.tooling/` directory.

## Deliverables

All exported app artifacts use `dist/<VERSION>/<platform>/`. See [release paths and signing](releases.md). Build products, signing credentials, `.app` directories, native libraries and local IDE state are excluded from Git. CI builds a debug APK and uploads only that explicit artifact directory.

The client release version comes from the root `VERSION`; plugin and relay package versions are independent. The Android application ID is unchanged by the cleanup.

## Validation boundaries

Offline shared tests and cross-target compilation do not verify live DSH integration. Live JVM tests need both `DSH_TEST_PAIR_URL` and `DSH_TEST_WORKSPACE`; never commit those values or publish their logs. Fixture-only screenshots live under `docs/images/kmp-ui-refresh/`.

The pinned Android lint analyzer currently reports Kotlin metadata-version diagnostics for the forked toolchain even when release assembly succeeds. A successful APK build should not be presented as a clean full lint result.
