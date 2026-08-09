import { useLiveQuery } from "dexie-react-hooks";
import { Ban, CalendarClock, Copy, FileInput, History, Plus, Repeat2, RotateCcw, Search } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Modal } from "../components/Modal";
import { TaskEditor, taskIssues, type EditableTask } from "../components/TaskEditor";
import { useToast } from "../components/Toast";
import { db } from "../db/database";
import { getOrCreateActiveDraft, updateDraft } from "../db/operations";
import { draftTaskFromTask } from "../model/defaults";
import { fieldsFromDst1 } from "../model/converters";
import type { GroupRecord, TaskRecord } from "../model/types";
import { decodeDst1 } from "../protocol/dst1";
import { createTransportId } from "../protocol/id";
import type { Dst1Batch, Dst1Task } from "../protocol/types";

interface RestorePreview { batch: Dst1Batch; conflicts: Set<string> }

export function LibraryPage() {
  const tasks = useLiveQuery(() => db.tasks.orderBy("updatedAt").reverse().toArray(), []) ?? [];
  const groups = useLiveQuery(() => db.groups.orderBy("order").toArray(), []) ?? [];
  const revisions = useLiveQuery(() => db.taskRevisions.toArray(), []) ?? [];
  const [search, setSearch] = useState("");
  const [groupFilter, setGroupFilter] = useState("all");
  const [kindFilter, setKindFilter] = useState("all");
  const [editing, setEditing] = useState<TaskRecord | null>(null);
  const [restoreText, setRestoreText] = useState<string | null>(null);
  const [restorePreview, setRestorePreview] = useState<RestorePreview | null>(null);
  const [useImported, setUseImported] = useState<Set<string>>(new Set());
  const searchRef = useRef<HTMLInputElement>(null);
  const { show } = useToast();
  const navigate = useNavigate();
  const groupMap = new Map(groups.map((group) => [group.id, group.name]));
  const filtered = useMemo(() => tasks.filter((task) => {
    const query = search.trim().toLocaleLowerCase("zh-CN");
    const matchesSearch = !query || `${task.name} ${task.description} ${task.id}`.toLocaleLowerCase("zh-CN").includes(query);
    const matchesGroup = groupFilter === "all" || (groupFilter === "ungrouped" ? task.groupId === null : task.groupId === groupFilter);
    const matchesKind = kindFilter === "all" || (kindFilter === "recurring" ? task.recurrence !== null : task.recurrence === null);
    return matchesSearch && matchesGroup && matchesKind;
  }), [groupFilter, kindFilter, search, tasks]);

  useEffect(() => { const handler = (event: KeyboardEvent) => { if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") { event.preventDefault(); searchRef.current?.focus(); } }; window.addEventListener("keydown", handler); return () => window.removeEventListener("keydown", handler); }, []);

  const addToDraft = async (task: TaskRecord, source: "existing" | "delay" = "existing") => {
    const draft = await getOrCreateActiveDraft();
    if (draft.tasks.some((item) => item.taskId === task.id)) { show("当前草稿已经包含这个 taskId", "error"); return; }
    await updateDraft(draft.id, { tasks: [...draft.tasks, draftTaskFromTask(task, source)] });
    show(source === "delay" ? "临时任务延期已加入草稿；请设置更晚截止时间" : "已加入当前草稿", "success"); navigate("/create");
  };
  const cancelInDraft = async (task: TaskRecord) => {
    const draft = await getOrCreateActiveDraft();
    if (draft.tasks.some((item) => item.taskId === task.id)) { show("当前草稿正在更新该任务，不能同时撤销", "error"); return; }
    await updateDraft(draft.id, { cancellations: [...new Set([...draft.cancellations, task.id])] }); show(task.recurrence ? "取消整个重复任务已加入草稿" : "撤销临时任务已加入草稿", "success"); navigate("/create");
  };
  const duplicateTask = async (task: TaskRecord) => {
    const now = new Date().toISOString();
    const copy: TaskRecord = { ...task, id: createTransportId(), name: `${task.name} · 副本`, createdAt: now, updatedAt: now, lastGeneratedAt: null, version: 0 };
    await db.tasks.add(copy); show("已创建使用新 taskId 的副本", "success");
  };
  const saveEdit = async () => {
    if (!editing) return;
    const issues = taskIssues(editing);
    if (issues.length) { show(issues[0], "error"); return; }
    await db.tasks.put({ ...editing, name: editing.name.trim().normalize("NFC"), updatedAt: new Date().toISOString() }); setEditing(null); show("任务工作副本已保存", "success");
  };
  const parseRestore = async () => {
    if (restoreText === null) return;
    try {
      const { batch } = decodeDst1(restoreText.trim());
      const ids = [...(batch.t ?? []), ...(batch.g?.flatMap((group) => group.t ?? []) ?? [])].map((task) => task.i);
      const existing = await db.tasks.where("id").anyOf(ids).primaryKeys();
      setRestorePreview({ batch, conflicts: new Set(existing.map(String)) }); setUseImported(new Set());
    } catch (error) { show(error instanceof Error ? error.message : "DST1 解析失败", "error"); }
  };
  const restoreTasks = async () => {
    if (!restorePreview) return;
    const now = new Date().toISOString();
    try {
      await db.transaction("rw", [db.groups, db.tasks], async () => {
        for (const group of restorePreview.batch.g ?? []) {
          const existing = await db.groups.get(group.i);
          if (!existing && group.n) await db.groups.add({ id: group.i, name: group.n, completeMessage: group.cm ?? "", incompleteMessage: group.im ?? "", order: (await db.groups.count()) + 1, createdAt: now, updatedAt: now });
          else if (existing && useImported.has(`group:${group.i}`)) await db.groups.put({ ...existing, name: group.n ?? existing.name, completeMessage: group.cm ?? existing.completeMessage, incompleteMessage: group.im ?? existing.incompleteMessage, updatedAt: now });
        }
        const saveTask = async (task: Dst1Task, groupId: string | null) => {
          const existing = await db.tasks.get(task.i);
          if (existing && !useImported.has(task.i)) return;
          await db.tasks.put({ ...fieldsFromDst1(task), id: task.i, groupId, createdAt: existing?.createdAt ?? now, updatedAt: now, lastGeneratedAt: existing?.lastGeneratedAt ?? null, version: existing?.version ?? 0 });
        };
        for (const group of restorePreview.batch.g ?? []) for (const task of group.t ?? []) await saveTask(task, group.i);
        for (const task of restorePreview.batch.t ?? []) await saveTask(task, null);
      });
      show("旧字符串中的任务定义已恢复；未创建发送历史", "success"); setRestorePreview(null); setRestoreText(null);
    } catch (error) { show(error instanceof Error ? error.message : "恢复失败", "error"); }
  };
  const restoredTasks = restorePreview ? [...(restorePreview.batch.t ?? []), ...(restorePreview.batch.g?.flatMap((group) => group.t ?? []) ?? [])] : [];
  return <div className="page">
    <header className="page-header"><div><p className="eyebrow">临时任务与重复任务分别管理</p><h1>任务库</h1><p>重复任务保存生成规则；临时任务只对应一个独立实例。这里不显示 Sub 的执行状态。</p></div><button className="button tonal" onClick={() => setRestoreText("")}><FileInput size={18} />粘贴旧字符串</button></header>
    <div className="filter-bar"><label className="search-field"><Search size={18} /><input ref={searchRef} value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索名称、描述或 taskId（Ctrl+K）" /></label><select aria-label="任务类型筛选" value={kindFilter} onChange={(event) => setKindFilter(event.target.value)}><option value="all">全部任务类型</option><option value="temporary">临时任务</option><option value="recurring">重复任务</option></select><select value={groupFilter} onChange={(event) => setGroupFilter(event.target.value)}><option value="all">全部积分组</option><option value="ungrouped">未分组</option>{groups.map((group) => <option key={group.id} value={group.id}>{group.name}</option>)}</select></div>
    {filtered.length === 0 ? <div className="empty-state"><Search size={44} /><h2>{tasks.length ? "没有匹配的任务" : "任务库还是空的"}</h2><p>在创建页生成第一个临时或重复任务，或从旧 DST1 字符串恢复。</p></div> : <div className="card-list">{filtered.map((task) => {
      const recurring = task.recurrence !== null;
      return <article className="data-card" key={task.id}>
        <div className="data-card-main" onClick={() => setEditing(task)}>
          <div className="card-title-row"><h2>{task.name}</h2><div className="status-pills"><span className={`status-pill ${recurring ? "recurring" : "temporary"}`}>{recurring ? <><Repeat2 size={12} />重复任务</> : "临时任务"}</span><span className={`status-pill ${task.required ? "required" : "optional"}`}>{task.required ? "必做" : "选做"}</span></div></div>
          <p>{task.description || "无描述"}</p>
          <div className="meta-row"><span>{groupMap.get(task.groupId ?? "") ?? "未分组"}</span>{recurring && <span>{task.recurrence?.f === 1 ? "每天" : "每周"}</span>}<span>{task.points} 分</span><span>v{task.version}</span><span className="mono">{task.id}</span></div>
        </div>
        <div className="card-actions"><button className="button tonal" onClick={() => addToDraft(task)}><Plus size={16} />{recurring ? "更新重复任务" : "更新临时任务"}</button><button className="button text" onClick={() => duplicateTask(task)}><Copy size={16} />复制</button>{!recurring && <button className="button text" onClick={() => addToDraft(task, "delay")}><CalendarClock size={16} />延期</button>}<button className="button text" onClick={() => addToDraft(task)}><RotateCcw size={16} />恢复</button><button className="button text danger" onClick={() => cancelInDraft(task)}><Ban size={16} />{recurring ? "取消重复任务" : "撤销临时任务"}</button></div>
        {revisions.some((item) => item.taskId === task.id) && <div className="revision-note"><History size={15} />已保存 {revisions.filter((item) => item.taskId === task.id).length} 个不可变版本</div>}
      </article>;
    })}</div>}
    {editing && <Modal title="编辑任务工作副本" onClose={() => setEditing(null)} wide><TaskEditor value={editing} groups={groups} onChange={(value) => setEditing({ ...editing, ...value })} /><div className="modal-actions"><button className="button text" onClick={() => setEditing(null)}>取消</button><button className="button primary" onClick={saveEdit}>保存工作副本</button></div></Modal>}
    {restoreText !== null && !restorePreview && <Modal title="从旧 DST1 恢复" onClose={() => setRestoreText(null)} wide><p className="supporting">只恢复积分组和任务定义；不会创建发送历史，也不会推断 Sub 状态。</p><label className="field"><span>DST1 字符串</span><textarea className="mono" rows={9} value={restoreText} onChange={(event) => setRestoreText(event.target.value)} autoFocus /></label><div className="modal-actions"><button className="button text" onClick={() => setRestoreText(null)}>取消</button><button className="button primary" onClick={parseRestore}>解析并预览</button></div></Modal>}
    {restorePreview && <Modal title="恢复预览" onClose={() => setRestorePreview(null)} wide><div className="preview-stats"><span><strong>{restoredTasks.length}</strong> 个任务</span><span><strong>{restorePreview.batch.g?.length ?? 0}</strong> 个积分组</span><span><strong>{restorePreview.conflicts.size}</strong> 个任务冲突</span></div><div className="conflict-list">{restoredTasks.map((task) => <label key={task.i} className="conflict-row"><div><strong>{task.n}</strong><small className="mono">{task.i}</small></div>{restorePreview.conflicts.has(task.i) ? <span><input type="checkbox" checked={useImported.has(task.i)} onChange={(event) => setUseImported((current) => { const next = new Set(current); event.target.checked ? next.add(task.i) : next.delete(task.i); return next; })} />使用导入版本（默认保留本地）</span> : <span className="status-pill optional">新增</span>}</label>)}</div><div className="modal-actions"><button className="button text" onClick={() => setRestorePreview(null)}>返回</button><button className="button primary" onClick={restoreTasks}>确认恢复</button></div></Modal>}
  </div>;
}
