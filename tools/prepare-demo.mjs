import { cpSync, existsSync, mkdirSync } from "node:fs";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";

const feature = process.argv[2];
const allowed = new Set(["battery", "camera", "device", "geolocation", "network", "vibration"]);
if (!allowed.has(feature)) {
  throw new Error(`Usage: bun tools/prepare-demo.mjs <${[...allowed].join("|")}>`);
}
const root = resolve(import.meta.dirname, "..");
const example = resolve(root, "examples", feature);
const build = spawnSync("bun", ["run", "build"], { cwd: example, stdio: "inherit" });
if (build.status !== 0) process.exit(build.status ?? 1);
const source = resolve(example, "dist", "main-thread.bundle");
if (!existsSync(source)) throw new Error(`Expected ${source}`);
const assets = resolve(root, "demo-host", "src", "main", "assets");
mkdirSync(assets, { recursive: true });
cpSync(source, resolve(assets, "main-thread.bundle"));
console.log(`Prepared the ${feature} bundle for demo-host.`);
