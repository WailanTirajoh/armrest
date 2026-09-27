.PHONY: mac android windows test test-mac test-android test-windows clean

# Build Armrest.app + DMG ke dist/
mac:
	cd mac-agent && ./scripts/bundle.sh

# Build APK debug ke android-app/app/build/outputs/apk/debug/
android:
	cd android-app && ./gradlew :app:assembleDebug

# Build agent Windows (bisa dari macOS/Linux, tapi hanya jalan di Windows). Installer dibuat CI.
windows:
	cd windows-agent && dotnet build src/Agent.Windows -c Release

test: test-mac test-android test-windows

test-mac:
	cd mac-agent && swift test
	python3 scripts/check-mac-strings.py

test-android:
	cd android-app && ./gradlew :core:test :app:testDebugUnitTest

test-windows:
	cd windows-agent && dotnet test tests/Agent.Tests

clean:
	rm -rf dist mac-agent/.build windows-agent/publish
	cd windows-agent && dotnet clean --nologo -v quiet
	cd android-app && ./gradlew clean
