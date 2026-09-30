import { pluginLynxConfig } from "@lynx-js/config-rsbuild-plugin";
import { pluginQRCode } from "@lynx-js/qrcode-rsbuild-plugin";
import { defineConfig } from "@lynx-js/rspeedy";
import { pluginTypeCheck } from "@rsbuild/plugin-type-check";
import { pluginMithrilLynx } from "mithril-lynx/plugin";

export function defineExampleConfig(projectRoot) {
  return defineConfig({
    source: { entry: { "main-thread": `${projectRoot}/src/main-thread.ts` } },
    output: {
      distPath: { root: `${projectRoot}/dist` },
      filename: "[name].bundle",
      dataUriLimit: Infinity,
    },
    plugins: [
      pluginMithrilLynx(),
      pluginLynxConfig({ enableNewGesture: true, enableCSSRule: true }),
      pluginQRCode({ schema: (url) => `${url}?fullscreen=true` }),
      pluginTypeCheck(),
    ],
  });
}
