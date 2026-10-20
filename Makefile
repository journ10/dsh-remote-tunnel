.PHONY: test test-server test-plugin test-mobile test-scripts audit android apk release-android docker-config

test: test-server test-plugin test-mobile test-scripts audit

test-server:
	cd server && npm test

test-plugin:
	cd plugin && pnpm test

test-mobile:
	cd mobile && ./gradlew :composeApp:jvmTest

test-scripts:
	python3 -m unittest discover -s scripts/tests -v

audit:
	python3 scripts/audit_publication.py

android:
	cd mobile && ./gradlew :androidApp:assembleDebug
	python3 scripts/export_artifacts.py android --variant debug

apk: android

release-android:
	cd mobile && ./gradlew :androidApp:assembleRelease :androidApp:bundleRelease
	python3 scripts/export_artifacts.py android --variant release

docker-config:
	cd server && RELAY_ENV_FILE=.env.example docker compose -f docker-compose.yml config --quiet
