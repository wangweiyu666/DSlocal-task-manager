import { cloudApi, CloudApiError } from "./api";
import { connectedDb, entityKey } from "./db";
import type { CloudEntity, ConflictRecord, OutboxRecord, SyncCommand } from "./types";
import { uuidV7 } from "../protocol/cloud";

export { uuidV7 } from "../protocol/cloud";

function cloudEntity(spaceId: string, value: Record<string, unknown>): CloudEntity {
  const entityType = String(value.entityType);
  const entityId = String(value.entityId);
  return {
    key: entityKey(spaceId, entityType, entityId),
    spaceId,
    entityType,
    entityId,
    entityVersion: Number(value.entityVersion ?? 0),
    payloadVersion: Number(value.payloadVersion ?? 1),
    payload: value.payload as Record<string, unknown>,
    updatedAt: String(value.updatedAt ?? new Date().toISOString()),
  };
}

async function applyRemote(spaceId: string, entity: CloudEntity): Promise<void> {
  const local = await connectedDb.entities.get(entity.key);
  if (!local?.pendingCommandId) await connectedDb.entities.put(entity);
}

async function fetchServerEntity(spaceId: string, entityId: string): Promise<CloudEntity | null> {
  let cursor: string | undefined;
  do {
    const page = await cloudApi.snapshot(spaceId, cursor);
    const match = (page.entities as Array<Record<string, unknown>>).find((item) => String(item.entityId) === entityId);
    if (match) return cloudEntity(spaceId, match);
    cursor = page.hasMore ? String(page.nextCursor) : undefined;
  } while (cursor);
  return null;
}

export async function replaceFromSnapshot(spaceId: string): Promise<void> {
  let cursor: string | undefined;
  let changesCursor = "";
  const entities: CloudEntity[] = [];
  do {
    const page = await cloudApi.snapshot(spaceId, cursor);
    for (const raw of page.entities as Array<Record<string, unknown>>) entities.push(cloudEntity(spaceId, raw));
    changesCursor = String(page.changesCursor);
    cursor = page.hasMore ? String(page.nextCursor) : undefined;
  } while (cursor);
  await connectedDb.transaction("rw", connectedDb.entities, connectedDb.syncMeta, async () => {
    const pending = await connectedDb.entities.where("spaceId").equals(spaceId).filter((item) => Boolean(item.pendingCommandId)).toArray();
    await connectedDb.entities.where("spaceId").equals(spaceId).delete();
    await connectedDb.entities.bulkPut([...entities, ...pending]);
    await connectedDb.syncMeta.put({ spaceId, changesCursor, lastSyncedAt: new Date().toISOString() });
  });
}

export async function pullChanges(spaceId: string): Promise<void> {
  const meta = await connectedDb.syncMeta.get(spaceId);
  if (!meta?.changesCursor) return replaceFromSnapshot(spaceId);
  let cursor = meta.changesCursor;
  try {
    while (true) {
      const page = await cloudApi.changes(spaceId, cursor);
      for (const raw of page.changes as Array<Record<string, unknown>>) {
        if (Number(raw.payloadVersion ?? 1) !== 1) throw new CloudApiError(409, "UNSUPPORTED_PAYLOAD_VERSION", "同步数据版本过新，请升级应用");
        const key = entityKey(spaceId, String(raw.entityType), String(raw.entityId));
        if (raw.operation === "DELETE") await connectedDb.entities.delete(key);
        else await applyRemote(spaceId, cloudEntity(spaceId, { ...raw, payloadVersion: 1, updatedAt: raw.createdAt }));
      }
      cursor = String(page.nextCursor);
      if (!page.hasMore) break;
    }
    await connectedDb.syncMeta.put({ spaceId, changesCursor: cursor, lastSyncedAt: new Date().toISOString() });
  } catch (error) {
    if (error instanceof CloudApiError && error.code === "SYNC_CURSOR_EXPIRED") return replaceFromSnapshot(spaceId);
    throw error;
  }
}

export async function queueCommand(spaceId: string, command: SyncCommand, entityType: string, optimisticPayload: Record<string, unknown>): Promise<void> {
  const key = entityKey(spaceId, entityType, command.entityId);
  const current = await connectedDb.entities.get(key);
  const record: OutboxRecord = { commandId: command.commandId, spaceId, command, original: current?.payload ?? null, queuedAt: new Date().toISOString(), attempts: 0 };
  await connectedDb.transaction("rw", connectedDb.entities, connectedDb.outbox, async () => {
    await connectedDb.outbox.put(record);
    await connectedDb.entities.put({
      key,
      spaceId,
      entityType,
      entityId: command.entityId,
      entityVersion: command.baseVersion + (command.type.includes("PUBLISH") || command.type.includes("UPDATE") || command.type.includes("RESTORE") ? 1 : 0),
      payloadVersion: 1,
      payload: optimisticPayload,
      updatedAt: command.createdAt,
      pendingCommandId: command.commandId,
    });
  });
}

export async function flushOutbox(spaceId: string): Promise<{ accepted: number; conflicts: number; pending: number }> {
  const rows = await connectedDb.outbox.where("spaceId").equals(spaceId).sortBy("queuedAt");
  let accepted = 0;
  let conflicts = 0;
  for (const row of rows) {
    let result: Record<string, unknown>;
    try {
      result = (await cloudApi.commands(spaceId, [row.command])).results[0];
    } catch (error) {
      if (error instanceof CloudApiError && error.retryable) break;
      throw error;
    }
    const status = String(result.status);
    if (status === "accepted" || status === "duplicate") {
      await connectedDb.transaction("rw", connectedDb.outbox, connectedDb.entities, async () => {
        await connectedDb.outbox.delete(row.commandId);
        const candidates = await connectedDb.entities.where("pendingCommandId").equals(row.commandId).toArray();
        await Promise.all(candidates.map((item) => connectedDb.entities.put({ ...item, entityVersion: Number(result.entityVersion ?? item.entityVersion), pendingCommandId: undefined })));
      });
      accepted += 1;
    } else if (status === "conflict") {
      const local = (await connectedDb.entities.where("pendingCommandId").equals(row.commandId).first()) ?? null;
      const server = await fetchServerEntity(spaceId, row.command.entityId);
      const conflict: ConflictRecord = {
        commandId: row.commandId,
        spaceId,
        entityId: row.command.entityId,
        commandType: row.command.type,
        original: row.original,
        local: local?.payload ?? row.command.payload,
        server: server?.payload ?? null,
        serverVersion: server?.entityVersion ?? row.command.baseVersion,
        createdAt: new Date().toISOString(),
      };
      await connectedDb.transaction("rw", connectedDb.outbox, connectedDb.conflicts, async () => {
        await connectedDb.outbox.delete(row.commandId);
        await connectedDb.conflicts.put(conflict);
      });
      conflicts += 1;
    } else if (status === "retryable") {
      await connectedDb.outbox.update(row.commandId, { attempts: row.attempts + 1 });
      break;
    } else {
      await connectedDb.outbox.delete(row.commandId);
      const local = await connectedDb.entities.where("pendingCommandId").equals(row.commandId).first();
      if (local) {
        if (row.original) await connectedDb.entities.put({ ...local, payload: row.original, pendingCommandId: undefined });
        else await connectedDb.entities.delete(local.key);
      }
    }
  }
  return { accepted, conflicts, pending: await connectedDb.outbox.where("spaceId").equals(spaceId).count() };
}

export async function synchronize(spaceId: string): Promise<void> {
  await flushOutbox(spaceId);
  await pullChanges(spaceId);
}
