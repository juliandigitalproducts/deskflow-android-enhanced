# Build, CI & release signing

This text was taken over unchanged from the previous README so that the build information is preserved.

Paths and links in the excerpted README text are relative to the repository root, not to `docs/`.

### Build manually

1. Clone the repository:
   ```bash
   git clone https://git.juliandigitalproducts.de/jdaniels/Deskflow-Android-Enhanced.git
   ```
2. Build the APK (requires JDK 17 & the Android SDK):
   ```bash
   ./gradlew :app:assembleRelease
   ```
   The APK is written to `app/build/outputs/apk/release/`.
   Alternatively open the project in Android Studio, then build and run the app on your device.

## Release signing

Release builds are signed only with the release key – from four environment variables or
`../deskflow-release.keystore.properties` next to the repository. Releases are built by the Forgejo CI
(`.forgejo/workflows/android.yml`): a push to `main` raises the patch version, signs the APK with the
`DESKFLOW_*` repository secrets and publishes `Deskflow-Android-Enhanced-<version>.apk` as a release; other
branches only run the unit tests and a debug build, without the key. A local `./gradlew :app:assembleRelease`
also writes the renamed copy to `app/build/outputs/apk-ready/release/`.
Without a key the release build **stops**; it never falls back to the public debug key. Since 1.1.0 the
app id is `de.juliandigitalproducts.deskflow` (before: `org.tfv.deskflow`, same as upstream), so it installs
next to the old app; set it up once again and uninstall the old one.

Gradle reads `DESKFLOW_KEYSTORE_FILE` (absolute path to `deskflow-release.jks`), `DESKFLOW_KEYSTORE_PASSWORD`,
`DESKFLOW_KEY_ALIAS` and `DESKFLOW_KEY_PASSWORD`.

Keep the `.jks` file and its passwords safe and **out of git** — losing them means you can't ship
signed updates that install over an existing install.
