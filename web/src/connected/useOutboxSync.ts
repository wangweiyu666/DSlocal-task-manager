import { useCallback, useEffect, useRef, useState } from "react";

/** One request at a time; a retained outbox always waits before trying again. */
export function useOutboxSync(enabled: boolean, pendingCount: number, operation: () => Promise<number>) {
  const inFlight = useRef(false);
  const failures = useRef(0);
  const [syncing, setSyncing] = useState(false);
  const [retryAt, setRetryAt] = useState(0);
  const synchronize = useCallback(async () => {
    if (!enabled || inFlight.current) return;
    inFlight.current = true;
    setSyncing(true);
    let retry = true;
    try { retry = await operation() > 0; }
    catch { /* The caller presents errors; retained work still needs a delayed retry. */ }
    finally {
      failures.current = retry ? failures.current + 1 : 0;
      const delay = retry ? Math.min(60_000, 1_000 * 2 ** Math.min(failures.current - 1, 6)) : 0;
      setRetryAt(Date.now() + delay);
      inFlight.current = false;
      setSyncing(false);
    }
  }, [enabled, operation]);

  useEffect(() => {
    if (!enabled || pendingCount === 0 || syncing) return;
    const timer = setTimeout(() => { void synchronize(); }, Math.max(0, retryAt - Date.now()));
    return () => clearTimeout(timer);
  }, [enabled, pendingCount, syncing, retryAt, synchronize]);

  return { syncing, synchronize };
}
