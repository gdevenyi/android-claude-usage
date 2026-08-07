# Submitting to F-Droid

F-Droid does not read this directory. The build recipe lives in F-Droid's own
`fdroiddata` repository; `io.github.gdevenyi.claudeusage.yml` here is a ready
copy of it, kept alongside the code so it stays in step with the app.

The listing text and images F-Droid *does* read from this repository are in
`fastlane/metadata/android/en-US/`.

## What is already in place

- GPL-3.0-or-later `LICENSE`, public source, no proprietary dependencies
  (androidx, Material Components and Kotlin, all Apache-2.0; no Play Services).
- `fastlane/metadata/android/en-US/` with `title.txt`, `short_description.txt`
  (79 chars), `full_description.txt`, `changelogs/1.txt` (matching
  `versionCode` 1) and `images/` with a 512px `icon.png` and four
  `phoneScreenshots/`.
- `versionCode` and `versionName` are literals in `app/build.gradle.kts`, so
  F-Droid's regex can read them.
- A `v0.1.0` tag on the release commit, matching `versionName`.
- `./gradlew :app:assembleRelease` produces an unsigned APK — no keystore is
  needed, F-Droid signs it.

## Submitting

1. Fork https://gitlab.com/fdroid/fdroiddata and branch it
   `io.github.gdevenyi.claudeusage`.
2. Copy `io.github.gdevenyi.claudeusage.yml` from this directory to
   `metadata/io.github.gdevenyi.claudeusage.yml` in that fork.
3. Check it locally, if you have `fdroidserver` installed:

   ```bash
   fdroid lint io.github.gdevenyi.claudeusage
   fdroid rewritemeta io.github.gdevenyi.claudeusage
   fdroid build io.github.gdevenyi.claudeusage
   ```

4. Commit as `New App: io.github.gdevenyi.claudeusage` and open a merge
   request against `master`.

Expect roughly 24–48 hours between the merge and the app appearing, since
signing needs a maintainer with keystore access.

## If the build fails on the scanner

F-Droid's scanner objects to committed binaries. `gradle/wrapper/gradle-wrapper.jar`
is the only one here, and F-Droid builds with its own Gradle rather than the
wrapper, so it can simply be dropped during the build:

```yaml
    scandelete:
      - gradle/wrapper/gradle-wrapper.jar
```

## Releasing a new version

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
   (500 characters at most).
3. Tag the commit `v<versionName>` and push the tag.

`UpdateCheckMode: Tags` means F-Droid picks new tags up on its own; the
metadata file only needs editing if the build recipe changes.
