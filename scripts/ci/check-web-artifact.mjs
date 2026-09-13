import { readFileSync } from 'node:fs';
const stamp = JSON.parse(readFileSync('web/verification.json', 'utf8'));
if (!/^[a-f0-9]{40}$/.test(process.env.RELEASE_SHA ?? '') || stamp.commit !== process.env.RELEASE_SHA || !['smoke', 'full'].includes(stamp.profile)) {
  throw new Error('Downloaded Web artifact does not match the verified source.');
}
for (const variant of ['offline', 'connected']) {
  if (!readFileSync(`web/dist-${variant}/index.html`, 'utf8').includes('<html')) throw new Error(`Missing ${variant} build.`);
}
console.log(`Verified Web artifact: ${stamp.commit} (${stamp.profile})`);
