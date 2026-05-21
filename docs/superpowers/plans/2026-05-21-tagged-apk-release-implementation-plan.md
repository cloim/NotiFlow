# Tagged APK Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a tag-triggered GitHub Actions release pipeline that builds installable dev/prod APKs, signs prod with existing keystore secrets, and uploads both APKs to GitHub Releases.

**Architecture:** Android product flavors split app identity into `dev` and `prod` while preserving existing debug/release build types. Release signing is configured from Gradle properties supplied by GitHub Actions, so local dev builds do not require secrets. A single workflow builds web assets, verifies Android code, builds APKs, renames artifacts with the tag, and publishes a GitHub Release.

**Tech Stack:** Android Gradle Plugin 8.5.2, Kotlin DSL Gradle, Vite/React/npm, GitHub Actions, `softprops/action-gh-release`.

---

## File Structure

- Modify `app/build.gradle.kts`: add flavor dimension, `dev`/`prod` product flavors, release signing config sourced from Gradle properties, and release build signing assignment when all signing properties exist.
- Modify `app/src/main/AndroidManifest.xml`: replace hardcoded app label with `@string/app_name` so flavors can override it.
- Modify `app/src/main/res/values/strings.xml`: define default production `app_name`.
- Create `app/src/dev/res/values/strings.xml`: define development `app_name` as `NotiFlow Dev`.
- Create `.github/workflows/release-apk.yml`: build and release APKs on `v*` tag pushes.
- Modify `README.md`: document tag release workflow and required GitHub Secrets.

---

### Task 1: Add Android Flavor App Labels

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:9-17`
- Modify: `app/src/main/res/values/strings.xml:1-2`
- Create: `app/src/dev/res/values/strings.xml`

- [ ] **Step 1: Write the failing label-resource expectation check**

Run this before editing files:

```powershell
rg --fixed-strings 'android:label="@string/app_name"' app/src/main/AndroidManifest.xml; if ($LASTEXITCODE -eq 0) { exit 1 } else { exit 0 }
```

Expected: command exits `0` because the desired label reference is missing.

- [ ] **Step 2: Replace the manifest label**

Change the `application` label in `app/src/main/AndroidManifest.xml` from:

```xml
android:label="NotiFlow"
```

to:

```xml
android:label="@string/app_name"
```

- [ ] **Step 3: Add production app name resource**

Replace `app/src/main/res/values/strings.xml` with:

```xml
<resources>
    <string name="app_name">NotiFlow</string>
</resources>
```

- [ ] **Step 4: Add development app name override**

Create `app/src/dev/res/values/strings.xml` with:

```xml
<resources>
    <string name="app_name">NotiFlow Dev</string>
</resources>
```

- [ ] **Step 5: Verify label resources exist**

Run:

```powershell
rg --fixed-strings 'android:label="@string/app_name"' app/src/main/AndroidManifest.xml
rg --fixed-strings '<string name="app_name">NotiFlow</string>' app/src/main/res/values/strings.xml
rg --fixed-strings '<string name="app_name">NotiFlow Dev</string>' app/src/dev/res/values/strings.xml
```

Expected: all three commands find one match.

- [ ] **Step 6: Commit flavor label resources**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/dev/res/values/strings.xml
git commit -m "Configure dev and prod app labels"
```

---

### Task 2: Add Product Flavors and Signing Properties

**Files:**
- Modify: `app/build.gradle.kts:8-52`

- [ ] **Step 1: Write the failing Gradle task expectation check**

Run before modifying Gradle:

```powershell
.\gradlew.bat tasks --all | rg "assembleDevDebug|assembleProdRelease"; if ($LASTEXITCODE -eq 0) { exit 1 } else { exit 0 }
```

Expected: command exits `0` because flavor-specific tasks do not exist yet.

- [ ] **Step 2: Add signing-property helpers near the top of `app/build.gradle.kts`**

Insert after `webAppUrl`:

```kotlin
fun signingProperty(name: String): String? =
    (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }

val releaseStoreFile = signingProperty("androidReleaseStoreFile")
val releaseStorePassword = signingProperty("androidReleaseStorePassword")
val releaseKeyAlias = signingProperty("androidReleaseKeyAlias")
val releaseKeyPassword = signingProperty("androidReleaseKeyPassword")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it != null }
```

- [ ] **Step 3: Add signing config and flavors inside `android {}`**

