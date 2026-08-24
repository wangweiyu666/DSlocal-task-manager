import { readFile } from "node:fs/promises";

const environment = process.argv[2];
if (!new Set(["staging", "production"]).has(environment)) throw new Error("expected staging or production");
const config = JSON.parse((await readFile(new URL("../wrangler.jsonc", import.meta.url), "utf8")).replace(/^\s*\/\/.*$/gm, ""));
const selected = config.env[environment];
const id = selected.d1_databases[0].database_id;
if (/^(11111111|22222222)-/.test(id)) throw new Error(`${environment} D1 still uses a placeholder database_id`);
if (selected.vars.ENVIRONMENT !== environment) throw new Error("environment marker mismatch");
const other = environment === "staging" ? config.env.production : config.env.staging;
if (id === other.d1_databases[0].database_id) throw new Error("staging and production share a D1 database");
console.log(`${environment} configuration guard passed`);
