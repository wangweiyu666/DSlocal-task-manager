export function uuidV7(now = Date.now()): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  let value = BigInt(now);
  for (let index = 5; index >= 0; index -= 1) { bytes[index] = Number(value & 0xffn); value >>= 8n; }
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function cloudOccurrenceKey(taskId: string, taskRevision: number, timeZoneVersion: number, scheduledLocalTime: string): string {
  if (!/^[A-Za-z0-9_-]{16}$/u.test(taskId)) throw new Error("invalid DST1 task id");
  if (!Number.isInteger(taskRevision) || taskRevision < 1 || !Number.isInteger(timeZoneVersion) || timeZoneVersion < 1) throw new Error("invalid cloud version");
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/u.test(scheduledLocalTime)) throw new Error("invalid scheduled local time");
  return `${taskId}:${taskRevision}:${timeZoneVersion}:${scheduledLocalTime}`;
}
