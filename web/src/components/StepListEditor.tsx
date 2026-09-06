import { ChevronDown, ChevronUp, Plus, Trash2 } from "lucide-react";
import type { Dst1LeafExecution, Dst1Step } from "../protocol/types";
import { createTransportId, legacyStepId } from "../protocol/id";

interface Props { steps: Dst1Step[]; taskId: string; onChange: (steps: Dst1Step[]) => void; max?: number; }
const leaf = (step: Dst1Step): Dst1LeafExecution | undefined => step.u;
export function StepListEditor({ steps, taskId, onChange, max = 50 }: Props) {
  const stableSteps = steps.map((step, index) => step.i ? step : { ...step, i: legacyStepId(taskId, index) });
  const patch = (index: number, value: Partial<Dst1Step>) => onChange(stableSteps.map((step, itemIndex) => itemIndex === index ? { ...step, ...value } : step));
  const move = (index: number, delta: -1 | 1) => { const target = index + delta; if (target < 0 || target >= stableSteps.length) return; const next = [...stableSteps]; [next[index], next[target]] = [next[target], next[index]]; onChange(next); };
  const setExecution = (index: number, kind: number) => {
    const u = kind === 1 ? { k: 1 as const, a: 2 as const, v: 10 } : kind === 2 ? { k: 2 as const, v: 600 } : kind === 3 ? { k: 3 as const } : kind === 4 ? { k: 4 as const } : undefined;
    patch(index, { u });
  };
  return <div className="step-list-editor">
    {stableSteps.map((step, index) => <div className="step-editor-card" key={step.i}>
      <div className="step-row"><input value={step.n} maxLength={100} aria-label={`步骤 ${index + 1}`} onChange={(event) => patch(index, { n: event.target.value })} /><select value={step.r} aria-label="步骤属性" onChange={(event) => patch(index, { r: Number(event.target.value) as 0 | 1 })}><option value={1}>必需</option><option value={0}>选做</option></select><button type="button" className="icon-button" onClick={() => move(index, -1)} disabled={index === 0} aria-label="上移"><ChevronUp size={18} /></button><button type="button" className="icon-button" onClick={() => move(index, 1)} disabled={index === steps.length - 1} aria-label="下移"><ChevronDown size={18} /></button><button type="button" className="icon-button danger" onClick={() => onChange(stableSteps.filter((_, itemIndex) => itemIndex !== index))} aria-label="删除步骤"><Trash2 size={18} /></button></div>
      <StepExecution step={step} index={index} patch={patch} setExecution={setExecution} />
    </div>)}
    <button className="button tonal" type="button" onClick={() => onChange([...stableSteps, { i: createTransportId(), n: "", r: 1 }])} disabled={stableSteps.length >= max}><Plus size={17} />添加步骤</button>
  </div>;
}

function StepExecution({ step, index, patch, setExecution }: { step: Dst1Step; index: number; patch: (index: number, value: Partial<Dst1Step>) => void; setExecution: (index: number, kind: number) => void }) {
  const current = leaf(step);
  return <div className="step-execution-row"><select value={current?.k ?? 0} aria-label="步骤执行类型" onChange={(event) => setExecution(index, Number(event.target.value))}><option value={0}>直接完成</option><option value={1}>计数</option><option value={2}>计时</option><option value={3}>填写信息</option><option value={4}>心情记录</option></select>{current?.k === 1 && <><select value={current.a} aria-label="计数方式" onChange={(event) => patch(index, { u: { k: 1, a: Number(event.target.value) as 1 | 2, v: current.v } })}><option value={1}>拖动计数</option><option value={2}>点击计数</option></select><label>目标次数<input type="number" min={1} max={999} value={current.v} onChange={(event) => patch(index, { u: { k: 1, a: current.a, v: Number(event.target.value) } })} /></label></>}{current?.k === 2 && <label>目标秒数<input type="number" min={1} max={3600} value={current.v} onChange={(event) => patch(index, { u: { k: 2, v: Number(event.target.value) } })} /></label>}</div>;
}