Insert after `defaultConfig { ... }` and before `buildTypes {`:

```kotlin
    signingConfigs {
        create("releaseFromProperties") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
        }
        create("prod") {
            dimension = "environment"
        }
    }
```

- [ ] **Step 4: Assign release signing config only when properties are present**

Update the `release` block in `buildTypes` to:

```kotlin
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("releaseFromProperties")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
```

- [ ] **Step 5: Verify flavor tasks now exist**

Run:

```powershell
.\gradlew.bat tasks --all | rg "assembleDevDebug|assembleProdRelease|lintDevDebug"
```

Expected: output includes `assembleDevDebug`, `assembleProdRelease`, and `lintDevDebug`.

- [ ] **Step 6: Verify dev debug builds locally**

Run:

```powershell
.\gradlew.bat assembleDevDebug
```

Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/dev/debug/app-dev-debug.apk` exists.

- [ ] **Step 7: Commit Gradle flavor/signing configuration**

```bash
git add app/build.gradle.kts
git commit -m "Add Android dev and prod flavors"
```

---

### Task 3: Add Tag-Triggered APK Release Workflow

**Files:**
- Create: `.github/workflows/release-apk.yml`

- [ ] **Step 1: Write the failing workflow presence check**

Run before creating the workflow:

```powershell
Test-Path -LiteralPath ".github\workflows\release-apk.yml"; if ($?) { if (Test-Path -LiteralPath ".github\workflows\release-apk.yml") { exit 1 } else { exit 0 } }
```

Expected: command exits `0` because the workflow file does not exist.

- [ ] **Step 2: Create `.github/workflows/release-apk.yml`**

Use this complete workflow:

```yaml
name: Release APK

on:
  push:
    tags:
      - "v*"
  workflow_dispatch:

permissions:
  contents: write

