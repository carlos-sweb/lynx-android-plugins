import m from "mithril-runtime";
import { redraw } from "mithril-lynx/mount-redraw";
import { network } from "lynx-android-plugins/network";
import "../../../shared/style.css";

let result = "Press the button to read network state.";

async function readNetwork() {
  try {
    const info = await network.get();
    result = `${info.online ? "Connected" : "No Internet"}\n${info.type} · ${info.metered ? "metered" : "unmetered"}`;
  } catch (error) {
    result = error instanceof Error ? error.message : String(error);
  }
  redraw();
}

export function view() {
  return m("view", { class: "Page" }, [
    m("text", { class: "Title" }, "Network"),
    m("text", { class: "Subtitle" }, "Network state and transport"),
    m("text", { class: "Result" }, result),
    m("view", { class: "Button", ontap: readNetwork }, m("text", { class: "ButtonText" }, "Read network")),
  ]);
}
