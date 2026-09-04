import { useLiveQuery } from "dexie-react-hooks";
import { ArrowDown, ArrowUp, Bell, Cloud, CloudOff, Copy, FolderKanban, ListTodo, LogOut, Plus, RefreshCw, Repeat2, Send, Settings, ShieldAlert, Users } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { GroupEditor } from "../components/GroupEditor";
import { Modal } from "../components/Modal";
import { TaskEditor, taskIssues, type EditableTask } from "../components/TaskEditor";
import { TaskLibraryFilters } from "../components/TaskLibraryFilters";
import { emptyTaskFields } from "../model/defaults";
import { groupIssue, newGroup, normalizeGroup } from "../model/groups";
import type { GroupRecord } from "../model/types";
import { createTransportId } from "../protocol/id";
import { cloudApi } from "./api";
import { connectedDb, purgeAllConnectedData, purgeSpace } from "./db";
import { buildCloudTaskContent, editableFromCloudTask, unpackCloudTask } from "./library";
import { presentExecutionResult } from "./results";
import { pullChanges, queueCommand, synchronize, uuidV7 } from "./sync";
import type { Bootstrap, CloudEntity, ConflictRecord, MembershipBootstrap, SpaceMember, SyncCommand, SyncMeta } from "./types";

type AuthState = "restoring" | "email" | "code" | "privacy" | "deletion-pending" | "create-space" | "ready" | "wrong-role";
type Tab = "tasks" | "groups" | "results" | "settings";
type SettingsSection = "general" | "notifications" | "audit";
type AssignmentMode = "ALL" | "SELECTED";
type SensitiveAction = "export" | "delete-scheduled" | "delete-immediate";
type TaskEditorState = { taskId: string; baseVersion: number; value: EditableTask; assignmentMode: AssignmentMode; executorMembershipIds: string[]; conflictCommandId?: string };

function newEditable(): EditableTask { return { ...emptyTaskFields(), groupId: null }; }

function Login({ state, email, setEmail, code, setCode, busy, error, onEmail, onCode }: {
  state: AuthState; email: string; setEmail: (value: string) => void; code: string; setCode: (value: string) => void; busy: boolean; error: string; onEmail: () => void; onCode: () => void;
}) {
  return <main className="connected-auth"><section className="connected-auth-card">
    <div className="connected-mark"><Cloud size={28} /></div><p className="eyebrow">DStationery 联网版</p><h1>管理员工作台</h1>
    <p className="supporting">任务在离线时也可编辑；恢复联网后按顺序同步。可携带偏好只保存在此浏览器。</p>
    {state === "restoring" ? <div className="connected-restoring"><RefreshCw className="spin" />正在恢复安全会话…</div> : <>
      <label className="field"><span>管理员邮箱</span><input type="email" value={email} disabled={state === "code" || busy} onChange={(event) => setEmail(event.target.value)} autoComplete="email" /></label>
      {state === "code" && <label className="field"><span>6 位验证码</span><input inputMode="numeric" maxLength={6} value={code} disabled={busy} onChange={(event) => setCode(event.target.value.replace(/\D/gu, ""))} autoComplete="one-time-code" /></label>}
      {error && <p className="connected-error">{error}</p>}
      <button className="button primary" disabled={busy || (state === "code" ? code.length !== 6 : !email.includes("@"))} onClick={state === "code" ? onCode : onEmail}>{busy ? "请稍候…" : state === "code" ? "验证并进入" : "发送验证码"}</button>
    </>}
  </section></main>;
}

