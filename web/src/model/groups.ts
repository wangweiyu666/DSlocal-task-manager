import type { GroupRecord } from "./types";
import { createTransportId } from "../protocol/id";

export function newGroup(order: number): GroupRecord {
  const now = new Date().toISOString();
  return { id: createTransportId(), name: "", completeMessage: "", incompleteMessage: "", order, createdAt: now, updatedAt: now };
}

export const normalizeGroupName = (value: string) => value.trim().normalize("NFC").toLocaleLowerCase("zh-CN");

export function groupIssue(value: GroupRecord, groups: GroupRecord[]): string | null {
  const name = value.name.trim().normalize("NFC");
  if (!name) return "请填写积分组名称";
  if ([...name].length > 50) return "积分组名称不能超过 50 个字符";
  if (groups.some((group) => group.id !== value.id && normalizeGroupName(group.name) === normalizeGroupName(name))) return "活动积分组不能重名";
  return null;
}

export function normalizeGroup(value: GroupRecord): GroupRecord {
  return {
    ...value,
    name: value.name.trim().normalize("NFC"),
    completeMessage: value.completeMessage.trim().normalize("NFC"),
    incompleteMessage: value.incompleteMessage.trim().normalize("NFC"),
    updatedAt: new Date().toISOString(),
  };
}
