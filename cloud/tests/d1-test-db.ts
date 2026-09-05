import { DatabaseSync } from "node:sqlite";
import { readFileSync, readdirSync } from "node:fs";
import { hmac } from "../src/crypto";
import type { Env } from "../src/types";

// Execute the repository's migrations and SQL against SQLite. A batch is one
// transaction, matching D1's rollback boundary; failures can be injected at any write.
export async function testDatabase() {
  const sqlite = new DatabaseSync(":memory:");
  const migrations = new URL("../migrations/", import.meta.url);
  for (const file of readdirSync(migrations).sort()) sqlite.exec(readFileSync(new URL(file, migrations), "utf8"));
  const controls = { failOnce: null as RegExp | null, beforeBatch: null as (() => void) | null };
  class Statement {
    constructor(readonly query: string, readonly bindings: (string | number | null)[] = []) {}
    bind(...values: (string | number | null)[]) { return new Statement(this.query, values); }
    async first() { return sqlite.prepare(this.query).get(...this.bindings) ?? null; }
    async all() { return { results: sqlite.prepare(this.query).all(...this.bindings), success: true, meta: {} }; }
    execute() {
      if (controls.failOnce?.test(this.query)) { controls.failOnce = null; throw new Error("injected database failure"); }
      const statement = sqlite.prepare(this.query);
      if (/^SELECT\b/.test(this.query)) return { results: statement.all(...this.bindings), success: true, meta: {} };
      const result = statement.run(...this.bindings);
      return { results: [], success: true, meta: { changes: Number(result.changes) } };
    }
    async run() { return this.execute(); }
  }
  const db = {
    prepare: (query: string) => new Statement(query),
    batch: async (statements: Statement[]) => {
      const before = controls.beforeBatch; controls.beforeBatch = null; before?.();
      sqlite.exec("BEGIN");
      try { const result = statements.map((statement) => statement.execute()); sqlite.exec("COMMIT"); return result; }
      catch (error) { sqlite.exec("ROLLBACK"); throw error; }
    },
  } as unknown as D1Database;
  const env = { DB: db, AUTH_PEPPER: "synthetic-test-secret", ALLOWED_ORIGIN: "https://example.invalid", ENVIRONMENT: "local" } as Env;
  const spaceId = "00000000-0000-7000-8000-000000000001";
  const now = new Date().toISOString(), future = new Date(Date.now() + 86_400_000).toISOString();
  sqlite.prepare("INSERT INTO spaces(id,name,created_at) VALUES (?,'test',?)").run(spaceId, now);
  for (const role of ["ADMIN", "EXECUTOR"]) {
    sqlite.prepare("INSERT INTO accounts(id,email_normalized,email_delivery,created_at,verified_at,privacy_notice_version) VALUES (?,?,?,?,?,1)")
      .run(role, `${role.toLowerCase()}@example.invalid`, `${role.toLowerCase()}@example.invalid`, now, now);
    sqlite.prepare("INSERT INTO memberships(id,space_id,account_id,role,joined_at) VALUES (?,?,?,?,?)").run(role, spaceId, role, role, now);
    sqlite.prepare("INSERT INTO device_sessions(id,account_id,access_digest,refresh_digest,csrf_digest,created_at,last_seen_at,access_expires_at,idle_expires_at,absolute_expires_at) VALUES (?,?,?,?,?,?,?,?,?,?)")
      .run(role, role, await hmac(env.AUTH_PEPPER, `access:${role}`), await hmac(env.AUTH_PEPPER, `refresh:${role}`), "csrf", now, now, future, future, future);
  }
  return { env, sqlite, controls, spaceId };
}
