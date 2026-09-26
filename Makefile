.PHONY: mac android test test-mac test-android clean

# Build Cursor Controller.app + DMG ke dist/
mac:
	cd mac-agent && ./scripts/bundle.sh

# Build APK debug ke android-app/app/build/outputs/apk/debug/
android:
	cd android-app && ./gradlew :app:assembleDebug

test: test-mac test-android

test-mac:
	cd mac-agent && swift test

test-android:
	cd android-app && ./gradlew :core:test :app:testDebugUnitTest

clean:
	rm -rf dist mac-agent/.build
	cd android-app && ./gradlew clean
