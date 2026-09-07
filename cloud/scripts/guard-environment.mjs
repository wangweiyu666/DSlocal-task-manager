import { readFile } from "node:fs/promises";

const environment = process.argv[2];
if (environment !== "production") throw new Error("expected production; staging is retired");
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
}
const webConfig = JSON.parse((await readFile(new URL("../../cloud-web/wrangler.jsonc", import.meta.url), "utf8")).replace(/^\s*\/\/.*$/gm, ""));
const web = webConfig.env?.[environment];
const host = "prod.rochelimit.me";
if (web?.workers_dev !== false || webConfig.preview_urls !== false) throw new Error(`${environment} web must disable workers.dev and preview URLs`);
if (web?.vars?.ACCESS_REQUIRED !== "true") throw new Error(`${environment} web must require Cloudflare Access`);
if (web?.vars?.MANAGEMENT_HOST !== host || web?.routes?.length !== 1 || web.routes[0].pattern !== host || web.routes[0].custom_domain !== true) throw new Error(`${environment} web hostname mismatch`);
if (web.services?.find((item) => item.binding === "API")?.service !== `dstationery-api-${environment}`) throw new Error(`${environment} web API binding mismatch`);
if (webConfig.assets?.run_worker_first !== true) throw new Error("all assets must pass through Access JWT validation");
for (const secret of ["ACCESS_TEAM_DOMAIN", "ACCESS_AUD", "MANAGEMENT_ADMIN_EMAIL"]) {
  if (Object.hasOwn(web?.vars ?? {}, secret)) throw new Error(`${environment} ${secret} must not be committed as a public variable`);
  if (!web?.secrets?.required?.includes(secret)) throw new Error(`${environment} web must require ${secret}`);
}
for (const secret of ["ACCESS_TEAM_DOMAIN", "ACCESS_AUD"]) {
  if (Object.hasOwn(selected.vars, secret) || !selected.secrets?.required?.includes(secret)) throw new Error(`${environment} API must require protected ${secret}`);
}
for (const name of ["API_RATE_LIMITER", "AUTH_RATE_LIMITER"]) {
  const binding = selected.ratelimits?.find((item) => item.name === name);
  if (!binding || binding.simple?.period !== 60 || !(binding.simple.limit > 0)) throw new Error(`${environment} requires ${name}`);
}
const rateNamespaces = [config, config.env.production].flatMap((item) => (item.ratelimits ?? []).map((binding) => binding.namespace_id));
if (new Set(rateNamespaces).size !== rateNamespaces.length) throw new Error("rate limit namespaces must be separate across bindings and environments");
const otherIds = new Set(config.d1_databases.map((database) => database.database_id));
for (const database of selected.d1_databases) {
  if (otherIds.has(database.database_id)) throw new Error("local and production share a D1 database");
}
if (databases.DB.database_id === databases.DELETION_LEDGER.database_id) throw new Error(`${environment} main and deletion-ledger databases must be separate`);
console.log(`${environment} configuration guard passed`);