jobs:
  release-apk:
    name: Build and release APKs
    runs-on: ubuntu-latest

    steps:
      - name: Check out repository
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Set up Node.js
        uses: actions/setup-node@v4
        with:
          node-version: "24"
          cache: npm
          cache-dependency-path: web/package-lock.json

      - name: Install web dependencies
        working-directory: web
        run: npm ci

      - name: Build web assets for Android
        working-directory: web
        run: npm run build:android

      - name: Restore Android release keystore
        env:
          ANDROID_RELEASE_KEYSTORE_BASE64: ${{ secrets.ANDROID_RELEASE_KEYSTORE_BASE64 }}
        run: |
          if [ -z "$ANDROID_RELEASE_KEYSTORE_BASE64" ]; then
            echo "ANDROID_RELEASE_KEYSTORE_BASE64 is required" >&2
            exit 1
          fi
          echo "$ANDROID_RELEASE_KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/notiflow-release.jks"

      - name: Verify required signing secrets
        env:
          ANDROID_RELEASE_KEY_ALIAS: ${{ secrets.ANDROID_RELEASE_KEY_ALIAS }}
          ANDROID_RELEASE_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_RELEASE_KEYSTORE_PASSWORD }}
          ANDROID_RELEASE_KEY_PASSWORD: ${{ secrets.ANDROID_RELEASE_KEY_PASSWORD }}
        run: |
          test -n "$ANDROID_RELEASE_KEY_ALIAS" || { echo "ANDROID_RELEASE_KEY_ALIAS is required" >&2; exit 1; }
          test -n "$ANDROID_RELEASE_KEYSTORE_PASSWORD" || { echo "ANDROID_RELEASE_KEYSTORE_PASSWORD is required" >&2; exit 1; }
          test -n "$ANDROID_RELEASE_KEY_PASSWORD" || { echo "ANDROID_RELEASE_KEY_PASSWORD is required" >&2; exit 1; }

      - name: Run Android tests and lint
        run: ./gradlew testDevDebugUnitTest lintDevDebug

      - name: Build dev and prod APKs
        env:
          ANDROID_RELEASE_KEY_ALIAS: ${{ secrets.ANDROID_RELEASE_KEY_ALIAS }}
          ANDROID_RELEASE_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_RELEASE_KEYSTORE_PASSWORD }}
          ANDROID_RELEASE_KEY_PASSWORD: ${{ secrets.ANDROID_RELEASE_KEY_PASSWORD }}
        run: |
          ./gradlew assembleDevDebug assembleProdRelease \
            -PandroidReleaseStoreFile="$RUNNER_TEMP/notiflow-release.jks" \
            -PandroidReleaseStorePassword="$ANDROID_RELEASE_KEYSTORE_PASSWORD" \
            -PandroidReleaseKeyAlias="$ANDROID_RELEASE_KEY_ALIAS" \
            -PandroidReleaseKeyPassword="$ANDROID_RELEASE_KEY_PASSWORD"

      - name: Prepare release assets
        id: assets
        shell: bash
        run: |
          TAG_NAME="${GITHUB_REF_NAME:-manual}"
          mkdir -p release-assets
          cp app/build/outputs/apk/dev/debug/app-dev-debug.apk "release-assets/NotiFlow-dev-${TAG_NAME}.apk"
          cp app/build/outputs/apk/prod/release/app-prod-release.apk "release-assets/NotiFlow-prod-${TAG_NAME}.apk"
          ls -la release-assets

      - name: Publish GitHub Release
        if: github.ref_type == 'tag'
        uses: softprops/action-gh-release@3bb12739c298aeb8a4eeaf626c5b8d85266b0e65
        with:
          tag_name: ${{ github.ref_name }}
          name: NotiFlow ${{ github.ref_name }}
          files: release-assets/*.apk
          fail_on_unmatched_files: true
```

- [ ] **Step 3: Verify workflow syntax-critical strings exist**

Run:

```powershell
rg --fixed-strings 'tags:' .github/workflows/release-apk.yml
rg --fixed-strings 'ANDROID_RELEASE_KEYSTORE_BASE64' .github/workflows/release-apk.yml
rg --fixed-strings 'assembleDevDebug assembleProdRelease' .github/workflows/release-apk.yml
rg --fixed-strings 'softprops/action-gh-release@3bb12739c298aeb8a4eeaf626c5b8d85266b0e65' .github/workflows/release-apk.yml
```

Expected: all commands find matches.

- [ ] **Step 4: Commit release workflow**

```bash
git add .github/workflows/release-apk.yml
git commit -m "Add tagged APK release workflow"
```

---

### Task 4: Document Release Secrets and Tag Usage

**Files:**
- Modify: `README.md:87-102`

- [ ] **Step 1: Write the failing README release-doc check**

Run before editing README:

```powershell
rg --fixed-strings 'ANDROID_RELEASE_KEYSTORE_BASE64' README.md; if ($LASTEXITCODE -eq 0) { exit 1 } else { exit 0 }
```

Expected: command exits `0` because release secrets are not documented yet.

- [ ] **Step 2: Add release documentation to `README.md`**

Append this section after the Android build section and before the security section:

````markdown
## GitHub Actions APK 배포
`v*` 태그를 푸시하면 GitHub Actions가 개발/운영 APK를 빌드하고 GitHub Release에 업로드합니다.

생성 APK:
- 개발: `NotiFlow-dev-<tag>.apk` (`applicationId: com.notiflow.dev`, 앱 이름: NotiFlow Dev)
- 운영: `NotiFlow-prod-<tag>.apk` (`applicationId: com.notiflow`, 앱 이름: NotiFlow)

필수 GitHub Secrets:
- `ANDROID_RELEASE_KEYSTORE_BASE64`: 기존 release keystore 파일을 base64 인코딩한 값
- `ANDROID_RELEASE_KEY_ALIAS`: release key alias
- `ANDROID_RELEASE_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_RELEASE_KEY_PASSWORD`: key password

Windows PowerShell에서 keystore를 base64로 인코딩하는 예:
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\release.jks")) | Set-Clipboard
```

배포 태그 예:
```bash
git tag v1.0.5
git push origin v1.0.5
```
````

- [ ] **Step 3: Verify README release documentation**

Run:

```powershell
rg --fixed-strings 'GitHub Actions APK 배포' README.md
rg --fixed-strings 'ANDROID_RELEASE_KEYSTORE_BASE64' README.md
rg --fixed-strings 'git push origin v1.0.5' README.md
```

Expected: all commands find matches.

- [ ] **Step 4: Commit release documentation**

```bash
git add README.md
git commit -m "Document tagged APK releases"
```

---

### Task 5: Full Local Verification

**Files:**
- Verify only; no intended file changes.

- [ ] **Step 1: Build and sync web assets**

Run:

```powershell
npm run build:android
```

Working directory: `web/`

Expected: Vite build succeeds and `sync-assets` copies `web/dist` to `app/src/main/assets/web`.

- [ ] **Step 2: Run Android tests, lint, and dev APK build**

Run:

```powershell
.\gradlew.bat clean testDevDebugUnitTest lintDevDebug assembleDevDebug
```

Expected: `BUILD SUCCESSFUL`, and `app/build/outputs/apk/dev/debug/app-dev-debug.apk` exists.

- [ ] **Step 3: Verify prod release task is configured**

Run:

```powershell
.\gradlew.bat tasks --all | rg "assembleProdRelease"
```

Expected: output includes `assembleProdRelease`.

- [ ] **Step 4: Optionally verify prod signing locally when keystore is available**

Run only if a local release keystore is available:

```powershell
.\gradlew.bat assembleProdRelease -PandroidReleaseStoreFile="C:\path\to\release.jks" -PandroidReleaseStorePassword="<keystore-password>" -PandroidReleaseKeyAlias="<key-alias>" -PandroidReleaseKeyPassword="<key-password>"
```

Expected: `BUILD SUCCESSFUL`, and `app/build/outputs/apk/prod/release/app-prod-release.apk` exists.

- [ ] **Step 5: Confirm no secrets were committed**

Run:

```powershell
rg --hidden -n "ANDROID_RELEASE_KEYSTORE_PASSWORD|ANDROID_RELEASE_KEY_PASSWORD|BEGIN RSA|BEGIN OPENSSH|\.jks|\.keystore" .
```

Expected: matches are limited to documentation/workflow references and `.gitignore` patterns; no keystore file or real password value appears.

- [ ] **Step 6: Commit any verification-induced asset changes only if needed**

Run:

```powershell
git status --short --branch -uall
```

Expected: no changes. If `app/src/main/assets/web/*` changes because the committed bundle was stale, inspect with `git diff -- app/src/main/assets/web`, then commit only those asset updates:

```bash
git add app/src/main/assets/web
git commit -m "Refresh bundled web assets"
```

---

### Task 6: Push and Tag Release Verification

**Files:**
- Git/GitHub operations only.

- [ ] **Step 1: Inspect commits and status before push**

Run:

```powershell
git status --short --branch -uall
git log --oneline -10
```

Expected: working tree clean and recent commits include Tasks 1-4 commits.

- [ ] **Step 2: Push implementation commits**

Run:

```bash
git push
```

Expected: `main -> main` push succeeds.

- [ ] **Step 3: Confirm required GitHub Secrets are configured**

Run:

```powershell
gh secret list --repo cloim/NotiFlow
```

Expected: output includes `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_KEY_ALIAS`, `ANDROID_RELEASE_KEYSTORE_PASSWORD`, and `ANDROID_RELEASE_KEY_PASSWORD`.

- [ ] **Step 4: Create and push a release tag**

Use the next intended version tag. Example:

```bash
git tag v1.0.5
git push origin v1.0.5
```

Expected: tag push succeeds and starts the `Release APK` workflow.

- [ ] **Step 5: Watch the workflow**

Run:

```powershell
gh run list --repo cloim/NotiFlow --workflow "Release APK" --limit 5
```

Then watch the latest run:

```powershell
gh run watch --repo cloim/NotiFlow
```

Expected: workflow completes successfully.

- [ ] **Step 6: Verify GitHub Release assets**

Run:

```powershell
gh release view v1.0.5 --repo cloim/NotiFlow --json tagName,assets --jq '.tagName, (.assets[].name)'
```

Expected: output includes `NotiFlow-dev-v1.0.5.apk` and `NotiFlow-prod-v1.0.5.apk`.

---

## Self-Review

- Spec coverage: Tasks cover flavors/app labels, prod signing through secrets, tag-triggered GitHub Release upload, docs, local verification, and tag verification.
- Completeness scan: The plan contains concrete commands, paths, and code snippets. The only angle-bracket values are deliberate examples for user-owned local keystore inputs.
- Type consistency: Gradle property names match between `app/build.gradle.kts` instructions and workflow command: `androidReleaseStoreFile`, `androidReleaseStorePassword`, `androidReleaseKeyAlias`, `androidReleaseKeyPassword`.
- Scope check: Google Play, Firebase distribution, changelog automation, and automatic version bumping remain out of implementation scope as defined in the approved design.
