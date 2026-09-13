import { writeFileSync } from 'node:fs';
const commit = process.env.GITHUB_SHA;
if (!/^[a-f0-9]{40}$/.test(commit ?? '')) throw new Error('Full source SHA required.');
writeFileSync('web/verification.json', JSON.stringify({
  commit, profile: process.env.FULL_REGRESSION === 'true' ? 'full' : 'smoke',
}) + '\n');
