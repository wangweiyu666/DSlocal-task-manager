import { useEffect, useMemo, useRef, useState } from "react";
import type { CloudEntity, SpaceMember } from "./types";
import { filterTemperatures, formatTemperature, temperatureRange, temperatureRecords, temperatureSummary, type TemperatureRecord } from "./temperature";

const colors = ["#2563eb", "#b45309", "#7c3aed", "#047857", "#be185d", "#0e7490"];
const seriesLabel = (item: TemperatureRecord) => `${item.taskName}${item.stepName ? ` · ${item.stepName}` : ""}`;
const timeLabel = (time: string, timeZone: string) => new Intl.DateTimeFormat("zh-CN", { timeZone, month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(time));

export function TemperatureChart({ records, timeZone }: { records: TemperatureRecord[]; timeZone: string }) {
  const [activeId, setActiveId] = useState<string | null>(null);
  const plot = useRef<SVGSVGElement>(null);
  const [width, setWidth] = useState(900);
  useEffect(() => {
    const element = plot.current;
    if (!element) return;
    const measure = () => { const value = element.getBoundingClientRect().width; if (value > 0) setWidth(Math.max(240, value)); };
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [records.length]);
  const series = [...new Set(records.map((item) => item.seriesId))];
  if (!records.length) return <div className="empty-state"><h2>暂无体温记录</h2><p>所选范围内还没有已采用的体温完成结果。</p></div>;
  const first = Date.parse(records[0].occurredAt), last = Date.parse(records.at(-1)!.occurredAt);
  const x = (item: TemperatureRecord) => first === last ? width / 2 : 54 + (Date.parse(item.occurredAt) - first) / (last - first) * (width - 78);
  const y = (item: TemperatureRecord) => 256 - (item.temperatureTenthsC - 350) / 70 * 224;
  const active = records.find((item) => item.id === activeId);
  return <div className="temperature-chart">
    <svg ref={plot} viewBox={`0 0 ${width} 310`} role="group" aria-label="体温趋势图，横轴为提交时间，纵轴为摄氏度">
      {[350, 360, 370, 380, 390, 400, 410, 420].map((value) => {
        const top = 256 - (value - 350) / 70 * 224;
        return <g key={value}><line x1="54" x2={width - 24} y1={top} y2={top} stroke="currentColor" opacity=".12" /><text x="44" y={top + 4} textAnchor="end" fill="currentColor" fontSize="12">{(value / 10).toFixed(1)}</text></g>;
      })}
      {series.map((id, index) => <polyline key={id} points={records.filter((item) => item.seriesId === id).map((item) => `${x(item)},${y(item)}`).join(" ")} fill="none" stroke={colors[index % colors.length]} strokeWidth="2" />)}
      {records.map((item) => <circle key={item.id} cx={x(item)} cy={y(item)} r="6" fill={colors[series.indexOf(item.seriesId) % colors.length]} tabIndex={0} role="button"
        aria-label={`${timeLabel(item.occurredAt, timeZone)}，${seriesLabel(item)}，${formatTemperature(item.temperatureTenthsC)}`}
        onMouseEnter={() => setActiveId(item.id)} onFocus={() => setActiveId(item.id)} onClick={() => setActiveId(item.id)}
        onKeyDown={(event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); setActiveId(item.id); } }}>
        <title>{`${timeLabel(item.occurredAt, timeZone)} · ${seriesLabel(item)} · ${formatTemperature(item.temperatureTenthsC)}`}</title>
      </circle>)}
      <text x="54" y="288" fill="currentColor" fontSize="12">{timeLabel(records[0].occurredAt, timeZone)}</text>
      <text x={width - 24} y="288" fill="currentColor" fontSize="12" textAnchor="end">{timeLabel(records.at(-1)!.occurredAt, timeZone)}</text>
    </svg>
    <p className="temperature-tooltip" aria-live="polite">{active ? `${timeLabel(active.occurredAt, timeZone)} · ${seriesLabel(active)} · ${formatTemperature(active.temperatureTenthsC)}` : "悬停或点选记录查看详情"}</p>
    <div className="temperature-legend">{series.map((id, index) => <span key={id}><i style={{ background: colors[index % colors.length] }} />{seriesLabel(records.find((item) => item.seriesId === id)!)}</span>)}</div>
  </div>;
}

