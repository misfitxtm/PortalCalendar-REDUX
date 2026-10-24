# Security audit — 2026-10-14

Scope: the config server path, the manifest, secret storage, feed fetching, and the phone setup page.
Method: code reading plus on-device curl checks. At that point there was no fuzzing and the Rust parser did not exist yet; both came later (see the table below and the addendum).

## Fixed

| Finding | Fix |
|---|---|
| Config API had no authentication; any LAN host could read the export (all credentials) and change settings | Rust gate: per-device token, cookie handoff from the QR code |
| CSRF and DNS rebinding against the API | `Host` must be an IP, `Origin` must match, `SameSite=Strict` cookie |
| SSRF through feed validation, including redirects | `UrlGuard`: blocks loopback/link-local/wildcard, re-checks each redirect hop |
| Reflected XSS on `/oauth` | HTML-encoded |
| Unbounded feed download could exhaust the heap | 10 MB cap |
| iCloud password, Google tokens and the Gemini key stored in plain SharedPreferences | Sealed with a non-exportable Android Keystore AES-GCM key; existing plain values are sealed on first start |
| Backup export was base64, not encrypted | Passphrase-encrypted (AES-256-GCM, PBKDF2-HMAC-SHA256, 210k iterations, header authenticated, iteration count capped on import). Old plain backups still import |
| Calendar feed parsing (an untrusted download) ran in a Java library with no limits on hostile input | Replaced by a Rust parser (`core/src/ics.rs`): input, line, event, instance and recurrence-work caps, a wall-clock deadline, panics contained per event. A mutation fuzzer (1.5M inputs) found a library panic on a non-ASCII `BYDAY` and rules that never match but stall the search; both fixed. Compared with the old parser on generated and real feeds; the only difference is a fix (an all-day series' `UNTIL` date is inclusive) |
| Hub-and-spoke sync had no way to sign in to the hub | Pairing code sent as a Bearer token, kept sealed; a paired Portal only talks to the typed hub address, never to one found over mDNS, so a rogue advertiser can't harvest the code |
| Release builds were debug-signed and debuggable | Release APKs signed with a dedicated key held outside the repo (`docs/RELEASING.md`); `debuggable` is off |
| Restoring a backup wiped the pairing token, desyncing the running server | Import now preserves the token |
| `adb backup` could extract app data (`allowBackup` defaulted on) | `allowBackup="false"` |
| Gemini key sent in the URL query | Moved to the `x-goog-api-key` header |
| CalDAV response body written to logcat | Log line removed |

## Open

| Severity | Finding | Plan |
|---|---|---|
| Low | The pairing token and the hub-to-spoke mirror bundle are not encrypted at rest/in transit (the token is on screen in the QR code by design) | TLS, later |
| Low | Keystore key is not tied to a screen lock, so code running as the app (or root) can still use it | Acceptable on a wall display |
| Low | Cleartext HTTP on the LAN exposes the sign-in cookie and the Portal-to-hub pairing code to a sniffer | Local TLS, later |
| Low | Fuzzing is mutation-only (no coverage guidance) because this machine has no C++ compiler for libFuzzer | Run `cargo fuzz` after installing `gcc-c++` |
| Low | The pairing code is the hub's full pairing token, so a paired Portal can do anything the hub's phone page can | A narrower per-Portal credential |
| Low | `config.html` builds `onclick` strings from ids; a malicious imported backup could inject script | Use data attributes plus event delegation; validate ids on import |
| Low | Rust server: no read timeouts or connection cap. Tested 2026-10-24: 200 idle or half-sent connections, including unauthenticated `POST /pair` with an unsent body, did not slow a signed-in request (see the addendum) | Swap the HTTP layer if exposure grows |
| Info | `usesCleartextTraffic` is on so plain-http feeds work | Keep, document |
| Info | The parent PIN (Shopping tab, adjusting points, marking potty days) is enforced only in the board UI. Anyone with the phone page's login can do the same things, and the PIN is a 4-digit value stored in plain text. It is a family convenience, not a security boundary | Document; token pairing is the real control |
| Info | Voice commands are off by default: no mic button, no recording, no model in memory until a parent enables them. The default engine is offline (audio never leaves the Portal). The optional Gemini engine sends audio to Google | By design; documented in the README |

## Addendum — 2026-10-24

Scope: what was added after the audit above, up to 4.0.0-redux.8: the pairing code (`POST /pair`, `POST /api/pair/new`), one-off download links (`GET /dl/shopping-list.md`), the chore approval PIN and queue (`/api/approval-pin`, `/api/chores`), and the sign-in token itself. Method: code reading, `cargo test` and `cargo deny check advisories` on `core/` (both clean), and local tests of the server code with a dummy handler. Not covered: `BoardController.kt`, `FamilySync`, `GoogleCal`, `CalDav`, `Gemini`, `BackupCrypto`, `Secrets`, the Gradle dependencies, and the app on a device.

### Fixed

| Finding | Fix |
|---|---|
| The sign-in token could not be revoked or rotated, and the pairing code hands it to computers as a one-year cookie, so a computer signed in once stayed signed in for good | **Sign out all devices** (Portal: Settings; setup page: Settings). It makes a new token, stores it and swaps it into the running Rust gate at once, so every old cookie, link and QR code stops working. Tests: gate unit tests, an end-to-end test on the live server, and a token-format test |

### Checked and found sound

| Route | Why |
|---|---|
| `POST /pair` (no sign-in) | The gate requires the same-origin check; the code is 6 digits, works once, lasts 5 minutes and is burned after 5 wrong tries, so one guess in about 200,000 per code; compared in constant time |
| `GET /dl/shopping-list.md` (no sign-in) | Needs a 16-byte random code that lasts 10 minutes and opens only that one file; `..` is refused at the gate; the file name in the response comes from a safe-character check |
| Chore approvals over the setup page | Refused: `toggle`, `request`, `approve` and `deny` only work on the Portal |
| Stalled connections | Tested against the real server code: 4, 16, 64 and 200 stalled connections (body never sent, headers never finished, or silent) did not slow a legitimate request |

### Open

| Severity | Finding | Plan |
|---|---|---|
| Low | Anyone on the Wi-Fi can send 5 wrong codes to `/pair` and burn the current pairing code, so a parent has to ask for a new one | A per-address limit needs the client address passed from the Rust gate to the handler; not worth it yet |
| Low | A Portal paired as a spoke keeps the hub's old token after "Sign out all devices" and needs the new pairing code | Documented in the confirmation text |
| Low | Fuzzing is still mutation-only: libFuzzer needs a C++ compiler (`sudo dnf install gcc-c++`); `cargo-fuzz` and a nightly toolchain are installed | Run `cargo fuzz` once the compiler is installed |
