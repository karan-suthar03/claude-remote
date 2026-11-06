# Building and installing the app

## Option A: Android Studio (easiest)

1. Install [Android Studio](https://developer.android.com/studio).
2. Open the `android/` folder of this repo. Let it sync; it downloads what it needs.
3. On the phone, turn on **Developer options** (Settings → About phone → tap **Build number** 7 times), then **USB debugging**.
4. Connect the phone by USB, accept the prompt on the phone, and press **Run**.

## Option B: command line

You need a **JDK 17** and the **Android SDK** with `platforms;android-36`, `build-tools;36.0.0` and `platform-tools`.

1. Point the build at your SDK: create `android/local.properties` with
   ```
   sdk.dir=/path/to/Android/Sdk
   ```
   (on Windows, use forward slashes: `sdk.dir=C:/Users/you/AppData/Local/Android/Sdk`). This file is not committed.
2. Build:
   ```sh
   cd android
   ./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
   ```
   The APK is `android/app/build/outputs/apk/debug/app-debug.apk`.
3. Install it with adb (USB, or wireless below):
   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## Installing without a USB cable (wireless debugging over Tailscale)

Works from anywhere both devices are on Tailscale.

1. On the phone: **Developer options → Wireless debugging → on**.
2. Tap **Pair device with pairing code**. It shows a port and a 6-digit code.
3. On the computer, using the **phone's Tailscale IP** (Tailscale app → your phone) and that port:
   ```sh
   adb pair <phone-tailscale-ip>:<pairing-port> <code>
   ```
   Pairing is needed once per computer.
4. Back on the main **Wireless debugging** screen, note the port under **IP address & Port**, then:
   ```sh
   adb connect <phone-tailscale-ip>:<port>
   adb devices          # the phone should show as "device"
   ```
   This port changes each time Wireless debugging is turned on.
5. Install as in step 3 of Option B.

## Notes

- The app ID is `me.karansuthar.clauderemote`. Change `applicationId` in `android/app/build.gradle.kts` if you want your own.
- Debug and release builds are signed with the local debug key, so an APK built on one computer can't be installed over one built on another. Uninstall the old one first.
