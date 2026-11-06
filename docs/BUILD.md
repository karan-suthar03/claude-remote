# Building and installing the app

The app is in `android/`. It's Kotlin with Jetpack Compose, and builds with Gradle (wrapper included).

| | |
|---|---|
| Application ID | `me.karansuthar.clauderemote` (set by `applicationId` in `android/app/build.gradle.kts`) |
| `minSdk` | 31 (Android 12) |
| `compileSdk` / `targetSdk` | 36 |
| Java / Kotlin target | 17 |

## Option A: Android Studio

1. Install [Android Studio](https://developer.android.com/studio). It bundles a suitable JDK.
2. **File → Open** the `android/` folder of this repo and let Gradle sync. It downloads SDK 36 and the dependencies.
3. On the phone, turn on **Developer options**: Settings → About phone → tap **Build number** 7 times. Then turn on **USB debugging**.
4. Connect the phone by USB, accept the prompt on the phone, choose the phone in the toolbar, and press **Run**.

## Option B: command line

You need:

- **JDK 17**. Point `JAVA_HOME` at it.
- **Android SDK** with `platforms;android-36`, `build-tools;36.0.0` and `platform-tools`. With the command-line tools:
  ```sh
  sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
  ```

Then:

1. Tell Gradle where the SDK is. Create `android/local.properties` containing:
   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```
   On Windows, use forward slashes: `sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk`. This file is git-ignored. Setting `ANDROID_HOME` works too.
2. Build a debug APK:
   ```sh
   cd android
   ./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
   ```
   The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.
3. Install it on a connected phone (by USB, or wirelessly as below):
   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   `-r` replaces an existing install and keeps its data (your device list).

You can also copy the APK to the phone and open it there, after allowing installs from that app.

## Installing without a cable

### First time: pair over the same Wi-Fi

Android's wireless debugging pairs and connects over the local network, so for the first time the phone and computer must be on the **same Wi-Fi**.

1. On the phone: **Developer options → Wireless debugging → On**. Allow it for this network if asked.
2. Tap **Pair device with pairing code**. It shows an IP address and port, and a 6-digit code.
3. On the computer:
   ```sh
   adb pair <phone-wifi-ip>:<pairing-port> <code>
   ```
   You only need to pair once per computer.
4. On the main **Wireless debugging** screen, note the address under **IP address & Port** (a different port from the pairing one), then:
   ```sh
   adb connect <phone-wifi-ip>:<port>
   adb devices                      # the phone should be listed as "device"
   ```
   This port changes every time Wireless debugging is turned on.
5. Install as in step 3 of Option B.

### Afterwards: keep using adb over Tailscale

While the phone is connected (by USB or wireless debugging), switch adbd to a fixed TCP port:

```sh
adb tcpip 5555
```

From then on you can connect from anywhere both devices are on Tailscale, using the **phone's Tailscale IP** (shown in the Tailscale app):

```sh
adb connect 100.x.y.z:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

This lasts until the phone reboots, or until Wireless debugging or USB debugging is turned off. After that, pair or plug in again and repeat `adb tcpip 5555`.

Anyone who can reach port 5555 on the phone can control it through adb. Keep that port reachable only by your own devices (see the ACL example in [SECURITY.md](SECURITY.md)), and turn debugging off when you are done.

## Notes

- **Signing.** Debug builds are signed with the debug keystore of the computer that built them (`~/.android/debug.keystore`). An APK built on one computer can't be installed over one built on another. Uninstall the old app first: `adb uninstall me.karansuthar.clauderemote`. Uninstalling clears the device list. Sessions live on the computers and aren't affected.
- **Your own ID.** To install your build alongside someone else's, change `applicationId` in `android/app/build.gradle.kts`.
- **Clean build.** If the build behaves oddly after updating the repo, run `./gradlew clean assembleDebug`.
