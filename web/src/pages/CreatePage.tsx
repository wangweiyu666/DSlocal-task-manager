import { useLiveQuery } from "dexie-react-hooks";
import { ArrowLeft, Clipboard, Copy, FilePlus2, Plus, Repeat2, Send, Trash2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { Modal } from "../components/Modal";
import { TaskEditor, taskIssues, type EditableTask } from "../components/TaskEditor";
import { useToast } from "../components/Toast";
import { db } from "../db/database";
import { getOrCreateActiveDraft } from "../db/operations";
import { createDraft, createRecurringDraftTask, createTemporaryDraftTask, draftTaskFromTemplate } from "../model/defaults";
import { normalizeEditableSteps } from "../model/steps";
import type { DraftRecord, DraftTask, TaskExceptionRevision, TaskRevision } from "../model/types";
import { buildBatch, taskRecordFromDraft } from "../protocol/builder";
import { encodeDst1, type EncodedDst1 } from "../protocol/dst1";
import { createLocalId, createTransportId } from "../protocol/id";
import type { Dst1Batch, Dst1Group, Dst1Task } from "../protocol/types";
import { createPreviewSnapshot } from "../model/preview";
import { commitPreviewSnapshot } from "../model/preview";

interface PreviewState { batch: Dst1Batch; encoded: EncodedDst1; draftSnapshot: DraftRecord }

export function naturalDate(timeZone: string): string {
  try {
    const parts = new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date());
    const values = Object.fromEntries(parts.filter((part) => part.type !== "literal").map((part) => [part.type, part.value]));
    if (values.year && values.month && values.day) return `${values.year}-${values.month}-${values.day}`;
  } catch { /* use browser timezone below */ }
  return new Intl.DateTimeFormat("en-CA", { year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
}

export function applyDefaultDates(batch: Dst1Batch, draft: DraftRecord, timeZone: string): Dst1Batch {
  const date = naturalDate(timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone);
  const taskMap = new Map(draft.tasks.map((task) => [task.taskId, task]));
  const patchTask = (task: Dst1Task): Dst1Task => {
    const source = taskMap.get(task.i);
    if (!source) return task;
    if (source.taskDateIntent === "preserve" || (source.source === "existing" && source.taskDateIntent === undefined)) return task;
    if (source.recurrence) return task.x && !task.x.s ? { ...task, x: { ...task.x, s: date } } : task;
    return task.y ? task : { ...task, y: date };
  };
  return { ...batch, t: batch.t?.map(patchTask), g: batch.g?.map((group) => ({ ...group, t: group.t?.map(patchTask) })) };
}

function taskTypeLabel(task: DraftTask): string {
  if (!task.recurrence) return "临时任务";
  return task.recurrence.f === 1 ? "每日重复" : "每周重复";
}

function taskSourceLabel(task: DraftTask): string {
  if (task.source === "existing") return "更新";
  if (task.source === "delay") return "延期";
  return "新建";
}

async function copyText(value: string): Promise<void> {
  if (navigator.clipboard?.writeText) return navigator.clipboard.writeText(value);
  const area = document.createElement("textarea"); area.value = value; document.body.append(area); area.select(); document.execCommand("copy"); area.remove();
}

export function CreatePage() {
  const drafts = useLiveQuery(() => db.drafts.orderBy("updatedAt").reverse().toArray(), []) ?? [];
  const groups = useLiveQuery(() => db.groups.orderBy("order").toArray(), []) ?? [];
  const templates = useLiveQuery(() => db.templates.orderBy("updatedAt").reverse().toArray(), []) ?? [];
  const settings = useLiveQuery(() => db.settings.get("app"), []);
  const [draftId, setDraftId] = useState<string | null>(null);
  const [workingDraft, setWorkingDraft] = useState<DraftRecord | null>(null);
  const [selectedItemId, setSelectedItemId] = useState<string | null>(null);
  const [preview, setPreview] = useState<PreviewState | null>(null);
  const [committing, setCommitting] = useState(false);
  const [templateId, setTemplateId] = useState("");
  const { show } = useToast();
  const liveDraft = drafts.find((item) => item.id === draftId) ?? null;
  const draft = workingDraft?.id === draftId ? workingDraft : liveDraft;

  useEffect(() => { void getOrCreateActiveDraft().then((active) => setDraftId((current) => { if (current) return current; setWorkingDraft(active); return active.id; })); }, []);
  useEffect(() => { if (settings?.lastDraftId && !draftId) setDraftId(settings.lastDraftId); }, [draftId, settings]);
  useEffect(() => {
    if (!draftId) return;
    const next = drafts.find((item) => item.id === draftId);
    if (next) setWorkingDraft((current) => current?.id === draftId ? current : next);
  }, [draftId, drafts]);
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key === "Enter") { event.preventDefault(); createPreview(); }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "n") { event.preventDefault(); addTask("temporary", null); }
    };
    window.addEventListener("keydown", handler); return () => window.removeEventListener("keydown", handler);
  });

  const selectedTaskRecord = draft?.tasks.find((task) => task.draftItemId === selectedItemId) ?? null;
  const selectedTask = selectedTaskRecord ? { ...selectedTaskRecord, timeZone: settings?.timeZone } : null;
  const groupedTasks = useMemo(() => {
    const map = new Map<string | null, DraftTask[]>();
    if (!draft) return map;
    for (const task of draft.tasks) map.set(task.groupId, [...(map.get(task.groupId) ?? []), task]);
    return map;
  }, [draft]);

  const writeDraft = (patch: Partial<DraftRecord>) => {
    if (!draft) return;
    const next = { ...draft, ...patch, updatedAt: new Date().toISOString() };
    // A preview is an immutable submission snapshot. Any draft edit must force
    // the user to regenerate it so dates/IDs/exception payloads cannot diverge.
    setPreview(null);
    setWorkingDraft(next);
    void db.drafts.put(next).catch((error: unknown) => show(error instanceof Error ? `自动保存失败：${error.message}` : "自动保存失败", "error"));
  };
  useEffect(() => {
    if (!draft || !selectedTaskRecord) return;
    const normalized = normalizeEditableSteps(selectedTaskRecord.taskId, selectedTaskRecord.name, selectedTaskRecord.steps, selectedTaskRecord.execution);
    if (JSON.stringify(normalized.steps) === JSON.stringify(selectedTaskRecord.steps) && JSON.stringify(normalized.execution) === JSON.stringify(selectedTaskRecord.execution)) return;
    writeDraft({ tasks: draft.tasks.map((task) => task.draftItemId === selectedTaskRecord.draftItemId ? { ...task, ...normalized } : task) });
  }, [draft, selectedTaskRecord]);
  const selectDraft = (id: string, nextDraft = drafts.find((item) => item.id === id) ?? null) => { setPreview(null); setWorkingDraft(nextDraft); setDraftId(id); setSelectedItemId(null); void db.settings.update("app", { lastDraftId: id, updatedAt: new Date().toISOString() }); };
  const addTask = (kind: "temporary" | "recurring", groupId: string | null) => {
    if (!draft) return;
    const task = kind === "recurring" ? createRecurringDraftTask(groupId) : createTemporaryDraftTask(groupId);
    writeDraft({ tasks: [...draft.tasks, task] }); setSelectedItemId(task.draftItemId);
  };
  const addTemplate = () => {
    const template = templates.find((item) => item.id === templateId);
    if (!draft || !template) return;
    const task = draftTaskFromTemplate(template);
    writeDraft({ tasks: [...draft.tasks, task] }); setSelectedItemId(task.draftItemId); setTemplateId("");
  };
  const changeSelectedTask = (value: EditableTask) => {
    if (!draft || !selectedTask) return;
    const { timeZone: _timeZone, ...editableValue } = value;
    writeDraft({ tasks: draft.tasks.map((task) => task.draftItemId === selectedTask.draftItemId ? { ...task, ...editableValue } : task) });
  };
  const removeSelectedTask = () => {
    if (!draft || !selectedTask || !confirm(`从草稿移除“${selectedTask.name || "未命名任务"}”？任务库中的原任务不会删除。`)) return;
    writeDraft({ tasks: draft.tasks.filter((task) => task.draftItemId !== selectedTask.draftItemId) }); setSelectedItemId(null);
  };
  const createNewDraft = async () => { const next = createDraft(`批次 ${drafts.length + 1}`); await db.drafts.put(next); selectDraft(next.id, next); };
  const duplicateDraft = async () => {
    if (!draft) return;
    const next = createDraft(`${draft.name} · 副本`);
    next.description = draft.description; next.domNameMode = draft.domNameMode; next.domName = draft.domName; next.includeGroupIds = [...draft.includeGroupIds]; next.cancellations = [...draft.cancellations];
    next.exceptions = (draft.exceptions ?? []).map((item) => ({ ...item, draftItemId: createLocalId("exception-item"), directive: { ...item.directive } }));
    next.tasks = draft.tasks.map((task) => ({ ...task, draftItemId: createLocalId("item"), taskId: ["new", "template"].includes(task.source) ? createTransportId() : task.taskId }));
    await db.drafts.put(next); selectDraft(next.id, next);
  };
  const deleteDraft = async () => {
    if (!draft || !confirm(`永久删除草稿“${draft.name}”？此操作不会删除任务库和历史批次。`)) return;
    await db.drafts.delete(draft.id); const remaining = drafts.filter((item) => item.id !== draft.id);
    if (remaining.length) selectDraft(remaining[0].id, remaining[0]); else await createNewDraft();
  };

  const createPreview = () => {
    if (!draft) return;
    if (settings?.timeZone) {
      try { new Intl.DateTimeFormat("en", { timeZone: settings.timeZone }).format(); }
      catch { show("任务时区未知，请先在设置中刷新或修正时区。", "error"); return; }
    }
    const issues = draft.tasks.flatMap((task) => taskIssues(task).map((issue) => `${task.name || "未命名任务"}：${issue}`));
    if (issues.length) { show(issues[0], "error"); return; }
    try {
      setPreview(createPreviewSnapshot(draft, groups, settings?.timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone));
    }
    catch (error) { show(error instanceof Error ? error.message : "无法生成 DST1", "error"); }
  };

  const commitGeneration = async () => {
    if (!preview || !draft || committing) return;
    setCommitting(true);
    const now = new Date().toISOString();
    const draftSnapshot = preview.draftSnapshot;
    try {
      const nextTasks = await commitPreviewSnapshot(preview, draftSnapshot.name, now);
      setWorkingDraft({ ...draftSnapshot, tasks: nextTasks, updatedAt: now });
      setCommitting(false);
      try { await copyText(preview.encoded.envelope); show(`${preview.batch.sv === 1 ? "DST1.1" : "DST1"} 已保存到批次历史并复制`, "success"); } catch { show("批次已保存，但浏览器拒绝访问剪贴板；请在预览中手动复制", "error"); }
      setPreview(null);
      return;
    } catch (error) { setCommitting(false); show(error instanceof Error ? `保存失败：${error.message}` : "保存失败，请重试", "error"); return; }
    return;
    /* Legacy inline commit retained below only as historical reference. */
    const previewForLegacy = preview;
    {
    const preview = previewForLegacy!;
    try { await db.transaction("rw", [db.batchHistory, db.tasks, db.taskRevisions, db.taskExceptions, db.exceptionRevisions, db.drafts], async () => {
      await db.batchHistory.add({ id: preview.batch.b, draftName: draftSnapshot.name, generatedAt: now, envelope: preview.encoded.envelope, jsonBytes: preview.encoded.jsonBytes, envelopeChars: preview.encoded.envelope.length, taskCount: draftSnapshot.tasks.length, snapshot: preview.batch });
      const nextTasks: DraftTask[] = [];
      for (const item of draftSnapshot.tasks) {
        const previous = await db.tasks.get(item.taskId);
        const record = taskRecordFromDraft(item, previous);
        await db.tasks.put(record);
        const revisionSnapshot = [...(preview.batch.t ?? []), ...(preview.batch.g ?? []).flatMap((group: Dst1Group) => group.t ?? [])].find((task) => task.i === item.taskId);
        if (!revisionSnapshot) throw new Error(`预览快照缺少任务 ${item.taskId}`);
        const revision: TaskRevision = { id: createLocalId("revision"), taskId: record.id, version: record.version, generatedAt: now, batchId: preview.batch.b, snapshot: revisionSnapshot };
        await db.taskRevisions.add(revision);
        nextTasks.push({ ...item, source: "existing" });
      }
      for (const item of draftSnapshot.exceptions ?? []) {
        const directive = item.directive;
        const exceptionId = `${directive.i}|${directive.y}`;
        const existing = await db.taskExceptions.get(exceptionId);
        const clear = Object.keys(directive).every((key) => key === "i" || key === "y");
        if (clear) await db.taskExceptions.delete(exceptionId);
        else await db.taskExceptions.put({ id: exceptionId, taskId: directive.i, date: directive.y, directive, createdAt: existing?.createdAt ?? now, updatedAt: now });
        const revision: TaskExceptionRevision = { id: createLocalId("exception-revision"), exceptionId, taskId: directive.i, date: directive.y, generatedAt: now, batchId: preview.batch.b, snapshot: directive };
        await db.exceptionRevisions.add(revision);
      }
      await db.drafts.update(draftSnapshot.id, { tasks: nextTasks, updatedAt: now });
      setWorkingDraft({ ...draftSnapshot, tasks: nextTasks, updatedAt: now });
    }); } catch { setCommitting(false); show("保存失败，请重试", "error"); return; }
    setCommitting(false);
    try { await copyText(preview.encoded.envelope); show(`${preview.batch.sv === 1 ? "DST1.1" : "DST1"} 已保存到批次历史并复制`, "success"); }
    catch { show("批次已保存，但浏览器拒绝访问剪贴板；请在预览中手动复制", "error"); }
    setPreview(null);
    }
  };

  if (!draft) return <div className="page"><div className="loading">正在恢复草稿…</div></div>;
  const treeGroups = [...groups.map((group) => ({ id: group.id as string | null, name: group.name })), { id: null, name: "未分组" }];
  return <div className="page create-page">
    <header className="page-header"><div><p className="eyebrow">DST1 / DST1.1</p><h1>创建任务</h1><p>组织一个可原子导入的任务批次，草稿会自动保存在本机。</p></div><button className="button primary" onClick={createPreview}><Send size={18} />生成预览</button></header>
    <div className="draft-toolbar">
      <label className="compact-field"><span>当前草稿</span><select value={draft.id} onChange={(event) => selectDraft(event.target.value)}>{drafts.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
      <button className="button tonal" onClick={createNewDraft}><FilePlus2 size={17} />新建</button><button className="button text" onClick={duplicateDraft}><Copy size={17} />复制草稿</button><button className="button text danger" onClick={deleteDraft}><Trash2 size={17} />删除</button>
      <div className="template-quick"><select value={templateId} onChange={(event) => setTemplateId(event.target.value)}><option value="">从模板添加…</option>{templates.map((template) => <option key={template.id} value={template.id}>{template.title}</option>)}</select><button className="button tonal" disabled={!templateId} onClick={addTemplate}>添加</button></div>
    </div>
    <div className={`create-layout ${selectedTask ? "has-selection" : ""}`}>
      <section className="batch-panel">
        <div className="batch-meta form-grid">
          <label className="field"><span>草稿名称</span><input value={draft.name} maxLength={100} onChange={(event) => writeDraft({ name: event.target.value })} /></label>
          <label className="field"><span>Dom 名称操作</span><select value={draft.domNameMode} onChange={(event) => writeDraft({ domNameMode: event.target.value as DraftRecord["domNameMode"] })}><option value="preserve">不修改 Sub 中的名称</option><option value="set">设置名称</option><option value="clear">清除名称</option></select></label>
          {draft.domNameMode === "set" && <label className="field"><span>Dom 显示名称</span><input value={draft.domName} maxLength={50} onChange={(event) => writeDraft({ domName: event.target.value })} placeholder={settings?.domName || "Dom"} /></label>}
          <label className="field span-2"><span>本次导入说明</span><textarea rows={2} maxLength={500} value={draft.description} onChange={(event) => writeDraft({ description: event.target.value })} /></label>
        </div>
        <div className="batch-tree">
          {treeGroups.map((group) => <section className="tree-group" key={group.id ?? "ungrouped"}><div className="tree-group-heading"><div><strong>{group.name}</strong><span>{groupedTasks.get(group.id)?.length ?? 0} 项</span></div><div className="tree-group-actions">{group.id && <label className="small-check"><input type="checkbox" checked={draft.includeGroupIds.includes(group.id)} onChange={(event) => writeDraft({ includeGroupIds: event.target.checked ? [...draft.includeGroupIds, group.id!] : draft.includeGroupIds.filter((id) => id !== group.id) })} />发送组资料</label>}<button className="tree-add-button" onClick={() => addTask("temporary", group.id)} aria-label={`在${group.name}添加临时任务`}><Plus size={15} />临时</button><button className="tree-add-button" onClick={() => addTask("recurring", group.id)} aria-label={`在${group.name}添加重复任务`}><Repeat2 size={15} />重复</button></div></div>
            <div className="tree-tasks">{(groupedTasks.get(group.id) ?? []).map((task) => <button key={task.draftItemId} className={`tree-task ${selectedItemId === task.draftItemId ? "selected" : ""}`} onClick={() => setSelectedItemId(task.draftItemId)}><span>{task.name || "未命名任务"}</span><small>{taskTypeLabel(task)} · {task.required ? "必做" : "选做"} · {task.points} 分 · {taskSourceLabel(task)}</small></button>)}</div>
          </section>)}
        </div>
        <div className="cancel-box"><label className="field"><span>撤销任务 ID（每行一个）</span><textarea rows={3} value={draft.cancellations.join("\n")} onChange={(event) => writeDraft({ cancellations: event.target.value.split(/\s+/u).map((item) => item.trim()).filter(Boolean).slice(0, 100) })} placeholder="16 位 taskId" /></label></div>
        {(draft.exceptions ?? []).length > 0 && <div className="cancel-box"><strong>单日例外</strong>{draft.exceptions.map((item) => <div className="meta-row" key={item.draftItemId}><span className="mono">{item.directive.i}</span><span>{item.directive.y}</span><span>{item.directive.c === 1 ? "撤销当天" : Object.keys(item.directive).length === 2 ? "恢复模板" : "覆盖"}</span><button className="button text danger" onClick={() => writeDraft({ exceptions: draft.exceptions.filter((entry) => entry.draftItemId !== item.draftItemId) })}>移除</button></div>)}</div>}
        <div className="batch-summary"><span>{draft.tasks.filter((task) => !task.recurrence).length} 个临时任务</span><span>{draft.tasks.filter((task) => task.recurrence).length} 个重复任务</span><span>{draft.includeGroupIds.length} 个组资料更新</span><span>{draft.cancellations.length} 个整项撤销</span><span>{(draft.exceptions ?? []).length} 个单日例外</span></div>
      </section>
      <section className="editor-panel">
        {selectedTask ? <><div className="editor-toolbar"><button className="button text mobile-only" onClick={() => setSelectedItemId(null)}><ArrowLeft size={18} />返回批次</button><div><strong>{selectedTask.name || "编辑任务"}</strong><small>{taskTypeLabel(selectedTask)} · <span className="mono">{selectedTask.taskId}</span></small></div><button className="button text danger" onClick={removeSelectedTask}><Trash2 size={17} />移除</button></div><TaskEditor value={selectedTask} groups={groups} onChange={changeSelectedTask} /></> : <div className="empty-state"><Clipboard size={44} /><h2>选择任务开始编辑</h2><p>临时任务只生成一个实例；重复任务按每日或每周规则生成实例。</p><div className="empty-actions"><button className="button primary" onClick={() => addTask("temporary", null)}><Plus size={18} />添加临时任务</button><button className="button tonal" onClick={() => addTask("recurring", null)}><Repeat2 size={18} />添加重复任务</button></div></div>}
      </section>
    </div>
    {preview && <Modal title="生成预览" onClose={() => !committing && setPreview(null)} wide><div className="preview-stats"><span><strong>{preview.draftSnapshot.tasks.length}</strong> 个任务</span><span><strong>{(preview.draftSnapshot.exceptions ?? []).length}</strong> 个单日例外</span><span><strong>{preview.encoded.jsonBytes}</strong> JSON 字节</span><span><strong>{preview.encoded.compressedBytes}</strong> 压缩字节</span><span><strong>{preview.encoded.envelope.length}</strong> 字符</span></div><label className="field"><span>{preview.batch.sv === 1 ? "DST1.1" : "DST1"} 字符串</span><textarea className="mono envelope-preview" readOnly rows={7} value={preview.encoded.envelope} onFocus={(event) => event.currentTarget.select()} /></label><details><summary>查看规范化 JSON</summary><pre className="json-preview">{JSON.stringify(preview.batch, null, 2)}</pre></details><div className="modal-actions"><button className="button text" disabled={committing} onClick={() => setPreview(null)}>返回修改</button><button className="button primary" disabled={committing} onClick={commitGeneration}>{committing ? "保存中…" : <><Clipboard size={18} />保存并复制</>}</button></div></Modal>}
  </div>;
}
