import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export function eligibleRun(run, sha, currentRun) {
  return run.id !== Number(currentRun) && run.head_sha === sha && run.head_branch === 'main'
    && ['push', 'workflow_dispatch'].includes(run.event) && run.status === 'completed'
    && ['success', 'failure'].includes(run.conclusion);
}

export function verifiedArtifact(jobs, artifacts, sha) {
  return jobs.some(job => job.name === 'verify' && job.conclusion === 'success')
    && artifacts.some(artifact => artifact.name === `cloud-verification-${sha}` && !artifact.expired);
}

export function failedVerification(jobs) {
  return jobs.some(job => job.name === 'verify' && job.conclusion === 'failure');
}

async function main() {
  const env = process.env;
  const sha = env.RELEASE_SHA || env.GITHUB_SHA;
  if (!/^[a-f0-9]{40}$/.test(sha ?? '')) throw new Error('A full source SHA is required.');
  const full = env.FULL_REGRESSION === 'true';
  if (full && !env.DIAGNOSTIC_REASON?.trim()) throw new Error('Full regression requires a screenshot/manual failure reason.');
  if (env.DEPLOY_TARGET === 'production' && (env.PRODUCTION_CONFIRMATION !== 'DEPLOY_PRODUCTION' || sha !== env.GITHUB_SHA || env.GITHUB_REF !== 'refs/heads/main')) {
    throw new Error('Production requires an explicitly confirmed fixed main SHA.');
  }
  const emit = (runId) => {
    const output = `reuse=${Boolean(runId)}\nrun_id=${runId || ''}\n`;
    if (env.GITHUB_OUTPUT) appendFileSync(env.GITHUB_OUTPUT, output);
    else process.stdout.write(output);
  };
  if (full || (env.DEPLOY_TARGET !== 'production' && env.REQUIRE_EXISTING !== 'true')) return emit(null);
  const api = async (path) => {
    const response = await fetch(`${env.GITHUB_API_URL || 'https://api.github.com'}/repos/${env.GITHUB_REPOSITORY}/${path}`, {
      headers: { Authorization: `Bearer ${env.GITHUB_TOKEN}`, Accept: 'application/vnd.github+json' },
    });
    if (!response.ok) throw new Error(`GitHub verification lookup failed: ${response.status}`);
    return response.json();
  };
  // Expired/missing evidence falls back to one fresh verification, never an unchecked deployment.
  const { workflow_runs: runs } = await api(`actions/workflows/cloud-ci.yml/runs?head_sha=${sha}&per_page=100`);
  for (const run of runs.filter(run => eligibleRun(run, sha, env.GITHUB_RUN_ID))) {
    const [{ jobs }, { artifacts }] = await Promise.all([
      api(`actions/runs/${run.id}/jobs?per_page=100`), api(`actions/runs/${run.id}/artifacts?per_page=100`),
    ]);
    if (failedVerification(jobs)) throw new Error('The latest verification for this SHA failed. Resolve it before reusing older evidence.');
    if (verifiedArtifact(jobs, artifacts, sha)) return emit(run.id);
  }
  if (env.REQUIRE_EXISTING === 'true') throw new Error('No verified Web artifact for this SHA. Run Cloud Connected verification once first.');
  emit(null);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
