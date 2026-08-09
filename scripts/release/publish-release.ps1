[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$EvidencePath,
    [string]$Repository = 'wangweiyu666/DSlocal-task-manager'
)

$ErrorActionPreference = 'Stop'
$evidence = Get-Content -LiteralPath $EvidencePath -Raw | ConvertFrom-Json
if ($evidence.status -ne 'approved') { throw 'Release evidence status must be approved.' }
if (git status --porcelain) { throw 'Working tree must be clean.' }
$head = (git rev-parse HEAD).Trim()
if ($head -ne $evidence.commit) { throw 'Evidence does not match HEAD.' }
if ((git branch --show-current).Trim() -ne 'main') { throw 'Publishing is allowed only from main.' }
$apk = Join-Path (Split-Path -Parent $EvidencePath) "DStationery-$($evidence.version).apk"
$actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLowerInvariant()
if ($actualHash -ne $evidence.apkSha256) { throw 'APK hash does not match evidence.' }
$pending = @($evidence.deviceChecks.psobject.Properties | Where-Object { $_.Value -ne 'passed' })
if ($pending) { throw 'All device checks must be passed.' }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'GitHub CLI is required.' }
gh auth status
if ($LASTEXITCODE -ne 0) { throw 'GitHub CLI is not authenticated.' }
$tag = "v$($evidence.version)"
if (git tag --list $tag) { throw "Tag already exists: $tag" }
$confirmation = Read-Host "Type $tag to create and upload the prerelease"
if ($confirmation -ne $tag) { throw 'Publishing cancelled.' }
git tag -a $tag -m "DStationery $($evidence.version)"
if ($LASTEXITCODE -ne 0) { throw 'Unable to create annotated Git tag.' }
git push origin $tag
if ($LASTEXITCODE -ne 0) { throw 'Unable to push Git tag.' }
gh release create $tag $apk "$apk.sha256" (Join-Path (Split-Path -Parent $EvidencePath) 'THIRD_PARTY_NOTICES.txt') $EvidencePath `
    --repo $Repository --title "DStationery $($evidence.version)" --prerelease --generate-notes
if ($LASTEXITCODE -ne 0) { throw 'Unable to create GitHub prerelease.' }
