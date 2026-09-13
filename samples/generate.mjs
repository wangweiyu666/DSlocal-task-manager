// Use the application's real codec and validator; no second protocol implementation.
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { randomBytes } from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, resolve } from 'node:path';
import assert from 'node:assert/strict';

const samples = dirname(fileURLToPath(import.meta.url));
const root = resolve(samples, '..');
const args = process.argv.slice(2);
const modes = args.filter(arg => ['--check', '--write', '--fresh'].includes(arg));
if (modes.length > 1) throw new Error('Choose one of --check, --write or --fresh.');
const mode = modes[0] ?? '--fresh';
let date;
for (let i = 0; i < args.length; i++) {
  if (modes.includes(args[i])) continue;
  if (args[i] === '--date' && args[i + 1] && mode === '--fresh') { date = args[++i]; continue; }
  throw new Error(`Unsupported argument: ${args[i]}`);
}
if (date && (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !Number.isFinite(Date.parse(date)) || new Date(date).toISOString().slice(0, 10) !== date)) {
  throw new Error('Date must be a real YYYY-MM-DD.');
}
if (!date) {
  const now = new Date();
  if (now.getHours() < 4) now.setDate(now.getDate() - 1);
  date = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}
const output = resolve(root, 'build/samples');
await mkdir(output, { recursive: true });
const requireWeb = createRequire(resolve(root, 'web/package.json'));
const { build } = requireWeb('esbuild');
const codecPath = resolve(output, 'sample-codec.mjs');
await build({
  entryPoints: [resolve(root, 'web/src/protocol/dst1.ts')], outfile: codecPath,
  bundle: true, platform: 'node', format: 'esm', logLevel: 'silent',
});
const { encodeDst1, decodeDst1 } = await import(pathToFileURL(codecPath).href);
const filenames = ['current-features', 'current-exception'];
for (const name of filenames) {
  let batch = JSON.parse(await readFile(resolve(samples, `${name}.json`), 'utf8'));
  if (mode === '--fresh') {
    const ids = new Map();
    const collect = value => {
      if (!value || typeof value !== 'object') return;
      for (const [key, item] of Object.entries(value)) {
        if ((key === 'i' || key === 'b') && typeof item === 'string' && !ids.has(item)) ids.set(item, randomBytes(12).toString('base64url'));
        collect(item);
      }
    };
    collect(batch);
    const remap = value => {
      if (typeof value === 'string') return ids.get(value) ?? value;
      if (Array.isArray(value)) return value.map(remap);
      if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, remap(item)]));
      return value;
    };
    batch = remap(batch);
    const tasks = [...(batch.t ?? []), ...(batch.g ?? []).flatMap(group => group.t ?? [])];
    for (const task of tasks) {
      if (task.x) task.x.s = date;
      else task.y = date;
    }
    for (const exception of batch.e ?? []) exception.y = date;
    batch.m = `合成样例，任务日 ${date}；仅用于专用测试数据。`;
  }
  const encoded = encodeDst1(batch);
  assert.deepEqual(decodeDst1(encoded.envelope).batch, JSON.parse(encoded.json));
  if (mode === '--check') {
    const committed = (await readFile(resolve(samples, `${name}.dst1`), 'utf8')).trim();
    assert.deepEqual(decodeDst1(committed).batch, JSON.parse(encoded.json), `${name}: JSON/envelope drift`);
  } else {
    const path = resolve(mode === '--write' ? samples : output, `${name}.dst1`);
    await writeFile(path, encoded.envelope + '\n');
    if (mode === '--fresh') await writeFile(resolve(output, `${name}.json`), JSON.stringify(batch, null, 2) + '\n');
    console.log(path);
  }
}
if (mode === '--check') {
  const legacy = (await readdir(samples)).filter(name => name.endsWith('.dst1') && !filenames.some(current => name === `${current}.dst1`));
  for (const name of legacy) decodeDst1((await readFile(resolve(samples, name), 'utf8')).trim());
  console.log(`Validated ${filenames.length} JSON/envelope pairs and ${legacy.length} legacy envelopes; no import, network or deployment.`);
}
