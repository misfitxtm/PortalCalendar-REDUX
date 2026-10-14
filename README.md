# PortalHub REDUX

A security-focused fork of **[PortalHub](https://github.com/thefloppytaco/portal-calendar)** by
[@thefloppytaco](https://github.com/thefloppytaco): an always-on family calendar, chores, lists and
meal planner for a Meta Portal. The app and its Kotlin UI are theirs. See their
[README](https://github.com/thefloppytaco/portal-calendar#readme) for the full feature tour and
screenshots. This fork keeps their MIT license and copyright in [LICENSE](LICENSE).

## What's different

**Security**
- A Rust core (`core/`) now runs the setup-page server. The page needs a login: the QR code on the
  Portal carries a per-device secret, so nobody else on your Wi-Fi can read or change anything.
- Blocks DNS-rebinding, cross-site and feed-redirect (SSRF) attacks, and escapes the OAuth page.
- iCloud, Google and Gemini secrets are encrypted with the Android Keystore. Backups are
  passphrase-encrypted.
- Details and open items: [docs/SECURITY-AUDIT.md](docs/SECURITY-AUDIT.md).

**Features**
- **Chore points:** add or remove points (bonus or penalty, with a reason and undo), points per
  chore, a starter chore list, and a weekly reset every Friday.
- **Rewards:** a prize list kids can spend this week's points on.
- **Potty tracker (optional):** a dry-night streak toward 3 weeks with a month calendar. You choose
  which kids use it.
- **Shopping list (optional):** quick-add household items or type your own. Locked behind the parent
  PIN on the board.
- **Choose your tabs and your home screen:** switch any tab off and pick the one the board opens on.
- **Voice commands (optional, off by default):** say "add paper towels to the shopping list". It runs
  on the Portal with no account and sends no audio anywhere. English only, a few fixed phrases.
- **Proton Calendar (read-only):** paste a Proton "share with anyone" link like any other calendar.

**Known limitations**
- Syncing several Portals together (hub and spoke) doesn't work yet: followers can't sign in to the hub.
- Release APKs are debug-signed for now, so only update from builds made with the same key.

## Requirements

- A Meta Portal with ADB enabled and the [Immortal launcher](https://github.com/starbrightlab/immortal).
  Developed and tested on a Portal `omni` (Android 10, arm64). The original targets the Portal+ gen 1
  (`aloha`). Other models are untested, so reports are welcome.
- A computer with `adb`, and the Portal and your phones on the same Wi-Fi.

## Install

The APK is about 60 MB because the voice model is bundled.

1. Download `portalhub-redux-<version>.apk` from the
   [Releases](https://github.com/misfitxtm/PortalCalendar-REDUX/releases) page.
2. Connect the Portal over USB (or `adb connect <portal-ip>:5555`) and run:

```sh
adb install -r portalhub-redux-<version>.apk
adb shell am start -n com.portal.calendar/.MainActivity
# Optional: lets the app take the screen back from the stock photo frame
adb shell appops set com.portal.calendar GET_USAGE_STATS allow
```

3. Scan the QR code on the Portal with your phone. That opens the setup page and signs it in.

**Already running the original PortalHub?** This fork uses the same app id, so Android only lets one
of them be installed, and an install over the top fails with a signature error if the keys differ.
Export a backup from the old app first (its Settings → Backup), then `adb uninstall com.portal.calendar`
and install this one. The old unencrypted backup code can be imported here.

**Upgrading this fork:** run the same `adb install -r` with a newer APK, as long as it was signed with
the same key.

Wireless adb: with USB connected once, `adb tcpip 5555`, unplug, then `adb connect <portal-ip>:5555`.
It resets after a Portal reboot.

## Build from source

You need JDK 21 or newer, the Android SDK with platform 36, the NDK (see `ndkVersion` in
`app/build.gradle.kts`), Rust from [rustup](https://rustup.rs) with the `aarch64-linux-android`
target, and `cargo install cargo-ndk`. The first build needs internet: it downloads the voice model
(about 40 MB, checksum-verified) once into `~/.gradle/portalhub/`.

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug        # builds the Rust core and bundles the voice model too
(cd core && cargo test)             # Rust tests, run on your computer
./gradlew :app:testDebugUnitTest    # Kotlin tests
```

The APK is written to `app/build/outputs/apk/debug/`. Only `arm64-v8a` is built.

## Voice commands

Off until you turn them on under **Settings → Voice commands** on the setup page. While they are off
the board shows no mic button and never records. When on, tap 🎤 (parent PIN), speak, and confirm what
it heard. Recognition is restricted to a fixed vocabulary so a small offline model stays accurate.
Gemini is an optional alternative if you add your own key; it sends audio to Google.

## Credits and licenses

- **PortalHub** by [@thefloppytaco](https://github.com/thefloppytaco), MIT.
- [Vosk](https://github.com/alphacep/vosk-api) and the `vosk-model-small-en-us-0.15` model, Apache 2.0.
- [JNA](https://github.com/java-native-access/jna), LGPL 2.1 or Apache 2.0.
- This fork, MIT.
