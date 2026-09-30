import m from "mithril-runtime";
import { redraw } from "mithril-lynx/mount-redraw";
import "../../../shared/style.css";
let result = "Press the button to read the current level.";
let notice = "";
function readBattery() {
  const plugin = (NativeModules as any).LynxBatteryPlugin;
  if (!plugin) { notice = "Lynx Go can display this bundle, but it does not include LynxBatteryPlugin. Open it in this repository's Android host."; redraw(); return; }
  const requestId = `battery-${Date.now()}`;
  const emitter = lynx.getJSModule("GlobalEventEmitter");
  const listener = (event: any) => { if (event?.requestId !== requestId) return; emitter.removeListener("lynxAndroidPlugins:battery", listener); result = event.ok ? `${Math.round(event.data.level * 100)}% · ${event.data.charging ? "cargando" : "en descarga"}` : event.data.message; redraw(); };
  emitter.addListener("lynxAndroidPlugins:battery", listener); plugin.getStatus(requestId);
}
export function view() { return m("view", { class: "Page" }, [m("text", { class: "Title" }, "Battery Status"), m("text", { class: "Subtitle" }, "Battery level and charging state"), m("text", { class: "Result" }, result), m("view", { class: "Button", bindtap: readBattery }, m("text", { class: "ButtonText" }, "Read battery")), notice && m("text", { class: "Notice" }, notice)]); }