export function TemperaturePage({ entities, executors, timeZone, online, lastSyncedAt }: { entities: CloudEntity[]; executors: SpaceMember[]; timeZone: string; online: boolean; lastSyncedAt?: string | null }) {
  const all = useMemo(() => temperatureRecords(entities), [entities]);
  const [selectedExecutor, setExecutor] = useState("");
  const executor = executors.some((item) => item.id === selectedExecutor) ? selectedExecutor : executors[0]?.id ?? "";
  const [task, setTask] = useState("all");
  const [period, setPeriod] = useState("7");
  const [customRange, setCustomRange] = useState(() => temperatureRange(7, timeZone));
  const range = period === "custom" ? customRange : temperatureRange(Number(period), timeZone);
  const tasks = [...new Map(all.filter((item) => item.executorId === executor).map((item) => [item.taskId, item.taskName])).entries()];
  const records = filterTemperatures(all, executor, task, range.start, range.end, timeZone);
  const summary = temperatureSummary(records);
  return <section className="connected-page">
    <div className="page-header"><div><p className="eyebrow">健康记录</p><h1>体温统计</h1><p>仅统计当前正式完成结果。横轴为提交时间，步骤采用整项任务的提交时间。</p></div></div>
    <p>{online ? "已连接" : "离线：显示上次同步缓存"} · 最近同步：{lastSyncedAt ? new Date(lastSyncedAt).toLocaleString("zh-CN", { timeZone }) : "尚未同步"} · 时区：{timeZone}</p>
    <div className="filter-bar temperature-filters">
      <label className="field"><span>执行者</span><select value={executor} onChange={(event) => { setExecutor(event.target.value); setTask("all"); }}>{executors.length ? executors.map((item, index) => <option value={item.id} key={item.id}>{item.email ?? `执行者 ${index + 1}`}</option>) : <option value="">暂无执行者</option>}</select></label>
      <label className="field"><span>体温任务</span><select value={task} onChange={(event) => setTask(event.target.value)}><option value="all">全部任务</option>{tasks.map(([id, name]) => <option value={id} key={id}>{name}</option>)}</select></label>
      <label className="field"><span>日期范围</span><select value={period} onChange={(event) => { setPeriod(event.target.value); if (event.target.value === "custom") setCustomRange(range); }}><option value="7">近 7 日</option><option value="30">近 30 日</option><option value="custom">自定义</option></select></label>
      {period === "custom" && <><label className="field"><span>开始日期</span><input type="date" value={customRange.start} onChange={(event) => setCustomRange({ ...customRange, start: event.target.value })} /></label><label className="field"><span>结束日期</span><input type="date" value={customRange.end} onChange={(event) => setCustomRange({ ...customRange, end: event.target.value })} /></label></>}
    </div>
    {range.start > range.end && <p role="alert">开始日期不能晚于结束日期。</p>}
    <dl className="temperature-summary">{[["记录数", summary.count], ["最新值", summary.latest], ["最低值", summary.min], ["最高值", summary.max], ["平均值", summary.mean]].map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{label === "记录数" ? value : value === null ? "—" : formatTemperature(Number(value))}</dd></div>)}</dl>
    <TemperatureChart records={records} timeZone={timeZone} />
    {records.length > 0 && <div className="temperature-table"><table><caption>体温记录明细（提交时间）</caption><thead><tr><th>提交时间</th><th>任务／步骤</th><th>体温</th></tr></thead><tbody>{[...records].reverse().map((item) => <tr key={item.id}><td>{new Date(item.occurredAt).toLocaleString("zh-CN", { timeZone })}</td><td>{seriesLabel(item)}</td><td>{formatTemperature(item.temperatureTenthsC)}</td></tr>)}</tbody></table></div>}
  </section>;
}
