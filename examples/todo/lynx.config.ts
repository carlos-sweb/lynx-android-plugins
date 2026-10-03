import { defineExampleConfig } from "../../shared/lynx-config.js";

declare const process: { cwd(): string };
export default defineExampleConfig(process.cwd());
