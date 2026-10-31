/** Presentation groups shared by task, exception and step editors. */
export const executionGroups = [
  { label: "基础执行", options: [[0, "直接完成"], [1, "完成指定次数"], [2, "完成指定时长"]] },
  { label: "信息采集", options: [[3, "填写信息告知"], [7, "单选积分"]] },
  { label: "健康记录", options: [[4, "心情记录"], [8, "体温询问"]] },
  { label: "通知", options: [[6, "阅读通知并确认"]] },
  { label: "组合任务", options: [[5, "分步骤"]] },
] as const;

export function ExecutionOptions({ includeSteps = false }: { includeSteps?: boolean }) {
  return <>{executionGroups.filter((group) => includeSteps || group.label !== "组合任务").map((group) =>
    <optgroup label={group.label} key={group.label}>{group.options.map(([kind, label]) =>
      <option key={kind} value={kind}>{label}</option>)}</optgroup>)}</>;
}
