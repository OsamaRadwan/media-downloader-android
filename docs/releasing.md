# Publishing an Android release

The public repository is `OsamaRadwan/media-downloader-android`. GitHub Pages publishes `docs/index.html` from `main`; that page reads public GitHub Releases and lists each published version with its APK, release notes, and source archive. Only versions with an actual generic-package APK are listed. The first available build is v1.49.

1. Update `android:versionCode` and `android:versionName` in `AndroidManifest.xml`, and write `docs/releases/v<version>.md` with changes and install notes.
2. Run `./test.ps1` and `./build.ps1`. Confirm the signed `MediaDownloader.apk` reports the expected package and version with Android SDK `aapt dump badging`.
3. Record the APK's SHA-256 in the release notes. Copy it to `MediaDownloader-v<version>.apk` for the GitHub Release asset. Keep the root `MediaDownloader.apk` in the source commit as requested.
4. Commit and push `main`; create and push the `v<version>` tag on that exact commit.
5. Publish a GitHub Release from the tag. Paste the matching release notes and upload the versioned APK. Check the asset's SHA-256 and direct download URL.
6. Open the [download page](https://osamaradwan.github.io/media-downloader-android/) and confirm the new version, APK link, and notes appear. GitHub Pages reads Releases dynamically, including prereleases; no page edit is needed for each version. A release whose phone journey has not been tested should say so clearly and remain a prerelease until verified.

The current build scripts use the local Android SDK at `D:\Random\AndroidSdk` and the existing debug signer at `C:\apktool\uber-apk-signer.jar`. Public release APKs must use a consistent signing key for Android to install an update over a prior release. The v1.49 APK is debug-signed, as stated in its release notes.
