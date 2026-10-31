import { createCipheriv, createPublicKey, publicEncrypt, randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

export function recoveryPublicKey(encoded) {
  const key = createPublicKey({ key: Buffer.from(encoded || '', 'base64'), format: 'der', type: 'spki' });
  if (key.asymmetricKeyType !== 'rsa' || key.asymmetricKeyDetails.modulusLength < 3072) {
    throw new Error('Recovery evidence requires an RSA public key of at least 3072 bits.');
  }
  return key;
}

export function encryptRecovery(evidence, key) {
  const secret = randomBytes(32);
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', secret, iv);
  const data = Buffer.concat([cipher.update(JSON.stringify(evidence), 'utf8'), cipher.final()]);
  return {
    format: 'dstationery-production-recovery-1',
    key: publicEncrypt({ key, oaepHash: 'sha256' }, secret).toString('base64'),
    iv: iv.toString('base64'), tag: cipher.getAuthTag().toString('base64'), data: data.toString('base64'),
  };
}

export async function captureRecovery(api, configs, sha) {
  const evidence = { capturedAt: new Date().toISOString(), sha, databases: [], workers: [] };
  for (const db of configs[0].d1_databases) {
    const recovery = await api(`d1/database/${db.database_id}/time_travel/bookmark`);
    if (!recovery?.bookmark) throw new Error('Recovery preflight returned no D1 bookmark.');
    evidence.databases.push({ name: db.database_name, recovery });
  }
  for (const config of configs) {
    const deployments = await api(`workers/scripts/${config.name}/deployments`);
    if (!deployments?.deployments?.some(item => item.versions?.length)) throw new Error('Recovery preflight returned no Worker version.');
    evidence.workers.push({ name: config.name, deployments, configuredRoutes: config.routes, services: config.services });
  }
  const domains = await api('workers/domains');
  evidence.domains = configs.flatMap(config => config.routes.map(route => {
    const domain = domains.find(item => item.hostname === route.pattern && item.service === config.name);
    if (!domain) throw new Error('Recovery preflight could not verify a production domain route.');
    return domain;
  }));
  return evidence;
}

async function main() {
  // Validate the operator's public key before reading production metadata. Never persist plaintext on the runner.
  const key = recoveryPublicKey(process.env.RECOVERY_PUBLIC_KEY);
  const token = process.env.CLOUDFLARE_API_TOKEN;
  const account = process.env.CLOUDFLARE_ACCOUNT_ID;
  if (!token || !account) throw new Error('Production credentials are required.');
  const api = async (path) => {
    const response = await fetch(`https://api.cloudflare.com/client/v4/accounts/${account}/${path}`, {
      headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(30_000),
    });
    const data = await response.json();
    if (!response.ok || !data.success) throw new Error(`Recovery preflight failed (HTTP ${response.status}).`);
    return data.result;
  };
  const configs = ['cloud', 'cloud-web'].map(directory => JSON.parse(readFileSync(`${directory}/wrangler.jsonc`, 'utf8')).env.production);
  const evidence = await captureRecovery(api, configs, process.env.GITHUB_SHA);
  writeFileSync(join(process.env.RUNNER_TEMP, 'production-recovery.enc.json'), JSON.stringify(encryptRecovery(evidence, key)), { mode: 0o600 });
  console.log('Encrypted recovery evidence captured: two D1 bookmarks, two Worker deployments and production domain routes.');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(() => { console.error('Production recovery preflight failed. No production mutation is permitted.'); process.exitCode = 1; });
}
