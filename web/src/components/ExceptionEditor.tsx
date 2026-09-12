import { ChoiceNoticeEditor, defaultLeafExecution } from "./ChoiceNoticeEditor";
import { validateConditionalSteps, validateExecutionConfiguration } from "../../../shared/protocol/execution";
import { useMemo, useState } from "react";
import type { TaskRecord } from "../model/types";
import type { Dst11Exception, Dst1Execution, Dst1Step } from "../protocol/types";
import { StepListEditor } from "./StepListEditor";
import { normalizeEditableSteps } from "../model/steps";

type OverrideKey = "n" | "r" | "d" | "l" | "p" | "o" | "s" | "m" | "h" | "u";

interface ExceptionEditorProps {
  task: TaskRecord;
  initial?: Dst11Exception;
  onSave: (value: Dst11Exception) => void;
  onCancel: () => void;
}

const labels: Record<OverrideKey, string> = {
  n: "名称", r: "必做/选做", d: "描述", l: "截止时间", p: "积分", o: "人工排序",
  s: "步骤", m: "完成提示", h: "提醒", u: "执行方式"
};

export function ExceptionEditor({ task, initial, onSave, onCancel }: ExceptionEditorProps) {
  const initialKeys = useMemo(() => new Set<OverrideKey>(Object.keys(initial ?? {}).filter((key): key is OverrideKey => key in labels)), [initial]);
  const [date, setDate] = useState(initial?.y ?? "");
  const [cancelled, setCancelled] = useState(initial?.c === 1);
  const [selected, setSelected] = useState(initialKeys);
  const [name, setName] = useState(initial?.n ?? task.name);
  const [required, setRequired] = useState((initial?.r ?? (task.required ? 1 : 0)) === 1);
  const [description, setDescription] = useState(initial?.d ?? task.description);
  const [deadlineMode, setDeadlineMode] = useState<"datetime" | "none">(initial?.l === null ? "none" : "datetime");
  const [deadline, setDeadline] = useState(typeof initial?.l === "string" ? initial.l : "");
  const [points, setPoints] = useState(initial?.p ?? task.points);
  const [orderMode, setOrderMode] = useState<"value" | "default">(initial?.o === null ? "default" : "value");
  const [order, setOrder] = useState(initial?.o ?? task.order ?? 0);
  const normalizedInitial = normalizeEditableSteps(task.id, task.name, initial?.s ?? task.steps, (initial && "u" in initial ? initial.u : task.execution) ?? null);
  const initialSteps = normalizedInitial.steps;
  const [steps, setSteps] = useState<Dst1Step[]>(initialSteps);
  const [messageMode, setMessageMode] = useState<"value" | "default">(initial?.m === null ? "default" : "value");
  const [message, setMessage] = useState(typeof initial?.m === "string" ? initial.m : task.completionMessage);
  const [remindersText, setRemindersText] = useState((initial?.h ?? task.reminders).join(", "));
  const initialExecution = normalizedInitial.execution;
  const [specialExecution, setSpecialExecution] = useState<Dst1Execution | null>(initialExecution ?? null);
  const [executionKind, setExecutionKind] = useState(initialExecution?.k ?? 0);
  const [executionAction, setExecutionAction] = useState(initialExecution?.k === 1 ? initialExecution.a : 2);
  const [executionTarget, setExecutionTarget] = useState(initialExecution?.k === 1 || initialExecution?.k === 2 ? initialExecution.v : 10);
  const [error, setError] = useState("");

  const toggle = (key: OverrideKey) => setSelected((current) => {
    const next = new Set(current); next.has(key) ? next.delete(key) : next.add(key); return next;
  });

  const save = () => {
    if (!/^\d{4}-\d{2}-\d{2}$/u.test(date)) { setError("请选择计划日期"); return; }
    if (cancelled) { onSave({ i: task.id, y: date, c: 1 }); return; }
    const value: Dst11Exception = { i: task.id, y: date };
    const effectiveSteps = selected.has("s") ? steps : task.steps;
    if (selected.has("n")) { if (!name.trim()) { setError("覆盖名称不能为空"); return; } value.n = name.trim().normalize("NFC"); }
    if (selected.has("r")) value.r = required ? 1 : 0;
    if (selected.has("d")) value.d = description.trim().normalize("NFC");
    if (selected.has("l")) {
      if (deadlineMode === "none") value.l = null;
      else if (!/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d$/u.test(deadline)) { setError("请填写完整截止时间"); return; }
      else value.l = deadline;
    }
    if (selected.has("p")) { if (!Number.isInteger(points) || points < 0 || points > 9999) { setError("积分必须是 0～9999 的整数"); return; } value.p = points; }
    if (selected.has("o")) value.o = orderMode === "default" ? null : order;
    if (selected.has("s")) {
      if (steps.length > 50 || steps.some((step) => !step.n)) { setError("步骤必须有名称，且最多 50 个"); return; }
      if (steps.some((step) => !step.i || !/^[A-Za-z0-9_-]{16}$/u.test(step.i)) || new Set(steps.map((step) => step.i)).size !== steps.length) { setError("步骤 ID 必须是唯一的 16 位协议 ID"); return; }
      if (steps.some((step) => step.u?.k === 1 && (!Number.isInteger(step.u.v) || step.u.v < 1 || step.u.v > 999) || step.u?.k === 2 && (!Number.isInteger(step.u.v) || step.u.v < 1 || step.u.v > 3600))) { setError("步骤计数/计时目标无效"); return; }
      value.s = steps;
      value.u = steps.length ? { k: 5 } : null;
    }
    if (selected.has("m")) value.m = messageMode === "default" ? null : message.trim().normalize("NFC");
    if (selected.has("h")) {
      const reminders = remindersText.split(/[\s,，]+/u).filter(Boolean).map(Number);
      if (reminders.some((item) => !Number.isInteger(item) || item < 0 || item > 10080) || new Set(reminders).size !== reminders.length || reminders.length > 5) { setError("提醒必须是 0～10080 的不重复整数，最多 5 个"); return; }
      value.h = reminders.sort((a, b) => b - a);
    }
    if (selected.has("u")) {
      let execution: Dst1Execution | null = null;
      if (executionKind === 1) execution = { k: 1, a: executionAction, v: executionTarget };
      else if (executionKind === 2) execution = { k: 2, v: executionTarget };
      else if (executionKind === 3) execution = { k: 3 };
      else if (executionKind === 4) execution = { k: 4 };
      else if (executionKind === 5) execution = { k: 5 };
      else if (executionKind === 6 || executionKind === 7) execution = specialExecution;
      if (selected.has("s") && steps.length > 0) {
        value.s = steps;
        value.u = { k: 5 };
      } else if (selected.has("s")) {
        value.s = [];
        value.u = executionKind === 5 ? null : execution;
      } else {
        if (executionKind === 5 && effectiveSteps.length === 0) { setError("分步骤执行必须包含至少一个步骤"); return; }
        value.u = execution;
      }
      if (executionKind !== 5 && task.steps.length > 0 && !selected.has("s")) value.s = [];
    }
    try { validateExecutionConfiguration(value.u === undefined ? task.execution : value.u); validateConditionalSteps(value.s ?? task.steps); } catch (error) { setError(error instanceof Error ? error.message : "执行配置无效"); return; }
    onSave(value);
  };

  return <div className="task-editor">
    <div className="form-grid">
      <label className="field"><span>计划日期 *</span><input type="date" lang="en-CA" value={date} onChange={(event) => { setDate(event.target.value); if (!deadline && event.target.value) setDeadline(`${event.target.value}T20:00`); }} /></label>
      <label className="small-check"><input type="checkbox" checked={cancelled} onChange={(event) => setCancelled(event.target.checked)} />撤销当天任务</label>
    </div>
    {!cancelled && <>
      <p className="supporting">勾选需要覆盖的字段；未勾选字段继承 Sub 当前重复模板。全部不勾选表示恢复模板。</p>
      <div className="chip-row">{(Object.keys(labels) as OverrideKey[]).map((key) => <button type="button" className={`chip ${selected.has(key) ? "selected" : ""}`} key={key} onClick={() => toggle(key)}>{labels[key]}</button>)}</div>
      {selected.has("n") && <label className="field"><span>当天名称</span><input maxLength={100} value={name} onChange={(event) => setName(event.target.value)} /></label>}
      {selected.has("r") && <label className="field"><span>当天属性</span><select value={required ? "1" : "0"} onChange={(event) => setRequired(event.target.value === "1")}><option value="1">必做</option><option value="0">选做</option></select></label>}
      {selected.has("d") && <label className="field"><span>当天描述</span><textarea maxLength={2000} rows={3} value={description} onChange={(event) => setDescription(event.target.value)} /></label>}
      {selected.has("l") && <div className="form-grid"><label className="field"><span>截止方式</span><select value={deadlineMode} onChange={(event) => setDeadlineMode(event.target.value as "datetime" | "none")}><option value="datetime">指定时间</option><option value="none">永不截止</option></select></label>{deadlineMode === "datetime" && <label className="field"><span>截止时间</span><input type="datetime-local" value={deadline} onChange={(event) => setDeadline(event.target.value)} /></label>}</div>}
      {selected.has("p") && <label className="field"><span>当天积分</span><input type="number" min={0} max={9999} value={points} onChange={(event) => setPoints(Number(event.target.value))} /></label>}
      {selected.has("o") && <div className="form-grid"><label className="field"><span>排序方式</span><select value={orderMode} onChange={(event) => setOrderMode(event.target.value as "value" | "default")}><option value="value">指定排序</option><option value="default">默认排序</option></select></label>{orderMode === "value" && <label className="field"><span>排序值</span><input type="number" value={order} onChange={(event) => setOrder(Number(event.target.value))} /></label>}</div>}
      {selected.has("s") && <div className="field"><span>当天步骤</span><StepListEditor steps={steps} taskId={task.id} onChange={setSteps} /></div>}
      {selected.has("m") && <div className="form-grid"><label className="field"><span>完成提示方式</span><select value={messageMode} onChange={(event) => setMessageMode(event.target.value as "value" | "default")}><option value="value">指定提示</option><option value="default">系统默认</option></select></label>{messageMode === "value" && <label className="field"><span>完成提示</span><input maxLength={500} value={message} onChange={(event) => setMessage(event.target.value)} /></label>}</div>}
      {selected.has("h") && <label className="field"><span>提醒分钟数（逗号分隔，留空清除）</span><input value={remindersText} onChange={(event) => setRemindersText(event.target.value)} /></label>}
      {selected.has("u") && !(selected.has("s") && steps.length > 0) && <div className="form-grid"><label className="field"><span>执行方式</span><select value={executionKind} onChange={(event) => { const kind = Number(event.target.value); setExecutionKind(kind); setSpecialExecution(defaultLeafExecution(kind) ?? null); }}><option value={0}>普通完成</option><option value={1}>计数</option><option value={2}>计时</option><option value={3}>信息告知</option><option value={4}>心情记录</option><option value={5}>分步骤</option><option value={6}>通知确认</option><option value={7}>单选积分</option></select></label>{executionKind === 1 && <label className="field"><span>计数方式</span><select value={executionAction} onChange={(event) => setExecutionAction(Number(event.target.value) as 1 | 2)}><option value={1}>拖动</option><option value={2}>点击</option></select></label>}{(executionKind === 1 || executionKind === 2) && <label className="field"><span>目标值</span><input type="number" value={executionTarget} onChange={(event) => setExecutionTarget(Number(event.target.value))} /></label>}</div>}
    </>}
    {selected.has("u") && (executionKind === 6 || executionKind === 7) && !(selected.has("s") && steps.length > 0) && <ChoiceNoticeEditor execution={specialExecution} onChange={setSpecialExecution} />}
    {selected.has("u") && executionKind === 4 && !cancelled && <p className="supporting">需要更新到支持心情记录的 Android 版本。</p>}
    {error && <div className="validation-box">{error}</div>}
    <div className="modal-actions"><button className="button text" onClick={onCancel}>取消</button><button className="button primary" onClick={save}>保存并加入草稿</button></div>
  </div>;
}
