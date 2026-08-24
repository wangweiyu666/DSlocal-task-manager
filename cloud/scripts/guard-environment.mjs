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
const other = environment === "staging" ? config.env.production : config.env.staging;
const otherIds = new Set(other.d1_databases.map((database) => database.database_id));
for (const database of selected.d1_databases) {
  if (otherIds.has(database.database_id)) throw new Error("staging and production share a D1 database");
}
if (databases.DB.database_id === databases.DELETION_LEDGER.database_id) throw new Error(`${environment} main and deletion-ledger databases must be separate`);
console.log(`${environment} configuration guard passed`);
