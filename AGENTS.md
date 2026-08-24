# Repository command environment

- Invoke installed tools directly through the user `PATH`, using short command names such as `git`, `node`, `npm`, `java`, `gradle`, and `adb`. Invoke the repository wrapper as `.\gradlew.bat`. This is the default because it shortens commands and reduces path, quoting, and token errors.
- Use PowerShell only when a command actually needs PowerShell features such as `$env:PATH`, `Get-Command`, `Join-Path`, object pipelines, or PowerShell control flow. In that case use PowerShell 7 (`pwsh`), not Windows PowerShell 5.1 (`powershell`), unless a task specifically requires 5.1.
- Do not wrap a simple executable invocation in `pwsh -Command`; run the short command directly.
- Do not embed machine-specific executable paths in commands, scripts, or documentation.
- If the required version is not first on `PATH`, locate a user-level installation with `Get-Command -All` or under `$env:USERPROFILE` / `$env:LOCALAPPDATA`, prepend its directory only to the current process `$env:PATH`, and continue using the short command name.
- Wrangler requires Node.js 22 or newer. Verify `node --version` before cloud commands.
- Android builds require JDK 17 and the local Android SDK. Verify `java --version` and `adb version` before running Gradle tasks.
- See `docs/windows-tooling.md` for command-selection rules and the verified discovery/current-process PATH setup commands.
