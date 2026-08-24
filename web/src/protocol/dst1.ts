import { deflate, Inflate } from "pako";
import { base64UrlToBytes, bytesToBase64Url } from "./id";
import { canonicalizeBatch } from "./compact";
import { Dst1ProtocolError, type Dst1Batch } from "./types";
import { validateDst1Batch } from "./validation";

export { canonicalizeBatch, compactException, compactTask } from "./compact";

export const DST1_LIMITS = {
  inputChars: 128 * 1024,
  compressedBytes: 96 * 1024,
  jsonBytes: 256 * 1024
} as const;

function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}

export interface EncodedDst1 {
  envelope: string;
  json: string;
  jsonBytes: number;
  compressedBytes: number;
  checksum: string;
}

export function encodeDst1(batch: Dst1Batch): EncodedDst1 {
  const canonical = canonicalizeBatch(batch);
  validateDst1Batch(canonical);
  const json = JSON.stringify(canonical);
  const jsonBytes = new TextEncoder().encode(json);
  if (jsonBytes.length > DST1_LIMITS.jsonBytes) throw new Dst1ProtocolError("JSON_TOO_LARGE", "$.json", "解压后的 JSON 超过 256 KiB");
  const compressed = deflate(jsonBytes, { level: 6 });
  if (compressed.length > DST1_LIMITS.compressedBytes) throw new Dst1ProtocolError("COMPRESSED_DATA_TOO_LARGE", "$.payload", "压缩数据超过 96 KiB");
  const checksum = crc32(compressed).toString(16).padStart(8, "0").toUpperCase();
  const envelope = `${canonical.sv === 1 ? "DST1.1" : "DST1"}.${bytesToBase64Url(compressed)}.${checksum}`;
  if (envelope.length > DST1_LIMITS.inputChars) throw new Dst1ProtocolError("INPUT_TOO_LARGE", "$", "任务字符串超过 128 KiB");
  return { envelope, json, jsonBytes: jsonBytes.length, compressedBytes: compressed.length, checksum };
}

function inflateBounded(compressed: Uint8Array): Uint8Array {
  const chunks: Uint8Array[] = [];
  let length = 0;
  const inflater = new Inflate({ chunkSize: 16 * 1024 });
  inflater.onData = (chunk: Uint8Array) => {
    length += chunk.length;
    if (length > DST1_LIMITS.jsonBytes) throw new Dst1ProtocolError("JSON_TOO_LARGE", "$.json", "解压后的 JSON 超过 256 KiB");
    chunks.push(chunk);
  };
  try {
    inflater.push(compressed, true);
  } catch (error) {
    if (error instanceof Dst1ProtocolError) throw error;
    throw new Dst1ProtocolError("DECOMPRESSION_FAILED", "$.payload", "zlib 解压失败", { cause: error });
  }
  if (inflater.err) throw new Dst1ProtocolError("DECOMPRESSION_FAILED", "$.payload", inflater.msg || "zlib 解压失败");
  const output = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { output.set(chunk, offset); offset += chunk.length; }
  return output;
}

export function decodeDst1(value: string): { batch: Dst1Batch; json: string } {
  if (value.length > DST1_LIMITS.inputChars) throw new Dst1ProtocolError("INPUT_TOO_LARGE", "$", "任务字符串超过 128 KiB");
  const parts = value.split(".");
  const minor = parts.length === 4 && parts[0] === "DST1" && parts[1] === "1" ? 1 : 0;
  if (!((minor === 0 && parts.length === 3 && parts[0] === "DST1") || minor === 1)) throw new Dst1ProtocolError("INVALID_ENVELOPE", "$", "不是有效的 DST1 或 DST1.1 字符串");
  const payloadIndex = minor === 1 ? 2 : 1;
  const checksumIndex = minor === 1 ? 3 : 2;
  if (!/^[0-9A-F]{8}$/u.test(parts[checksumIndex])) throw new Dst1ProtocolError("INVALID_CHECKSUM_FORMAT", "$.checksum", "CRC32 格式无效");
  if (!/^[A-Za-z0-9_-]+$/u.test(parts[payloadIndex])) throw new Dst1ProtocolError("INVALID_BASE64URL", "$.payload", "payload 不是无填充 Base64URL");
  let compressed: Uint8Array;
  try { compressed = base64UrlToBytes(parts[payloadIndex]); }
  catch (error) { throw new Dst1ProtocolError("INVALID_BASE64URL", "$.payload", "Base64URL 解码失败", { cause: error }); }
  if (compressed.length > DST1_LIMITS.compressedBytes) throw new Dst1ProtocolError("COMPRESSED_DATA_TOO_LARGE", "$.payload", "压缩数据超过 96 KiB");
  const checksum = crc32(compressed).toString(16).padStart(8, "0").toUpperCase();
  if (checksum !== parts[checksumIndex]) throw new Dst1ProtocolError("CHECKSUM_MISMATCH", "$.checksum", "CRC32 校验失败");
  const bytes = inflateBounded(compressed);
  let json: string;
  try { json = new TextDecoder("utf-8", { fatal: true }).decode(bytes); }
  catch (error) { throw new Dst1ProtocolError("INVALID_UTF8", "$.json", "JSON 不是有效的 UTF-8", { cause: error }); }
  let parsed: unknown;
  try { parsed = JSON.parse(json); }
  catch (error) { throw new Dst1ProtocolError("INVALID_JSON", "$", "JSON 解析失败", { cause: error }); }
  validateDst1Batch(parsed);
  if ((minor === 1) !== (parsed.sv === 1)) throw new Dst1ProtocolError("INVALID_VALUE", "sv", "信封版本与 JSON 次版本不一致");
  return { batch: parsed, json };
}
