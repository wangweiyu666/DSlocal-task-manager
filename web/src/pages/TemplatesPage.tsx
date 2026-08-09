import { useLiveQuery } from "dexie-react-hooks";
import { Copy, LayoutTemplate, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Modal } from "../components/Modal";
import { TaskEditor, taskIssues } from "../components/TaskEditor";
import { useToast } from "../components/Toast";
import { db } from "../db/database";
import { getOrCreateActiveDraft, updateDraft } from "../db/operations";
import { draftTaskFromTemplate, emptyTaskFields } from "../model/defaults";
import type { TemplateRecord } from "../model/types";
import { createLocalId } from "../protocol/id";

function newTemplate(): TemplateRecord {
  const now = new Date().toISOString();
  return { ...emptyTaskFields(), id: createLocalId("template"), title: "", groupId: null, createdAt: now, updatedAt: now };
}

export function TemplatesPage() {
  const templates = useLiveQuery(() => db.templates.orderBy("updatedAt").reverse().toArray(), []) ?? [];
  const groups = useLiveQuery(() => db.groups.orderBy("order").toArray(), []) ?? [];
  const [editing, setEditing] = useState<TemplateRecord | null>(null);
  const { show } = useToast();
  const navigate = useNavigate();
  const save = async () => {
    if (!editing) return;
    if (!editing.title.trim()) { show("请填写模板名称", "error"); return; }
    const issues = taskIssues(editing);
    if (issues.length) { show(issues[0], "error"); return; }
    await db.templates.put({ ...editing, title: editing.title.trim().normalize("NFC"), name: editing.name.trim().normalize("NFC"), updatedAt: new Date().toISOString() });
    setEditing(null); show("模板已保存", "success");
  };
  const useTemplate = async (template: TemplateRecord) => {
    const draft = await getOrCreateActiveDraft(); await updateDraft(draft.id, { tasks: [...draft.tasks, draftTaskFromTemplate(template)] }); show("已使用新 taskId 加入当前草稿", "success"); navigate("/create");
  };
  const duplicate = async (template: TemplateRecord) => { const now = new Date().toISOString(); await db.templates.add({ ...template, id: createLocalId("template"), title: `${template.title} · 副本`, createdAt: now, updatedAt: now }); };
  const remove = async (template: TemplateRecord) => { if (confirm(`删除模板“${template.title}”？已生成的任务不会受影响。`)) await db.templates.delete(template.id); };
  return <div className="page"><header className="page-header"><div><p className="eyebrow">每次使用都生成新 taskId</p><h1>任务模板</h1><p>模板是表单预设，与已下发任务定义严格分离。</p></div><button className="button primary" onClick={() => setEditing(newTemplate())}><Plus size={18} />新建模板</button></header>
    {templates.length === 0 ? <div className="empty-state"><LayoutTemplate size={44} /><h2>还没有模板</h2><p>把经常重复布置的任务保存为模板。</p><button className="button primary" onClick={() => setEditing(newTemplate())}><Plus size={18} />新建模板</button></div> : <div className="card-grid">{templates.map((template) => <article className="data-card" key={template.id}><div className="data-card-main" onClick={() => setEditing(template)}><p className="eyebrow">{groups.find((group) => group.id === template.groupId)?.name ?? "未分组"}</p><h2>{template.title}</h2><p>{template.name || "未填写任务名称"}</p><div className="meta-row"><span>{template.required ? "必做" : "选做"}</span><span>{template.points} 分</span><span>{template.recurrence ? "重复" : "单次"}</span></div></div><div className="card-actions"><button className="button tonal" onClick={() => useTemplate(template)}><Plus size={16} />加入草稿</button><button className="button text" onClick={() => duplicate(template)}><Copy size={16} />复制</button><button className="button text danger" onClick={() => remove(template)}><Trash2 size={16} />删除</button></div></article>)}</div>}
    {editing && <Modal title={templates.some((item) => item.id === editing.id) ? "编辑模板" : "新建模板"} onClose={() => setEditing(null)} wide><label className="field template-title"><span>模板名称 *</span><input value={editing.title} maxLength={100} onChange={(event) => setEditing({ ...editing, title: event.target.value })} placeholder="例如：每周阅读任务" /></label><TaskEditor value={editing} groups={groups} onChange={(value) => setEditing({ ...editing, ...value })} allowKindChange /><div className="modal-actions"><button className="button text" onClick={() => setEditing(null)}>取消</button><button className="button primary" onClick={save}>保存模板</button></div></Modal>}
  </div>;
}
