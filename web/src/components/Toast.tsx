import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { CheckCircle2, CircleAlert, X } from "lucide-react";

type ToastKind = "success" | "error" | "info";
interface ToastItem { id: number; message: string; kind: ToastKind }
interface ToastApi { show: (message: string, kind?: ToastKind) => void }
const ToastContext = createContext<ToastApi>({ show: () => undefined });

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const show = useCallback((message: string, kind: ToastKind = "info") => {
    const id = Date.now() + Math.random();
    setItems((current) => [...current, { id, message, kind }]);
    window.setTimeout(() => setItems((current) => current.filter((item) => item.id !== id)), 4200);
  }, []);
  const api = useMemo(() => ({ show }), [show]);
  return <ToastContext.Provider value={api}>
    {children}
    <div className="toast-stack" aria-live="polite">
      {items.map((item) => <div key={item.id} className={`toast toast-${item.kind}`}>
        {item.kind === "success" ? <CheckCircle2 size={19} /> : <CircleAlert size={19} />}
        <span>{item.message}</span>
        <button className="icon-button" onClick={() => setItems((current) => current.filter((entry) => entry.id !== item.id))} aria-label="关闭"><X size={17} /></button>
      </div>)}
    </div>
  </ToastContext.Provider>;
}

export const useToast = () => useContext(ToastContext);
