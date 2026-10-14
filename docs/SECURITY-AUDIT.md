# Security audit — 2026-10-14

Scope: the config server path, the manifest, secret storage, feed fetching, and the phone setup page.
Method: code reading plus on-device curl checks. No fuzzing yet, and the Rust parser doesn't exist yet.

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
| Restoring a backup wiped the pairing token, desyncing the running server | Import now preserves the token |
| `adb backup` could extract app data (`allowBackup` defaulted on) | `allowBackup="false"` |
| Gemini key sent in the URL query | Moved to the `x-goog-api-key` header |
| CalDAV response body written to logcat | Log line removed |

## Open

| Severity | Finding | Plan |
|---|---|---|
| Low | The pairing token and the hub-to-spoke mirror bundle are not encrypted at rest/in transit (the token is on screen in the QR code by design) | TLS, later |
| Low | Keystore key is not tied to a screen lock, so code running as the app (or root) can still use it | Acceptable on a wall display |
| Medium | FamilySync: spokes can't authenticate to the hub; a future hub token must not be sent to an unverified mDNS-discovered address | Pair by entering the hub's address and token explicitly |
| Low | Cleartext HTTP on the LAN exposes the cookie to a sniffer | Local TLS, later |
| Low | Debug-signed, debuggable build: anyone with an authorized adb can read app data | Release keystore for real use |
| Low | `config.html` builds `onclick` strings from ids; a malicious imported backup could inject script | Use data attributes plus event delegation; validate ids on import |
| Low | Rust server: no read timeouts or connection cap (slow-loris after auth) | Swap the HTTP layer if exposure grows |
| Low | Token never rotates | "Reset token" button |
| Info | `usesCleartextTraffic` is on so plain-http feeds work | Keep, document |
| Info | The parent PIN (Shopping tab, adjusting points, marking potty days) is enforced only in the board UI. Anyone with the phone page's login can do the same things, and the PIN is a 4-digit value stored in plain text. It is a family convenience, not a security boundary | Document; token pairing is the real control |
| Info | Voice commands are off by default: no mic button, no recording, no model in memory until a parent enables them. The default engine is offline (audio never leaves the Portal). The optional Gemini engine sends audio to Google | By design; documented in the README |
