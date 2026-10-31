import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createDecipheriv, generateKeyPairSync, privateDecrypt } from 'node:crypto';
import { captureRecovery, encryptRecovery, recoveryPublicKey } from './capture-production-recovery.mjs';

const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 3072 });
const encoded = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
test('recovery ciphertext round trips only with the retained private key and rejects tampering', () => {
  const evidence = { bookmark: 'private-bookmark', versions: ['previous-worker-version'] };
  const encrypted = encryptRecovery(evidence, recoveryPublicKey(encoded));
  assert.doesNotMatch(JSON.stringify(encrypted), /private-bookmark|previous-worker-version/);
  const secret = privateDecrypt({ key: privateKey, oaepHash: 'sha256' }, Buffer.from(encrypted.key, 'base64'));
  const decrypt = (data) => {
    const decipher = createDecipheriv('aes-256-gcm', secret, Buffer.from(encrypted.iv, 'base64'));
    decipher.setAuthTag(Buffer.from(encrypted.tag, 'base64'));
    return JSON.parse(Buffer.concat([decipher.update(data), decipher.final()]).toString('utf8'));
  };
  assert.deepEqual(decrypt(Buffer.from(encrypted.data, 'base64')), evidence);
  const tampered = Buffer.from(encrypted.data, 'base64'); tampered[0] ^= 1;
  assert.throws(() => decrypt(tampered));
});
test('recovery rejects absent, malformed and weak public keys', () => {
  for (const value of [undefined, 'invalid', generateKeyPairSync('rsa', { modulusLength: 2048 }).publicKey.export({ type: 'spki', format: 'der' }).toString('base64')]) {
    assert.throws(() => recoveryPublicKey(value));
  }
});
const configs = [{ name: 'api', d1_databases: [{ database_name: 'main', database_id: 'main' }, { database_name: 'ledger', database_id: 'ledger' }], routes: [{ pattern: 'api.example.com' }] }, { name: 'web', routes: [{ pattern: 'admin.example.com' }], services: [{ binding: 'API', service: 'api' }] }];
const response = (path) => path.startsWith('d1/') ? { bookmark: path } : path === 'workers/domains' ? [{ hostname: 'api.example.com', service: 'api' }, { hostname: 'admin.example.com', service: 'web' }, { hostname: 'unrelated.example.com', service: 'other' }] : { deployments: [{ versions: [{ version_id: path }] }] };
test('recovery captures both databases and Workers and only required live routes', async () => {
  const paths = [];
  const evidence = await captureRecovery(async path => { paths.push(path); return response(path); }, configs, 'a'.repeat(40));
  assert.equal(paths.length, 5);
  assert.deepEqual(evidence.databases.map(item => item.name), ['main', 'ledger']);
  assert.deepEqual(evidence.workers.map(item => item.name), ['api', 'web']);
  assert.equal(evidence.domains.length, 2);
  assert.equal(evidence.sha, 'a'.repeat(40));
});
test('recovery fails closed for missing bookmarks, Worker versions, routes and API access', async () => {
  for (const target of ['d1/database/main/time_travel/bookmark', 'workers/scripts/web/deployments', 'workers/domains']) {
    await assert.rejects(captureRecovery(async path => path === target ? (path === 'workers/domains' ? [] : {}) : response(path), configs, 'sha'));
  }
  await assert.rejects(captureRecovery(async () => { throw new Error('permission denied'); }, configs, 'sha'));
});
