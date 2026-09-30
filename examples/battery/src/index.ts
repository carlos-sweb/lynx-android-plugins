import m from "mithril-runtime";
import { redraw } from "mithril-lynx/mount-redraw";
import { battery } from "lynx-android-plugins/battery";
import "../../../shared/style.css";

let result = "Press the button to read the current level.";

async function readBattery() {
  try {
    const state = await battery.get();
    result = `${state.level < 0 ? "Unknown" : `${Math.round(state.level * 100)}%`} · ${state.charging ? "charging" : "discharging"}`;
  } catch (error) {
    result = error instanceof Error ? error.message : String(error);
  }
  redraw();
}

export function view() {
  return m("view", { class: "Page" }, [
    m("text", { class: "Title" }, "Battery Status"),
    m("text", { class: "Subtitle" }, "Battery level and charging state"),
    m("text", { class: "Result" }, result),
    m("view", { class: "Button", ontap: readBattery }, m("text", { class: "ButtonText" }, "Read battery")),
  ]);
}
