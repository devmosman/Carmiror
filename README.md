# CarMirror

Mirror an Android phone onto an Android car head unit over Wi-Fi, with touch control from the car screen.

- `phone/` : **CarMirror Phone** (Android 8+). Captures the screen, encodes H.264, streams it, and replays touches.
- `car/` : **CarMirror Car** (Android 7+, built for the GAC GS8 2023 head unit on Android 9). Shows the picture and sends touches back.

Status: first version. It builds, but has **not yet been run on a phone or the car**, so expect a round or two of fixes.

## Build the APKs (no Android Studio needed)

1. Create a new empty repository on GitHub and upload the contents of this folder to the `main` branch.
2. Open the repository's **Actions** tab. The "Build APKs" workflow runs on every push (or start it with **Run workflow**).
3. When it finishes, download the APKs from the **Releases** page (the "Latest build" release is replaced on every push to `main`):
   - `CarMirror-Phone.apk`: view-only phone app. Installs straight from a browser.
   - `CarMirror-Phone-Touch.apk`: the same app plus touch control from the car screen. Install with adb (see below).
   - `CarMirror-Car.apk`: the car app.

Or open the folder in Android Studio and run `assembleDebug`.

Both APKs are signed with the key in `keystore/`, so a newer build installs over an older one. The key and its password are in the repository on purpose; do not reuse it for anything you publish.

## Install

**Phone:** open `CarMirror-Phone.apk` and allow installing from this source.

**Phone with touch control:** Google Play Protect blocks any APK that declares an accessibility service when it is installed from a browser, chat app or file manager ("App blocked to protect your device"). Touch control needs that service, so the Touch build has to be installed from a computer: enable USB debugging on the phone, then run `adb install -r CarMirror-Phone-Touch.apk`. It has the same app ID and signature as the view-only build, so it updates it in place.

**Car (GAC GS8 2023):** copy `car-debug.apk` to a FAT32 USB stick, then use the owner-reported hidden menu: navigation search, long-press the comma key, choose the language option, tap search at the top right, type `file`, and install the APK from the USB stick. Installed apps do not show in the stock menu, so install a third-party launcher the same way first. This is unofficial and may affect the head unit's warranty.

## Use

1. Connect the car to the phone, either way:
   - **Wi-Fi:** turn on the phone's hotspot and connect the car's Wi-Fi to it.
   - **Cable:** plug the phone into the car's USB port and turn on **USB tethering** on the phone. This only works if the head unit accepts a USB network connection; if the car app keeps searching, use Wi-Fi.
2. Phone: open CarMirror Phone, tap **Start mirroring**, and share the whole screen.
3. Car: open CarMirror Car. It finds the phone by itself (broadcast, then the gateway address of each network). If it does not, tap the gear and type the address shown in the phone app.
4. For touch from the car screen (Touch build only): in the phone app tap **Enable touch control** and switch on "CarMirror touch control". If Android greys it out, open the phone app's App info, tap the three dots, and choose **Allow restricted settings**.

The left bar on the car screen has **Exit** (the cross) and **Settings** (the gear). With the Touch build connected it also shows Back, Home and Recents for the phone.

**Car screen saver:** if the car's clock screen saver covers the mirror, first look for a screen saver or standby setting in the car's display settings. Otherwise tick "Come back to the mirror if the car's screen saver covers it" in the car app's settings. That option brings the app back two seconds after anything covers it while mirroring, so leave it off if it gets in the way of other car screens.

## Known limits

- Audio is not mirrored. Pair the phone to the car by Bluetooth for sound.
- When the link or the decoder falls behind, waiting frames are skipped and the picture jumps ahead instead of lagging further.
- A touch is replayed when the finger lifts, so taps, swipes and long presses work, but live dragging does not.
- Protected video (Netflix and similar) shows black. That is Android's rule for screen capture.
- The phone screen stays on (dimmed) while the car is connected.
- The stream is not encrypted or paired. Any device on the same Wi-Fi could connect and control the phone, so use the phone's own password-protected hotspot, not a shared network.
- Do not operate the mirrored phone while driving.

## Tuning

`MirrorService.java`: `MAX_LONG_SIDE` (1280), `BIT_RATE` (4 Mbps), `FRAME_RATE` (30). Lower them if the picture stutters on the head unit.

## Ports

TCP 47800 (video and input), UDP 47801 (discovery beacon).
