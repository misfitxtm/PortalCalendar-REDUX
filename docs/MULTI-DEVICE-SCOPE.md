# Scope: running PortalHub REDUX on more than the Meta Portal

Goal: the same app runs well on (A) the Meta Portal (today), (B) an ordinary Android tablet, and (C) an Android
family-calendar frame that lets you install APKs. Family data, chores, rewards, setup page and hub/spoke sync stay
identical everywhere. This is a scoping note, not a build plan that has been started.

## What already works anywhere

- Chores, stars, rewards, Potty Tracker, Get Ready, lists, meals, shopping, calendar feeds, the web setup page,
  pairing codes, encrypted backup and hub/spoke sync. None of it calls a Portal API.
- Orientation can already be set to landscape, portrait or auto (`docs/PORTRAIT_DESIGN.md`).
- No Google Play Services dependency, so Fire tablets and de-Googled frames are not excluded by that.

## What is tied to the Portal today

| # | Area | Today | Why it breaks elsewhere |
|---|------|-------|-------------------------|
| 1 | CPU | `arm64-v8a` only (app and Rust core, `app/build.gradle.kts`) | Many cheap tablets and frames are 32-bit (`armeabi-v7a`); emulators are x86. They will not install or will crash on start. |
| 2 | Idle / sleep | Takeover mode listens for Meta's screensaver "dream" broadcasts; the photo-frame check hardcodes `com.immortal.launcher` and needs an adb-granted usage-access permission | A plain tablet has neither. Only "Off" and "Yield to screensaver" make sense there, and there is no night dimming or screen-off schedule. |
| 3 | Auto-start | `BOOT_COMPLETED` receiver only starts a service; actual launch relies on Immortal's boot list | Android 10+ blocks starting an activity from the background, so a tablet will not open the board after a reboot. |
| 4 | Install steps | Several are adb-only: usage-access `appops`, storage permission (`pm grant`) | Someone with a tablet and no computer cannot finish setup. The app never asks for storage permission itself. |
| 5 | targetSdk 28 | Deliberate, keeps Portal behaviour | Newer Android treats old-target apps differently (storage, background limits, battery optimisation). Needs testing; may need targeted code (e.g. MediaStore for photo upload on Android 11+). |
| 6 | Own IP address | Read from `WifiManager.connectionInfo` | Deprecated; wrong or empty on Ethernet, some Android 10+ builds and VPNs, so the QR code / setup address could be wrong. |
| 7 | Screen shape | Designed on 1280x800 landscape | 4:3 and 16:10 tablets, 1080p frames and portrait are untested. |
| 8 | Fixed port | Always 8090 | Fine, but needs a clear message if something else holds it. |

## Phases

**Phase 0: Compatibility audit (1 to 2 days).** Add `armeabi-v7a` and `x86_64` to the Gradle ABI list and the
`cargo ndk` targets. Run on emulators at API 28, 30, 34 and one phone-size tablet. Output: a list of crashes and
broken screens, ranked. Decides how big phases 1 to 3 really are.

**Phase 1: "Works on any tablet" (about 1 week).**
- Universal APK (arm64 + v7a); check size (today ~60 MB with the voice model) and decide on split APKs.
- Fix the IP lookup (use `ConnectivityManager` / `NetworkInterface`).
- Add an in-app permission flow for storage, battery-optimisation exemption and (Android 13+) notifications.
- Idle options for generic devices: Always on, Dim after N minutes, Screen off between chosen hours.
- Optional "Use as the home screen" (HOME intent filter), the reliable way to auto-start and stay on top on a tablet
  or frame without root or adb.
- Layout pass for 16:10, 4:3 and portrait.

**Phase 2: Easy install (about 3 days).** A first-run checklist screen inside the app (what is granted, what is not,
one tap each). README sections for installing without a computer: browser download plus "install unknown apps", or
a file manager. The adb steps become the optional route.

**Phase 3: Device profiles (about 3 days).** Settings → Device type: Portal / Tablet / Frame. Hides Portal-only and
Immortal-only options, picks sensible defaults for idle and auto-start. Keeps the Portal behaviour untouched.

**Phase 4: targetSdk and hardening (later, optional).** Raising targetSdk brings foreground-service type
declarations, notification permission, scoped storage and background limits. Do it only if a real device needs it.

## Needs your decision

1. Which exact tablet(s) or frame(s)? Some frames are locked down (no unknown sources, no adb) and cannot be
   supported at all; the model decides this.
2. Is a ~100 MB universal APK acceptable, or should releases ship separate arm64 and 32-bit APKs?
3. Do you want the "use as home screen" mode (best for frames, more intrusive on a shared tablet)?
4. Stay on targetSdk 28 for now (recommended) or move it up in Phase 4?

## Risks

- A locked-down frame may refuse any sideloaded app; verify with the real device before investing.
- 32-bit builds need the Vosk voice library for that ABI; if it is missing there, voice is disabled on those devices.
- Hub/spoke sync between devices on different app versions: add a version check before mixing devices.
- No device hardware is available here beyond the Portal, so Phases 1 to 3 would be tested on emulators first.

## Out of scope

iOS, the Play Store, a hosted/cloud version, and non-Android frames.
