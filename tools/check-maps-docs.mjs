import { readFileSync, existsSync } from "node:fs";
import { resolve } from "node:path";
import ts from "typescript";

const root = resolve(import.meta.dirname, "..");
const guide = readFileSync(resolve(root, "docs/maps.md"), "utf8");
const blocks = [...guide.matchAll(/```ts\n([\s\S]*?)```/g)].map((match) => match[1]);
const virtualFile = resolve(root, "packages/js/src/maps-doc-examples.ts");
const options = { strict: true, skipLibCheck: true, noEmit: true, target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext, moduleResolution: ts.ModuleResolutionKind.Bundler };
const host = ts.createCompilerHost(options);
const getSourceFile = host.getSourceFile.bind(host);
host.getSourceFile = (file, language, ...rest) => file === virtualFile ? ts.createSourceFile(file, blocks.join("\n"), language, true) : getSourceFile(file, language, ...rest);
const program = ts.createProgram([virtualFile, resolve(root, "packages/js/src/mithril-runtime.d.ts")], options, host);
const diagnostics = ts.getPreEmitDiagnostics(program);
if (diagnostics.length) {
  console.error(ts.formatDiagnosticsWithColorAndContext(diagnostics, { getCanonicalFileName: (file) => file, getCurrentDirectory: () => root, getNewLine: () => "\n" }));
  process.exit(1);
}
for (const [file, link] of [["README.md", "docs/maps.md"], ["packages/js/README.md", "docs/maps.md"]]) {
  if (!readFileSync(resolve(root, file), "utf8").includes(`](${link})`) || !existsSync(resolve(root, file, "..", link))) throw new Error(`Missing Maps guide link in ${file}.`);
}
if (readFileSync(resolve(root, "packages/js/docs/maps.md"), "utf8") !== guide) throw new Error("Run tools/sync-maps-docs.mjs before packaging.");
console.log(`Validated ${blocks.length} TypeScript examples, README links, and the packaged guide.`);
