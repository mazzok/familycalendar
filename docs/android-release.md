# Android releases

Every push to `main` runs [`.github/workflows/android-release.yml`](../.github/workflows/android-release.yml).
When the new commits contain a release-worthy change, it builds a signed APK and publishes it as a
GitHub release. The latest APK is always at:

**https://github.com/mazzok/familycalendar/releases/latest/download/app-release.apk**

## How a run works

1. Check out the full history, set up JDK 17, the Android SDK (platform 35, build-tools 35.0.0) and Node 24.
2. Restore `app/google-services.json` and the release keystore from secrets.
3. Run the unit tests (`./gradlew testDebugUnitTest`). If they fail, the run stops.
4. Run [semantic-release](https://semantic-release.gitbook.io/), configured in [`.releaserc.json`](../.releaserc.json):
   1. Read the commits since the last `v*` tag and work out the next version. If no commit warrants
      a release, the run ends here and nothing is published.
   2. **prepare:** [`scripts/build-release-apk.sh`](../scripts/build-release-apk.sh) builds
      `assembleRelease` with that version. It checks that the APK's `versionName` matches the version,
      that the APK is signed, and that it is signed with the expected key (see `ANDROID_CERT_SHA256`).
      It then copies the APK to `dist/app-release.apk`.
   3. **publish:** create the tag `vX.Y.Z` and a GitHub release with generated notes and
      `app-release.apk` attached.

Steps 4.1 to 4.2 run before any tag exists. A failed test, build or check therefore never produces a tag
or release. Runs share the `android-release` concurrency group: a second push waits for the running
release to finish rather than racing it for the same version. semantic-release also skips publishing
when the checked-out commit is no longer the head of `main`.

You can start a run by hand under **Actions → Android release → Run workflow** (only on `main`).

## One-time setup

### 1. Create the release keystore

Do this once. Keep the file and passwords for as long as the app exists. Android only installs an
update when it is signed with the same key as the installed app. If the key is lost, everyone has to
uninstall the app and install it again.

```
keytool -genkeypair -v -keystore familycalendar-release.jks -storetype PKCS12 \
  -alias familycalendar -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=Family Calendar"
```

keytool asks for a password. With PKCS12 the key password is the same as the store password.

Store the `.jks` file and its password somewhere safe outside the repository, such as a password manager.
`.gitignore` excludes `*.jks` and `*.keystore`. Do not commit the file anyway.

Show the certificate fingerprint. The value is public and is used in step 3:

```
keytool -list -v -keystore familycalendar-release.jks -alias familycalendar | grep SHA256
```

### 2. Add repository secrets

**Settings → Secrets and variables → Actions → Secrets → New repository secret**

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The keystore file, base64-encoded (see below) |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | `familycalendar` (the `-alias` above) |
| `ANDROID_KEY_PASSWORD` | Key password (same as the keystore password for PKCS12) |
| `GOOGLE_SERVICES_JSON_BASE64` | `app/google-services.json`, base64-encoded |

Base64-encode a file:

```
# Linux
base64 -w0 familycalendar-release.jks
# macOS
base64 -i familycalendar-release.jks
# Windows PowerShell (copies to the clipboard)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("familycalendar-release.jks")) | Set-Clipboard
```

Or use the GitHub CLI, which reads the value from stdin:

```
base64 -w0 familycalendar-release.jks | gh secret set ANDROID_KEYSTORE_BASE64
base64 -w0 app/google-services.json  | gh secret set GOOGLE_SERVICES_JSON_BASE64
```

`GITHUB_TOKEN` is provided automatically. Do not create it.

### 3. Add the certificate fingerprint variable (recommended)

**Settings → Secrets and variables → Actions → Variables → New repository variable**

| Variable | Value |
|---|---|
| `ANDROID_CERT_SHA256` | SHA-256 fingerprint from step 1, e.g. `2B:4E:7D:…` (colons and case don't matter) |

If the variable is set, a build signed with any other key fails before the release step. This guards
against a secret that was replaced by mistake. If the variable is not set, this check is skipped.

### 4. Check repository settings

- **Settings → Actions → General → Actions permissions:** Actions must be allowed. The workflow
  requests `contents: write` itself, so the default *read* token permission is fine. Your organisation
  must not forbid workflows from requesting write access.
- If rulesets or tag protection cover `v*` tags or `main`, allow GitHub Actions to create tags.
- The repository must be public for the download link to work without signing in.

### About Firebase

`app/google-services.json` is the Firebase *client* configuration that is built into every APK. It is
not an admin credential. It is git-ignored in this project, so CI receives it from a secret and the
existing Firebase setup stays as it is. Never add Firebase service-account keys or other admin
credentials to the repository or to this workflow; the build does not need them.

The app uses email/password sign-in, so the release certificate does not have to be registered in the
Firebase console. Register the SHA-256 there only if you later add Google Sign-In, App Check or
similar features.

## Commit messages

The commit messages decide the version, following [Conventional Commits](https://www.conventionalcommits.org/):

| Commit message | Release |
|---|---|
| `fix: reminder fires twice after reboot` | patch: 1.4.2 → 1.4.3 |
| `feat: weekly view shows week numbers` | minor: 1.4.2 → 1.5.0 |
| `feat!: drop Android 7 support`, or a `BREAKING CHANGE:` footer | major: 1.4.2 → 2.0.0 |
| `perf: …` | patch |
| `docs:`, `chore:`, `ci:`, `refactor:`, `test:`, `style:`, `build:` | no release |

Squash merges use the PR title as the commit message, so give PRs Conventional Commit titles. Commits
that don't follow the format do not trigger a release. The project's commits before this pipeline was
added are like that, so the first release (`1.0.0`) comes with the first `feat:` or `fix:` commit.

## Versioning

- `versionName` is the semantic-release version, e.g. `1.4.2`.
- `versionCode` is calculated from it in [`app/build.gradle.kts`](../app/build.gradle.kts):
  `MAJOR × 1,000,000 + MINOR × 1,000 + PATCH`. For example, `1.4.2` → `1004002`.

The same version always gives the same code, and every newer version gives a higher one. The highest
supported version is `2099.999.999` (2,099,999,999), below Android's limit of 2,100,000,000. Gradle
fails the build if MINOR or PATCH exceeds 999 or MAJOR exceeds 2099. MINOR and PATCH reset with
every bump, so that limit is not reached in practice.

Local builds without `-PreleaseVersion` keep `versionName "1.0"` and `versionCode 1`. To reproduce a CI
build locally (bash, with `ANDROID_HOME` and the `ANDROID_KEYSTORE_*` / `ANDROID_KEY_*` variables set):

```
bash scripts/build-release-apk.sh 1.4.2
```

## Installing and updating on a phone

1. On the phone, open
   https://github.com/mazzok/familycalendar/releases/latest/download/app-release.apk
   (or pick a specific version under **Releases**).
2. Open the downloaded file. The first time, Android asks you to allow installs from this source
   (browser or Files app): **Settings → Allow from this source**.
3. Tap **Install**. Play Protect may warn about an app it doesn't know; choose to install anyway.

**Updating:** download the latest APK and install it the same way. Android keeps the app's data because
each release is signed with the same key and has a higher `versionCode`. The app does not update itself.

**Switching from an Android Studio build:** apps installed from Android Studio are signed with a
debug key. Android refuses to install the release APK over them ("App not installed" or "conflicts with
an existing package"). Uninstall the debug app once, then install the release APK. Calendar data lives
in Firestore and comes back after you sign in again.

## Troubleshooting

- **No release was created:** the run log shows "There are no relevant changes". None of the new commits
  is a `feat`, `fix`, `perf` or breaking change.
- **The tag exists but the release is missing or has no APK:** the publish step failed after it pushed
  the tag, for example because the asset upload failed. Delete the tag (`git push --delete origin vX.Y.Z`),
  then re-run the workflow.
- **"APK is not signed with the expected release key":** `ANDROID_KEYSTORE_BASE64` or the alias does not
  match `ANDROID_CERT_SHA256`. Restore the original keystore. Do not change the variable to fit a new
  key unless you accept that every user has to reinstall.
