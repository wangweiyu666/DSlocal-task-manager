[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{40}$')][string]$Commit,
    [string]$Repository = 'wangweiyu666/DSlocal-task-manager'
)
$ErrorActionPreference = 'Stop'
$raw = gh api "repos/$Repository/actions/workflows/android-ci.yml/runs?head_sha=$Commit&per_page=100"
if ($LASTEXITCODE -ne 0) { throw 'Unable to read Android CI evidence.' }
$runs = ($raw | ConvertFrom-Json).workflow_runs
foreach ($run in $runs) {
    if ($run.head_sha -ne $Commit -or $run.head_branch -ne 'main' -or
        $run.event -notin @('push', 'workflow_dispatch') -or
        $run.status -ne 'completed') { continue }
    if ($run.conclusion -eq 'failure') { throw 'The latest Android CI for this SHA failed. Resolve it before reusing older evidence.' }
    if ($run.conclusion -ne 'success') { continue }
    $rawJobs = gh api "repos/$Repository/actions/runs/$($run.id)/jobs?per_page=100"
    if ($LASTEXITCODE -ne 0) { throw 'Unable to read Android CI jobs.' }
    $jobs = ($rawJobs | ConvertFrom-Json).jobs
    if (@($jobs | Where-Object { $_.name -eq 'verify' -and $_.conclusion -eq 'success' }).Count -eq 0 -or
        @($jobs | Where-Object { $_.name -eq 'screenshots' -and $_.conclusion -eq 'success' }).Count -eq 0) { continue }
    return [ordered]@{ source = 'github-actions'; commit = $Commit; runId = $run.id; url = $run.html_url }
}
throw 'No successful Android CI (verify + production screenshots) for this main SHA. Run Android CI once; both release preparations reuse it.'
