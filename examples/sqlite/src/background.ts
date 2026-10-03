import m from "mithril-runtime";
import { renderApp } from "mithril-lynx/background";
import * as page from "./index.js";

const host = { view: () => page.view() };
renderApp({ root: () => m(host) });
