# Releasing

1. Set `VERSION_NAME` in `gradle.properties`, date the `CHANGELOG.md` entry,
   and merge to `main` with CI green.
2. Publish a GitHub release whose tag is `v<VERSION_NAME>` on that commit.
3. The **Release** workflow (`.github/workflows/release.yml`) then:
   - refuses a tag that does not match `VERSION_NAME`;
   - runs the unit tests and builds the release AAR;
   - attaches `shield-android-<version>.aar` and its SHA-256 to the release;
   - publishes `net.findip:shield-android:<version>` to Maven Central, signed,
     when the secrets below exist. Without them it skips Central with a notice.

To publish an existing tag to Maven Central later, run the workflow by hand:

```bash
gh workflow run release.yml --repo findip-net/findip-shield-android -f tag=v1.0.0
```

## Maven Central: one-time setup

Repository secrets used by the workflow:

| Secret | What it is |
|---|---|
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | A user token from [central.sonatype.com](https://central.sonatype.com) (Account → Generate User Token) for an account that owns the `net.findip` namespace. |
| `SIGNING_KEY` | The ASCII-armored private GPG key that signs the artifacts. Its public key must be on a public keyserver (for example `keyserver.ubuntu.com`). |
| `SIGNING_KEY_PASSWORD` | That key's passphrase. |

The `net.findip` namespace is verified with a DNS TXT record on `findip.net`
whose value the Central Portal shows when the namespace is added.
