# Changelog

All notable changes to this library are documented here.

## 1.0.1 - 2026-10-29

### Fixed

- `track` no longer runs on the caller's thread. Called from the main thread
  (`lifecycleScope`, `viewModelScope`), 1.0.0 read the visitor ID from disk,
  ran the root checks and hashed the identity there, which StrictMode reports.
- The visitor ID moved from SharedPreferences to `noBackupFilesDir`, so Auto
  Backup and device transfer no longer copy it to another phone. An ID kept by
  1.0.0 moves over on first use.
- The first event of a session no longer waits for its `session_start`.

### Added

- `ShieldOptions.timeoutMillis` (default 5 000): the longest `track` waits for
  Shield. After it, `track` returns an UNKNOWN verdict with MONITOR and
  `Dropped(UNREACHABLE)`; 1.0.0 could wait more than 30 seconds offline.

## 1.0.0 - 2026-10-29

### Added

- `FindIPShield.configure(context, siteKey, options)`, `track(event, context, screen)`
  (suspend, plus a callback form for Java), `identify(userId, email, plan, salt, custom)`,
  `setConsent`, `sessionId`, `reset()` and `resetVisitor()`, as in the
  FindIP Shield mobile SDK specification.
- Identity is hashed (SHA-256) and encrypted (RSA-OAEP with SHA-256 and MGF1
  SHA-256) on the device; the raw email and user ID never leave it.
- Permission `INTERNET` only. One dependency: kotlinx-coroutines.
- The shared contract fixtures of the specification run in the unit tests.
