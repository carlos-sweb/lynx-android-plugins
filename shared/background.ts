import m from "mithril-runtime";
import { renderApp } from "mithril-lynx/background";
import * as indexModule from "./index.js";
let currentView: typeof indexModule = indexModule;
const host: m.Component = { view: () => currentView.view() };
const app = renderApp({ root: () => m(host) });
declare const module: { hot?: { accept(path: string, callback: () => void): void } };
declare function require(id: "./index.js"): typeof indexModule;
if (module.hot) module.hot.accept("./index.js", () => { currentView = require("./index.js"); app.redraw(); });
