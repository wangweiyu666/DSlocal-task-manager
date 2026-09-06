export function createTransportId(): string {
  const bytes = new Uint8Array(12);
  crypto.getRandomValues(bytes);
  return bytesToBase64Url(bytes);
}

export function createLocalId(prefix: string): string {
  return `${prefix}_${createTransportId()}`;
}

export function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

export function base64UrlToBytes(value: string): Uint8Array {
  const padded = value.replaceAll("-", "+").replaceAll("_", "/").padEnd(Math.ceil(value.length / 4) * 4, "=");
  const binary = atob(padded);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

/** Protocol-stable ID used when migrating a legacy step into STEPS. */
export function legacyStepId(taskId: string, zeroBasedIndex: number): string {
  if (!/^[A-Za-z0-9_-]{16}$/u.test(taskId)) throw new Error("invalid task ID");
  if (!Number.isInteger(zeroBasedIndex) || zeroBasedIndex < 0 || zeroBasedIndex >= 36 ** 3) throw new Error("invalid step index");
  return `s${taskId.slice(0, 12)}${zeroBasedIndex.toString(36).padStart(3, "0")}`;
}

/** Stable ID for the former task-level execution when converting to STEPS. */
export function legacyExecutionStepId(taskId: string): string {
  if (!/^[A-Za-z0-9_-]{16}$/u.test(taskId)) throw new Error("invalid task ID");
  return `r${taskId.slice(0, 12)}000`;
}
