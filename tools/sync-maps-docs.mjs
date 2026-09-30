import { copyFileSync, mkdirSync } from "node:fs";
import { resolve } from "node:path";

// Keep one authoritative guide, and include a readable copy in the npm tarball.
const root = resolve(import.meta.dirname, "..");
mkdirSync(resolve(root, "packages/js/docs"), { recursive: true });
copyFileSync(resolve(root, "docs/maps.md"), resolve(root, "packages/js/docs/maps.md"));
