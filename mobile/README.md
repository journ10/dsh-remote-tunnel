# DSH Remote mobile

The sole mobile client is Kotlin Multiplatform + Compose Multiplatform. Android and iOS share the UI and protocol layer; HarmonyOS is experimental. See [platform support](../docs/cross-platform.md).

```text
composeApp/src/commonMain/  UI, models, relay protocol, instance store
composeApp/src/androidMain/ Android platform services and Activity
composeApp/src/iosMain/     iOS platform services and controller
composeApp/src/ohosMain/    experimental Harmony adapter
composeApp/src/jvmMain/     desktop verification host
androidApp/                Android application wrapper
iosApp/                    SwiftUI/Xcode host
harmonyApp/                DevEco/ArkTS host
```

## Build

With JDK 17 and Android SDK 35:

```sh
./gradlew :composeApp:jvmTest :androidApp:assembleDebug
./gradlew :composeApp:compileKotlinIosSimulatorArm64  # macOS
./gradlew :composeApp:compileKotlinOhosArm64
```

Native intermediate outputs remain under each module's `build/`. Export Android deliverables from the repository root:

```sh
python3 scripts/export_artifacts.py android --variant debug
```

The canonical path is `dist/<VERSION>/android/`. See [release/export instructions](../docs/releases.md) for release signing, iOS and Harmony outputs. Signing keys are not included. Copy `keystore.properties.example` to the ignored `keystore.properties` only when supplying your own signing configuration.

The Android application ID remains `com.dshremote.app.android` so installed KMP clients retain their data. The retired standalone Android application used a different application ID; its private storage cannot be imported directly.

## UI and behavior

The workbench lists sessions by workspace, supports all/running filters and task creation, and provides instance management. Chat includes live output, changes, approvals/questions, attachments, model/permission controls, commands/references, child sessions, files, jobs, browser and terminal tools.

- [UI design and offline previews](docs/ui-refresh.md)
- [Multi-instance behavior and credential isolation](docs/multi-instance.md)
- [Harmony toolchain notes](docs/harmony-cmp-on-official-kmp.md)
- [iOS host](iosApp/README.md)

Android uses OkHttp and iOS uses Ktor Darwin. Android and iOS persist the instance list; the JVM verification host uses memory. The HarmonyOS network adapter is a placeholder and does not yet connect to a relay. Some platform pickers/previews are also incomplete outside Android.

## Verification

`./gradlew :composeApp:jvmTest` runs offline model, parser, transport and lifecycle tests. Live tests are opt-in through `DSH_TEST_PAIR_URL` and `DSH_TEST_WORKSPACE`; use a disposable DSH workspace. A test run without those variables does not verify a real relay. See [development](../docs/development.md).

To render the production components using synthetic data:

```sh
DSH_UI_CAPTURE_DIR="$PWD/build/reports/ui" ./gradlew :composeApp:jvmTest --rerun-tasks
```

These renders are not device screenshots or proof of end-to-end behavior.
