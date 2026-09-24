# Beacon Display Android TWA

This project builds the minimal `org.jointhebeacon.display` wrapper for:

`https://jointhebeacon.org/network/elmbrook-church/display`

It uses Google Android Browser Helper and explicitly launches the installed
`com.android.chrome` package as a Trusted Web Activity. It does not contain an
Android WebView or a bundled browser. A failed Digital Asset Links check falls
back to a Chrome Custom Tab with visible browser UI so verification failures are
not concealed.

On Android 7.1.2, `BeaconDisplayActivity` is eligible to be selected manually as
the system HOME application, allowing its native startup screen to appear as
soon as Android reaches the launcher stage. A manifest receiver still listens
for `BOOT_COMPLETED` as a fallback, but skips its launch when the Activity has
already started so it cannot reset an active loader or TWA session. A successful
TWA launch is also recorded against Android's API 24+ boot counter, preserving
that duplicate-launch guard if the lightweight wrapper process is reclaimed
while Chrome remains open. The Activity
displays a tiny fullscreen native startup screen while it checks the existing public
`/.well-known/assetlinks.json` resource over HTTPS. It retries every three
seconds and launches the existing TWA as soon as Beacon responds directly with
HTTP 200. After ten seconds it exposes buttons for Android Wi-Fi settings and
the stock X96 launcher without stopping automatic retries. The launcher recovery
action targets `com.droidlogic.mboxlauncher/.Launcher` explicitly; if that
component is unavailable, Beacon stays open and records the failure in logcat.

The recovery buttons are deliberately absent from the XML layout. They are
created after the ten-second threshold as unstyled platform `Button` instances
with no XML `AttributeSet` or theme default-style lookup. This avoids a broken
binary-XML `Button` inflation path found on the target X96 Android 7.1.2 ROM.

Boot receiver events use the `BeaconDisplayBoot` logcat tag. Native startup,
connectivity, settings, HOME, and TWA launch events use
`BeaconDisplayStartup`.

Android does not let the application silently make itself the preferred HOME
handler. After installing this build, press HOME and manually choose Beacon
Display as the default launcher when Android presents the chooser.

## Build

The release signing identity is stored outside the repository at:

`~/.config/beacon-display/signing/beacon-display-release.jks`

Its generated password is stored beside it in
`beacon-display-release.password`. Both files are mode `0600`, and Gradle reads
them directly. Back up both files together before moving this project to a new
machine. Build with the bundled wrapper and Android Studio JDK:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew clean assembleRelease
```

The signed APK is written to:

`app/build/outputs/apk/release/app-release.apk`

After the first install, launch Beacon Display once before testing a cold boot.
Android does not deliver boot broadcasts to an application that remains in the
stopped state from installation. Updates installed with `adb install -r`
preserve the existing application state.

Do not delete or replace the signing key. Any APK signed with a different key
will require a corresponding change to the production Digital Asset Links file.
