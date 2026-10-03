import m from "mithril-runtime";
import { redraw } from "mithril-lynx/mount-redraw";
import { qb, sqlite } from "lynx-android-plugins/sqlite";
import type { SQLiteDatabase } from "lynx-android-plugins/sqlite";
import "./style.css";

type Task = { id: number; title: string };

const database = {
  name: "todo_example",
  version: 1,
  migrations: [{ to: 1, statements: [
    "CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL)",
  ] }],
};

let tasks: Task[] = [];
let draft = "";
let inputVersion = 0;
let busy = false;
let loading = true;
let message = "";

async function withDatabase<T>(operation: (db: SQLiteDatabase) => Promise<T>): Promise<T> {
  const db = await sqlite.open(database);
  try {
    return await operation(db);
  } finally {
    await db.close();
  }
}

async function readTasks(db: SQLiteDatabase): Promise<Task[]> {
  const { rows } = await qb
    .select("id", "title")
    .from("tasks")
    .orderBy("id", "DESC")
    .limit(1000)
    .query<Task>(db);
  return rows;
}

function showError(error: unknown): void {
  message = error instanceof Error ? error.message : String(error);
}

export async function loadTasks(): Promise<void> {
  busy = true;
  loading = true;
  redraw();
  try {
    tasks = await withDatabase(readTasks);
    message = "";
  } catch (error) {
    showError(error);
  } finally {
    busy = false;
    loading = false;
    redraw();
  }
}

async function addTask(): Promise<void> {
  if (busy) return;
  const title = draft.trim();
  if (!title) {
    message = "Enter a task first.";
    redraw();
    return;
  }
  busy = true;
  message = "";
  redraw();
  try {
    tasks = await withDatabase(async (db) => {
      await qb.insertInto("tasks").values({ title }).execute(db);
      return readTasks(db);
    });
    draft = "";
    inputVersion += 1;
  } catch (error) {
    showError(error);
  } finally {
    busy = false;
    redraw();
  }
}

async function removeTask(id: number): Promise<void> {
  if (busy) return;
  busy = true;
  message = "";
  redraw();
  try {
    tasks = await withDatabase(async (db) => {
      await qb.deleteFrom("tasks").where("id", "=", id).execute(db);
      return readTasks(db);
    });
  } catch (error) {
    showError(error);
  } finally {
    busy = false;
    redraw();
  }
}

export function view() {
  return m("view", { class: "Page" }, [
    m("view", { class: "Content" }, [
      m("text", { class: "Eyebrow" }, "SQLITE EXAMPLE"),
      m("text", { class: "Title" }, "To-do list"),
      m("text", { class: "Description" }, "Add a task, then remove it when you are done. Persistence uses the SQLite query builder."),
      m("view", { class: "Composer" }, [
        m("input", {
          key: inputVersion,
          class: "TaskInput",
          type: "text",
          placeholder: "What needs doing?",
          maxlength: 120,
          "confirm-type": "done",
          oninput: (event: { detail?: { value?: string } }) => {
            draft = event.detail?.value ?? "";
          },
          onconfirm: (event: { detail?: { value?: string } }) => {
            if (typeof event.detail?.value === "string") draft = event.detail.value;
            void addTask();
          },
        }),
        m("view", { class: "AddButton", key: "add-button", ontap: () => { void addTask(); } },
          m("text", { class: "AddButtonText" }, "Add")),
      ]),
      message ? m("text", { class: "Message" }, message) : null,
      m("view", { class: "ListHeader" }, [
        m("text", { class: "ListTitle" }, "Tasks"),
        m("text", { class: "Count" }, `${tasks.length}`),
      ]),
      loading
        ? m("text", { class: "Empty" }, "Loading tasks...")
        : tasks.length === 0
          ? m("text", { class: "Empty" }, "No tasks yet. Add your first one above.")
          : m("scroll-view", { class: "TaskList", "scroll-orientation": "vertical" },
            tasks.map((task) => m("view", { class: "TaskRow", key: task.id }, [
              m("view", { class: "TaskMarker" }),
              m("text", { class: "TaskText" }, task.title),
              m("view", { class: "DeleteButton", ontap: () => { void removeTask(task.id); } },
                m("text", { class: "DeleteText" }, "Delete")),
            ]))),
    ]),
  ]);
}
