# BaoTime

BaoTime is an Android app for joining FaceTime web rooms, creating shareable links through a Mac running BlueBubbles, and answering incoming invitations. It runs on Android 10 or later. You can join an existing link without a Mac connection.

## Use BaoTime

- **Join a room.** Open **New Call**, paste the full `https://facetime.apple.com/…` link, and tap **Join FaceTime Link**. In the room, tap **Continue** when prompted for camera and microphone access. The host may need to admit you.
- **Create a link.** Tap **Create FaceTime Link** in **New Call** or from a contact. The phone number or Apple ID field is optional. BaoTime opens the room after BlueBubbles creates the link. Use **Share** in **Room options** to invite the other person. Anyone with the link can ask to join.
- **Answer an invitation.** Tap an incoming call alert, then choose **Accept** or **Decline**. BaoTime asks your Mac to answer a live FaceTime call and opens the returned web room. For a notification containing a FaceTime link, it opens that link directly.
- **Find people and past calls.** **Contacts** searches favorites, Mac contacts synced through BlueBubbles, and Android contacts. Use **Dial With Phone App** from a phone-number contact for a cellular call. **Recents** stores local history with options to rejoin, copy, share, or delete links.

<a name="limitations"></a>

BaoTime opens one room at a time in an embedded Android WebView and offers picture-in-picture. If the room fails to load or media access fails, copy the link and open it in the latest Chrome or Edge, the browsers Apple documents for FaceTime on Android. [Apple’s FaceTime web guide](https://support.apple.com/en-us/109364)

## Connect to your Mac

1. Run BlueBubbles Server **1.9.12** on a Mac signed in to FaceTime. Creating and answering calls through the server requires **Private API** enabled and the FaceTime helper connected. [Server 1.9.12](https://github.com/BlueBubblesApp/bluebubbles-server/releases/tag/v1.9.12) · [Private API setup](https://docs.bluebubbles.app/private-api/installation)
2. In BaoTime, go to **Settings → Mac Connection**. Enter the server's base URL and BlueBubbles password in their separate fields, then tap **Save Connection**. Prefer HTTPS. BaoTime stores the password encrypted using Android Keystore.
3. Check the connection status. **Saved · Mac unavailable** means the settings were saved but the connection check failed. Verify the phone can reach the Mac and check the server address and password. If the Mac is reachable and link creation still fails, check the version and helper status.
4. Optionally use **Settings → FaceTime Links** to choose BaoTime in Android’s link settings, if Android offers it. To use the device address book, grant BaoTime **Contacts** access in Android’s app settings.

### Incoming call delivery

| Source | Setup and behavior |
| --- | --- |
| Live BlueBubbles connection | Works while BaoTime’s process is running and connected to the Mac. |
| UnifiedPush | Install an Android UnifiedPush distributor and save the Mac connection. BaoTime registers the push endpoint with BlueBubbles for background delivery. |
| BlueBubbles or Beeper notifications | In **Settings → Incoming Invitations**, grant notification access. BaoTime can surface an invitation when one of those apps posts a notification containing a FaceTime link. |

Allow BaoTime notifications in Android settings for background call alerts. If you force-stop BaoTime, reopen it to resume delivery. [Android’s stopped-state behavior](https://developer.android.com/about/versions/15/behavior-changes-all#enhanced-stop-states)

## Build and install

Install the **Android 17 SDK** (`platforms;android-37.0`) and Android SDK Platform-Tools. Set `ANDROID_HOME` or let Android Studio create `local.properties` with `sdk.dir`. [Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk)

The project pins Gradle **9.9.0**, Android Gradle Plugin **9.6.0-alpha03**, and a Gradle daemon running on **JDK 25**. The wrapper needs a local Java installation to start and can provision the daemon JDK. [AGP release notes](https://developer.android.com/build/releases/agp-9-6-0-release-notes) · [Gradle daemon guide](https://docs.gradle.org/9.9.0/userguide/gradle_daemon.html)

From the repository root, with an emulator or a device with USB debugging enabled:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app appears as **BaoTime** in the launcher. The Android package is `com.baotime.app`. On Windows, use `gradlew.bat`.

<a name="verification-status"></a>

## Verify changes

```sh
./gradlew :app:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
python3 scripts/audit_design.py
```

The Gradle command runs JVM tests, builds the app and instrumentation APKs, and runs Android Lint. The Python audit checks design source and icon provenance.

For [instrumented tests](app/src/androidTest/java/com/baotime/app/), select tests and runner arguments for the device you’re using.

| Tests | Requirements |
| --- | --- |
| Native journeys, contacts, history, credential storage, and room UI | Dedicated audit emulator, `auditFixture=true`, and an `evidenceRun` of lowercase letters, digits, or hyphens |
| `InstalledLauncherTest` | `installationCheck=true` and `evidenceRun` |
| `DeviceConnectionCheckTest` | Xiaomi deployment device, `deploymentCheck=true`, and `expectedServer` matching the Tailscale HTTPS URL in the local `filesDir/deployment-connection-input.json` fixture |

## Code map

| Area | Where to look |
| --- | --- |
| Android entry point, navigation, and call actions | [MainActivity](app/src/main/java/com/baotime/app/MainActivity.kt) |
| Incoming call state shared by socket, push, and notification handling | [CallLifecycle](app/src/main/java/com/baotime/app/call/CallLifecycle.kt) |
| BlueBubbles requests and server requirements | [BlueBubblesClient](app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt) and [ServerCapability](app/src/main/java/com/baotime/app/network/ServerCapability.kt) |
| Web room navigation and media permissions | [FaceTimeWebViewHelper](app/src/main/java/com/baotime/app/ui/FaceTimeWebViewHelper.kt) |
| Build settings and dependency pins | [App build configuration](app/build.gradle.kts) and [version catalog](gradle/libs.versions.toml) |

<a name="documentation-index"></a>

See the [implementation notes](tasks/README.md) for server contracts and device verification, and [NOTICE](NOTICE) for third-party dependency notices.

