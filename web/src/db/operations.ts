import { createDraft } from "../model/defaults";
import type { DraftRecord } from "../model/types";
import { db, getSettings, updateSettings } from "./database";

export async function getOrCreateActiveDraft(): Promise<DraftRecord> {
  const settings = await getSettings();
  if (settings.lastDraftId) {
    const existing = await db.drafts.get(settings.lastDraftId);
    if (existing) return existing;
  }
  const newest = await db.drafts.orderBy("updatedAt").last();
  if (newest) {
    await updateSettings({ lastDraftId: newest.id });
    return newest;
  }
  const draft = createDraft();
  await db.drafts.put(draft);
  await updateSettings({ lastDraftId: draft.id });
  return draft;
}

export async function updateDraft(id: string, patch: Partial<Omit<DraftRecord, "id" | "createdAt">>): Promise<void> {
  await db.drafts.update(id, { ...patch, updatedAt: new Date().toISOString() });
}
