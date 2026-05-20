# Tagged APK Release Design

## Goal
When a `v*` Git tag is pushed, GitHub Actions builds both development and production APKs, signs the production APK with the existing release keystore stored in GitHub Secrets, and publishes both APKs to a GitHub Release.

## Decisions
- Use existing release keystore material through GitHub Secrets.
- Use these required secret names: `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_KEY_ALIAS`, `ANDROID_RELEASE_KEYSTORE_PASSWORD`, `ANDROID_RELEASE_KEY_PASSWORD`.
- Keep development and production apps installable on the same device.
- Use GitHub Releases as the initial distribution channel.
- Trigger only on tags matching `v*`.

## Android Build Model
Add Android product flavors with a single flavor dimension, for example `environment`.

- `dev`: uses `applicationIdSuffix = ".dev"` and app name `NotiFlow Dev`.
- `prod`: keeps the current package id `com.notiflow` and app name `NotiFlow`.

The existing `debug` and `release` build types remain. The release signing config should read signing values from Gradle properties or environment variables so local unsigned/dev builds continue to work without secrets. In GitHub Actions, the workflow restores the keystore file from `ANDROID_RELEASE_KEYSTORE_BASE64` and passes the signing properties to Gradle.

Expected APK outputs:
- Development: `app/build/outputs/apk/dev/debug/app-dev-debug.apk`
- Production: `app/build/outputs/apk/prod/release/app-prod-release.apk`

## Workflow Architecture
Create one workflow at `.github/workflows/release-apk.yml`.

The workflow runs on:
- `push` tags matching `v*`
- optional `workflow_dispatch` for manual verification, if implementation keeps it simple and safe

The workflow jobs should:
1. Check out the repository.
2. Set up JDK 17.
3. Set up Node using the version available to the project, with npm cache under `web/package-lock.json`.
4. Run `npm ci` in `web/`.
5. Run `npm run build:android` in `web/` to refresh `app/src/main/assets/web`.
6. Decode `ANDROID_RELEASE_KEYSTORE_BASE64` to a temporary keystore file outside the repository or under the runner temp directory.
7. Run Android verification: `./gradlew testDebugUnitTest lintDevDebug`.
8. Build APKs: `./gradlew assembleDevDebug assembleProdRelease` with signing properties for prod release.
9. Rename APKs to include the tag, for example `NotiFlow-dev-v1.0.5.apk` and `NotiFlow-prod-v1.0.5.apk`.
10. Create or update the GitHub Release for the tag and upload both APKs.

## Release Notes and Versioning
The workflow should use the tag name as the release name. The Android `versionName` and `versionCode` can stay source-controlled for the first iteration. Automatic version derivation from the tag is out of scope for this first implementation because the current project hardcodes `versionName = "1.0.4"` and `versionCode = 5`.

If later needed, a follow-up can parse tags such as `v1.0.5` into `versionName` and derive `versionCode` from `github.run_number` or a repository variable.

## Error Handling
- If any signing secret is missing, the prod release build should fail clearly during the signing/build step.
- If web build fails, the Android build should not run with stale assets.
- If tests or lint fail, no APKs should be uploaded.
- If GitHub Release upload fails, the build artifacts remain available in the workflow logs only if the implementation also uploads Actions artifacts. Uploading fallback artifacts is optional for the first iteration.

## Security
- Never commit keystore files or passwords.
- Decode the keystore from `ANDROID_RELEASE_KEYSTORE_BASE64` at runtime only.
- Keep decoded keystore material out of the repository path where practical.
- Do not print secret values or signing command arguments containing passwords.

## Testing Strategy
Local verification before merging the workflow:
- `npm run build:android`
- `./gradlew testDebugUnitTest lintDevDebug assembleDevDebug`
- `./gradlew assembleProdRelease` with local signing properties only if a local keystore is available

CI verification:
- Push a test tag such as `v0.0.0-ci-test` after secrets are configured.
- Confirm the workflow creates a GitHub Release.
- Confirm the Release contains both dev and prod APK files.
- Confirm both APKs can be installed on the same device because their application ids differ.

## Out of Scope
- Google Play upload.
- Firebase App Distribution upload.
- Automatic changelog generation.
- Automatic Android version bumping from tags.
- Generating a new release keystore.
