import { useLiveQuery } from "dexie-react-hooks";
import { ArrowLeft, Clipboard, Copy, FilePlus2, Plus, Repeat2, Send, Trash2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { Modal } from "../components/Modal";
import { TaskEditor, taskIssues, type EditableTask } from "../components/TaskEditor";
import { useToast } from "../components/Toast";
import { db } from "../db/database";
import { getOrCreateActiveDraft } from "../db/operations";
import { createDraft, createRecurringDraftTask, createTemporaryDraftTask, draftTaskFromTemplate } from "../model/defaults";
import type { DraftRecord, DraftTask, TaskRevision } from "../model/types";
import { buildBatch, draftTaskToDst1, taskRecordFromDraft } from "../protocol/builder";
import { encodeDst1, type EncodedDst1 } from "../protocol/dst1";
import { createLocalId, createTransportId } from "../protocol/id";
import type { Dst1Batch } from "../protocol/types";

interface PreviewState { batch: Dst1Batch; encoded: EncodedDst1 }

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

  const selectedTask = draft?.tasks.find((task) => task.draftItemId === selectedItemId) ?? null;
  const groupedTasks = useMemo(() => {
    const map = new Map<string | null, DraftTask[]>();
    if (!draft) return map;
    for (const task of draft.tasks) map.set(task.groupId, [...(map.get(task.groupId) ?? []), task]);
    return map;
  }, [draft]);

  const writeDraft = (patch: Partial<DraftRecord>) => {
    if (!draft) return;
    const next = { ...draft, ...patch, updatedAt: new Date().toISOString() };
    setWorkingDraft(next);
    void db.drafts.put(next).catch((error: unknown) => show(error instanceof Error ? `自动保存失败：${error.message}` : "自动保存失败", "error"));
  };
  const selectDraft = (id: string, nextDraft = drafts.find((item) => item.id === id) ?? null) => { setWorkingDraft(nextDraft); setDraftId(id); setSelectedItemId(null); void db.settings.update("app", { lastDraftId: id, updatedAt: new Date().toISOString() }); };
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
    writeDraft({ tasks: draft.tasks.map((task) => task.draftItemId === selectedTask.draftItemId ? { ...task, ...value } : task) });
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
    const issues = draft.tasks.flatMap((task) => taskIssues(task).map((issue) => `${task.name || "未命名任务"}：${issue}`));
    if (issues.length) { show(issues[0], "error"); return; }
    try { const batch = buildBatch(draft, groups); setPreview({ batch, encoded: encodeDst1(batch) }); }
    catch (error) { show(error instanceof Error ? error.message : "无法生成 DST1", "error"); }
  };

  const commitGeneration = async () => {
    if (!preview || !draft) return;
    const now = new Date().toISOString();
    await db.transaction("rw", [db.batchHistory, db.tasks, db.taskRevisions, db.drafts], async () => {
      await db.batchHistory.add({ id: preview.batch.b, draftName: draft.name, generatedAt: now, envelope: preview.encoded.envelope, jsonBytes: preview.encoded.jsonBytes, envelopeChars: preview.encoded.envelope.length, taskCount: draft.tasks.length, snapshot: preview.batch });
      const nextTasks: DraftTask[] = [];
      for (const item of draft.tasks) {
        const previous = await db.tasks.get(item.taskId);
        const record = taskRecordFromDraft(item, previous);
        await db.tasks.put(record);
        const revision: TaskRevision = { id: createLocalId("revision"), taskId: record.id, version: record.version, generatedAt: now, batchId: preview.batch.b, snapshot: draftTaskToDst1(item) };
        await db.taskRevisions.add(revision);
        nextTasks.push({ ...item, source: "existing" });
      }
      await db.drafts.update(draft.id, { tasks: nextTasks, updatedAt: now });
      setWorkingDraft({ ...draft, tasks: nextTasks, updatedAt: now });
    });
    try { await copyText(preview.encoded.envelope); show("DST1 已保存到批次历史并复制", "success"); }
    catch { show("批次已保存，但浏览器拒绝访问剪贴板；请在预览中手动复制", "error"); }
    setPreview(null);
  };

  if (!draft) return <div className="page"><div className="loading">正在恢复草稿…</div></div>;
  const treeGroups = [...groups.map((group) => ({ id: group.id as string | null, name: group.name })), { id: null, name: "未分组" }];
  return <div className="page create-page">
    <header className="page-header"><div><p className="eyebrow">DST1 v1</p><h1>创建任务</h1><p>组织一个可原子导入的任务批次，草稿会自动保存在本机。</p></div><button className="button primary" onClick={createPreview}><Send size={18} />生成预览</button></header>
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
        <div className="batch-summary"><span>{draft.tasks.filter((task) => !task.recurrence).length} 个临时任务</span><span>{draft.tasks.filter((task) => task.recurrence).length} 个重复任务</span><span>{draft.includeGroupIds.length} 个组资料更新</span><span>{draft.cancellations.length} 个撤销</span></div>
      </section>
      <section className="editor-panel">
        {selectedTask ? <><div className="editor-toolbar"><button className="button text mobile-only" onClick={() => setSelectedItemId(null)}><ArrowLeft size={18} />返回批次</button><div><strong>{selectedTask.name || "编辑任务"}</strong><small>{taskTypeLabel(selectedTask)} · <span className="mono">{selectedTask.taskId}</span></small></div><button className="button text danger" onClick={removeSelectedTask}><Trash2 size={17} />移除</button></div><TaskEditor value={selectedTask} groups={groups} onChange={changeSelectedTask} /></> : <div className="empty-state"><Clipboard size={44} /><h2>选择任务开始编辑</h2><p>临时任务只生成一个实例；重复任务按每日或每周规则生成实例。</p><div className="empty-actions"><button className="button primary" onClick={() => addTask("temporary", null)}><Plus size={18} />添加临时任务</button><button className="button tonal" onClick={() => addTask("recurring", null)}><Repeat2 size={18} />添加重复任务</button></div></div>}
      </section>
    </div>
    {preview && <Modal title="生成预览" onClose={() => setPreview(null)} wide><div className="preview-stats"><span><strong>{draft.tasks.length}</strong> 个任务</span><span><strong>{preview.encoded.jsonBytes}</strong> JSON 字节</span><span><strong>{preview.encoded.compressedBytes}</strong> 压缩字节</span><span><strong>{preview.encoded.envelope.length}</strong> 字符</span></div><label className="field"><span>DST1 字符串</span><textarea className="mono envelope-preview" readOnly rows={7} value={preview.encoded.envelope} onFocus={(event) => event.currentTarget.select()} /></label><details><summary>查看规范化 JSON</summary><pre className="json-preview">{JSON.stringify(preview.batch, null, 2)}</pre></details><div className="modal-actions"><button className="button text" onClick={() => setPreview(null)}>返回修改</button><button className="button primary" onClick={commitGeneration}><Clipboard size={18} />保存并复制</button></div></Modal>}
  </div>;
}
