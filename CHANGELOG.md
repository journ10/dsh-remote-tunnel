# Changelog

## 0.36.0

- Consolidated the mobile client into Kotlin Multiplatform and Compose Multiplatform; retired the standalone Android source tree.
- Refreshed the workbench, chat composer, tool controls, code-change and deliverable views.
- Restored multiple paired instances, persistent active selection on Android/iOS, migration of existing KMP pairings and origin-scoped credentials.
- Isolated session, approval and WebSocket state when switching instances.
- Standardized exported artifacts under `dist/<version>/<platform>/` with hashes and manifests.
- Removed private developer paths, live screenshots, obsolete build instructions and template-only source.
- Added publication auditing and KMP CI. Live deployment credentials and signing files remain local.

Relay and plugin package versions are maintained separately. See their package metadata for the current component versions.
