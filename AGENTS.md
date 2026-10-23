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

## Delegation and model selection

When delegating work to subagents, match the model to the complexity of the task. Use the cheapest model that can do the task reliably, and escalate if the result is wrong or incomplete.

- **Haiku** (`haiku`): simple, mechanical, low-risk work. Examples: locating files or symbols, listing usages, reading and summarizing a single file, renaming or reformatting, editing strings or resource XML, bumping `versionCode`/`versionName`, and running a build or test command and reporting the output.
- **Sonnet** (`sonnet`): the default for typical development. Examples: implementing a focused feature or bug fix in one or a few files, writing or updating unit tests, fixing `pmd`/`checkstyle`/`lint` findings, routine refactors, and small Compose or view layout changes.
- **Opus** (`opus`): hard or high-risk work. Examples: changes spanning multiple modules (`src/main`, `libs/redreader-common`, `libs/redreader-datamodel`), architecture or design decisions, concurrency, networking, caching, or database changes, subtle debugging with unclear root cause, merging upstream changes while preserving fork behavior (`NativeTTSManager`, the `.fork` application ID), and security-sensitive code.
- **Fable** (`fable`): reserve for the most demanding cases, such as large cross-cutting migrations or investigations that Opus could not resolve.

Guidelines:

- Do small tasks directly rather than delegating them. Delegate only when it saves context or allows parallel work.
- Use the read-only `Explore` agent for broad code searches and `Plan` for designing non-trivial changes before implementing them.
- Give each subagent a self-contained prompt, including the relevant file paths and the constraints from this guide (tabs, 100-character lines, GPLv3 header on new files, no signing credentials).
- Review subagent changes before reporting them as done, and run `.\gradlew.bat pmd checkstyle lint test` for code changes.
