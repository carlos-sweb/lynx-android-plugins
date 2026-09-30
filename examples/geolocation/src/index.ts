import m from "mithril-runtime";
import { redraw } from "mithril-lynx/mount-redraw";
import { gps } from "lynx-android-plugins/geolocation";
import "../../../shared/style.css";

let result = "Press the button and grant location permission.";

async function locate() {
  try {
    const position = await gps.get({ highAccuracy: true });
    result = `${position.latitude.toFixed(6)}, ${position.longitude.toFixed(6)}\n± ${Math.round(position.accuracy)} m · ${position.provider}`;
  } catch (error) {
    result = error instanceof Error ? error.message : String(error);
  }
  redraw();
}

export function view() {
  return m("view", { class: "Page" }, [
    m("text", { class: "Title" }, "Geolocation"),
    m("text", { class: "Subtitle" }, "Your current position"),
    m("text", { class: "Result" }, result),
    m("view", { class: "Button", ontap: locate }, m("text", { class: "ButtonText" }, "Show my coordinates")),
  ]);
}
