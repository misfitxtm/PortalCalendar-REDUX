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
- Calendar feeds are parsed in Rust with hard limits on size, events and recurrence work. A
  mutation fuzzer found and fixed a crash and a slow-rule problem in the parsing code.
- Several Portals can sync with a hub using a pairing code, and a paired Portal never sends the code
  to an address it merely discovered on the network.
- Details and open items: [docs/SECURITY-AUDIT.md](docs/SECURITY-AUDIT.md).

**Features**
- **Chore points:** add or remove points (bonus or penalty, with a reason and undo), points per
  chore, a starter chore list, and a weekly reset every Friday.
- **💰 Reward Shop 💰:** a gold tab on the board where a kid picks a prize and a parent approves it with
  the PIN. 26 ready-made rewards to add (screen time, treats, activities, privileges, prizes), with
  costs you can change. Spending uses this week's points, which reset every Friday. On the setup page
  every reward's emoji, name and cost can be edited in place. The board's shop is a gold marquee with
  gift and money emoji, and the rewards a kid can afford glow gold with a "Get it!" button.
- **Celebrations:** finishing a Get Ready list, or marking a dry night in the Pee Tracker, plays a
  full-screen party: a bouncing trophy or star, confetti and a rising power-up jingle (a synthesized
  chiptune run, not a recording). Each can be switched off, and previewed, on the setup page under
  Settings → Celebrations.
- **"Ouch" screen:** taking points off a kid (from the board or the setup page) shows a full-screen dark
  red screen: a skull hops up and falls off the bottom, one 💔 per star lost, "−5 ⭐" shakes in, and a sad game-over tune plays
  (also synthesized). It has its own on/off switch and Preview button next to the celebrations.
- **Get Ready (optional):** each kid's routine checklist that follows the clock: morning until noon,
  afternoon until 5, then evening. Chips let you look at another part of the day, a progress bar shows
  how much is done, and finishing the list celebrates. An item can be linked to a chore: ticking "Make
  bed" in Get Ready checks off that kid's "Make the bed" chore (and its points) too.
- **Friday recap:** the first days of a new points week show last week's points per kid.
- **🚽 Pee Tracker (optional):** a dry-night streak toward 3 weeks with milestones at 3, 7, 14 and 21
  days, and a month calendar. You choose which kids use it. An "Accident while awake" button (parent
  PIN, with a confirmation) starts that kid's whole week over: streak, this week's marks and this week's
  chore points go to zero, and the board plays an angry reaction with a siren and three falling "womps"
  (switch the sounds off in Settings → Layout). Every 7 straight dry days earns +5 bonus stars.
- **Shopping list (optional):** quick-add household items or type your own. Locked behind the parent
  PIN on the board. "📱 Save to phone" shows a QR code; scanning it downloads the list as
  `shopping-list.md` (a checklist). The code works for 10 minutes and opens only that file, never the
  setup page.
- **Chore profiles:** the "Add a chore" window offers age profiles (Ages 4–6, 7–9, 10–13) instead of a
  suggestion list. Tap one to give it to the selected kids. On the setup page (Chores → Edit chore
  profiles) you can change what is in each profile, rename or delete it, make your own, or restore the
  defaults. Profiles can also be saved from a kid's existing chores.
- **Arrange the tabs:** Settings on the Portal (↕ Arrange tabs) or the setup page (Settings → Arrange the
  tabs) puts the tab cards in the order you want.
- **☢️ Nuclear option:** for a kid who really got in trouble. In the points badge menu on the board (or the
  Points card on the setup page) it takes every star they earned this week down to zero and plays the angry
  screen with the siren. Checked chores stay checked, and it shows in the history so it can be undone.
- **Start fresh:** reset just one part without touching the rest: this week's points, all chores, the
  Pee Tracker, or the Get Ready lists. Settings → ↺ Start fresh… on the Portal, or Settings → Start
  fresh on the setup page (type RESET). People, rewards, profiles and settings stay.
- **Reset PortalHub:** Settings → Reset PortalHub (Portal, double confirmation) or the setup page (type
  RESET) erases all data and settings and returns the app to a fresh install. Back up first.
- **Hide the side panel:** one tap on the board (or a setting on the phone page) tucks the clock and
  today list away so the calendar or chores use the whole screen. A ☰ button brings it back.
- **Dark setup page:** the phone/browser setup page is dark by default, with a button to switch to light
  (remembered per browser).
- **Choose your tabs and your home screen:** switch any tab off and pick the one the board opens on.
- **Voice commands (optional, off by default):** say "add paper towels to the shopping list". It runs
  on the Portal with no account and sends no audio anywhere. English only, a few fixed phrases.
- **Proton Calendar (read-only):** paste a Proton "share with anyone" link like any other calendar.

**Known limitations**
- Syncing several Portals (hub and spoke) is only tested against a stand-in hub, not two real Portals.
- Traffic on your Wi-Fi is plain HTTP, so someone who can sniff it could see the sign-in cookie.
- Calendar recurrence rules that repeat more often than hourly are ignored on purpose.

## Requirements

- A Meta Portal with ADB enabled and the [Immortal launcher](https://github.com/starbrightlab/immortal).
  Developed and tested on a Portal `omni` (Android 10, arm64). The original targets the Portal+ gen 1
  (`aloha`). Other models are untested, so reports are welcome.
- A computer with `adb`, and the Portal and your phones on the same Wi-Fi.

## Install

The APK is about 60 MB because the voice model is bundled.

1. Download `portalhub-redux-<version>.apk` (and its `.sha256` file) from the
   [Releases](https://github.com/misfitxtm/PortalCalendar-REDUX/releases) page. Check it:
   `sha256sum -c portalhub-redux-<version>.apk.sha256`. Release APKs are signed with a certificate whose
   SHA-256 fingerprint is
   `9B:3A:B0:DC:CF:98:5F:E3:C9:4D:D7:B9:DE:00:81:B1:0D:6F:DF:19:94:D0:1E:2B:C7:BD:CA:69:05:C4:5B:3B`
   (`apksigner verify --print-certs <apk>` shows it).
2. Connect the Portal over USB (or `adb connect <portal-ip>:5555`) and run:

```sh
adb install -r portalhub-redux-<version>.apk
adb shell am start -n com.portalhub.redux/com.portal.calendar.MainActivity
# Optional: lets the app take the screen back from the stock photo frame
adb shell appops set com.portalhub.redux GET_USAGE_STATS allow
```

3. On the Portal tap **⚙ Settings** (it asks for the parent PIN), then scan the QR code with your phone's
   camera and open the link in your browser. That opens the setup page and signs it in. If you reinstall
   the app the code changes, so scan the new one; an old bookmark or home-screen icon won't sign in.

**Already running the original PortalHub?** This fork has its own app id (`com.portalhub.redux`), so
both can be installed, but they both use port 8090, so stop or remove the original first. Your data
doesn't carry over automatically: in the old app use Settings → Backup, then `adb uninstall
com.portal.calendar`, install this one, and paste the backup into Settings → Backup here. Old plain
backup codes still import.

**Upgrading this fork:** run the same `adb install -r` with a newer release. It only works if the
signing key matches, which is why the key matters (see [docs/RELEASING.md](docs/RELEASING.md)).

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

The APK is written to `app/build/outputs/apk/debug/`. Only `arm64-v8a` is built. A debug APK is
debuggable and signed with your local debug key, so it is for your own Portal only. To make a
release APK, see [docs/RELEASING.md](docs/RELEASING.md).

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
