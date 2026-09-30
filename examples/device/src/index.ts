import m from "mithril-runtime";
import { redraw } from "mithril-lynx/mount-redraw";
import { device } from "lynx-android-plugins/device";
import "../../../shared/style.css";

let result = "Press the button to read device information.";

async function readDevice() {
  try {
    const info = await device.get();
    result = `${info.manufacturer} ${info.model}\nAndroid ${info.osVersion} · API ${info.apiLevel}`;
  } catch (error) {
    result = error instanceof Error ? error.message : String(error);
  }
  redraw();
}

export function view() {
  return m("view", { class: "Page" }, [
    m("text", { class: "Title" }, "Device"),
    m("text", { class: "Subtitle" }, "Non-identifying device information"),
    m("text", { class: "Result" }, result),
    m("view", { class: "Button", ontap: readDevice }, m("text", { class: "ButtonText" }, "Read device")),
  ]);
}