export default function ConnectedApp() {
  const [authState, setAuthState] = useState<AuthState>("restoring");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [challengeId, setChallengeId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [bootstrap, setBootstrap] = useState<Bootstrap | null>(null);
  const [membership, setMembership] = useState<MembershipBootstrap | null>(null);
  const [tab, setTab] = useState<Tab>("tasks");
  const [settingsSection, setSettingsSection] = useState<SettingsSection>("general");
  const [online, setOnline] = useState(navigator.onLine);
  const [syncing, setSyncing] = useState(false);
  const [editor, setEditor] = useState<TaskEditorState | null>(null);
  const [groupEditor, setGroupEditor] = useState<{ baseVersion: number; value: GroupRecord; conflictCommandId?: string } | null>(null);
  const [taskSearch, setTaskSearch] = useState("");
  const [taskKindFilter, setTaskKindFilter] = useState("all");
  const [taskGroupFilter, setTaskGroupFilter] = useState("all");
  const taskSearchRef = useRef<HTMLInputElement>(null);
  const [inviteEmail, setInviteEmail] = useState("");
  const [newSpaceName, setNewSpaceName] = useState("DStationery");
  const [spaceTimeZone, setSpaceTimeZone] = useState("Asia/Hong_Kong");
  const [notice, setNotice] = useState("");
  const [remoteNotifications, setRemoteNotifications] = useState<Array<Record<string, unknown>>>([]);
  const [auditEvents, setAuditEvents] = useState<Array<Record<string, unknown>>>([]);
  const [spaceMembers, setSpaceMembers] = useState<SpaceMember[]>([]);
  const [privacyVersion, setPrivacyVersion] = useState(1);
  const [deletionDueAt, setDeletionDueAt] = useState<string | null>(null);
  const [sensitiveAction, setSensitiveAction] = useState<SensitiveAction | null>(null);
  const [sensitiveEmail, setSensitiveEmail] = useState("");
  const [sensitiveCode, setSensitiveCode] = useState("");
  const [sensitiveChallengeId, setSensitiveChallengeId] = useState("");

  const spaceId = membership?.space.id ?? "";
  const entities = useLiveQuery<CloudEntity[], CloudEntity[]>(async () => spaceId ? connectedDb.entities.where("spaceId").equals(spaceId).toArray() : [], [spaceId], []);
  const outboxCount = useLiveQuery<number, number>(async () => spaceId ? connectedDb.outbox.where("spaceId").equals(spaceId).count() : 0, [spaceId], 0);
  const conflicts = useLiveQuery<ConflictRecord[], ConflictRecord[]>(async () => spaceId ? connectedDb.conflicts.where("spaceId").equals(spaceId).toArray() : [], [spaceId], []);
  const meta = useLiveQuery<SyncMeta | undefined>(async () => spaceId ? connectedDb.syncMeta.get(spaceId) : undefined, [spaceId]);
  const tasks = useMemo(() => entities.filter((item) => item.entityType === "task").sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)), [entities]);
  const results = useMemo(() => entities.filter((item) => item.entityType === "execution_event").sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)), [entities]);
  const selections = useMemo(() => entities.filter((item) => item.entityType === "result_selection").sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)), [entities]);
  const executorMembershipEntities = useMemo(() => entities.filter((item) => item.entityType === "membership" && item.payload.role === "EXECUTOR" && item.payload.status === "ACTIVE"), [entities]);
  const executorMembers = useMemo<SpaceMember[]>(() => {
    const remote = spaceMembers.filter((item) => item.role === "EXECUTOR" && item.status === "ACTIVE");
    return remote.length ? remote : executorMembershipEntities.map((item) => ({ id: item.entityId, role: "EXECUTOR", status: "ACTIVE", joinedAt: String(item.payload.joinedAt ?? item.updatedAt), email: null }));
  }, [executorMembershipEntities, spaceMembers]);
  const groupEntities = useMemo(() => entities.filter((item) => item.entityType === "task_group" && item.payload.status === "ACTIVE"), [entities]);
  const groups = useMemo<GroupRecord[]>(() => groupEntities.map((item) => {
    const content = item.payload.content as Record<string, unknown> | undefined;
    return { id: item.entityId, name: String(content?.name ?? "任务组"), completeMessage: String(content?.completeMessage ?? ""), incompleteMessage: String(content?.incompleteMessage ?? ""), order: Number(content?.order ?? 0), createdAt: item.updatedAt, updatedAt: item.updatedAt };
  }).sort((a, b) => a.order - b.order), [groupEntities]);
  const filteredTasks = useMemo(() => tasks.filter((item) => {
    const definition = unpackCloudTask(item.payload.content as Record<string, unknown> | undefined);
    if (!definition) return false;
    const query = taskSearch.trim().toLocaleLowerCase("zh-CN");
    const matchesSearch = !query || `${definition.task.n} ${definition.task.d ?? ""} ${item.entityId}`.toLocaleLowerCase("zh-CN").includes(query);
    const matchesKind = taskKindFilter === "all" || (taskKindFilter === "recurring" ? Boolean(definition.task.x) : !definition.task.x);
    const groupId = definition.group?.i ?? null;
    const matchesGroup = taskGroupFilter === "all" || (taskGroupFilter === "ungrouped" ? groupId === null : groupId === taskGroupFilter);
    return matchesSearch && matchesKind && matchesGroup;
  }), [taskGroupFilter, taskKindFilter, taskSearch, tasks]);

  const openTaskEditor = (item: CloudEntity, value: EditableTask, copy = false) => {
    const assignmentMode: AssignmentMode = item.payload.assignmentMode === "SELECTED" ? "SELECTED" : "ALL";
    const activeIds = new Set(executorMembers.map((member) => member.id));
    const storedIds = Array.isArray(item.payload.executorMembershipIds) ? item.payload.executorMembershipIds.filter((id): id is string => typeof id === "string" && activeIds.has(id)) : [];
    setEditor({
      taskId: copy ? createTransportId() : item.entityId,
      baseVersion: copy ? 0 : item.entityVersion,
      value: copy ? { ...value, name: `${value.name} · 副本` } : value,
      assignmentMode,
      executorMembershipIds: assignmentMode === "SELECTED" ? storedIds : executorMembers.map((member) => member.id),
    });
  };

  const selectBootstrap = useCallback(async (value: Bootstrap) => {
    setBootstrap(value);
    const admin = value.memberships.find((item) => item.role === "ADMIN") ?? null;
    if (!admin) {
      setMembership(value.memberships[0] ?? null);
      setAuthState(value.memberships.length === 0 ? "create-space" : "wrong-role");
      return;
    }
    setMembership(admin);
    setSpaceTimeZone(admin.space.timeZone);
    setAuthState("ready");
    try { await pullChanges(admin.space.id); } catch { /* cached state remains usable */ }
    try { setSpaceMembers((await cloudApi.members(admin.space.id)).members); } catch { /* member IDs remain available offline */ }
  }, []);

  const enterAccount = useCallback(async () => {
    const status = await cloudApi.account();
    if (status.account.status === "DELETION_PENDING") {
      await purgeAllConnectedData();
      setDeletionDueAt(status.account.deletionDueAt);
      setAuthState("deletion-pending");
      return;
    }
    if (status.account.privacyNoticeVersion < status.account.requiredPrivacyNoticeVersion) {
      setPrivacyVersion(status.account.requiredPrivacyNoticeVersion);
      setAuthState("privacy");
      return;
    }
    await selectBootstrap(await cloudApi.bootstrap());
  }, [selectBootstrap]);

  useEffect(() => {
    void cloudApi.refresh().then(enterAccount).catch(() => setAuthState("email"));
    const onlineHandler = () => { setOnline(true); };
    const offlineHandler = () => setOnline(false);
    window.addEventListener("online", onlineHandler); window.addEventListener("offline", offlineHandler);
    return () => { window.removeEventListener("online", onlineHandler); window.removeEventListener("offline", offlineHandler); };
  }, [enterAccount]);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (tab === "tasks" && (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault(); taskSearchRef.current?.focus();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [tab]);

  const sync = useCallback(async () => {
    if (!spaceId || !navigator.onLine || syncing) return;
    setSyncing(true); setError("");
    try {
      await synchronize(spaceId);
      try { setSpaceMembers((await cloudApi.members(spaceId)).members); } catch { /* sync remains successful if the member list cannot refresh */ }
      setNotice("同步完成");
    }
    catch (value) { setError(value instanceof Error ? value.message : "同步失败"); }
    finally { setSyncing(false); }
  }, [spaceId, syncing]);

  useEffect(() => { if (online && authState === "ready" && outboxCount > 0) void sync(); }, [online, authState, outboxCount, sync]);

  const sendChallenge = async () => {
    setBusy(true); setError("");
    try { const value = await cloudApi.requestChallenge(email); setChallengeId(value.challengeId); setAuthState("code"); }
    catch (value) { setError(value instanceof Error ? value.message : "发送失败"); }
    finally { setBusy(false); }
  };
  const verify = async () => {
    setBusy(true); setError("");
    try { await cloudApi.verify(challengeId, email, code); await enterAccount(); }
    catch (value) { setError(value instanceof Error ? value.message : "验证失败"); }
    finally { setBusy(false); }
  };

  const acknowledgePrivacy = async () => {
    setBusy(true); setError("");
    try { await cloudApi.acknowledgePrivacy(privacyVersion); await selectBootstrap(await cloudApi.bootstrap()); }
    catch (value) { setError(value instanceof Error ? value.message : "隐私确认失败"); }
    finally { setBusy(false); }
  };

  const cancelAccountDeletion = async () => {
    setBusy(true); setError("");
    try { await cloudApi.cancelDeletion(); setDeletionDueAt(null); await selectBootstrap(await cloudApi.bootstrap()); }
    catch (value) { setError(value instanceof Error ? value.message : "账号恢复失败"); }
    finally { setBusy(false); }
  };

  const createFirstSpace = async () => {
    const name = newSpaceName.trim();
    if (!name) return;
    setBusy(true); setError("");
    try { await cloudApi.createSpace(name); await selectBootstrap(await cloudApi.bootstrap()); }
    catch (value) { setError(value instanceof Error ? value.message : "空间创建失败"); }
    finally { setBusy(false); }
  };

  const sendSensitiveCode = async () => {
    setBusy(true); setError("");
    try {
      const value = await cloudApi.requestChallenge(sensitiveEmail, "SENSITIVE_ACTION");
      setSensitiveChallengeId(value.challengeId);
    } catch (value) { setError(value instanceof Error ? value.message : "安全验证码发送失败"); }
    finally { setBusy(false); }
  };

  const finishSensitiveAction = async () => {
    if (!sensitiveAction) return;
    setBusy(true); setError("");
    try {
      await cloudApi.verifySensitive(sensitiveChallengeId, sensitiveEmail, sensitiveCode);
      if (sensitiveAction === "export") {
        const datasets: Record<string, unknown[]> = {};
        let manifest: Record<string, unknown> | null = null;
        let cursor: string | undefined;
        do {
          const page = await cloudApi.exportPage(spaceId, cursor);
          if (page.manifest) manifest = page.manifest;
          if (page.dataset) (datasets[page.dataset] ??= []).push(...page.records);
          cursor = page.nextCursor ?? undefined;
        } while (cursor);
        const blob = new Blob([JSON.stringify({ manifest, datasets }, null, 2)], { type: "application/json" });
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement("a");
        anchor.href = url; anchor.download = `DStationery-${spaceId}-${new Date().toISOString().slice(0, 10)}.dsexport.json`; anchor.click();
        URL.revokeObjectURL(url);
        setNotice("DSEXPORT v1 已导出");
      } else {
        const result = await cloudApi.requestDeletion(sensitiveAction === "delete-immediate" ? "IMMEDIATE" : "SCHEDULED");
        if (spaceId) await purgeSpace(spaceId);
        cloudApi.setSession(null); setBootstrap(null); setMembership(null);
        setDeletionDueAt(result.executeAfter ?? null);
        setAuthState(result.status === "PENDING" ? "deletion-pending" : "email");
      }
      setSensitiveAction(null); setSensitiveCode(""); setSensitiveChallengeId("");
    } catch (value) { setError(value instanceof Error ? value.message : "敏感操作失败"); }
    finally { setBusy(false); }
  };

  const saveTask = async () => {
    if (!editor || !spaceId || taskIssues(editor.value).length) return;
    if (editor.assignmentMode === "SELECTED" && editor.executorMembershipIds.length === 0) { setError("指定执行者时至少选择一名成员"); return; }
    const content = buildCloudTaskContent(editor.taskId, editor.value, groups);
    const assignment = { assignmentMode: editor.assignmentMode, executorMembershipIds: editor.assignmentMode === "SELECTED" ? editor.executorMembershipIds : [] };
    const command: SyncCommand = { commandId: uuidV7(), entityId: editor.taskId, baseVersion: editor.baseVersion, createdAt: new Date().toISOString(), type: editor.baseVersion === 0 ? "TASK_PUBLISH" : "TASK_UPDATE", payload: { content, ...assignment } };
    await queueCommand(spaceId, command, "task", { id: editor.taskId, version: editor.baseVersion + 1, status: "ACTIVE", content, ...assignment, assignment: null });
    if (editor.conflictCommandId) await connectedDb.conflicts.delete(editor.conflictCommandId);
    setEditor(null); setNotice(navigator.onLine ? "已加入同步队列" : "已离线保存，联网后同步");
    if (navigator.onLine) void sync();
  };

  const useServerConflict = async (commandId: string) => {
    const conflict = await connectedDb.conflicts.get(commandId);
    if (!conflict) return;
    const local = await connectedDb.entities.where("pendingCommandId").equals(commandId).first();
    if (local) {
      if (conflict.server) await connectedDb.entities.put({ ...local, entityVersion: conflict.serverVersion, payload: conflict.server, pendingCommandId: undefined });
      else await connectedDb.entities.delete(local.key);
    }
    await connectedDb.conflicts.delete(commandId);
  };

  const editConflict = async (commandId: string) => {
    const conflict = await connectedDb.conflicts.get(commandId);
    if (!conflict) return;
    if (conflict.commandType === "GROUP_UPSERT") {
      const content = conflict.local.content as Record<string, unknown> | undefined;
      if (content) setGroupEditor({
        baseVersion: conflict.serverVersion,
        conflictCommandId: commandId,
        value: {
          id: conflict.entityId,
          name: String(content.name ?? ""),
          completeMessage: String(content.completeMessage ?? ""),
          incompleteMessage: String(content.incompleteMessage ?? ""),
          order: Number(content.order ?? groups.length),
          createdAt: conflict.createdAt,
          updatedAt: conflict.createdAt,
        },
      });
      return;
    }
    const value = editableFromCloudTask(conflict.local.content as Record<string, unknown> | undefined);
    if (value) setEditor({
      taskId: conflict.entityId,
      baseVersion: conflict.serverVersion,
      value,
      assignmentMode: conflict.local.assignmentMode === "SELECTED" ? "SELECTED" : "ALL",
      executorMembershipIds: Array.isArray(conflict.local.executorMembershipIds) ? conflict.local.executorMembershipIds.filter((id): id is string => typeof id === "string") : [],
      conflictCommandId: commandId,
    });
  };

  const queueGroupUpdate = async (value: GroupRecord, baseVersion: number, conflictCommandId?: string) => {
    const normalized = normalizeGroup(value);
    const content = { id: normalized.id, name: normalized.name, completeMessage: normalized.completeMessage, incompleteMessage: normalized.incompleteMessage, order: normalized.order };
    const command: SyncCommand = { commandId: uuidV7(), entityId: normalized.id, baseVersion, createdAt: new Date().toISOString(), type: "GROUP_UPSERT", payload: { content } };
    await queueCommand(spaceId, command, "task_group", { id: normalized.id, version: baseVersion + 1, status: "ACTIVE", content });
    if (conflictCommandId) await connectedDb.conflicts.delete(conflictCommandId);
  };

  const saveGroup = async () => {
    if (!groupEditor) return;
    const issue = groupIssue(groupEditor.value, groups);
    if (issue) { setError(issue); return; }
    await queueGroupUpdate(groupEditor.value, groupEditor.baseVersion, groupEditor.conflictCommandId);
    setGroupEditor(null); setNotice(navigator.onLine ? "积分组已加入同步队列" : "积分组已离线保存");
    if (navigator.onLine) void sync();
  };

  const moveGroup = async (group: GroupRecord, direction: -1 | 1) => {
    const index = groups.findIndex((item) => item.id === group.id);
    const targetIndex = index + direction;
    if (index < 0 || targetIndex < 0 || targetIndex >= groups.length) return;
    const target = groups[targetIndex];
    const sourceEntity = groupEntities.find((item) => item.entityId === group.id);
    const targetEntity = groupEntities.find((item) => item.entityId === target.id);
    if (!sourceEntity || !targetEntity) return;
    if (sourceEntity.pendingCommandId || targetEntity.pendingCommandId) { setError("请先同步当前积分组更改，再继续排序"); return; }
    await queueGroupUpdate({ ...group, order: target.order }, sourceEntity.entityVersion);
    await queueGroupUpdate({ ...target, order: group.order }, targetEntity.entityVersion);
    setNotice("积分组顺序已加入同步队列"); if (navigator.onLine) void sync();
  };

  const archiveGroup = async (group: GroupRecord) => {
    const assigned = tasks.filter((item) => unpackCloudTask(item.payload.content as Record<string, unknown> | undefined)?.group?.i === group.id && item.payload.status === "ACTIVE").length;
    if (assigned > 0) { setError(`该积分组仍有 ${assigned} 个活动任务，请先将它们移到其他组或未分组`); return; }
    if (!confirm(`归档积分组“${group.name}”？历史任务修订中的组快照会保留。`)) return;
    const entity = groupEntities.find((item) => item.entityId === group.id);
    if (!entity || entity.pendingCommandId) { setError("请先同步当前积分组更改"); return; }
    const command: SyncCommand = { commandId: uuidV7(), entityId: group.id, baseVersion: entity.entityVersion, createdAt: new Date().toISOString(), type: "GROUP_ARCHIVE", payload: {} };
    await queueCommand(spaceId, command, "task_group", { id: group.id, version: entity.entityVersion, status: "ARCHIVED" });
    setNotice("积分组归档已加入同步队列"); if (navigator.onLine) void sync();
  };

  const taskAction = async (entityId: string, version: number, action: "TASK_CANCEL" | "TASK_ARCHIVE" | "TASK_RESTORE") => {
    const current = tasks.find((item) => item.entityId === entityId);
    if (!current) return;
    const command: SyncCommand = { commandId: uuidV7(), entityId, baseVersion: version, createdAt: new Date().toISOString(), type: action, payload: action === "TASK_RESTORE" ? { content: current.payload.content, assignmentMode: current.payload.assignmentMode ?? "ALL", executorMembershipIds: current.payload.executorMembershipIds ?? [] } : {} };
    await queueCommand(spaceId, command, "task", { ...current.payload, version: action === "TASK_RESTORE" ? version + 1 : version, status: action === "TASK_CANCEL" ? "CANCELLED" : action === "TASK_ARCHIVE" ? "ARCHIVED" : "ACTIVE" });
    if (navigator.onLine) void sync();
  };

  const selectResult = async (item: CloudEntity) => {
    if (!online) { setError("确认正式结果必须联网进行"); return; }
    const commandId = uuidV7();
    try {
      const response = await cloudApi.commands(spaceId, [{
        commandId,
        entityId: commandId,
        baseVersion: 0,
        createdAt: new Date().toISOString(),
        type: "RESULT_SELECT",
        payload: { assignmentId: item.payload.assignmentId, occurrenceKey: item.payload.occurrenceKey, executionEventId: item.entityId, reason: "管理员确认此结果" },
      }]);
      const result = response.results[0];
      if (!result || (result.status !== "accepted" && result.status !== "duplicate")) throw new Error(`结果确认失败：${String(result?.code ?? "UNKNOWN")}`);
      await pullChanges(spaceId); setNotice("正式结果已更新");
    } catch (value) { setError(value instanceof Error ? value.message : "结果确认失败"); }
  };

  const updateTimeZone = async () => {
    if (!online || !membership) { setError("空间时区必须联网修改"); return; }
    const commandId = uuidV7();
    try {
      const response = await cloudApi.commands(spaceId, [{ commandId, entityId: spaceId, baseVersion: membership.space.timeZoneVersion, createdAt: new Date().toISOString(), type: "SPACE_TIMEZONE_UPDATE", payload: { timeZone: spaceTimeZone.trim(), effectiveAt: new Date().toISOString() } }]);
      const result = response.results[0];
      if (!result || result.status !== "accepted") throw new Error(`时区更新失败：${String(result?.code ?? "UNKNOWN")}`);
      await selectBootstrap(await cloudApi.bootstrap()); setNotice("空间时区已更新");
    } catch (value) { setError(value instanceof Error ? value.message : "时区更新失败"); }
  };

  const removeExecutor = async (memberId: string) => {
    if (!online) { setError("移除成员必须联网进行"); return; }
    if (!confirm("移除后将立即撤销执行者会话；其设备下次联网时会清除空间缓存和未同步命令。确认继续？")) return;
    try { await cloudApi.removeMember(spaceId, memberId); await pullChanges(spaceId); setSpaceMembers((await cloudApi.members(spaceId)).members); setNotice("执行者已移除"); }
    catch (value) { setError(value instanceof Error ? value.message : "成员移除失败"); }
  };

  const invite = async () => {
    if (!online) { setError("邀请成员必须联网进行"); return; }
    setBusy(true); setError("");
    try { await cloudApi.invite(spaceId, inviteEmail); setInviteEmail(""); setNotice("邀请已发送；对方登录后可领取"); }
    catch (value) { setError(value instanceof Error ? value.message : "邀请失败"); }
    finally { setBusy(false); }
  };

  const loadNotifications = async () => {
    setSettingsSection("notifications");
    if (!online) return;
    try { setRemoteNotifications((await cloudApi.notifications(spaceId)).notifications); } catch (value) { setError(value instanceof Error ? value.message : "通知加载失败"); }
  };

  const loadAudit = async () => {
    setSettingsSection("audit");
    if (!online) return;
    try { setAuditEvents((await cloudApi.audit(spaceId)).events); } catch (value) { setError(value instanceof Error ? value.message : "审计记录加载失败"); }
  };

  const markNotificationsRead = async () => {
    const ids = remoteNotifications.flatMap((item) => Array.isArray(item.notificationIds) ? item.notificationIds.map(String) : [String(item.id)]);
    if (!online || ids.length === 0) return;
    const commandId = uuidV7();
    try {
      await cloudApi.commands(spaceId, [{ commandId, entityId: commandId, baseVersion: 0, createdAt: new Date().toISOString(), type: "NOTIFICATION_READ", payload: { notificationIds: ids.slice(0, 100) } }]);
      await loadNotifications();
    } catch (value) { setError(value instanceof Error ? value.message : "通知状态更新失败"); }
  };

  const logout = async () => {
    if (!confirm("退出会清除此浏览器中的加密会话、空间缓存和未同步命令。确认继续？")) return;
    try { await cloudApi.logout(); } catch { /* local purge is still explicit user intent */ }
    if (spaceId) await purgeSpace(spaceId);
    cloudApi.setSession(null); setBootstrap(null); setMembership(null); setAuthState("email");
  };

  const settingsNavigation = <nav className="settings-tabs" aria-label="设置分类">
    <button className={`chip ${settingsSection === "general" ? "selected" : ""}`} onClick={() => setSettingsSection("general")}>常规</button>
    <button className={`chip ${settingsSection === "notifications" ? "selected" : ""}`} onClick={() => void loadNotifications()}><Bell size={16} />通知</button>
    <button className={`chip ${settingsSection === "audit" ? "selected" : ""}`} onClick={() => void loadAudit()}><ShieldAlert size={16} />审计</button>
  </nav>;

  if (authState === "privacy") return <main className="connected-auth"><section className="connected-auth-card"><ShieldAlert size={42} /><p className="eyebrow">首次使用</p><h1>联网版隐私说明</h1><p>任务、执行结果、成员邮箱和审计记录会保存到当前 Cloudflare 环境并按空间权限同步。服务不提供端到端加密；运营服务技术上可以读取任务内容。系统不接入产品分析、行为遥测或崩溃正文上传。</p><p>账号可先进入 30 天删除恢复期，也可经邮箱复验后立即从活动系统永久删除；供应商灾备副本会在其保留窗口届满后清除。</p>{error && <p className="connected-error">{error}</p>}<button className="button primary" disabled={busy} onClick={() => void acknowledgePrivacy()}>{busy ? "请稍候…" : "我已了解并继续"}</button></section></main>;
  if (authState === "deletion-pending") return <main className="connected-auth"><section className="connected-auth-card"><ShieldAlert size={42} /><p className="eyebrow">账号已冻结</p><h1>账号正在删除恢复期内</h1><p>{deletionDueAt ? `计划在 ${new Date(deletionDueAt).toLocaleString("zh-CN")} 永久删除。` : "账号不能读取、同步或产生新的业务数据。"} 本次登录已经重新验证邮箱，可以取消删除并恢复。</p>{error && <p className="connected-error">{error}</p>}<button className="button primary" disabled={busy || cloudApi.getSession() === null} onClick={() => void cancelAccountDeletion()}>{busy ? "请稍候…" : "取消删除并恢复"}</button><button className="button text" onClick={() => { cloudApi.setSession(null); setAuthState("email"); }}>返回登录</button></section></main>;
  if (authState === "create-space") return <main className="connected-auth"><section className="connected-auth-card"><Cloud size={42} /><p className="eyebrow">管理员初始化</p><h1>创建首个空间</h1><p>空间创建后才能邀请两个执行者账号。服务器会再次核对当前邮箱是否等于受保护的管理员白名单。</p><label className="field"><span>空间名称</span><input value={newSpaceName} maxLength={80} disabled={busy} onChange={(event) => setNewSpaceName(event.target.value)} autoFocus /></label>{error && <p className="connected-error">{error}</p>}<button className="button primary" disabled={busy || !newSpaceName.trim()} onClick={() => void createFirstSpace()}>{busy ? "正在创建…" : "创建并进入"}</button></section></main>;
  if (authState !== "ready" && authState !== "wrong-role") return <Login state={authState} email={email} setEmail={setEmail} code={code} setCode={setCode} busy={busy} error={error} onEmail={() => void sendChallenge()} onCode={() => void verify()} />;
  if (authState === "wrong-role") return <main className="connected-auth"><section className="connected-auth-card"><ShieldAlert size={42} /><p className="eyebrow">平台角色不匹配</p><h1>请在 Android 应用中执行任务</h1><p>此 Web 入口仅提供管理员功能；服务器仍以空间成员角色为权限依据。</p><button className="button tonal" onClick={() => void logout()}>退出账号</button></section></main>;

  return <div className="connected-shell" data-build-channel={__BUILD_CHANNEL__}>
    <aside className="connected-rail"><div><p className="eyebrow">联网版 · 管理员</p><h2>{membership?.space.name}</h2><span>{membership?.space.timeZone}</span></div>
      <nav><button className={tab === "tasks" ? "active" : ""} onClick={() => setTab("tasks")}><ListTodo />任务库</button><button className={tab === "groups" ? "active" : ""} onClick={() => setTab("groups")}><FolderKanban />积分组</button><button className={tab === "results" ? "active" : ""} onClick={() => setTab("results")}><Send />结果</button><button className={tab === "settings" ? "active" : ""} onClick={() => { setTab("settings"); setSettingsSection("general"); }}><Settings />设置</button></nav>
      <button className="button text" onClick={() => void logout()}><LogOut size={17} />退出并清除本地数据</button>
    </aside>
    <main className="connected-main"><header className="connected-topbar"><div className={`sync-state ${online ? "online" : "offline"}`}>{online ? <Cloud size={17} /> : <CloudOff size={17} />}{online ? syncing ? "同步中" : "已联网" : "离线工作"}{outboxCount > 0 && <strong>{outboxCount} 待同步</strong>}</div><button className="button tonal" disabled={!online || syncing} onClick={() => void sync()}><RefreshCw className={syncing ? "spin" : ""} size={17} />立即同步</button></header>
      {error && <div className="connected-banner error">{error}<button onClick={() => setError("")}>×</button></div>}{notice && <div className="connected-banner">{notice}<button onClick={() => setNotice("")}>×</button></div>}
      {conflicts.length > 0 && <section className="connected-conflict-list"><div className="connected-conflicts"><ShieldAlert /><div><strong>{conflicts.length} 个冲突需要处理</strong><span>系统未覆盖服务器版本。原始、本地和服务器三份数据均已保留。</span></div></div>{conflicts.map((conflict) => <article className="data-card" key={conflict.commandId}><div className="data-card-main"><h2>{conflict.entityId}</h2><p>本地命令基于旧版本，服务器当前为 v{conflict.serverVersion}。</p><details><summary>查看三方数据</summary><pre className="json-preview">{JSON.stringify({ original: conflict.original, local: conflict.local, server: conflict.server }, null, 2)}</pre></details></div><div className="card-actions"><button className="button text" onClick={() => void useServerConflict(conflict.commandId)}>采用服务器</button><button className="button tonal" onClick={() => void editConflict(conflict.commandId)}>以本地内容重新编辑</button></div></article>)}</section>}

      {tab === "tasks" && <section className="connected-page"><div className="page-header"><div><p className="eyebrow">临时任务与重复任务分别管理</p><h1>任务库</h1><p>沿用离线版编辑器、筛选和积分组快照；每次保存都会形成不可变云端修订。</p></div><button className="button primary" onClick={() => setEditor({ taskId: createTransportId(), baseVersion: 0, value: newEditable(), assignmentMode: "ALL", executorMembershipIds: executorMembers.map((member) => member.id) })}><Plus />新建任务</button></div>
        <TaskLibraryFilters groups={groups} search={taskSearch} onSearchChange={setTaskSearch} kindFilter={taskKindFilter} onKindFilterChange={setTaskKindFilter} groupFilter={taskGroupFilter} onGroupFilterChange={setTaskGroupFilter} searchInputRef={taskSearchRef} />
        <div className="connected-grid">{filteredTasks.length === 0 ? <div className="empty-state"><ListTodo size={38} /><h2>{tasks.length ? "没有匹配的任务" : "任务库还是空的"}</h2><p>新建任务后会先保存在本地，再安全同步到空间。</p></div> : filteredTasks.map((item) => {
          const definition = unpackCloudTask(item.payload.content as Record<string, unknown> | undefined);
          const value = editableFromCloudTask(item.payload.content as Record<string, unknown> | undefined);
          if (!definition || !value) return null;
          const task = definition.task; const status = String(item.payload.status ?? "ACTIVE"); const recurring = Boolean(task.x);
          const assignedCount = Array.isArray(item.payload.executorMembershipIds) ? item.payload.executorMembershipIds.length : executorMembers.length;
          return <article className="data-card" key={item.key}><div className="data-card-main" onClick={() => status === "ACTIVE" && !item.pendingCommandId && openTaskEditor(item, value)}><div className="card-title-row"><h2>{task.n}</h2><div className="status-pills"><span className={`status-pill ${recurring ? "recurring" : "temporary"}`}>{recurring ? <><Repeat2 size={12} />重复任务</> : "临时任务"}</span><span className={`status-pill ${task.r === 1 ? "required" : "optional"}`}>{task.r === 1 ? "必做" : "选做"}</span>{item.pendingCommandId && <span className="status-pill temporary">待同步</span>}<span className="status-pill temporary">{status}</span></div></div><p>{task.d || "无描述"}</p><div className="meta-row"><span>{definition.group?.n ?? "未分组"}</span>{recurring && <span>{task.x?.f === 1 ? "每天" : "每周"}</span>}<span>{task.p ?? 0} 分</span><span>{item.payload.assignmentMode === "SELECTED" ? `${assignedCount} 名指定执行者` : "全部执行者"}</span><span>v{item.entityVersion}</span><span className="mono">{item.entityId}</span></div></div><div className="card-actions"><button className="button text" disabled={status !== "ACTIVE" || Boolean(item.pendingCommandId)} onClick={() => openTaskEditor(item, value)}>编辑</button><button className="button text" onClick={() => openTaskEditor(item, value, true)}><Copy size={16} />复制</button><button className="button text danger" disabled={status !== "ACTIVE" || Boolean(item.pendingCommandId)} onClick={() => void taskAction(item.entityId, item.entityVersion, "TASK_CANCEL")}>取消</button>{status === "ARCHIVED" ? <button className="button tonal" disabled={Boolean(item.pendingCommandId)} onClick={() => void taskAction(item.entityId, item.entityVersion, "TASK_RESTORE")}>恢复为新修订</button> : <button className="button text" disabled={Boolean(item.pendingCommandId)} onClick={() => void taskAction(item.entityId, item.entityVersion, "TASK_ARCHIVE")}>归档</button>}</div></article>;
        })}</div>
      </section>}

      {tab === "groups" && <section className="connected-page"><div className="page-header"><div><p className="eyebrow">跨任务稳定 groupId</p><h1>积分组</h1><p>沿用离线版名称、结果文案和排序；保存后通过云端 outbox 同步。</p></div><button className="button primary" onClick={() => setGroupEditor({ baseVersion: 0, value: newGroup(groups.length) })}><Plus />新建积分组</button></div>
        {groups.length === 0 ? <div className="empty-state"><FolderKanban size={44} /><h2>还没有积分组</h2><p>任务可以保持未分组，也可以使用积分组计算分组结果。</p></div> : <div className="card-list">{groups.map((group, index) => {
          const entity = groupEntities.find((item) => item.entityId === group.id); const assigned = tasks.filter((item) => unpackCloudTask(item.payload.content as Record<string, unknown> | undefined)?.group?.i === group.id && item.payload.status === "ACTIVE").length;
          return <article className="data-card group-card" key={group.id}><div className="group-order"><button className="icon-button" onClick={() => void moveGroup(group, -1)} disabled={index === 0 || Boolean(entity?.pendingCommandId)} aria-label="上移"><ArrowUp size={18} /></button><button className="icon-button" onClick={() => void moveGroup(group, 1)} disabled={index === groups.length - 1 || Boolean(entity?.pendingCommandId)} aria-label="下移"><ArrowDown size={18} /></button></div><div className="data-card-main" onClick={() => entity && !entity.pendingCommandId && setGroupEditor({ baseVersion: entity.entityVersion, value: group })}><div className="card-title-row"><h2>{group.name}</h2>{entity?.pendingCommandId && <span className="status-pill temporary">待同步</span>}</div><p>完成：{group.completeMessage || "使用系统默认文案"}</p><p>未完成：{group.incompleteMessage || "使用系统默认文案"}</p><div className="meta-row"><span>{assigned} 个活动任务</span><span>v{entity?.entityVersion ?? 0}</span><span className="mono">{group.id}</span></div></div><div className="card-actions"><button className="button text" disabled={!entity || Boolean(entity.pendingCommandId)} onClick={() => entity && setGroupEditor({ baseVersion: entity.entityVersion, value: group })}>编辑</button><button className="button text danger" disabled={!entity || Boolean(entity.pendingCommandId)} onClick={() => void archiveGroup(group)}>归档</button></div></article>;
        })}</div>}
      </section>}

      {tab === "results" && <section className="connected-page"><div className="page-header"><div><p className="eyebrow">执行结果</p><h1>结果与复核</h1><p>这里使用面向用户的任务名称和状态；信息告知任务会展示执行者填写的正文。</p></div></div><div className="card-list">{results.length === 0 ? <div className="empty-state"><Send /><h2>暂无执行结果</h2></div> : results.map((item) => {
        const selected = selections.find((selection) => selection.payload.assignmentId === item.payload.assignmentId && selection.payload.occurrenceKey === item.payload.occurrenceKey)?.payload.executionEventId === item.entityId;
        const presentation = presentExecutionResult(item, entities, membership?.space.timeZone ?? "Asia/Hong_Kong");
        return <article className="data-card result-card" key={item.key}><div className="data-card-main"><div className="card-title-row"><div><h2>{presentation.taskName}</h2><p>{presentation.eventLabel}</p></div><div className="status-pills"><span className={`status-pill ${presentation.statusLabel === "已完成" ? "recurring" : "temporary"}`}>{presentation.statusLabel}</span>{selected && <span className="status-pill recurring">当前正式结果</span>}</div></div>{presentation.informationContent && <section className="information-result"><h3>填写内容</h3><p>{presentation.informationContent}</p></section>}<div className="meta-row">{presentation.taskDate && <span>任务日期：{presentation.taskDate}</span>}<span>提交时间：{presentation.occurredAt}</span>{presentation.taskRevision !== null && <span>任务版本：第 {presentation.taskRevision} 版</span>}</div>{presentation.reviewMessage && <p className="result-warning">{presentation.reviewMessage}</p>}{presentation.duplicateMessage && <p className="result-warning neutral">{presentation.duplicateMessage}</p>}</div><div className="card-actions"><button className="button tonal" disabled={!online || selected} onClick={() => void selectResult(item)}>{selected ? "已采用" : "采用为正式结果"}</button></div></article>;
      })}</div></section>}

      {tab === "settings" && settingsSection === "notifications" && <section className="connected-page"><div className="page-header"><div><p className="eyebrow">设置 · 通知</p><h1>空间动态</h1><p>服务端保留每个事件，此处按任务或批次合并显示。</p></div><button className="button tonal" disabled={!online || remoteNotifications.length === 0} onClick={() => void markNotificationsRead()}>全部标为已读</button></div>{settingsNavigation}<div className="card-list">{remoteNotifications.length === 0 ? <div className="empty-state"><Bell /><h2>{online ? "暂无通知" : "离线时显示上次缓存"}</h2></div> : remoteNotifications.map((item) => <article className="data-card" key={String(item.id)}><div className="data-card-main"><h2>{String(item.type)}</h2><p>{String(item.entityId ?? "空间事件")}</p><div className="meta-row"><span>{String(item.createdAt)}</span><span>{Number(item.eventCount ?? 1)} 个事件</span>{!item.readAt && <span>未读</span>}</div></div></article>)}</div></section>}

      {tab === "settings" && settingsSection === "audit" && <section className="connected-page"><div className="page-header"><div><p className="eyebrow">设置 · 安全审计</p><h1>空间操作时间线</h1><p>只展示安全元数据，不包含邮箱、令牌或任务正文。</p></div></div>{settingsNavigation}<div className="card-list">{auditEvents.length === 0 ? <div className="empty-state"><ShieldAlert /><h2>{online ? "暂无审计事件" : "审计时间线需要联网"}</h2></div> : auditEvents.map((item) => <article className="data-card" key={String(item.id)}><div className="data-card-main"><h2>{String(item.eventType)}</h2><p>{String(item.entityType ?? "系统")} · {String(item.entityId ?? "-")}</p><div className="meta-row"><span>{String(item.occurredAt)}</span></div></div></article>)}</div></section>}

      {tab === "settings" && settingsSection === "general" && <section className="connected-page"><div className="page-header"><div><p className="eyebrow">本机与空间</p><h1>设置</h1><p>主题等偏好只保存在此浏览器；空间时区属于共享业务数据。</p></div></div>{settingsNavigation}<div className="settings-grid"><article className="settings-card"><h2><Users />执行者成员</h2><p>当前有 {executorMembers.length} 名执行者。可以继续邀请新成员，每名执行者会获得独立的任务与结果记录。</p><div className="member-invite"><label className="field"><span>新执行者邮箱</span><input type="email" value={inviteEmail} onChange={(event) => setInviteEmail(event.target.value)} placeholder="name@example.com" /></label><button className="button primary" disabled={!online || busy || !inviteEmail.includes("@")} onClick={() => void invite()}>发送账号邀请</button></div><div className="member-list">{executorMembers.length === 0 ? <p>还没有执行者</p> : executorMembers.map((item, index) => <div className="member-row" key={item.id}><div><strong>{item.email ?? `执行者 ${index + 1}`}</strong><span>{item.email ? `加入于 ${new Date(item.joinedAt).toLocaleDateString("zh-CN")}` : "邮箱需联网加载"}</span></div><button className="button text danger" disabled={!online} onClick={() => void removeExecutor(item.id)}>移除并撤销会话</button></div>)}</div></article><article className="settings-card"><h2><Cloud />空间时区</h2><p>IANA 时区会版本化并同步到执行者设备；旧实例保持原映射。</p><label className="field"><span>IANA 时区</span><input value={spaceTimeZone} onChange={(event) => setSpaceTimeZone(event.target.value)} placeholder="Asia/Hong_Kong" /></label><button className="button tonal" disabled={!online || !spaceTimeZone.trim() || spaceTimeZone === membership?.space.timeZone} onClick={() => void updateTimeZone()}>更新空间时区</button></article><article className="settings-card"><h2><ShieldAlert />数据与账号</h2><p>导出和删除都需要在 10 分钟内重新验证邮箱。导出为版本化 DSEXPORT JSON，不包含令牌或内部安全事件。</p><button className="button tonal" disabled={!online} onClick={() => setSensitiveAction("export")}>导出空间数据</button><button className="button text danger" disabled={!online} onClick={() => confirm("账号和整个空间将立即冻结，并在 30 天后永久删除。确认继续验证？") && setSensitiveAction("delete-scheduled")}>申请 30 天后删除</button><button className="button text danger" disabled={!online} onClick={() => confirm("此操作会立即永久删除账号和整个空间，无法恢复。确认继续验证？") && setSensitiveAction("delete-immediate")}>立即永久删除</button></article><article className="settings-card"><h2><Settings />本地偏好</h2><p>这些值不会上传，也不会在账号设备间同步。</p><label className="field"><span>主题</span><select defaultValue={localStorage.getItem("dst-theme") ?? "system"} onChange={(event) => { localStorage.setItem("dst-theme", event.target.value); document.documentElement.dataset.theme = event.target.value === "system" ? "" : event.target.value; }}><option value="system">跟随系统</option><option value="light">浅色</option><option value="dark">深色</option></select></label><dl><dt>最近同步</dt><dd>{meta?.lastSyncedAt ?? "尚未同步"}</dd><dt>服务保护</dt><dd>{bootstrap?.service.mode ?? "NORMAL"}</dd><dt>账号</dt><dd className="mono">{bootstrap?.account.id}</dd></dl></article></div></section>}
    </main>

    {sensitiveAction && <Modal title={sensitiveAction === "export" ? "验证后导出" : sensitiveAction === "delete-scheduled" ? "验证后申请删除" : "验证后立即永久删除"} onClose={() => { setSensitiveAction(null); setSensitiveCode(""); setSensitiveChallengeId(""); }}><p>安全验证码有效 10 分钟，并且只能用于当前登录账号的敏感操作。</p><label className="field"><span>当前账号邮箱</span><input type="email" value={sensitiveEmail} disabled={Boolean(sensitiveChallengeId)} onChange={(event) => setSensitiveEmail(event.target.value)} /></label>{sensitiveChallengeId && <label className="field"><span>6 位验证码</span><input inputMode="numeric" maxLength={6} value={sensitiveCode} onChange={(event) => setSensitiveCode(event.target.value.replace(/\D/gu, ""))} /></label>}<div className="modal-actions"><button className="button text" onClick={() => setSensitiveAction(null)}>取消</button>{sensitiveChallengeId ? <button className="button primary" disabled={busy || sensitiveCode.length !== 6} onClick={() => void finishSensitiveAction()}>{busy ? "处理中…" : "验证并继续"}</button> : <button className="button primary" disabled={busy || !sensitiveEmail.includes("@")} onClick={() => void sendSensitiveCode()}>{busy ? "发送中…" : "发送安全验证码"}</button>}</div></Modal>}
    {editor && <Modal title={editor.baseVersion ? `编辑任务修订 v${editor.baseVersion}` : "新建任务"} onClose={() => setEditor(null)} wide><TaskEditor value={editor.value} groups={groups} onChange={(value) => setEditor({ ...editor, value })} allowKindChange advancedContent={<section className="assignment-targets"><div className="section-heading"><div><h3>执行者</h3><p>选择谁会在 Android 端收到并执行这项任务。</p></div></div><div className="assignment-mode-options"><label><input type="radio" name="assignment-mode" checked={editor.assignmentMode === "ALL"} onChange={() => setEditor({ ...editor, assignmentMode: "ALL" })} /><span><strong>所有当前及未来执行者</strong><small>以后加入空间的成员也会自动获得该任务。</small></span></label><label><input type="radio" name="assignment-mode" checked={editor.assignmentMode === "SELECTED"} onChange={() => setEditor({ ...editor, assignmentMode: "SELECTED", executorMembershipIds: editor.executorMembershipIds.length ? editor.executorMembershipIds : executorMembers.map((member) => member.id) })} /><span><strong>指定执行者</strong><small>只有下面勾选的成员会收到任务。</small></span></label></div>{editor.assignmentMode === "SELECTED" && <div className="executor-choice-list">{executorMembers.length === 0 ? <p className="empty-inline">当前没有可选执行者，请先在“设置”中邀请成员。</p> : executorMembers.map((member, index) => <label key={member.id}><input type="checkbox" checked={editor.executorMembershipIds.includes(member.id)} onChange={(event) => setEditor({ ...editor, executorMembershipIds: event.target.checked ? [...editor.executorMembershipIds, member.id] : editor.executorMembershipIds.filter((id) => id !== member.id) })} /><span><strong>{member.email ?? `执行者 ${index + 1}`}</strong><small>{member.email ? "邮箱账号" : "成员信息已离线缓存"}</small></span></label>)}</div>}{editor.assignmentMode === "SELECTED" && editor.executorMembershipIds.length === 0 && <p className="connected-error">请至少选择一名执行者。</p>}</section>} /><div className="modal-actions connected-modal-actions"><button className="button text" onClick={() => setEditor(null)}>放弃</button><button className="button primary" disabled={taskIssues(editor.value).length > 0 || (editor.assignmentMode === "SELECTED" && editor.executorMembershipIds.length === 0)} onClick={() => void saveTask()}>{online ? "保存并同步" : "离线保存"}</button></div></Modal>}
    {groupEditor && <Modal title={groupEditor.baseVersion ? `编辑积分组 v${groupEditor.baseVersion}` : "新建积分组"} onClose={() => setGroupEditor(null)}><GroupEditor value={groupEditor.value} onChange={(value) => setGroupEditor({ ...groupEditor, value })} /><div className="modal-actions"><button className="button text" onClick={() => setGroupEditor(null)}>取消</button><button className="button primary" disabled={Boolean(groupIssue(groupEditor.value, groups))} onClick={() => void saveGroup()}>{online ? "保存并同步" : "离线保存"}</button></div></Modal>}
  </div>;
}
