import { createHash } from "node:crypto";
import { createReadStream, createWriteStream } from "node:fs";
import { mkdir, rename, stat } from "node:fs/promises";
import { join, resolve } from "node:path";
import { pipeline } from "node:stream/promises";
import { Readable } from "node:stream";
import { spawn } from "node:child_process";

const options = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  if (!process.argv[index]?.startsWith("--") || !process.argv[index + 1]) {
    throw new Error("Usage: bun tools/build-chile-map.mjs --url https://your-host/chile.pmtiles [--version 2026-09] [--input /path/chile.osm.pbf]");
  }
  options.set(process.argv[index].slice(2), process.argv[index + 1]);
}

const url = options.get("url");
if (!url || new URL(url).protocol !== "https:") throw new Error("--url must be an HTTPS URL you control.");
const version = options.get("version") ?? new Date().toISOString().slice(0, 10);
const directory = resolve(options.get("output-dir") ?? "build/maps");
const input = resolve(options.get("input") ?? join(directory, "chile-latest.osm.pbf"));
const output = join(directory, "chile.pmtiles");
await mkdir(directory, { recursive: true });

const run = (command, args, quiet = false) => new Promise((resolveRun, reject) => {
  const child = spawn(command, args, { stdio: quiet ? "ignore" : "inherit" });
  child.once("error", reject);
  child.once("exit", (code) => code === 0 ? resolveRun() : reject(new Error(`${command} exited with code ${code}`)));
});
let containerEngine;
for (const candidate of ["docker", "podman"]) {
  try {
    await run(candidate, ["info"], true);
    containerEngine = candidate;
    break;
  } catch { /* Try the next engine. */ }
}
if (!containerEngine) throw new Error("Docker or Podman must be available before downloading the large Chile extract.");

if (!options.has("input")) {
  try {
    await stat(input);
  } catch {
    console.log("Downloading the Chile OpenStreetMap extract from Geofabrik…");
    const response = await fetch("https://download.geofabrik.de/south-america/chile-latest.osm.pbf");
    if (!response.ok || !response.body) throw new Error(`Geofabrik download failed: HTTP ${response.status}`);
    const partial = `${input}.download`;
    await pipeline(Readable.fromWeb(response.body), createWriteStream(partial));
    await rename(partial, input);
  }
}
if (!(await stat(input)).isFile()) throw new Error(`Input is not a file: ${input}`);

const image = "ghcr.io/systemed/tilemaker@sha256:bdc034e2a56952a2b7e401a3bb216e95197c0dd683ee2039f33390c3c1c43bf0";
const dockerMount = resolve(directory);
if (!input.startsWith(`${dockerMount}/`)) {
  throw new Error("--input must be inside --output-dir so Docker can access it.");
}
const relativeInput = input.slice(dockerMount.length + 1);
console.log(`Building an offline PMTiles archive with Tilemaker via ${containerEngine}. This can take hours for all of Chile…`);
await run(containerEngine, [
  "run", "--rm", ...(containerEngine === "podman"
    ? ["--userns=keep-id", "--security-opt", "label=disable"]
    : ["--user", `${process.getuid()}:${process.getgid()}`]),
  "--mount", `type=bind,source=${dockerMount},target=/data`,
  image, `/data/${relativeInput}`, "--output", "/data/chile.pmtiles",
  "--config", "/usr/src/app/resources/config-example.json",
  "--process", "/usr/src/app/resources/process-example.lua",
  "--store", "/data/tilemaker-store"
]);

const file = await stat(output);
if (file.size < 1024) throw new Error("The resulting PMTiles archive is unexpectedly small.");
const hash = createHash("sha256");
for await (const chunk of createReadStream(output)) hash.update(chunk);
const sha256 = hash.digest("hex");
await Bun.write(join(directory, "maps.json"), `${JSON.stringify({ url, sha256, version }, null, 2)}\n`);
console.log(`Map package: ${output} (${file.size} bytes)`);
console.log(`Configuration: ${join(directory, "maps.json")}`);
console.log("Upload chile.pmtiles to the configured URL, then copy maps.json into your app's android/ directory.");
