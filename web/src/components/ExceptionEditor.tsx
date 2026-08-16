import { useMemo, useState } from "react";
import type { TaskRecord } from "../model/types";
import type { Dst11Exception, Dst1Execution, Dst1Step } from "../protocol/types";

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
  const [stepsText, setStepsText] = useState((initial?.s ?? task.steps).map((step) => `${step.r}:${step.n}`).join("\n"));
  const [messageMode, setMessageMode] = useState<"value" | "default">(initial?.m === null ? "default" : "value");
  const [message, setMessage] = useState(typeof initial?.m === "string" ? initial.m : task.completionMessage);
  const [remindersText, setRemindersText] = useState((initial?.h ?? task.reminders).join(", "));
  const initialExecution = initial && "u" in initial ? initial.u : task.execution;
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
      const steps: Dst1Step[] = stepsText.split("\n").map((line) => line.trim()).filter(Boolean).map((line) => {
        const match = /^([01]):(.*)$/u.exec(line); return { r: match?.[1] === "0" ? 0 : 1, n: (match?.[2] ?? line).trim().normalize("NFC") };
      });
      if (steps.length > 50 || steps.some((step) => !step.n)) { setError("步骤必须有名称，且最多 50 个"); return; }
      value.s = steps;
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
      value.u = execution;
    }
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
      {selected.has("s") && <label className="field"><span>当天步骤（每行一个；可写 0:选做步骤）</span><textarea rows={5} value={stepsText} onChange={(event) => setStepsText(event.target.value)} /></label>}
      {selected.has("m") && <div className="form-grid"><label className="field"><span>完成提示方式</span><select value={messageMode} onChange={(event) => setMessageMode(event.target.value as "value" | "default")}><option value="value">指定提示</option><option value="default">系统默认</option></select></label>{messageMode === "value" && <label className="field"><span>完成提示</span><input maxLength={500} value={message} onChange={(event) => setMessage(event.target.value)} /></label>}</div>}
      {selected.has("h") && <label className="field"><span>提醒分钟数（逗号分隔，留空清除）</span><input value={remindersText} onChange={(event) => setRemindersText(event.target.value)} /></label>}
      {selected.has("u") && <div className="form-grid"><label className="field"><span>执行方式</span><select value={executionKind} onChange={(event) => setExecutionKind(Number(event.target.value))}><option value={0}>普通完成</option><option value={1}>计数</option><option value={2}>计时</option><option value={3}>信息告知</option></select></label>{executionKind === 1 && <label className="field"><span>计数方式</span><select value={executionAction} onChange={(event) => setExecutionAction(Number(event.target.value) as 1 | 2)}><option value={1}>拖动</option><option value={2}>点击</option></select></label>}{(executionKind === 1 || executionKind === 2) && <label className="field"><span>目标值</span><input type="number" value={executionTarget} onChange={(event) => setExecutionTarget(Number(event.target.value))} /></label>}</div>}
    </>}
    {error && <div className="validation-box">{error}</div>}
    <div className="modal-actions"><button className="button text" onClick={onCancel}>取消</button><button className="button primary" onClick={save}>保存并加入草稿</button></div>
  </div>;
}
