import { Search } from "lucide-react";
import type { RefObject } from "react";
import type { GroupRecord } from "../model/types";

interface TaskLibraryFiltersProps {
  groups: GroupRecord[];
  search: string;
  onSearchChange: (value: string) => void;
  kindFilter: string;
  onKindFilterChange: (value: string) => void;
  groupFilter: string;
  onGroupFilterChange: (value: string) => void;
  searchInputRef?: RefObject<HTMLInputElement>;
}

export function TaskLibraryFilters({
  groups,
  search,
  onSearchChange,
  kindFilter,
  onKindFilterChange,
  groupFilter,
  onGroupFilterChange,
  searchInputRef,
}: TaskLibraryFiltersProps) {
  return <div className="filter-bar">
    <label className="search-field"><Search size={18} /><input ref={searchInputRef} value={search} onChange={(event) => onSearchChange(event.target.value)} placeholder="搜索名称、描述或 taskId（Ctrl+K）" /></label>
    <select aria-label="任务类型筛选" value={kindFilter} onChange={(event) => onKindFilterChange(event.target.value)}><option value="all">全部任务类型</option><option value="temporary">临时任务</option><option value="recurring">重复任务</option></select>
    <select aria-label="积分组筛选" value={groupFilter} onChange={(event) => onGroupFilterChange(event.target.value)}><option value="all">全部积分组</option><option value="ungrouped">未分组</option>{groups.map((group) => <option key={group.id} value={group.id}>{group.name}</option>)}</select>
  </div>;
}
