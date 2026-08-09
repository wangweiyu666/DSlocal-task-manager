import { useLiveQuery } from "dexie-react-hooks";
import { Clipboard, FileClock, GitBranch, Search } from "lucide-react";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Modal } from "../components/Modal";
import { useToast } from "../components/Toast";
import { db } from "../db/database";
import { createDraft } from "../model/defaults";
import { draftTaskFromDst1 } from "../model/converters";
import { createLocalId } from "../protocol/id";
import type { BatchHistoryRecord } from "../model/types";

export function HistoryPage() {
  const history = useLiveQuery(() => db.batchHistory.orderBy("generatedAt").reverse().toArray(), []) ?? [];
  const [search, setSearch] = useState(""); const [selected, setSelected] = useState<BatchHistoryRecord | null>(null);
  const { show } = useToast(); const navigate = useNavigate();
  const filtered = useMemo(() => history.filter((item) => `${item.draftName} ${item.id} ${item.snapshot.m ?? ""}`.toLocaleLowerCase("zh-CN").includes(search.toLocaleLowerCase("zh-CN"))), [history, search]);
  const copy = async (value: string) => { try { await navigator.clipboard.writeText(value); show("DST1 已复制", "success"); } catch { show("浏览器拒绝访问剪贴板，请在详情中手动复制", "error"); } };
  const branch = async (item: BatchHistoryRecord) => {
    const now = new Date().toISOString();
    const groupRecords = await db.groups.toArray();
    const currentGroups = new Set(groupRecords.map((group) => group.id));
    const currentGroupNames = new Set(groupRecords.map((group) => group.name.trim().toLocaleLowerCase("zh-CN")));
    let nextOrder = groupRecords.length;
    let skippedGroups = 0;
    for (const group of item.snapshot.g ?? []) {
      if (!currentGroups.has(group.i) && group.n) {
        const normalizedName = group.n.trim().toLocaleLowerCase("zh-CN");
        if (currentGroupNames.has(normalizedName)) { skippedGroups += 1; continue; }
        await db.groups.add({ id: group.i, name: group.n, completeMessage: group.cm ?? "", incompleteMessage: group.im ?? "", order: nextOrder++, createdAt: now, updatedAt: now });
        currentGroups.add(group.i);
        currentGroupNames.add(normalizedName);
      }
    }
    const draft = createDraft(`${item.draftName} · 基于历史`); draft.description = item.snapshot.m ?? ""; draft.domNameMode = item.snapshot.d === undefined ? "preserve" : item.snapshot.d === "" ? "clear" : "set"; draft.domName = item.snapshot.d ?? ""; draft.cancellations = [...(item.snapshot.z ?? [])];
    draft.tasks = [
      ...(item.snapshot.g?.flatMap((group) => (group.t ?? []).map((task) => draftTaskFromDst1(task, currentGroups.has(group.i) ? group.i : null))) ?? []),
      ...(item.snapshot.t ?? []).map((task) => draftTaskFromDst1(task, null))
    ].map((task) => ({ ...task, draftItemId: createLocalId("item") }));
    draft.includeGroupIds = item.snapshot.g?.map((group) => group.i).filter((groupId) => currentGroups.has(groupId)) ?? [];
    await db.drafts.put(draft); await db.settings.update("app", { lastDraftId: draft.id, updatedAt: new Date().toISOString() }); show(skippedGroups ? "已创建新草稿；重名的历史积分组未恢复，相关任务已移到未分组" : "已创建新草稿；taskId 保持不变，用于生成新版", skippedGroups ? "info" : "success"); navigate("/create");
  };
  return <div className="page"><header className="page-header"><div><p className="eyebrow">不可变生成记录</p><h1>批次历史</h1><p>每条记录精确对应一个已经生成的 DST1 字符串。</p></div></header>
    <div className="filter-bar"><label className="search-field"><Search size={18} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索草稿名、batchId 或说明" /></label></div>
    {filtered.length === 0 ? <div className="empty-state"><FileClock size={44} /><h2>{history.length ? "没有匹配的批次" : "还没有生成历史"}</h2><p>在创建页确认生成后，批次会永久保存在这里。</p></div> : <div className="card-list">{filtered.map((item) => <article className="data-card" key={item.id}><div className="data-card-main" onClick={() => setSelected(item)}><h2>{item.draftName}</h2><p>{item.snapshot.m || "无批次说明"}</p><div className="meta-row"><span>{new Date(item.generatedAt).toLocaleString("zh-CN")}</span><span>{item.taskCount} 个任务</span><span>{item.envelopeChars} 字符</span><span className="mono">{item.id}</span></div></div><div className="card-actions"><button className="button tonal" onClick={() => copy(item.envelope)}><Clipboard size={16} />再次复制</button><button className="button text" onClick={() => branch(item)}><GitBranch size={16} />新建草稿</button></div></article>)}</div>}
    {selected && <Modal title={selected.draftName} onClose={() => setSelected(null)} wide><div className="preview-stats"><span><strong>{selected.taskCount}</strong> 个任务</span><span><strong>{selected.jsonBytes}</strong> JSON 字节</span><span><strong>{selected.envelopeChars}</strong> 字符</span><span><strong>{new Date(selected.generatedAt).toLocaleString("zh-CN")}</strong></span></div><label className="field"><span>不可变 DST1 字符串</span><textarea className="mono envelope-preview" rows={8} readOnly value={selected.envelope} onFocus={(event) => event.currentTarget.select()} /></label><details><summary>查看批次 JSON</summary><pre className="json-preview">{JSON.stringify(selected.snapshot, null, 2)}</pre></details><div className="modal-actions"><button className="button text" onClick={() => branch(selected)}><GitBranch size={17} />基于此批次新建草稿</button><button className="button primary" onClick={() => copy(selected.envelope)}><Clipboard size={17} />复制字符串</button></div></Modal>}
  </div>;
}
