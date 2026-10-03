import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
copyFileSync(resolve(root, "README.md"), resolve(root, "packages/js/README.md"));
mkdirSync(resolve(root, "packages/js/docs"), { recursive: true });
copyFileSync(resolve(root, "docs/sqlite.md"), resolve(root, "packages/js/docs/sqlite.md"));

// Design notes live at repo root with links like ./docs/sqlite.md; rewrite those
// for copies that sit next to sqlite.md inside docs/ or packages/js/docs/.
const queryBuilder = readFileSync(resolve(root, "queryBuilder.md"), "utf8")
  .replaceAll("](./docs/sqlite.md)", "](./sqlite.md)")
  .replaceAll("](docs/sqlite.md)", "](./sqlite.md)");
writeFileSync(resolve(root, "docs/queryBuilder.md"), queryBuilder);
writeFileSync(resolve(root, "packages/js/docs/queryBuilder.md"), queryBuilder);
