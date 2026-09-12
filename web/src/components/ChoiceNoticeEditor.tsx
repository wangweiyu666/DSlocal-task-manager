import type { Dst1Execution, Dst1LeafExecution } from "../protocol/types";
import { createTransportId } from "../protocol/id";

export function defaultLeafExecution(kind: number): Dst1LeafExecution | undefined {
  if (kind === 1) return { k: 1, a: 2, v: 10 };
  if (kind === 2) return { k: 2, v: 600 };
  if (kind === 3 || kind === 4) return { k: kind };
  if (kind === 6) return { k: 6, t: "" };
  if (kind === 7) return { k: 7, o: [1, 2].map((index) => ({ i: createTransportId(), n: `选项 ${index}`, p: 0 })) };
  return undefined;
}

export function ChoiceNoticeEditor({ execution, onChange }: { execution: Dst1Execution | null | undefined; onChange: (value: Dst1LeafExecution) => void }) {
  if (execution?.k === 6) return <label className="field span-2"><span>通知正文 *</span><textarea rows={4} value={execution.t} onChange={(event) => onChange({ k: 6, t: event.target.value })} /><small>执行者阅读后确认已知晓，最多 2000 个字符。</small></label>;
  if (execution?.k !== 7) return null;
  return <fieldset className="field span-2"><legend>单选选项与积分</legend>
    {execution.o.map((option, index) => <div className="step-row" key={option.i}>
      <label className="field"><span>选项 {index + 1}</span><input value={option.n} onChange={(event) => onChange({ k: 7, o: execution.o.map((item) => item.i === option.i ? { ...item, n: event.target.value } : item) })} /></label>
      <label className="field"><span>选项积分</span><input type="number" min={0} max={9999} value={option.p} onChange={(event) => onChange({ k: 7, o: execution.o.map((item) => item.i === option.i ? { ...item, p: Number(event.target.value) } : item) })} /></label>
      <button type="button" className="button text" disabled={index === 0} aria-label={`上移选项 ${index + 1}`} onClick={() => { const next = [...execution.o]; [next[index - 1], next[index]] = [next[index], next[index - 1]]; onChange({ k: 7, o: next }); }}>上移</button>
      <button type="button" className="button text danger" disabled={execution.o.length <= 2} onClick={() => onChange({ k: 7, o: execution.o.filter((item) => item.i !== option.i) })}>删除选项</button>
    </div>)}
    <button type="button" className="button tonal" disabled={execution.o.length >= 50} onClick={() => onChange({ k: 7, o: [...execution.o, { i: createTransportId(), n: "", p: 0 }] })}>添加选项</button>
    <small>单选；整项完成时结算基础积分＋已确认选项积分。每个选项 0～9999 分。</small>
  </fieldset>;
}
