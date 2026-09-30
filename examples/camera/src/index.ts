import m from "mithril-runtime";
import { redraw } from "mithril-lynx/mount-redraw";
import "../../../shared/style.css";
let result = "No photo has been captured yet."; let notice = "";
function takePhoto() { const plugin = (NativeModules as any).LynxCameraPlugin; if (!plugin) { notice = "Lynx Go does not include plugin cameras. Use the Android demonstration host."; redraw(); return; } const requestId = `camera-${Date.now()}`; const emitter = lynx.getJSModule("GlobalEventEmitter"); const listener = (event: any) => { if (event?.requestId !== requestId) return; emitter.removeListener("lynxAndroidPlugins:camera", listener); result = event.ok ? `Saved photo:\n${event.data.uri}` : event.data.message; redraw(); }; emitter.addListener("lynxAndroidPlugins:camera", listener); plugin.takePhoto(requestId); }
export function view() { return m("view", { class: "Page" }, [m("text", { class: "Title" }, "Camera"), m("text", { class: "Subtitle" }, "Capture a photo and receive a URI"), m("text", { class: "Result" }, result), m("view", { class: "Button", bindtap: takePhoto }, m("text", { class: "ButtonText" }, "Take photo")), notice && m("text", { class: "Notice" }, notice)]); }
