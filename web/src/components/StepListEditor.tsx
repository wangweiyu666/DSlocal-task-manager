import { useState } from "react";
import { ChoiceNoticeEditor, defaultLeafExecution } from "./ChoiceNoticeEditor";
import { ChevronDown, ChevronUp, Plus, Trash2 } from "lucide-react";
import type { Dst1LeafExecution, Dst1Step } from "../protocol/types";
import { createTransportId, legacyStepId } from "../protocol/id";

interface Props { steps: Dst1Step[]; taskId: string; onChange: (steps: Dst1Step[]) => void; max?: number; }
const leaf = (step: Dst1Step): Dst1LeafExecution | undefined => step.u;
export function StepListEditor({ steps, taskId, onChange, max = 50 }: Props) {
  const [error, setError] = useState("");
  const stableSteps = steps.map((step, index) => step.i ? step : { ...step, i: legacyStepId(taskId, index) });
  const commit = (next: Dst1Step[]) => {
    // Editing text may be temporarily invalid; never silently remove a condition.
    for (const [index, step] of next.entries()) {
      if (step.c) { const source = next.slice(0, index).find((item) => item.i === step.c!.s);
        if (source?.u?.k !== 7 || !source.u.o.some((option) => option.i === step.c!.o)) { setError("请先修正关联条件，再删除、移动或修改被引用的选项步骤。"); return; }
      }
    }
    setError(""); onChange(next);
  };
  const patch = (index: number, value: Partial<Dst1Step>) => commit(stableSteps.map((step, itemIndex) => itemIndex === index ? { ...step, ...value } : step));
  const move = (index: number, delta: -1 | 1) => { const target = index + delta; if (target < 0 || target >= stableSteps.length) return; const next = [...stableSteps]; [next[index], next[target]] = [next[target], next[index]]; commit(next); };
  const setExecution = (index: number, kind: number) => {
    const u = defaultLeafExecution(kind);
    patch(index, { u });
  };
  return <div className="step-list-editor">
    {stableSteps.map((step, index) => <div className="step-editor-card" key={step.i}>
      <div className="step-row"><input value={step.n} maxLength={100} aria-label={`步骤 ${index + 1}`} onChange={(event) => patch(index, { n: event.target.value })} /><select value={step.r} aria-label="步骤属性" onChange={(event) => patch(index, { r: Number(event.target.value) as 0 | 1 })}><option value={1}>必需</option><option value={0}>选做</option></select><button type="button" className="icon-button" onClick={() => move(index, -1)} disabled={index === 0} aria-label="上移"><ChevronUp size={18} /></button><button type="button" className="icon-button" onClick={() => move(index, 1)} disabled={index === steps.length - 1} aria-label="下移"><ChevronDown size={18} /></button><button type="button" className="icon-button danger" onClick={() => commit(stableSteps.filter((_, itemIndex) => itemIndex !== index))} aria-label="删除步骤"><Trash2 size={18} /></button></div>
      <StepExecution step={step} index={index} patch={patch} setExecution={setExecution} />
      <ChoiceNoticeEditor execution={step.u} onChange={(u) => patch(index, { u })} />
      <label className="field"><span>执行条件</span><select aria-label={`步骤 ${index + 1} 执行条件`} value={step.c ? `${step.c.s}:${step.c.o}` : ""} onChange={(event) => { const [s, o] = event.target.value.split(":"); patch(index, { c: s ? { s, o } : undefined }); }}>
        <option value="">始终执行</option>{stableSteps.slice(0, index).flatMap((source) => source.u?.k === 7 ? source.u.o.map((option) => <option key={`${source.i}:${option.i}`} value={`${source.i}:${option.i}`}>当「{source.n}」选择「{option.n}」</option>) : [])}
      </select></label>
    </div>)}
    {error && <p role="alert" className="validation-box">{error}</p>}
    <button className="button tonal" type="button" onClick={() => onChange([...stableSteps, { i: createTransportId(), n: "", r: 1 }])} disabled={stableSteps.length >= max}><Plus size={17} />添加步骤</button>
  </div>;
}

function StepExecution({ step, index, patch, setExecution }: { step: Dst1Step; index: number; patch: (index: number, value: Partial<Dst1Step>) => void; setExecution: (index: number, kind: number) => void }) {
  const current = leaf(step);
  return <div className="step-execution-row"><select value={current?.k ?? 0} aria-label="步骤执行类型" onChange={(event) => setExecution(index, Number(event.target.value))}><option value={0}>直接完成</option><option value={1}>计数</option><option value={2}>计时</option><option value={3}>填写信息</option><option value={4}>心情记录</option><option value={6}>通知确认</option><option value={7}>单选积分</option></select>{current?.k === 1 && <><select value={current.a} aria-label="计数方式" onChange={(event) => patch(index, { u: { k: 1, a: Number(event.target.value) as 1 | 2, v: current.v } })}><option value={1}>拖动计数</option><option value={2}>点击计数</option></select><label>目标次数<input type="number" min={1} max={999} value={current.v} onChange={(event) => patch(index, { u: { k: 1, a: current.a, v: Number(event.target.value) } })} /></label></>}{current?.k === 2 && <label>目标秒数<input type="number" min={1} max={3600} value={current.v} onChange={(event) => patch(index, { u: { k: 2, v: Number(event.target.value) } })} /></label>}</div>;
}
