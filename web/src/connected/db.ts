import Dexie, { type EntityTable } from "dexie";
import type { CloudEntity, ConflictRecord, LocalSetting, OutboxRecord, SyncMeta } from "./types";

export const entityKey = (spaceId: string, entityType: string, entityId: string) => `${spaceId}:${entityType}:${entityId}`;

export class ConnectedDatabase extends Dexie {
  entities!: EntityTable<CloudEntity, "key">;
  outbox!: EntityTable<OutboxRecord, "commandId">;
  conflicts!: EntityTable<ConflictRecord, "commandId">;
  syncMeta!: EntityTable<SyncMeta, "spaceId">;
  localSettings!: EntityTable<LocalSetting, "key">;

  constructor(name = "dstationery-connected-v2") {
    super(name);
    this.version(1).stores({
      entities: "key, [spaceId+entityType], [spaceId+entityType+entityId], pendingCommandId",
      outbox: "commandId, spaceId, queuedAt",
      conflicts: "commandId, spaceId, entityId, createdAt",
      syncMeta: "spaceId",
      localSettings: "key",
    });
  }
}

export const connectedDb = new ConnectedDatabase();

export async function purgeSpace(spaceId: string): Promise<void> {
  await connectedDb.transaction("rw", connectedDb.entities, connectedDb.outbox, connectedDb.conflicts, connectedDb.syncMeta, async () => {
    await connectedDb.entities.where("spaceId").equals(spaceId).delete();
    await connectedDb.outbox.where("spaceId").equals(spaceId).delete();
    await connectedDb.conflicts.where("spaceId").equals(spaceId).delete();
    await connectedDb.syncMeta.delete(spaceId);
  });
}

export async function purgeAllConnectedData(): Promise<void> {
  await connectedDb.transaction("rw", connectedDb.entities, connectedDb.outbox, connectedDb.conflicts, connectedDb.syncMeta, async () => {
    await Promise.all([
      connectedDb.entities.clear(),
      connectedDb.outbox.clear(),
      connectedDb.conflicts.clear(),
      connectedDb.syncMeta.clear(),
    ]);
  });
}
