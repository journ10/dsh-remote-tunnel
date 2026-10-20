# iOS host

SwiftUI host for the shared Compose UI. Requires macOS, Xcode and the selected iOS platform/simulator components installed through Xcode Settings.

From `mobile/`, build the framework matching the destination:

```sh
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
# Or, for a device:
./gradlew :composeApp:linkDebugFrameworkIosArm64
```

The checked-in Xcode project is generated from `project.yml` and has no developer team or provisioning identity. Regenerate with XcodeGen after editing that file:

```sh
cd iosApp
xcodegen generate
xcodebuild -project DSHRemote.xcodeproj -scheme DSHRemote \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO build
```

The host links the corresponding local `ComposeApp.framework`; it does not embed a developer's absolute path. Device/archive builds require your own signing setup. The current host project links debug Kotlin frameworks; configure matching release frameworks before shipping an IPA.

Keep native intermediates in `build/` and export only explicit deliverables through the [release exporter](../../docs/releases.md). Shared code compilation and unsigned host builds do not establish real-device pairing or App Store readiness.
