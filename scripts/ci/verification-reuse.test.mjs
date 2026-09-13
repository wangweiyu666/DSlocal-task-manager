import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { eligibleRun, verifiedArtifact, failedVerification } from './verification-reuse.mjs';

const sha = 'a'.repeat(40);
const passed = { id: 1, head_sha: sha, head_branch: 'main', event: 'push', status: 'completed', conclusion: 'success' };
test('reuse requires the exact completed main source and a trusted event', () => {
  assert.equal(eligibleRun(passed, sha, 2), true);
  for (const override of [{ head_sha: 'b'.repeat(40) }, { head_branch: 'feature' }, { event: 'pull_request' }, { status: 'in_progress' }, { conclusion: 'cancelled' }, { id: 2 }]) {
    assert.equal(eligibleRun({ ...passed, ...override }, sha, 2), false);
  }
});
test('failed deployment may reuse verification but failed verification never can', () => {
  assert.equal(failedVerification([{ name: 'verify', conclusion: 'failure' }]), true);
  assert.equal(failedVerification([{ name: 'verify', conclusion: 'success' }, { name: 'deploy-production', conclusion: 'failure' }]), false);
  assert.equal(eligibleRun({ ...passed, conclusion: 'failure' }, sha, 2), true);
  const artifact = { name: `cloud-verification-${sha}`, expired: false };
  assert.equal(verifiedArtifact([{ name: 'verify', conclusion: 'success' }], [artifact], sha), true);
  for (const conclusion of ['failure', 'cancelled', 'skipped']) {
    assert.equal(verifiedArtifact([{ name: 'verify', conclusion }], [artifact], sha), false);
  }
  assert.equal(verifiedArtifact([{ name: 'deploy-production', conclusion: 'success' }], [artifact], sha), false);
});
test('expired, missing and different-source artifacts are not evidence', () => {
  const jobs = [{ name: 'verify', conclusion: 'success' }];
  for (const artifacts of [[], [{ name: `cloud-verification-${sha}`, expired: true }], [{ name: 'cloud-verification-other', expired: false }]]) {
    assert.equal(verifiedArtifact(jobs, artifacts, sha), false);
  }
});

test('dispatch rejects unconfirmed production and unexplained full regressions before lookup', () => {
  const run = (overrides) => spawnSync(process.execPath, ['scripts/ci/verification-reuse.mjs'], {
    encoding: 'utf8', env: {
      ...process.env, GITHUB_OUTPUT: '', GITHUB_SHA: sha, RELEASE_SHA: sha,
      GITHUB_REF: 'refs/heads/main', DEPLOY_TARGET: 'verify', REQUIRE_EXISTING: '',
      FULL_REGRESSION: '', DIAGNOSTIC_REASON: '', PRODUCTION_CONFIRMATION: '', ...overrides,
    },
  });
  const smoke = run({});
  assert.equal(smoke.status, 0, smoke.stderr);
  assert.match(smoke.stdout, /reuse=false/);
  for (const overrides of [
    { DEPLOY_TARGET: 'production' },
    { DEPLOY_TARGET: 'production', PRODUCTION_CONFIRMATION: 'DEPLOY_PRODUCTION', RELEASE_SHA: 'b'.repeat(40) },
    { FULL_REGRESSION: 'true' },
    { FULL_REGRESSION: 'true', DIAGNOSTIC_REASON: '  ' },
  ]) assert.equal(run(overrides).status, 1);
  const diagnostic = run({ FULL_REGRESSION: 'true', DIAGNOSTIC_REASON: 'Production screenshot: option controls overlap' });
  assert.equal(diagnostic.status, 0, diagnostic.stderr);
  assert.match(diagnostic.stdout, /reuse=false/);
});
