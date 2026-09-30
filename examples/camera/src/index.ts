import m from "mithril-runtime";
import { redraw } from "mithril-lynx/mount-redraw";
import { camera } from "lynx-android-plugins/camera";
import "../../../shared/style.css";

let result = "No photo has been captured yet.";

async function takePhoto() {
  try {
    const photo = await camera.takePhoto();
    result = `Saved photo:\n${photo.uri}`;
  } catch (error) {
    result = error instanceof Error ? error.message : String(error);
  }
  redraw();
}

export function view() {
  return m("view", { class: "Page" }, [
    m("text", { class: "Title" }, "Camera"),
    m("text", { class: "Subtitle" }, "Capture a photo and receive a URI"),
    m("text", { class: "Result" }, result),
    m("view", { class: "Button", ontap: takePhoto }, m("text", { class: "ButtonText" }, "Take photo")),
  ]);
}
