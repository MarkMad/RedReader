## Repository guide

RedReader is an Android Reddit client fork written in Java and Kotlin, using traditional Android views and Jetpack Compose. The application code is under `src/main`, with JVM tests in `src/test` and instrumentation tests in `src/androidTest`. Shared code is split into the `redreader-common` and `redreader-datamodel` libraries under `libs/`; Gradle includes both modules from `settings.gradle.kts`. Dependency and tool versions live in `gradle/libs.versions.toml`, while Android module configuration is in the root `build.gradle.kts`.

On Windows, use the checked-in Gradle wrapper from PowerShell:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat pmd checkstyle lint test
```

The CI workflow in `.github/workflows/build.yml` runs `assembleRelease`, `assembleDebug`, `pmd`, `checkstyle`, `lint`, and `test`. Release builds can be unsigned; signed builds use the optional `keystore.properties` configuration in `build.gradle.kts`. Release automation lives in `.github/workflows/release.yml`. Do not expose or commit signing credentials, keystore files, or local machine configuration.

Follow `CONTRIBUTING.md` and `.editorconfig`: Java uses tabs and 100-character lines, XML should match its surrounding file, and new Java/XML files need the GPLv3 header. Keep changes focused and preserve existing style.

This fork has application-specific behavior, including native text-to-speech in `src/main/java/org/quantumbadger/redreader/audio/NativeTTSManager.java`. When integrating upstream changes, preserve that fork behavior and retain the fork application ID `org.quantumbadger.redreader.fork` in `build.gradle.kts`. Release version values (`versionCode` and `versionName`) are also maintained there.
