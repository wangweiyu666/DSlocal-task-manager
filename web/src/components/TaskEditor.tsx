import { ChoiceNoticeEditor, defaultLeafExecution } from "./ChoiceNoticeEditor";
import { validateConditionalSteps, validateExecutionConfiguration } from "../../../shared/protocol/execution";
import { ChevronDown } from "lucide-react";
import { useState, type ReactNode } from "react";
import type { GroupRecord, TaskFields } from "../model/types";
import { normalizeEditableSteps } from "../model/steps";
import { StepListEditor } from "./StepListEditor";

export type EditableTask = TaskFields & { groupId: string | null; taskId?: string; timeZone?: string };

export function taskIssues(task: EditableTask): string[] {
  const issues: string[] = [];
  try { validateExecutionConfiguration(task.execution); validateConditionalSteps(task.steps); } catch (error) { issues.push(error instanceof Error ? error.message : "执行配置无效"); }
  if (!task.name.trim()) issues.push("请填写任务名称");
  if ([...task.name.trim()].length > 100) issues.push("任务名称不能超过 100 个字符");
  if ([...task.description].length > 2000) issues.push("描述不能超过 2000 个字符");
  if (task.points < 0 || task.points > 9999 || !Number.isInteger(task.points)) issues.push("积分必须是 0～9999 的整数");
  if (!task.recurrence && (task.deadlineMode === "date" || task.deadlineMode === "datetime") && !task.deadline) issues.push("请填写截止时间");
  if (!task.recurrence && task.reminders.length && !["date", "datetime"].includes(task.deadlineMode)) issues.push("设置提醒时必须填写明确截止时间");
  if (task.steps.length > 50 || task.steps.some((step) => !step.n.trim() || [...step.n].length > 100)) issues.push("步骤必须有名称，且名称最多 100 个字符，步骤最多 50 个");
  if (new Set(task.steps.map((step) => step.i).filter(Boolean)).size !== task.steps.filter((step) => step.i).length) issues.push("步骤 ID 必须唯一");
  for (const step of task.steps) {
    if (step.u?.k === 1 && (!Number.isInteger(step.u.v) || step.u.v < 1 || step.u.v > 999)) issues.push("步骤计数目标必须是 1～999 的整数");
    if (step.u?.k === 2 && (!Number.isInteger(step.u.v) || step.u.v < 1 || step.u.v > 3600)) issues.push("步骤计时目标必须是 1～3600 秒的整数");
  }
  if (task.execution?.k === 1 && (task.execution.v < 1 || task.execution.v > 999)) issues.push("计数目标必须是 1～999");
  if (task.execution?.k === 2 && (task.execution.v < 1 || task.execution.v > 3600)) issues.push("计时目标必须是 1～3600 秒");
  if (task.recurrence?.f === 2 && !task.recurrence.w?.length) issues.push("每周重复至少选择一个星期");
  if (task.recurrence?.e && task.recurrence?.c) issues.push("重复结束日期和次数只能选择一种");
  return issues;
}

interface TaskEditorProps {
  value: EditableTask;
  groups: GroupRecord[];
  onChange: (value: EditableTask) => void;
  allowKindChange?: boolean;
  advancedContent?: ReactNode;
  timeZone?: string;
}

