import m from "mithril-runtime";
import { redraw } from "mithril-lynx/mount-redraw";
import { sqlite } from "lynx-android-plugins/sqlite";
import "../../../shared/style.css";

let result = "Tap to save and read a note.";
let busy = false;

async function saveAndRead() {
  if (busy) return;
  busy = true;
  result = "Opening database...";
  redraw();
  try {
    const db = await sqlite.open({
      name: "example_notes",
      version: 1,
      migrations: [{ to: 1, statements: ["CREATE TABLE notes (id INTEGER PRIMARY KEY, message TEXT NOT NULL)"] }],
    });
    try {
      await db.execute("INSERT INTO notes (message) VALUES (?)", ["Hello from Mithril"]);
      const { rows } = await db.query<{ count: number }>("SELECT COUNT(*) AS count FROM notes");
      result = `${rows[0]?.count ?? 0} notes saved on this device`;
    } finally {
      await db.close();
    }
  } catch (error) {
    result = error instanceof Error ? error.message : String(error);
  }
  busy = false;
  redraw();
}

export function view() {
  return m("view", { class: "Page" }, [
    m("text", { class: "Title" }, "SQLite"),
    m("text", { class: "Subtitle" }, "Local, persistent storage"),
    m("text", { class: "Result" }, result),
    m("view", { class: "Button", ontap: saveAndRead }, m("text", { class: "ButtonText" }, busy ? "Working..." : "Save and read")),
  ]);
}
