import { useLiveQuery } from "dexie-react-hooks";
import { ArrowDown, ArrowUp, FolderKanban, Plus, Send, Trash2 } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Modal } from "../components/Modal";
import { useToast } from "../components/Toast";
import { db } from "../db/database";
import { getOrCreateActiveDraft, updateDraft } from "../db/operations";
import type { GroupRecord } from "../model/types";
import { createTransportId } from "../protocol/id";

function newGroup(order: number): GroupRecord { const now = new Date().toISOString(); return { id: createTransportId(), name: "", completeMessage: "", incompleteMessage: "", order, createdAt: now, updatedAt: now }; }
const normalizeName = (value: string) => value.trim().normalize("NFC").toLocaleLowerCase("zh-CN");

export function GroupsPage() {
  const groups = useLiveQuery(() => db.groups.orderBy("order").toArray(), []) ?? [];
  const tasks = useLiveQuery(() => db.tasks.toArray(), []) ?? [];
  const [editing, setEditing] = useState<GroupRecord | null>(null);
  const { show } = useToast(); const navigate = useNavigate();
  const save = async () => {
    if (!editing) return; const name = editing.name.trim().normalize("NFC");
    if (!name) { show("请填写积分组名称", "error"); return; }
    if ([...name].length > 50) { show("积分组名称不能超过 50 个字符", "error"); return; }
    if (groups.some((group) => group.id !== editing.id && normalizeName(group.name) === normalizeName(name))) { show("活动积分组不能重名", "error"); return; }
    await db.groups.put({ ...editing, name, completeMessage: editing.completeMessage.trim().normalize("NFC"), incompleteMessage: editing.incompleteMessage.trim().normalize("NFC"), updatedAt: new Date().toISOString() }); setEditing(null); show("积分组已保存", "success");
  };
  const move = async (group: GroupRecord, direction: -1 | 1) => { const index = groups.findIndex((item) => item.id === group.id); const target = index + direction; if (target < 0 || target >= groups.length) return; await db.transaction("rw", db.groups, async () => { await db.groups.update(group.id, { order: groups[target].order, updatedAt: new Date().toISOString() }); await db.groups.update(groups[target].id, { order: group.order, updatedAt: new Date().toISOString() }); }); };
  const addUpdate = async (group: GroupRecord) => { const draft = await getOrCreateActiveDraft(); await updateDraft(draft.id, { includeGroupIds: [...new Set([...draft.includeGroupIds, group.id])] }); show("积分组资料更新已加入当前草稿", "success"); navigate("/create"); };
  const remove = async (group: GroupRecord) => {
    const affected = tasks.filter((task) => task.groupId === group.id).length;
    if (!confirm(`删除本地积分组“${group.name}”？${affected ? `其下 ${affected} 个任务将移到未分组。` : ""} 已生成的历史批次不会变化。`)) return;
    await db.transaction("rw", [db.groups, db.tasks, db.templates, db.drafts], async () => {
      await db.tasks.where("groupId").equals(group.id).modify({ groupId: null, updatedAt: new Date().toISOString() });
      await db.templates.where("groupId").equals(group.id).modify({ groupId: null, updatedAt: new Date().toISOString() });
      const drafts = await db.drafts.toArray();
      for (const draft of drafts) await db.drafts.put({ ...draft, includeGroupIds: draft.includeGroupIds.filter((id) => id !== group.id), tasks: draft.tasks.map((task) => task.groupId === group.id ? { ...task, groupId: null } : task), updatedAt: new Date().toISOString() });
      await db.groups.delete(group.id);
    }); show("积分组已删除，关联任务已移到未分组", "success");
  };
  return <div className="page"><header className="page-header"><div><p className="eyebrow">跨批次稳定 groupId</p><h1>积分组</h1><p>管理名称与每日结果文案；删除这里只影响 Dom 本地配置。</p></div><button className="button primary" onClick={() => setEditing(newGroup(groups.length))}><Plus size={18} />新建积分组</button></header>
    {groups.length === 0 ? <div className="empty-state"><FolderKanban size={44} /><h2>还没有积分组</h2><p>任务也可以保持未分组，或创建积分组来计算分组结果。</p></div> : <div className="card-list">{groups.map((group, index) => <article className="data-card group-card" key={group.id}><div className="group-order"><button className="icon-button" onClick={() => move(group, -1)} disabled={index === 0} aria-label="上移"><ArrowUp size={18} /></button><button className="icon-button" onClick={() => move(group, 1)} disabled={index === groups.length - 1} aria-label="下移"><ArrowDown size={18} /></button></div><div className="data-card-main" onClick={() => setEditing(group)}><h2>{group.name}</h2><p>完成：{group.completeMessage || "使用系统默认文案"}</p><p>未完成：{group.incompleteMessage || "使用系统默认文案"}</p><div className="meta-row"><span>{tasks.filter((task) => task.groupId === group.id).length} 个任务</span><span className="mono">{group.id}</span></div></div><div className="card-actions"><button className="button tonal" onClick={() => addUpdate(group)}><Send size={16} />生成局部更新</button><button className="button text danger" onClick={() => remove(group)}><Trash2 size={16} />删除</button></div></article>)}</div>}
    {editing && <Modal title={groups.some((group) => group.id === editing.id) ? "编辑积分组" : "新建积分组"} onClose={() => setEditing(null)}><div className="form-grid"><label className="field span-2"><span>名称 *</span><input value={editing.name} maxLength={50} onChange={(event) => setEditing({ ...editing, name: event.target.value })} /></label><label className="field span-2"><span>全部完成文案</span><textarea rows={3} maxLength={500} value={editing.completeMessage} onChange={(event) => setEditing({ ...editing, completeMessage: event.target.value })} /></label><label className="field span-2"><span>未全部完成文案</span><textarea rows={3} maxLength={500} value={editing.incompleteMessage} onChange={(event) => setEditing({ ...editing, incompleteMessage: event.target.value })} /></label><label className="field span-2"><span>groupId</span><input className="mono" readOnly value={editing.id} /></label></div><div className="modal-actions"><button className="button text" onClick={() => setEditing(null)}>取消</button><button className="button primary" onClick={save}>保存</button></div></Modal>}
  </div>;
}
