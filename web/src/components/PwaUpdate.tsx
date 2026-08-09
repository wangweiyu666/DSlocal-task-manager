import { useRegisterSW } from "virtual:pwa-register/react";
import { useToast } from "./Toast";
import { useEffect } from "react";

export function PwaUpdate() {
  const { show } = useToast();
  const { needRefresh: [needRefresh, setNeedRefresh], offlineReady: [offlineReady, setOfflineReady], updateServiceWorker } = useRegisterSW();
  useEffect(() => { if (offlineReady) { show("应用已可离线使用", "success"); setOfflineReady(false); } }, [offlineReady, setOfflineReady, show]);
  if (!needRefresh) return null;
  return <div className="update-banner"><span>新版本已准备好，刷新后启用。</span><button className="button primary" onClick={() => updateServiceWorker(true)}>刷新</button><button className="button text" onClick={() => setNeedRefresh(false)}>稍后</button></div>;
}
