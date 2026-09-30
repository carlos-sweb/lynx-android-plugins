import { mkdir } from "node:fs/promises";
import { join } from "node:path";

const base = new URL("../android-maps/src/main/assets/lynx_maps/glyphs/Noto Sans Regular/", import.meta.url);
await mkdir(base, { recursive: true });

for (const range of ["0-255", "256-511", "8192-8447"]) {
  const response = await fetch(`https://demotiles.maplibre.org/font/Noto%20Sans%20Regular/${range}.pbf`);
  if (!response.ok || !response.headers.get("content-type")?.includes("octet-stream")) {
    throw new Error(`Could not download ${range} glyphs: HTTP ${response.status}`);
  }
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (bytes.length < 100) throw new Error(`Invalid ${range} glyph file`);
  await Bun.write(new URL(`${range}.pbf`, base), bytes);
}

const license = await fetch("https://raw.githubusercontent.com/maplibre/demotiles/gh-pages/font/Noto%20Sans%20Regular/LICENSE.md");
if (!license.ok) throw new Error(`Could not download font license: HTTP ${license.status}`);
await Bun.write(new URL("LICENSE.md", base), await license.text());
console.log(`Offline glyphs and license installed in ${join(base.pathname)}`);
