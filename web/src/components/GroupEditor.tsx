import type { GroupRecord } from "../model/types";

interface GroupEditorProps {
  value: GroupRecord;
  onChange: (value: GroupRecord) => void;
}

export function GroupEditor({ value, onChange }: GroupEditorProps) {
  const patch = (next: Partial<GroupRecord>) => onChange({ ...value, ...next });
  return <div className="form-grid">
    <label className="field span-2"><span>名称 *</span><input value={value.name} maxLength={50} onChange={(event) => patch({ name: event.target.value })} /></label>
    <label className="field span-2"><span>全部完成文案</span><textarea rows={3} maxLength={500} value={value.completeMessage} onChange={(event) => patch({ completeMessage: event.target.value })} /></label>
    <label className="field span-2"><span>未全部完成文案</span><textarea rows={3} maxLength={500} value={value.incompleteMessage} onChange={(event) => patch({ incompleteMessage: event.target.value })} /></label>
    <label className="field span-2"><span>groupId</span><input className="mono" readOnly value={value.id} /></label>
  </div>;
}
