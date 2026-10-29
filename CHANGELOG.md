# Changelog

All notable changes to this library are documented here.

## 1.0.0 - Unreleased

### Added

- `FindIPShield.configure(context, siteKey, options)`, `track(event, context, screen)`
  (suspend, plus a callback form for Java), `identify(userId, email, plan, salt, custom)`,
  `setConsent`, `sessionId`, `reset()` and `resetVisitor()`, as in the
  FindIP Shield mobile SDK specification.
- Identity is hashed (SHA-256) and encrypted (RSA-OAEP with SHA-256 and MGF1
  SHA-256) on the device; the raw email and user ID never leave it.
- Permission `INTERNET` only. One dependency: kotlinx-coroutines.
- The shared contract fixtures of the specification run in the unit tests.
