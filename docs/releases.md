# Release layout

`VERSION` is the client/repository release version. Plugin and relay package versions remain independently managed. Generated files never belong in the source tree's Git history.

```text
dist/
  <version>/
    android/   dsh-remote-<version>-android-debug.apk
               dsh-remote-<version>-android-release.apk
               dsh-remote-<version>-android-release.aab
               SHA256SUMS
               manifest.json
    ios/       dsh-remote-<version>-ios-release.ipa
    harmony/   dsh-remote-<version>-harmony-release.hap
    desktop/   dsh-remote-<version>-desktop-release.dmg
```

Only platforms actually built have output directories. Each export creates its own checksums and manifest. Manifests contain relative filenames, byte counts and hashes, with no local machine or server metadata. A filename containing `unsigned` keeps that suffix when exported.

## Android

```sh
make android          # debug build + export
make release-android  # release APK/AAB + export
```

Signing is optional at build time. To sign your own release, place your key in `.local/signing/android/`, copy `mobile/keystore.properties.example` to `mobile/keystore.properties`, and configure the values locally. `storeFile` resolves relative to `mobile/androidApp/`. Neither signing properties nor keys are committed. Keep a secure backup of the key needed to update existing installations.

A release build without signing properties produces an unsigned APK; do not label it as installable or verified. Verify signed artifacts with your SDK's `apksigner verify` before distribution. GitHub CI uses debug signing and does not have a production key.

## Other platforms

Build and sign with the appropriate native tools, then export an explicit deliverable:

```sh
python3 scripts/export_artifacts.py ios --file /path/to/app.ipa
python3 scripts/export_artifacts.py ios --variant simulator --file /path/to/simulator-app.zip
python3 scripts/export_artifacts.py harmony --file /path/to/app.hap
python3 scripts/export_artifacts.py desktop --file /path/to/app.dmg
```

The exporter does not build, sign, or claim support for an unfinished platform. It refuses unknown extensions and duplicate output names. Do not include simulator logs, provisioning profiles, crash reports, private screenshots or deployment databases in a deliverable archive.

## Publication

```sh
python3 scripts/audit_publication.py
python3 scripts/audit_publication.py --staged
```

Review the exact staged tree, including images, before pushing. The audit rejects common credential formats, personal home paths/emails, non-example addresses, unexpected binary files and private/generated directories. Live screenshots must never be substituted for the synthetic previews without manual inspection. The audit cannot guarantee that every secret format is detected.

Push source and public examples only. If publishing a GitHub Release later, select the exact exported binary and checksum files; do not upload the whole `dist/` tree or local recovery archives. Never include historical commits containing private deployment details.
