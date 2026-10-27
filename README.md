# Share Panel

[中文说明](readme.zh.md)

Allow re-order share app list.

Android's share and “Open with” lists can contain many app entries, making familiar targets hard to find. Share Panel provides a configurable chooser where you can hide unwanted entries and move frequently used ones to the front.

The app has been tested on a **Xiaomi 14 running HyperOS 3**. Compatibility with other Android systems remains unverified; their share components, root management and manufacturer restrictions may differ.

## Features

- **Organize entries:** Hide or restore entries, drag them to reorder, or use the menu to move an entry to the top or bottom.
- **Configure by scenario:** Share and Open with actions have separate rules for different content types. Presets include images, videos, text, PDF and Word documents.
- **Configure directly:** Select a scenario on the app's home screen to view and arrange its entries. This list is generated from a simulated request; actual entries may vary with the source app and its request.
- **Switch choosers:** Switch between the system and custom chooser from the home screen.
- **Forward file permissions:** A root bridge can forward requests that need to preserve the original caller's identity. Sharing an image from Google Photos to LocalSend has been verified on the test device.

<img src="images/hyperos.webp" alt="HyperOS system share panel" width="240"> <img src="images/sharepanel.webp" alt="Share Panel custom chooser" width="240">

## Before you start

- The app can be installed on Android 10 (API 29) and later. Chooser takeover and file forwarding depend on the system implementation; the root bridge uses a privileged Android 16 API and has been verified on the device above.
- Switching the system chooser and starting the privileged forwarding service require root. Allow the app to use `su` in your root manager. Ordinary requests can be forwarded directly; some file shares require the bridge.
- Share Panel takes over the system's `ACTION_CHOOSER` entry point. An app's own share screen, a launch directed at a specific target, or an “Open with” request using another mechanism may continue to use its original interface.
- The switch targets `com.android.intentresolver/.ChooserActivity`. Other systems may use a different component name; check before using the switch.
- The app declares `QUERY_ALL_PACKAGES` to discover apps that can handle a request.
- The bridge runs as a root process and must be activated again after a reboot. If you use temporary root, restore root first. The status indicator in the upper-right corner of the home screen lets you check and activate the service.

## Getting started

1. Install the APK, open **Share Panel**, and grant it root access in your root manager.
2. On the home screen, tap **Switch to custom share panel**. This disables the target system chooser component and checks which activity the system resolves.
3. Select a share or Open with scenario. Use the menu beside an entry to hide, restore or move it, or drag entries to reorder them.
4. Tap the status indicator in the upper-right corner. If the service is stopped, activate the privileged forwarding service in the dialog.
5. Start a share or Open with request from a source app and check that the target list and file access work as expected.

Use **Switch to system share panel** on the home screen to restore the system chooser. Switching changes only the system chooser component's enabled state; the system APK remains unchanged.

> **Switch back to the system chooser and confirm sharing works before uninstalling Share Panel.** If you uninstall the custom panel directly, the system chooser component may remain disabled.

If you have already uninstalled the app and the system share entry is unavailable, you can restore the component's default state through ADB if the device still has root access. This example is for user 0:

```sh
adb shell su -c 'cmd package default-state --user 0 com.android.intentresolver/com.android.intentresolver.ChooserActivity'
```

This command targets the system component listed above; other systems may use a different component. After restoring it, start a share to confirm the system chooser works.

## Building from source

The project uses the Gradle Wrapper, Android Gradle Plugin 9.4.1 and Android SDK API 37. It has been built with JDK 21. Install the corresponding JDK and Android SDK, and make the SDK available to Gradle.

The build script reads `signing.properties` in the project root during configuration, so both debug and release builds require the file. Release builds also require your own signing key; keep it secure.

1. Create `signing/` in the project root and generate your own keystore with `keytool`, for example:

   ```sh
   keytool -genkeypair -v -keystore signing/local.jks -alias share-panel -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Create `signing.properties` in the project root and replace the values below with your own configuration:

   ```properties
   storeFile=signing/local.jks
   storePassword=YOUR_KEYSTORE_PASSWORD
   keyAlias=share-panel
   keyPassword=YOUR_KEY_PASSWORD
   ```

3. Build:

   ```sh
   ./gradlew :app:assembleDebug
   ./gradlew :app:assembleRelease
   ```

   On Windows, use `gradlew.bat`. APKs are written to `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`, respectively.

`signing.properties` and `signing/` are listed in `.gitignore`. Before publishing, confirm they are neither tracked by Git nor included in existing commits; files already tracked require separate handling.

## How it works and limitations

Share Panel is a regular APK. When you select the custom panel, the app uses root to change the system chooser component's enabled state. For requests requiring special file permissions, it starts a separate root bridge process that launches the target as the original caller. The system partition and system APK remain unchanged.

Manufacturer implementations and Android versions can affect chooser resolution, root authorization and file access. The switch changes the component state before checking the resolved activity, so the state may have changed even if the check reports an error. If switching fails or a target app cannot read a shared file, switch back to the system chooser and confirm it works, then check root authorization and the service status in the upper-right corner.
