import { Archive, FileClock, FolderKanban, LayoutTemplate, ListTodo, PlusCircle, Settings } from "lucide-react";
import { NavLink, Outlet } from "react-router-dom";

const navItems = [
  { to: "/create", label: "创建", icon: PlusCircle },
  { to: "/library", label: "任务库", icon: ListTodo },
  { to: "/templates", label: "模板", icon: LayoutTemplate },
  { to: "/groups", label: "积分组", icon: FolderKanban },
  { to: "/history", label: "批次历史", icon: FileClock },
  { to: "/settings", label: "设置", icon: Settings }
];

export function Shell() {
  return <div className="app-shell">
    <aside className="side-rail">
      <div className="brand"><Archive size={27} /><div><strong>DStationery</strong><span>Dom 任务生成器</span></div></div>
      <nav>{navItems.map(({ to, label, icon: Icon }) => <NavLink key={to} to={to} className={({ isActive }) => isActive ? "active" : ""}><Icon size={21} /><span>{label}</span></NavLink>)}</nav>
      <div className="privacy-note">离线存储 · 无账号 · 无遥测</div>
    </aside>
    <main className="main-content"><Outlet /></main>
    <nav className="bottom-nav">{navItems.map(({ to, label, icon: Icon }) => <NavLink key={to} to={to} className={({ isActive }) => isActive ? "active" : ""}><Icon size={20} /><span>{label === "批次历史" ? "历史" : label}</span></NavLink>)}</nav>
  </div>;
}
