# Repository workflow

## Tools

- Invoke tools by short names on the user PATH (git, node, npm, java, adb); use `.\gradlew.bat` for Gradle. Do not embed machine-specific executable paths or wrap simple commands in `pwsh -Command`.
- Use PowerShell 7 (`pwsh`) only for PowerShell features. If resolution/version is wrong, use `Get-Command -All` or user-level installations and prepend PATH for the current process only. See [Windows tooling](docs/windows-tooling.md) for discovery and cache setup.
- Before cloud commands verify Node 22+; before Android builds verify JDK 17 and `adb version` with the local SDK.

## Ownership and release

- The primary agent owns implementation, test design/execution, failure fixes, APK builds and delivery review. Do not delegate testing; other subagent work requires a separate explicit user request.
- Follow the current [production runbook](docs/stage4-production-runbook.md). Keep the verified SHA fixed, inspect existing runs before dispatching, and report success only after applicable workflows finish.
- Carry authorization through the current release without repeated confirmation. Push-only or model preferences do not authorize production changes or future releases. Local APK building does not authorize GitHub publication, deployment or device installation.
- The user grants standing authorization for encrypted production recovery archives. For an authorized production deployment, use existing GitHub production credentials to read the two D1 recovery bookmarks, the two Workers' current deployments and required routes, encrypt on the runner, upload only ciphertext as a GitHub artifact retained for 14 days, then download and verify decryption locally. Keep the private key local; never upload tokens or plaintext recovery information. Do not ask again for this archive operation. This does not authorize future deployments, database restoration/deletion or expanded credential permissions.

## Verification and APK handoff

- Default to the active smoke set once on the affected platform; see [minimal testing](docs/minimal-testing.md). Do not rerun locally and in CI just to commit, push, deploy or package. Reuse evidence and Web artifacts from the exact source SHA.
- Stable legacy regression tests are retained on demand. Run them only to investigate a production screenshot or user manual acceptance failure, starting with the relevant class; record the failure reason before requesting a full suite. New features and newly fixed regressions remain in the active set. Do not auto-expand solely for changed paths, release preparation, dependencies or version bumps.
- Android changes retain both variants' builds/Lint and the offline boundary check, with production screenshots by default. Offline screenshots and both-flavor full suites are diagnostic options. Web-only or documentation changes do not require Android CI.
- Never weaken assertions, hide failures or describe omitted tests as passed. A failed smoke/screenshot blocks delivery until fixed; old tests can diagnose the failure, not override it. Production-build screenshots with mocked services are not evidence of live production acceptance.
- Report actual commands, source state, passed/failed/skipped counts and coverage limits. Static method counts are not runtime evidence.
- After successful full Android tests, build/reuse Debug APKs for the tested variants; after a full two-variant matrix deliver offline and production. Targeted tests do not trigger handoff. Use the same tested source/configuration, do not repeat tests just to package, and report actual APK paths, environments and SHA-256 rather than stale artifacts.
