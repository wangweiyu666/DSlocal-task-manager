# Repository command environment

- Invoke installed tools directly through the user `PATH`, using short command names such as `git`, `node`, `npm`, `java`, `gradle`, and `adb`. Invoke the repository wrapper as `.\gradlew.bat`. This is the default because it shortens commands and reduces path, quoting, and token errors.
- Use PowerShell only when a command actually needs PowerShell features such as `$env:PATH`, `Get-Command`, `Join-Path`, object pipelines, or PowerShell control flow. In that case use PowerShell 7 (`pwsh`), not Windows PowerShell 5.1 (`powershell`), unless a task specifically requires 5.1.
- Do not wrap a simple executable invocation in `pwsh -Command`; run the short command directly.
- Do not embed machine-specific executable paths in commands, scripts, or documentation.
- If the required version is not first on `PATH`, locate a user-level installation with `Get-Command -All` or under `$env:USERPROFILE` / `$env:LOCALAPPDATA`, prepend its directory only to the current process `$env:PATH`, and continue using the short command name.
- Wrangler requires Node.js 22 or newer. Verify `node --version` before cloud commands.
- Android builds require JDK 17 and the local Android SDK. Verify `java --version` and `adb version` before running Gradle tasks.
- See `docs/windows-tooling.md` for command-selection rules and the verified discovery/current-process PATH setup commands.

# Push and deployment delegation

- The user prefers Luna (`gpt-5.6-luna`) for this project's push and deployment workflow. Delegate these tasks to Luna when that model is available, including pushing the authorized commit, waiting for CI/staging, and completing an authorized production release.
- Follow `docs/stage4-production-runbook.md` (Luna workflow section). Keep the verified commit fixed, inspect existing runs before dispatching, and report completion only after the relevant workflow jobs finish successfully.
- Carry existing authorization through the current release without repeatedly asking. A model preference does not authorize unrelated or future production releases; a push-only request requires clarifying production scope before production changes.
- If Luna is unavailable, explain that limitation instead of silently claiming another model is Luna.

# Debug APK handoff after full Android tests

- After each successful full Android test run, delegate Debug APK building and delivery to Luna (`gpt-5.6-luna`). Build the tested variants; after the full three-variant matrix, deliver offline, connected (staging), and production Debug APKs.
- Use the same tested Android source and build configuration. Do not repeat passing full tests solely to package APKs; targeted test runs do not trigger this handoff. Reuse verified outputs from the same source when available.
- Follow `docs/minimal-testing.md` for commands and handoff checks. Report actual APK paths, environments and SHA-256 values; do not report stale artifacts as new builds.
- Local Debug APK building does not itself authorize publishing a GitHub Release, production deployment, or installation on a device. If Luna is unavailable, report the limitation explicitly.
