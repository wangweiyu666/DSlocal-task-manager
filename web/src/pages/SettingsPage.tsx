import { useLiveQuery } from "dexie-react-hooks";
import { Database, Download, HardDrive, Keyboard, Save, Trash2, Upload } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { Modal } from "../components/Modal";
import { useToast } from "../components/Toast";
import { analyzeBackupConflicts, createBackup, downloadBackup, importBackup, parseBackup } from "../db/backup";
import { db, getSettings, updateSettings } from "../db/database";
import type { AppSettings, BackupConflict, DomBackup } from "../model/types";

interface ImportState { backup: DomBackup; conflicts: BackupConflict[]; mode: "merge" | "replace"; useImported: Set<string> }

export function SettingsPage() {
  const liveSettings = useLiveQuery(() => db.settings.get("app"), []);
  const counts = useLiveQuery(async () => ({ tasks: await db.tasks.count(), templates: await db.templates.count(), drafts: await db.drafts.count(), history: await db.batchHistory.count() }), []) ?? { tasks: 0, templates: 0, drafts: 0, history: 0 };
  const [form, setForm] = useState<AppSettings | null>(null);
  const [storage, setStorage] = useState<{ usage?: number; quota?: number; persisted?: boolean }>({});
  const [importState, setImportState] = useState<ImportState | null>(null);
  const fileRef = useRef<HTMLInputElement>(null); const { show } = useToast();
  useEffect(() => { if (liveSettings) setForm(liveSettings); else void getSettings(); }, [liveSettings]);
  useEffect(() => { void Promise.all([navigator.storage?.estimate?.(), navigator.storage?.persisted?.()]).then(([estimate, persisted]) => setStorage({ usage: estimate?.usage, quota: estimate?.quota, persisted })); }, []);
  const timeZones = useMemo(() => {
    try { return (Intl as typeof Intl & { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf?.("timeZone") ?? [Intl.DateTimeFormat().resolvedOptions().timeZone]; }
    catch { return [Intl.DateTimeFormat().resolvedOptions().timeZone]; }
  }, []);
  const save = async () => { if (!form) return; await updateSettings({ domName: form.domName.trim().normalize("NFC"), theme: form.theme, timeZone: form.timeZone }); show("设置已保存", "success"); };
  const exportData = async () => { downloadBackup(await createBackup()); show("配置备份已导出", "success"); };
  const chooseFile = async (file: File | undefined) => {
    if (!file) return;
    try { const backup = parseBackup(await file.text()); const conflicts = await analyzeBackupConflicts(backup); setImportState({ backup, conflicts, mode: "merge", useImported: new Set() }); }
    catch (error) { show(error instanceof Error ? error.message : "无法读取配置文件", "error"); }
    if (fileRef.current) fileRef.current.value = "";
  };
  const applyImport = async () => { if (!importState) return; try { await importBackup(importState.backup, importState.mode, importState.useImported); show(importState.mode === "replace" ? "本地配置已完全替换" : "配置已合并", "success"); setImportState(null); } catch (error) { show(error instanceof Error ? error.message : "导入失败", "error"); } };
  const requestPersistence = async () => { const result = await navigator.storage?.persist?.(); setStorage((value) => ({ ...value, persisted: result })); show(result ? "浏览器已允许持久存储" : "浏览器未授予持久存储；请定期导出备份", result ? "success" : "error"); };
  const clearHistory = async () => { if (!confirm("永久删除全部批次历史和任务版本？建议先导出配置备份。任务库、模板和草稿不会删除。")) return; await db.transaction("rw", [db.batchHistory, db.taskRevisions], async () => { await db.batchHistory.clear(); await db.taskRevisions.clear(); }); show("历史记录已删除且无法恢复", "success"); };
  if (!form) return <div className="page"><div className="loading">正在读取设置…</div></div>;
  const formatBytes = (bytes?: number) => bytes === undefined ? "未知" : bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KiB` : `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
  return <div className="page"><header className="page-header"><div><p className="eyebrow">仅保存在当前浏览器</p><h1>设置</h1><p>管理显示、时区、本地存储与完整配置备份。</p></div><button className="button primary" onClick={save}><Save size={18} />保存设置</button></header>
    <div className="settings-grid"><section className="settings-card"><h2>资料与显示</h2><div className="form-grid"><label className="field span-2"><span>默认 Dom 名称</span><input maxLength={50} value={form.domName} onChange={(event) => setForm({ ...form, domName: event.target.value })} /></label><label className="field"><span>主题</span><select value={form.theme} onChange={(event) => setForm({ ...form, theme: event.target.value as AppSettings["theme"] })}><option value="system">跟随系统</option><option value="light">浅色</option><option value="dark">深色</option></select></label><label className="field"><span>任务时区</span><select value={form.timeZone} onChange={(event) => setForm({ ...form, timeZone: event.target.value })}>{timeZones.map((zone) => <option key={zone}>{zone}</option>)}</select><small>DST1 输出本地墙上日期与时间，不包含时区。</small></label></div></section>
      <section className="settings-card"><h2>本地存储</h2><div className="storage-meter"><HardDrive size={24} /><div><strong>{formatBytes(storage.usage)} / {formatBytes(storage.quota)}</strong><span>{storage.persisted ? "浏览器已标记为持久存储" : "存储可能在空间不足时被浏览器清理"}</span></div></div><div className="count-grid"><span><strong>{counts.tasks}</strong>任务</span><span><strong>{counts.templates}</strong>模板</span><span><strong>{counts.drafts}</strong>草稿</span><span><strong>{counts.history}</strong>历史批次</span></div>{!storage.persisted && <button className="button tonal" onClick={requestPersistence}><Database size={17} />请求持久存储</button>}<p className="supporting">清除 Chrome/Edge 网站数据仍会删除 IndexedDB。网页无法拦截浏览器设置中的清除操作，请定期导出配置。</p></section>
      <section className="settings-card"><h2>配置备份</h2><p>`.dsdom.json` 包含积分组、任务、模板、草稿、设置和不可变历史，不包含任何 Sub 执行状态。</p><div className="button-row"><button className="button tonal" onClick={exportData}><Download size={17} />导出完整配置</button><button className="button tonal" onClick={() => fileRef.current?.click()}><Upload size={17} />导入配置</button><input ref={fileRef} hidden type="file" accept=".json,.dsdom.json,application/json" onChange={(event) => chooseFile(event.target.files?.[0])} /></div></section>
      <section className="settings-card"><h2><Keyboard size={20} />键盘操作</h2><div className="shortcut-list"><span><kbd>Ctrl</kbd> + <kbd>Enter</kbd><em>生成预览</em></span><span><kbd>Ctrl</kbd> + <kbd>K</kbd><em>搜索任务</em></span><span><kbd>Ctrl</kbd> + <kbd>N</kbd><em>新建任务</em></span><span><kbd>Esc</kbd><em>关闭弹窗</em></span></div></section>
      <section className="settings-card danger-zone"><h2>历史清理</h2><p>数据永不自动清理。只有你明确确认后，才会删除批次历史和任务版本。</p><button className="button text danger" onClick={clearHistory}><Trash2 size={17} />删除全部历史与版本</button></section>
    </div>
    {importState && <Modal title="导入配置预览" onClose={() => setImportState(null)} wide><div className="preview-stats"><span><strong>{importState.backup.tasks.length}</strong> 个任务</span><span><strong>{importState.backup.templates.length}</strong> 个模板</span><span><strong>{importState.backup.drafts.length}</strong> 个草稿</span><span><strong>{importState.conflicts.length}</strong> 个冲突</span></div><fieldset className="mode-choice"><legend>导入方式</legend><label><input type="radio" checked={importState.mode === "merge"} onChange={() => setImportState({ ...importState, mode: "merge" })} />合并：默认保留本地，可逐项使用导入版本</label><label><input type="radio" checked={importState.mode === "replace"} onChange={() => setImportState({ ...importState, mode: "replace" })} />完全替换：先清空本地配置</label></fieldset>{importState.mode === "merge" && <div className="conflict-list">{importState.conflicts.length === 0 ? <p>没有 ID 冲突，所有记录都将新增。</p> : importState.conflicts.map((conflict) => <label className="conflict-row" key={conflict.key}><div><strong>{conflict.label}</strong><small className="mono">{conflict.id}</small></div><span><input type="checkbox" checked={importState.useImported.has(conflict.key)} onChange={(event) => setImportState((current) => { if (!current) return current; const next = new Set(current.useImported); event.target.checked ? next.add(conflict.key) : next.delete(conflict.key); return { ...current, useImported: next }; })} />使用导入版本</span></label>)}</div>}<div className="modal-actions"><button className="button text" onClick={() => setImportState(null)}>取消</button><button className="button primary" onClick={applyImport}>{importState.mode === "replace" ? "确认完全替换" : "确认合并"}</button></div></Modal>}
  </div>;
}
