import { readFile } from "node:fs/promises";

const environment = process.argv[2];
if (!new Set(["staging", "production"]).has(environment)) throw new Error("expected staging or production");
const config = JSON.parse((await readFile(new URL("../wrangler.jsonc", import.meta.url), "utf8")).replace(/^\s*\/\/.*$/gm, ""));
const selected = config.env[environment];
const databases = Object.fromEntries(selected.d1_databases.map((database) => [database.binding, database]));
if (!databases.DB || !databases.DELETION_LEDGER) throw new Error(`${environment} must bind DB and DELETION_LEDGER`);
for (const [binding, database] of Object.entries(databases)) {
  if (/^(00000000|11111111|22222222|33333333)-/.test(database.database_id)) {
    throw new Error(`${environment} ${binding} still uses a placeholder database_id`);
  }
}
if (selected.vars.ENVIRONMENT !== environment) throw new Error("environment marker mismatch");
if (!selected.triggers?.crons?.length) throw new Error(`${environment} must define its own maintenance cron`);
if (environment === "production") {
  if (Object.hasOwn(selected.vars, "ALLOWED_ORIGIN")) throw new Error("production ALLOWED_ORIGIN must not be committed as a public variable");
  if (!selected.secrets?.required?.includes("ALLOWED_ORIGIN")) throw new Error("production must require the protected ALLOWED_ORIGIN secret");
  const webConfig = JSON.parse((await readFile(new URL("../../cloud-web/wrangler.jsonc", import.meta.url), "utf8")).replace(/^\s*\/\/.*$/gm, ""));
  const webProduction = webConfig.env?.production;
  if (webProduction?.routes?.length || webProduction?.route || webProduction?.domains?.length) {
    throw new Error("production administrator hostname must not be committed in public Worker routes");
  }
  if (webProduction?.workers_dev !== false) throw new Error("production administrator Worker must disable workers.dev");
  if (webProduction?.vars?.MANAGEMENT_GATE_ENABLED !== "true") throw new Error("production administrator Worker must enable the application gate");
  for (const secret of ["MANAGEMENT_GATE_SECRET", "MANAGEMENT_ADMIN_EMAIL"]) {
    if (Object.hasOwn(webProduction?.vars ?? {}, secret)) throw new Error(`production ${secret} must not be committed as a public variable`);
    if (!webProduction?.secrets?.required?.includes(secret)) throw new Error(`production administrator Worker must require ${secret}`);
  }
}
const other = environment === "staging" ? config.env.production : config.env.staging;
for (const name of ["API_RATE_LIMITER", "AUTH_RATE_LIMITER"]) {
  const binding = selected.ratelimits?.find((item) => item.name === name);
  if (!binding || binding.simple?.period !== 60 || !(binding.simple.limit > 0)) throw new Error(`${environment} requires ${name}`);
}
const rateNamespaces = [config, config.env.staging, config.env.production].flatMap((item) => (item.ratelimits ?? []).map((binding) => binding.namespace_id));
if (new Set(rateNamespaces).size !== rateNamespaces.length) throw new Error("rate limit namespaces must be separate across bindings and environments");
const otherIds = new Set(other.d1_databases.map((database) => database.database_id));
for (const database of selected.d1_databases) {
  if (otherIds.has(database.database_id)) throw new Error("staging and production share a D1 database");
}
if (databases.DB.database_id === databases.DELETION_LEDGER.database_id) throw new Error(`${environment} main and deletion-ledger databases must be separate`);
console.log(`${environment} configuration guard passed`);
