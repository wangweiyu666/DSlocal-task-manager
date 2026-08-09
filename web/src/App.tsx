import { HashRouter, Navigate, Route, Routes } from "react-router-dom";
import { Shell } from "./components/Shell";
import { PwaUpdate } from "./components/PwaUpdate";
import { useLiveQuery } from "dexie-react-hooks";
import { db, getSettings } from "./db/database";
import { lazy, Suspense, useEffect } from "react";

const CreatePage = lazy(() => import("./pages/CreatePage").then((module) => ({ default: module.CreatePage })));
const LibraryPage = lazy(() => import("./pages/LibraryPage").then((module) => ({ default: module.LibraryPage })));
const TemplatesPage = lazy(() => import("./pages/TemplatesPage").then((module) => ({ default: module.TemplatesPage })));
const GroupsPage = lazy(() => import("./pages/GroupsPage").then((module) => ({ default: module.GroupsPage })));
const HistoryPage = lazy(() => import("./pages/HistoryPage").then((module) => ({ default: module.HistoryPage })));
const SettingsPage = lazy(() => import("./pages/SettingsPage").then((module) => ({ default: module.SettingsPage })));

function ThemeController() {
  const settings = useLiveQuery(() => db.settings.get("app"), []);
  useEffect(() => { if (settings === undefined) void getSettings(); }, [settings]);
  useEffect(() => {
    const media = matchMedia("(prefers-color-scheme: dark)");
    const apply = () => document.documentElement.dataset.theme = settings?.theme === "dark" || (settings?.theme === "system" && media.matches) ? "dark" : "light";
    apply(); media.addEventListener("change", apply); return () => media.removeEventListener("change", apply);
  }, [settings?.theme]);
  return null;
}

export default function App() {
  return <HashRouter><ThemeController /><PwaUpdate /><Suspense fallback={<div className="loading">正在载入页面…</div>}><Routes>
    <Route element={<Shell />}>
      <Route index element={<Navigate to="/create" replace />} />
      <Route path="/create" element={<CreatePage />} />
      <Route path="/library" element={<LibraryPage />} />
      <Route path="/templates" element={<TemplatesPage />} />
      <Route path="/groups" element={<GroupsPage />} />
      <Route path="/history" element={<HistoryPage />} />
      <Route path="/settings" element={<SettingsPage />} />
    </Route>
    <Route path="*" element={<Navigate to="/create" replace />} />
  </Routes></Suspense></HashRouter>;
}
