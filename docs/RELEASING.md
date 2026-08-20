# Releasing TempoBox

Releases are fully automated by `.github/workflows/release.yml`: pushing a
version tag builds, tests, (optionally) signs, and publishes a GitHub Release
with the APK attached and auto-generated release notes.

## Cut a release

1. Bump the version in `app/build.gradle.kts`:
   ```kotlin
   versionCode = 2          // monotonically increasing integer
   versionName = "1.1.0"    // human-readable, matches the tag below
   ```
2. Commit and push to `main`; wait for CI to go green.
3. Tag and push the tag:
   ```bash
   git tag v1.1.0
   git push origin v1.1.0
   ```
4. Done. The **Release** workflow runs unit tests, builds the release APK,
   signs it (if signing secrets are configured), and publishes
   `https://github.com/<owner>/tempobox/releases/tag/v1.1.0` with generated
   notes from the merged PRs/commits since the last tag.

Alternatively, trigger it by hand: **Actions ▸ Release ▸ Run workflow** and
enter an existing tag (e.g. `v1.1.0`). Useful for re-publishing after adding
signing secrets.

## APK signing (one-time setup)

Without secrets the workflow publishes an *unsigned* APK
(`tempobox-release-unsigned.apk`) — fine for CI validation, but devices won't
install it. To publish installable signed builds:

1. Create a keystore (keep it safe; losing it means users must uninstall to
   update):
   ```bash
   keytool -genkeypair -v -keystore tempobox.keystore -alias tempobox \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Add four **repository secrets** (repo ▸ Settings ▸ Secrets and variables ▸
   Actions):

   | Secret              | Value                                   |
   |---------------------|-----------------------------------------|
   | `KEYSTORE_BASE64`   | `base64 -w0 tempobox.keystore` output   |
   | `KEYSTORE_PASSWORD` | keystore password                       |
   | `KEY_ALIAS`         | `tempobox` (or whatever you chose)      |
   | `KEY_PASSWORD`      | key password                            |

3. The next tagged release publishes a signed `tempobox-release.apk`.

## Versioning convention

Semantic-ish: `vMAJOR.MINOR.PATCH`. Bump MINOR for features, PATCH for fixes.
`versionCode` increases by 1 on every release regardless.
