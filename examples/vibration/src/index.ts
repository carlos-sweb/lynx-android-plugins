import m from "mithril-runtime";
import { redraw } from "mithril-lynx/mount-redraw";
import { vibration } from "lynx-android-plugins/vibration";
import "../../../shared/style.css";

let result = "Press the button to vibrate for 300 ms.";

async function vibrate() {
  try {
    await vibration.vibrate(300);
    result = "Sent a 300 ms vibration.";
  } catch (error) {
    result = error instanceof Error ? error.message : String(error);
  }
  redraw();
}

export function view() {
  return m("view", { class: "Page" }, [
    m("text", { class: "Title" }, "Vibration"),
    m("text", { class: "Subtitle" }, "A brief, bounded, cancelable vibration"),
    m("text", { class: "Result" }, result),
    m("view", { class: "Button", ontap: vibrate }, m("text", { class: "ButtonText" }, "Vibrate")),
  ]);
}
