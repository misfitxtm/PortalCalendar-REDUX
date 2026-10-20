# PortalHub — family hub for Meta Portal

[![Latest release](https://img.shields.io/github/v/release/thefloppytaco/portal-calendar)](https://github.com/thefloppytaco/portal-calendar/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Get it on OpenPortal](https://andronedev.github.io/openportal/openportal-badge.svg)](https://andronedev.github.io/openportal/apps/com.portal.calendar)

Turn a discontinued **Meta Portal+ (gen 1)** into an always-on family hub — a wall
display that syncs everyone's Google and Apple calendars, runs chore charts with star
rewards, keeps shared lists and a meal plan, takes voice commands, and (optionally)
gets AI superpowers. Think commercial family-display subscription, built from a $30
secondhand Portal, running entirely on your LAN. Got more than one? They keep each
other in step over Wi-Fi — no account, no cloud.

> Formerly "Family Calendar" — the app now shows as **PortalHub** on the device.
> The package, repo and install links are unchanged, so existing installs keep updating.

| Week board | Month board |
|---|---|
| ![Week view](docs/week-view.png) | ![Month view](docs/month-view.png) |

| Day timeline | Portrait (day-per-row) | Dark mode |
|---|---|---|
| ![Day timeline](docs/day-view.png) | ![Portrait](docs/portrait-view.png) | ![Dark](docs/dark-view.png) |

## Everything it does

### 📅 Calendar
- **Four views** — a **rolling Day timeline** (hour grid with a live "now" line, where
  overlapping events sit side-by-side in columns so you can see the clashes), Week,
  Month, and Plan (a two-week agenda). Pick which one the board opens on.
- **Your week, your way** — set the first day of the week (Sunday/Monday/Saturday or
  locale), the view it opens on, and a **theme** (light · dark · auto-by-clock ·
  match-system). Run it in **landscape, portrait, or auto-rotate**: auto follows the
  accelerometer and flips tall⇄wide when you physically turn the Portal — and portrait
  isn't a squeeze, the week re-lays-out as a **day-per-row agenda** instead of seven
  cramped columns.
- **Reads any iCal feed**: Google Calendar secret addresses, iCloud public links,
  any `.ics`/`webcal://` URL. Recurring events, edited instances and cancellations
  handled properly. One color per person, with white, black or auto-contrast text —
  rename or recolor a calendar any time from the setup page, no need to re-add it.
- **…or any CalDAV server** — connect a CalDAV account (iCloud, or your own
  Nextcloud, Radicale, Baïkal, Fastmail…) and put its calendars on the board with one
  tap, no share link needed. See [Connecting a CalDAV server](#connecting-a-caldav-server).
- **Two-way event create *and delete*** — add events from the board (`+ Add`) or the
  setup page, and **remove an event by tapping it → 🗑 Remove**. Both write to the
  *real* calendar (iCloud or any CalDAV server with a password, Google via its API
  with your own OAuth client), so changes sync to everyone's phones natively. (Remove
  shows only for events on a connected account; a deleted event stays hidden on the
  board even while a slow secret-iCal feed catches up.)
- **Weather** — free Open-Meteo forecasts: current conditions in the sidebar, daily
  highs in the week header, an hour-accurate forecast on each event.

### ⭐ Chores & stars
- Chores belong to a family member (or **several at once** — one card each), repeat
  on chosen weekdays or run **one-time** on a date, then retire themselves.
- Kids tap big cards to complete them and earn stars toward a weekly goal, with a
  per-kid tally and progress bar.
- Add chores on the board (`+ Add`, with a quick-pick bank that **learns your
  family's frequent chores**) or on the page; long-press a card to remove one.
- Optional **per-kid PIN**: only that kid — or a parent — can check off their chores.
- **Celebrations** — every check-off pops a burst of stars/confetti from the card,
  with a bigger payoff when a kid reaches their weekly goal. Toggle it off in Settings.

### ✅ Routines (optional)
- A separate **Routines** tab for kids' get-ready checklists — pack homework, pack
  lunch, brush teeth, shoes on — grouped by time of day (**morning / afternoon /
  evening / anytime**) so a column reads like a real morning list.
- Works just like chores (per-member or several at once, repeating weekdays or
  one-time, add on the board or the page, long-press to remove) — but with no stars:
  each member's header shows a simple **done-so-far progress bar**, and everything
  resets the next morning. Finishing the list is the point.
- **Off by default.** The tab only appears once you switch Routines on (setup wizard
  or Settings → Board tabs), so families who don't want it never see it.

### 📝 Lists, meals & magic words
- **Shared lists** (groceries, to-dos, packing) editable on the board and from any
  phone on the Wi-Fi; link any list **two-way with Google Tasks** to use it away
  from home.
- **Meal planner** — a week of breakfast/lunch/dinner/snack slots backed by a recipe
  box; tap a planned meal on the board to read its recipe while cooking.
- **Magic words** — a calendar event titled `todo: …`, `groceries: …` or
  `chore: Chani water the plants` is hidden from the board and routed to the right
  place (typo-tolerant; lists auto-create; a member's name assigns the chore on the
  event's date).
- **Inbox calendars** — add a dedicated shared calendar as an *Inbox*: every event in
  it is a command, nothing from it renders, and no prefixes are needed — plain
  phrases like `add oat milk to groceries` or `remind Chani to water the plants`
  just work. Hide the calendar from view on everyone's phones and it clutters nothing.

### ✨ AI (optional, bring your own free Gemini key)
Entirely opt-in: until the Settings toggle is on **and** a key is saved, no AI
options appear anywhere. The key is validated against Google's live model list, the
model picker is populated from it, and calls auto-fall-back if a model is retired.
- **🎤 Voice commands** — tap the mic and speak ("add dentist Tuesday at 3", "put eggs
  on groceries"). Gemini transcribes and acts on your *own* key — a private,
  no-subscription alternative to Alexa. Tap-to-talk (the Portal has no wake-word for
  side-loaded apps), and it routes through the same confirm-able action pipeline.
- **Smart Import** — paste an email or photograph a school flyer; AI proposes events,
  list items and chores, and nothing is added until you confirm.
- **✨ Plan a meal** — name a dish (board or page): AI writes the recipe, slots it
  into the menu, and creates a **dedicated shopping list named after the recipe** —
  check off what's in the pantry, shop the rest.
- **AI recipes** for the recipe box, and **smarter inbox parsing** (free phrasing,
  typo cleanup) with the rule-based parser as automatic fallback.

### 🔄 Sync across Portals (hub-and-spoke)
Got a Portal in the kitchen *and* one in the hallway? Keep them in step with no server
and no account — just your Wi-Fi.
- Pick one Portal as the **hub** (the source of truth); set the others to **follow** it.
- Followers **find the hub automatically** on the network (mDNS), or you can type its
  IP. Chores, stars, lists, meals and family members stay synced: check a chore off in
  the kitchen and it's done in the hallway too.
- The hub stays authoritative, so there's nothing to merge or conflict — a follower
  applies your tap instantly and reconciles with the hub a moment later.
- By default followers keep **their own** calendars, theme and layout (a kitchen
  board and a kid's-room board can look different) — or flip on **mirror everything**
  to make a follower a full clone of the hub, calendars and all.

### 🛡 Family-proofing
- **Kid lock** — an optional 4-digit PIN gates adding, Settings, and deletions on
  the touchscreen, while chore and list check-offs stay kid-friendly.
- **Idle screen, your call** — keep the calendar up as a true always-on display,
  **or** let it hand off to the Portal's screensaver after a few idle minutes (gentler
  on the panel; a tap brings the board right back), or leave it off entirely.
- **Backup & clone** — export the whole setup (calendars, members, chores/lists/meals,
  layout, *and* your connected accounts + AI key) as one code and paste it into another
  Portal to clone it. The code holds credentials, so keep it private.
- **Feature toggles** — show or hide each board tab (Chores, Lists, Meals, and the
  opt-in Routines) independently, so the board only carries what your family uses.
- **Remote-friendly (Portal TV)** — navigating with a remote instead of the
  touchscreen? A clear accent **focus outline** follows the selected button as you
  move around. It appears only in remote/D-pad use, so touchscreen Portals never see it.
- **Display size** — a live slider (70–160%) zooms the whole UI; drag on your phone
  and watch the board resize (10″ Portals like ~110–125%).
- **Guided setup wizard** — scan a fresh Portal's QR and a skippable wizard walks
  through everything above; re-run it anytime from Settings.

## Requirements

- Meta Portal+ gen 1 (`aloha`, Android 9 / API 28) with **ADB enabled** and the
  [Immortal launcher](https://github.com/starbrightlab/immortal) provisioned.
  Other Portal models are untested — reports welcome.
- A computer with `adb` for the one-time install.
- Wi-Fi shared by the Portal and your phones.

## Install

**No `adb`?** Install straight from a Chromium browser with
[OpenPortal](https://andronedev.github.io/openportal/apps/com.portal.calendar) — plug the
Portal in over USB, click install, done. (A community catalog by
[@andronedev](https://github.com/andronedev); the Portal pulls the APK from these releases
itself.)

Or with `adb` — grab
[`portal-calendar.apk`](https://github.com/thefloppytaco/portal-calendar/releases/latest/download/portal-calendar.apk)
(that link always points at the newest release), then:

```sh
adb install -r portal-calendar.apk
adb shell am start -n com.portal.calendar/.MainActivity

# Recommended: lets the takeover guard reclaim the screen from the stock
# photo frame no matter how it got there (it only ever watches for the frame):
adb shell appops set com.portal.calendar GET_USAGE_STATS allow
```

Wireless adb works too: with USB connected once, `adb tcpip 5555`, unplug, then
`adb connect <portal-ip>:5555` (TCP mode doesn't survive a Portal reboot — replug
and re-run after restarts).

**That's the whole computer part.** The board shows a QR code; scan it and the
**setup wizard** walks you through family members, calendars, two-way sync, weather,
the look (theme, layout, idle screen, size) and — if you have more than one — syncing
your Portals together. Every step is skippable and everything's changeable later. Add
the page to your phone's home screen and it opens like an app.

## Connecting a CalDAV server

Besides iCloud, the **Settings → Two-way sync → iCloud or CalDAV** card connects any
CalDAV server. One account per Portal.

1. In the setup page open **Settings → Two-way sync**.
2. **Server** — the server's CalDAV address (leave it blank for iCloud):

   | Server | Address |
   |---|---|
   | Nextcloud | `https://HOST/remote.php/dav` |
   | Radicale | `https://HOST/` — or on your home network `http://LAN-IP:5232/` |
   | Baïkal | `https://HOST/dav.php` |
   | Fastmail | `https://caldav.fastmail.com/` |

   Typing just `HOST` also works (it assumes `https://`). If the exact path is wrong
   but the server publishes the standard `/.well-known/caldav` redirect, that's tried
   automatically.
3. **Username** and **password** — Nextcloud and Fastmail need an **app password**
   (created in their security settings), not your normal login.
4. **Connect.** The account's calendars are listed; tap **Show on board** on the ones
   you want. They appear on the board within ~15 minutes, or straight away with
   **Sync the Portal now**. Recolor or rename them with ✎ like any other calendar.
   Events added or removed from the Portal are written to the server too.

Notes
- Credentials are sent with HTTP Basic authentication, stored only on the Portal, and
  included in the backup code. Use `https://` when you can: with a plain `http://`
  address (handy for a Radicale on your LAN) the password crosses the network
  unencrypted.
- The password is only ever sent to the server you connected (and the hosts it listed
  your calendars on, e.g. iCloud's `pNN-caldav` servers) — a board calendar whose
  address points anywhere else is refused rather than sent your credentials.
- If the server answers a read or a write with a redirect (for example `http://` to
  `https://`, or a moved address), the Portal reports "the server redirected the
  request — reconnect using its final address" rather than pretending it worked:
  connect again using the address the server redirects to.
- `https://` servers need a certificate Android already trusts; self-signed
  certificates aren't accepted.
- Disconnecting keeps the calendars on the board, but they stop updating (they keep
  showing their last copy until you remove them).
- **Tested:** [Radicale](https://radicale.org/) 3.8.3 — discovery (including the
  `/.well-known/caldav` fallback), calendar list and colors, reading (a recurring
  event with an edited instance comes back whole, non-ASCII titles survive, empty
  calendars), adding and deleting events; automated against a local server (see
  `CalDavClientRadicaleTest`). The Nextcloud, Baïkal and Fastmail addresses above are
  the servers' standard CalDAV endpoints but **haven't been tried against live
  installs**; the Nextcloud-style response parsing is covered by unit tests on a
  hand-written fixture only. Baïkal's default login type is *Digest*, which isn't
  supported — switch its WebDAV authentication to *Basic* in Baïkal's admin settings.

## Building from source

JDK 21, Android SDK (compileSdk 36), Gradle wrapper included:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
ANDROID_HOME=/path/to/android-sdk \
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## How it works (the interesting bits)

The Portal is locked-down Android 9: no Google Play Services, no root, locked
bootloader. The design works within that:

- **Reading calendars** uses plain ICS feeds — the one auth-free universal interface —
  parsed with [biweekly](https://github.com/mangstadt/biweekly), including proper
  `RECURRENCE-ID` override handling. A connected CalDAV account is read the same way:
  a `calendar-query` REPORT returns the events in the board's window, which are fed
  to the same parser.
- **Writing** uses real APIs: CalDAV for iCloud and other CalDAV servers (OkHttp, because `HttpURLConnection`
  refuses the PROPFIND verb) and Google's Calendar/Tasks REST with a bring-your-own
  Desktop OAuth client (the sign-in dead-ends on a localhost URL you paste back —
  or swap `localhost` for the Portal's IP and the Portal finishes it itself).
- **The screensaver takeover** is a small war story. The stock launcher re-asserts
  the system screensaver setting on every resume, dream windows draw *above*
  activities, and the Portal's presence policy sometimes skips the screensaver and
  sleeps directly. So the app listens for dream/screen-off broadcasts, wakes the
  device with an `ACQUIRE_CAUSES_WAKEUP` wakelock, relaunches the board in a timed
  salvo — and, with the optional usage-stats grant, a 30-second guard reclaims the
  screen from the stock photo frame no matter what path it snuck in through. The
  gentler **yield** mode does the opposite: it simply drops the keep-screen-on hold
  after an idle timer so the device falls into its own screensaver, and re-arms on
  the next touch.
- **The setup page** is served from the Portal itself (NanoHTTPD on `:8090`) as an
  installable PWA. No cloud, no accounts; nothing leaves your LAN except the feed
  fetches and the APIs you explicitly connect.
- **Multi-Portal sync** needs no server: the hub advertises itself over mDNS
  (`NsdManager`) and serves the shared family files through the same `:8090` HTTP
  endpoint; followers discover it, poll it, and write their edits back to it — so the
  hub is the single source of truth and there's no conflict resolution to get wrong.

## Privacy & security

Calendar URLs, the iCloud / CalDAV password, Google OAuth tokens, and the
optional Gemini key live only in the app's private storage on the Portal. Treat
secret calendar links like passwords. The setup page is unauthenticated by design —
anyone on your Wi-Fi can open it — so treat your home network accordingly. The
optional usage-stats permission is used solely to detect the stock photo frame.

## License

[MIT](LICENSE)
