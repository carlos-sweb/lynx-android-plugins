import { describe, expect, test } from "bun:test";
import { ConnectorError, qb } from "../src/sqlite";

describe("sqlite query builder", () => {
  test("compiles parameterized select/insert/update/delete", () => {
    expect(
      qb.select("id", "title").from("tasks").where("title", "LIKE", "%x%").orderBy("id", "DESC").limit(20).offset(5).compile(),
    ).toEqual({
      sql: "SELECT id, title FROM tasks WHERE title LIKE ? ORDER BY id DESC LIMIT 20 OFFSET 5",
      params: ["%x%"],
    });

    expect(qb.insertInto("tasks").values({ title: "Buy milk", done: 0 }).compile()).toEqual({
      sql: "INSERT INTO tasks (title, done) VALUES (?, ?)",
      params: ["Buy milk", 0],
    });

    expect(qb.update("tasks").set({ title: "Updated" }).where("id", "=", 3).compile()).toEqual({
      sql: "UPDATE tasks SET title = ? WHERE id = ?",
      params: ["Updated", 3],
    });

    expect(qb.deleteFrom("tasks").where("id", "=", 3).compile()).toEqual({
      sql: "DELETE FROM tasks WHERE id = ?",
      params: [3],
    });
  });

  test("supports joins, groupBy, having, upsert, and nested predicates", () => {
    expect(
      qb.select("t.id", "t.title", "u.name")
        .from("tasks")
        .leftJoin("users", "users.id", "=", "t.assignee_id")
        .where("t.id", "IN", [1, 2, 3])
        .orderBy("t.id", "DESC")
        .compile(),
    ).toEqual({
      sql: "SELECT t.id, t.title, u.name FROM tasks LEFT JOIN users ON users.id = t.assignee_id WHERE t.id IN (?, ?, ?) ORDER BY t.id DESC",
      params: [1, 2, 3],
    });

    expect(
      qb.select("assignee_id").from("tasks").groupBy("assignee_id").having("assignee_id", ">", 0).compile(),
    ).toEqual({
      sql: "SELECT assignee_id FROM tasks GROUP BY assignee_id HAVING assignee_id > ?",
      params: [0],
    });

    expect(
      qb.insertInto("tasks").values({ id: 1, title: "A" }).onConflict(["id"], "nothing").compile(),
    ).toEqual({
      sql: "INSERT INTO tasks (id, title) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
      params: [1, "A"],
    });

    expect(
      qb.insertInto("tasks").values({ id: 1, title: "A" })
        .onConflict(["id"], { update: { title: "B" } })
        .compile(),
    ).toEqual({
      sql: "INSERT INTO tasks (id, title) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET title = ?",
      params: [1, "A", "B"],
    });

    expect(
      qb.insertInto("tasks").orReplace().values({ id: 1, title: "A" }).compile(),
    ).toEqual({
      sql: "INSERT OR REPLACE INTO tasks (id, title) VALUES (?, ?)",
      params: [1, "A"],
    });

    expect(
      qb.select("id", "title").from("tasks").where((w) => w
        .where("done", "=", 0)
        .and((w2) => w2.where("priority", ">=", 2).or("assignee_id", "=", 9))
      ).compile(),
    ).toEqual({
      sql: "SELECT id, title FROM tasks WHERE (done = ? AND (priority >= ? OR assignee_id = ?))",
      params: [0, 2, 9],
    });
  });

  test("keeps malicious values in params and rejects unsafe identifiers", () => {
    const injected = "'; DROP TABLE tasks;--";
    expect(qb.select("id").from("tasks").where("title", "=", injected).compile()).toEqual({
      sql: "SELECT id FROM tasks WHERE title = ?",
      params: [injected],
    });

    expect(() => qb.select("id").from("tasks; DROP TABLE tasks--")).toThrow(ConnectorError);
    expect(() => qb.select("id").from("tasks").orderBy("id; DROP TABLE tasks--")).toThrow(ConnectorError);
    expect(() => qb.select("id").from("tasks").orderBy("id", "ASC; DELETE FROM tasks" as "ASC")).toThrow(ConnectorError);
    expect(() => qb.select("id").from("tasks").where("id", "= OR 1=1" as "=", 1)).toThrow(ConnectorError);
    expect(() => qb.select("id; DROP", "title").from("tasks")).toThrow(ConnectorError);
    expect(() => qb.update("tasks").set({ title: "x" }).compile()).toThrow(ConnectorError);
    expect(() => qb.deleteFrom("tasks").compile()).toThrow(ConnectorError);
    expect(qb.deleteFrom("tasks").all().compile()).toEqual({ sql: "DELETE FROM tasks", params: [] });
  });

  test("handles IN/BETWEEN/IS and count helper", () => {
    expect(qb.select("id").from("tasks").where("id", "IN", []).compile()).toEqual({
      sql: "SELECT id FROM tasks WHERE 0 = 1",
      params: [],
    });
    expect(qb.select("id").from("tasks").where("id", "BETWEEN", [1, 5]).compile()).toEqual({
      sql: "SELECT id FROM tasks WHERE id BETWEEN ? AND ?",
      params: [1, 5],
    });
    expect(qb.select("id").from("tasks").where("title", "IS", null).compile()).toEqual({
      sql: "SELECT id FROM tasks WHERE title IS NULL",
      params: [],
    });
    expect(qb.count("tasks").where("done", "=", 0).compile()).toEqual({
      sql: "SELECT COUNT(*) AS count FROM tasks WHERE done = ?",
      params: [0],
    });
    expect(
      qb.insertInto("tasks").valuesMany([{ title: "A" }, { title: "B" }]).compile(),
    ).toEqual({
      sql: "INSERT INTO tasks (title) VALUES (?), (?)",
      params: ["A", "B"],
    });
    expect(qb.select("id").from("tasks").toOperation()).toEqual({
      sql: "SELECT id FROM tasks",
      params: [],
    });
  });

  test("builders are immutable", () => {
    const base = qb.select("id", "title").from("tasks");
    const open = base.where("done", "=", 0);
    const done = base.where("done", "=", 1);
    expect(base.compile()).toEqual({ sql: "SELECT id, title FROM tasks", params: [] });
    expect(open.compile().params).toEqual([0]);
    expect(done.compile().params).toEqual([1]);
  });
});
