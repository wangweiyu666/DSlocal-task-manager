import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useOutboxSync } from "../src/connected/useOutboxSync";

describe("outbox retry scheduling", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { cleanup(); vi.useRealTimers(); });

  it("backs off retained commands instead of retrying on every render", async () => {
    const operation = vi.fn(async () => 1);
    const { rerender } = renderHook(() => useOutboxSync(true, 1, operation));
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(operation).toHaveBeenCalledTimes(1);
    rerender();
    await act(async () => { await vi.advanceTimersByTimeAsync(999); });
    expect(operation).toHaveBeenCalledTimes(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(operation).toHaveBeenCalledTimes(2);
    await act(async () => { await vi.advanceTimersByTimeAsync(1_999); });
    expect(operation).toHaveBeenCalledTimes(2);
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(operation).toHaveBeenCalledTimes(3);
  });

  it("deduplicates manual clicks and cancels automatic retry when offline", async () => {
    let finish!: (pending: number) => void;
    const operation = vi.fn(() => new Promise<number>((resolve) => { finish = resolve; }));
    const { result, rerender } = renderHook(({ online }) => useOutboxSync(online, 1, operation), { initialProps: { online: true } });
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    await act(async () => { void result.current.synchronize(); void result.current.synchronize(); });
    expect(operation).toHaveBeenCalledTimes(1);
    await act(async () => finish(1));
    rerender({ online: false });
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
    expect(operation).toHaveBeenCalledTimes(1);
  });

  it("backs off network errors and stops when the outbox drains", async () => {
    const operation = vi.fn().mockRejectedValueOnce(new Error("network")).mockResolvedValue(0);
    const { rerender } = renderHook(({ count }) => useOutboxSync(true, count, operation), { initialProps: { count: 1 } });
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    await act(async () => { await vi.advanceTimersByTimeAsync(999); });
    expect(operation).toHaveBeenCalledTimes(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    rerender({ count: 0 });
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
    expect(operation).toHaveBeenCalledTimes(2);
  });
});
