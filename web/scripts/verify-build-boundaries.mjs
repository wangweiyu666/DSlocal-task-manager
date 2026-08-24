import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

async function files(root) {
  const result = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) result.push(...await files(path));
    else result.push(path);
  }
  return result;
}

async function bundleText(root) {
  const paths = (await files(root)).filter((path) => /\.(?:html|js|css|json)$/.test(path));
  return (await Promise.all(paths.map((path) => readFile(path, "utf8")))).join("\n");
}

const offline = await bundleText(fileURLToPath(new URL("../dist-offline", import.meta.url)));
const connectedRoot = fileURLToPath(new URL("../dist-connected", import.meta.url));
const connectedPaths = await files(connectedRoot);
const connected = await bundleText(connectedRoot);
const connectedIndex = await readFile(fileURLToPath(new URL("../dist-connected/index.html", import.meta.url)), "utf8");
const forbidden = ["connected-foundation-v1", "/v1/auth/", "api.rochelimit.me", "cloud-api"];
for (const marker of forbidden) {
  if (offline.includes(marker)) throw new Error(`offline bundle contains connected marker: ${marker}`);
}
if (!connected.includes("data-build-channel") || !connected.includes("connected")) {
  throw new Error("connected bundle identity marker is missing");
}
if (/\b(?:src|href)="\.\/(?:assets|registerSW|manifest)/u.test(connectedIndex)) {
  throw new Error("connected entry assets must use root-relative URLs");
}
if (connectedPaths.some((path) => /(?:^|[\\/])(?:sw\.js|registerSW\.js|manifest\.webmanifest)$/u.test(path))) {
  throw new Error("connected build must not contain a service worker or web app manifest");
}
console.log("offline/connected bundle boundaries verified");