export function TaskEditor({ value, groups, onChange, allowKindChange = false, advancedContent, timeZone }: TaskEditorProps) {
  const [customReminder, setCustomReminder] = useState("10");
  const [advancedOpen, setAdvancedOpen] = useState(
    value.steps.length > 0 || value.order !== null || value.reminders.length > 0 || value.completionMessage !== "",
  );
  const patch = (next: Partial<EditableTask>) => {
    const merged = { ...value, ...next };
    const normalized = normalizeEditableSteps(merged.taskId, merged.name, merged.steps, merged.execution);
    merged.steps = normalized.steps;
    merged.execution = normalized.execution;
    onChange(merged);
  };
  const recurring = value.recurrence !== null;
  const defaultDateLabel = (() => { try { return new Intl.DateTimeFormat("zh-CN", { timeZone: timeZone || value.timeZone || Intl.DateTimeFormat().resolvedOptions().timeZone, year: "numeric", month: "numeric", day: "numeric" }).format(new Date()); } catch { return "今天"; } })();
  const recurrenceDeadlineMode = value.recurrence?.t === null ? "none" : typeof value.recurrence?.t === "string" ? "time" : "default";
  const moveStep = (index: number, direction: -1 | 1) => {
    const next = [...value.steps];
    const target = index + direction;
    if (target < 0 || target >= next.length) return;
    [next[index], next[target]] = [next[target], next[index]];
    patch({ steps: next });
  };

  return <div className="task-editor">
    <div className={`task-kind-summary ${recurring ? "recurring" : "temporary"}`}>
      {allowKindChange ? <label className="field"><span>任务类型</span><select value={recurring ? "recurring" : "temporary"} onChange={(event) => patch(event.target.value === "recurring" ? { recurrence: { f: 1 }, taskDate: "", taskDateIntent: "set", deadlineMode: "default", deadline: "", reminders: [] } : { recurrence: null, taskDateIntent: "set" })}><option value="temporary">临时任务</option><option value="recurring">重复任务</option></select></label> : <><strong>{recurring ? "重复任务" : "临时任务"}</strong><span>{recurring ? "按照下方计划持续生成任务实例" : "只生成一个独立任务实例"}</span></>}
    </div>

    <section className="form-section"><h3>基本信息</h3><div className="form-grid">
      <label className="field span-2"><span>任务名称 *</span><input value={value.name} maxLength={100} onChange={(event) => patch({ name: event.target.value })} placeholder={recurring ? "例如：每日完成训练" : "例如：完成今日训练"} /></label>
      <label className="field"><span>积分组</span><select value={value.groupId ?? ""} onChange={(event) => patch({ groupId: event.target.value || null })}><option value="">未分组</option>{groups.map((group) => <option key={group.id} value={group.id}>{group.name}</option>)}</select></label>
      <label className="field"><span>任务属性</span><select value={value.required ? "required" : "optional"} onChange={(event) => patch({ required: event.target.value === "required" })}><option value="required">必做</option><option value="optional">选做</option></select></label>
      <label className="field span-2"><span>描述 / 告知要求</span><textarea value={value.description} maxLength={2000} rows={3} onChange={(event) => patch({ description: event.target.value })} /></label>
    </div></section>

    {recurring ? <section className="form-section"><h3>重复计划与积分</h3><div className="form-grid">
      <label className="field"><span>重复频率</span><select value={value.recurrence!.f} onChange={(event) => { const frequency = Number(event.target.value); patch({ recurrence: frequency === 1 ? { ...value.recurrence!, f: 1, w: undefined } : { ...value.recurrence!, f: 2, w: value.recurrence!.w?.length ? value.recurrence!.w : [1] } }); }}><option value={1}>每天</option><option value={2}>每周</option></select></label>
      <label className="field"><span>开始日期（留空默认今天（{defaultDateLabel}））</span><input type="date" lang="en-CA" value={value.recurrence!.s ?? ""} onChange={(event) => patch({ recurrence: { ...value.recurrence!, s: event.target.value || undefined }, taskDateIntent: event.target.value ? "set" : "clear" })} /></label>
      {value.recurrence!.f === 2 && <fieldset className="weekday-field span-2"><legend>执行星期</legend>{["一", "二", "三", "四", "五", "六", "日"].map((label, index) => { const day = index + 1; const checked = value.recurrence!.w?.includes(day) ?? false; return <label key={day}><input type="checkbox" checked={checked} onChange={(event) => patch({ recurrence: { ...value.recurrence!, w: event.target.checked ? [...(value.recurrence!.w ?? []), day].sort() : value.recurrence!.w?.filter((item) => item !== day) } })} />周{label}</label>; })}</fieldset>}
      <label className="field"><span>结束方式</span><select value={value.recurrence!.e ? "date" : value.recurrence!.c ? "count" : "none"} onChange={(event) => patch({ recurrence: { ...value.recurrence!, e: event.target.value === "date" ? new Date().toISOString().slice(0, 10) : undefined, c: event.target.value === "count" ? 1 : undefined } })}><option value="none">长期重复</option><option value="date">截止到日期</option><option value="count">生成指定次数</option></select></label>
      {value.recurrence!.e !== undefined && <label className="field"><span>重复截止日期</span><input type="date" lang="en-CA" value={value.recurrence!.e} onChange={(event) => patch({ recurrence: { ...value.recurrence!, e: event.target.value } })} /></label>}
      {value.recurrence!.c !== undefined && <label className="field"><span>生成次数</span><input type="number" min={1} value={value.recurrence!.c} onChange={(event) => patch({ recurrence: { ...value.recurrence!, c: Number(event.target.value) } })} /></label>}
      <label className="field"><span>每次截止方式</span><select value={recurrenceDeadlineMode} onChange={(event) => patch({ recurrence: { ...value.recurrence!, t: event.target.value === "none" ? null : event.target.value === "time" ? (typeof value.recurrence!.t === "string" ? value.recurrence!.t : "20:00") : undefined } })}><option value="default">计划日次日 04:00</option><option value="time">计划日指定时间</option><option value="none">永不截止</option></select></label>
      {recurrenceDeadlineMode === "time" && <label className="field"><span>每次截止时间</span><input type="time" value={typeof value.recurrence!.t === "string" ? value.recurrence!.t : "20:00"} onChange={(event) => patch({ recurrence: { ...value.recurrence!, t: event.target.value } })} /></label>}
      <label className="field"><span>每次积分</span><input type="number" min={0} max={9999} value={value.points} onChange={(event) => patch({ points: Number(event.target.value) })} /></label>
    </div></section> : <section className="form-section"><h3>日期、截止与积分</h3><div className="form-grid">
      <label className="field"><span>归属日期（留空默认今天（{defaultDateLabel}））</span><input type="date" lang="en-CA" value={value.taskDate} onChange={(event) => patch({ taskDate: event.target.value, taskDateIntent: event.target.value ? "set" : "clear" })} /></label>
      <label className="field"><span>截止方式</span><select value={value.deadlineMode} onChange={(event) => patch({ deadlineMode: event.target.value as TaskFields["deadlineMode"], deadline: "", reminders: ["date", "datetime"].includes(event.target.value) ? value.reminders : [] })}><option value="default">任务日次日 04:00</option><option value="date">指定日期次日 04:00</option><option value="datetime">精确到分钟</option><option value="none">永不截止</option></select></label>
      {value.deadlineMode === "date" && <label className="field"><span>截止日期</span><input type="date" lang="en-CA" value={value.deadline} onChange={(event) => patch({ deadline: event.target.value })} /></label>}
      {value.deadlineMode === "datetime" && <label className="field"><span>截止时间</span><input type="datetime-local" lang="en-CA" value={value.deadline} onChange={(event) => patch({ deadline: event.target.value })} /></label>}
      <label className="field"><span>积分</span><input type="number" min={0} max={9999} value={value.points} onChange={(event) => patch({ points: Number(event.target.value) })} /></label>
    </div></section>}

    <section className="form-section"><h3>完成方式</h3><div className="form-grid">
      {value.steps.length > 0 ? <p className="supporting span-2">已启用分步骤执行。任务级完成方式已隐藏；请在每个步骤中选择执行类型。</p> : <label className="field"><span>执行类型</span><select value={value.execution?.k ?? 0} onChange={(event) => { const kind = Number(event.target.value); patch({ name: kind === 4 && !value.name.trim() ? "今天的心情怎么样" : value.name, execution: defaultLeafExecution(kind) ?? null }); }}><option value={0}>直接完成</option><option value={1}>完成指定次数</option><option value={2}>完成指定时长</option><option value={3}>填写信息告知</option><option value={4}>心情记录</option><option value={6}>阅读通知并确认</option><option value={7}>单选积分</option></select></label>}
      <ChoiceNoticeEditor execution={value.execution} onChange={(execution) => patch({ execution })} />
      {value.execution?.k === 4 && <p className="supporting span-2">执行者通过五档滑动条选择心情，可选填感受后主动完成。心情好坏不影响积分。需要更新到支持心情记录的 Android 版本。</p>}
      {value.execution?.k === 1 && <><label className="field"><span>计数方式</span><select value={value.execution.a} onChange={(event) => patch({ execution: { ...value.execution as Extract<NonNullable<TaskFields["execution"]>, { k: 1 }>, a: Number(event.target.value) as 1 | 2 } })}><option value={1}>拖动计数条</option><option value={2}>点击计数</option></select></label><label className="field"><span>目标次数</span><input type="number" min={1} max={999} value={value.execution.v} onChange={(event) => patch({ execution: { ...value.execution as Extract<NonNullable<TaskFields["execution"]>, { k: 1 }>, v: Number(event.target.value) } })} /></label></>}
      {value.execution?.k === 2 && <label className="field"><span>目标秒数</span><input type="number" min={1} max={3600} value={value.execution.v} onChange={(event) => patch({ execution: { k: 2, v: Number(event.target.value) } })} /></label>}
      {value.execution?.k === 3 && <p className="supporting span-2">Sub 必须填写告知正文并主动完成；上方描述作为填写要求。</p>}
    </div></section>

    <details className="form-section collapsible-section" open={advancedOpen} onToggle={(event) => setAdvancedOpen(event.currentTarget.open)}>
      <summary><span><strong>高级设置</strong><small>{recurring ? `步骤、排序、完成提示${advancedContent ? "与执行者" : ""}` : `步骤、排序、提醒、完成提示${advancedContent ? "与执行者" : ""}`}</small></span><ChevronDown size={19} /></summary>
      <div className="advanced-content">
        {advancedContent}
        <div className="section-heading"><h3>步骤</h3></div>
        {value.steps.length === 0 && <p className="empty-inline">无步骤；任务可直接按上方完成方式执行。</p>}
        <StepListEditor steps={value.steps} taskId={value.taskId ?? "0000000000000000"} onChange={(steps) => patch({ steps })} />
        <div className="form-grid advanced-fields">
          <label className="field"><span>人工排序（可留空）</span><input type="number" value={value.order ?? ""} onChange={(event) => patch({ order: event.target.value === "" ? null : Number(event.target.value) })} /></label>
          <label className="field span-2"><span>完成提示</span><textarea value={value.completionMessage} maxLength={500} rows={2} onChange={(event) => patch({ completionMessage: event.target.value })} placeholder="留空使用系统默认提示" /></label>
          {!recurring && <div className="field span-2"><span>提前提醒（分钟，最多 5 个）</span><div className="chip-row">{value.reminders.map((minutes) => <button className="chip selected" key={minutes} onClick={() => patch({ reminders: value.reminders.filter((item) => item !== minutes) })}>{minutes === 0 ? "截止时" : `${minutes} 分钟前`} ×</button>)}</div>
            <div className="inline-controls"><input type="number" min={0} max={10080} value={customReminder} onChange={(event) => setCustomReminder(event.target.value)} /><button className="button tonal" type="button" disabled={value.reminders.length >= 5 || !["date", "datetime"].includes(value.deadlineMode)} onClick={() => { const minutes = Number(customReminder); if (Number.isInteger(minutes) && minutes >= 0 && minutes <= 10080 && !value.reminders.includes(minutes)) patch({ reminders: [...value.reminders, minutes].sort((a, b) => b - a) }); }}>添加提醒</button></div>
            {!["date", "datetime"].includes(value.deadlineMode) && <small>设置明确截止时间后才能添加提醒。</small>}
          </div>}
        </div>
      </div>
    </details>
    {taskIssues(value).length > 0 && <div className="validation-box"><strong>仍需处理</strong><ul>{taskIssues(value).map((issue) => <li key={issue}>{issue}</li>)}</ul></div>}
  </div>;
}
