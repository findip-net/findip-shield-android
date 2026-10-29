# Contributing

Issues and pull requests are welcome.

- The behaviour of this SDK is defined by the FindIP Shield mobile SDK
  specification, kept by FindIP with the Shield API. A change in behaviour
  starts as a change to the specification.
- `./gradlew :shield:testDebugUnitTest` must pass, including the fixture
  tests in `shield/src/test/resources/fixtures`. Those files are shared with
  the iOS SDK and the Shield API; do not edit them here.
- Keep the library free of dependencies beyond kotlinx-coroutines, and of
  permissions beyond `INTERNET`.
- Report vulnerabilities privately, see [SECURITY.md](SECURITY.md).
