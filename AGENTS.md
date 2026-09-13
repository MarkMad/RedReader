# Agent workflow

## Delegate simple tasks

The user explicitly requests lightweight sub-agent delegation for easy work in this repository. The main agent should orchestrate the task and remain responsible for the final result.

- For small, well-defined, low-risk implementation tasks, delegate the bounded change to one lightweight sub-agent. Examples include copy edits, minor styling changes, straightforward bug fixes with an understood cause, and small test updates.
- Prefer `gpt-5.6-luna` with `reasoning_effort: "low"` when available. With `collaboration.spawn_agent`, use `fork_turns: "none"` and supply a concise, self-contained brief containing the request, relevant paths, constraints, and acceptance criteria. This file explicitly authorizes that model override.
- While the sub-agent implements the change, do useful independent work such as inspecting callers, identifying regression risks, or preparing verification. Avoid duplicating its implementation or editing the same files concurrently.
- Give the sub-agent a narrow scope and ask it to report changed files, checks performed, and unresolved issues. Do not let it delegate further for a simple task.
- Review its actual diff and perform proportionate verification before reporting completion. The main agent owns integration, correctness, and communication with the user.
- If the task turns out to require substantial investigation, architectural decisions, or changes with broad consequences, the main agent should take over or delegate only a clearly bounded portion.
- Answer simple conversational questions directly. If delegation tools or a lightweight model are unavailable, or higher-priority tool constraints prevent delegation, complete the work directly and briefly mention the fallback. Do not silently substitute a more expensive sub-agent.
- Do not ask for confirmation merely to use this workflow. Respect the user's latest instructions and all applicable permission boundaries.

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
