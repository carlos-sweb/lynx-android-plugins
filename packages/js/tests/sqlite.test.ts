import { describe, expect, test } from "bun:test";
import { sqlite } from "../src/sqlite";

type Call = { operation: string; payload: Record<string, unknown> };

function mockNative() {
  const globals = globalThis as Record<string, unknown>;
  const previousModules = globals.NativeModules;
  const previousLynx = globals.lynx;
  const listeners = new Map<string, (...args: unknown[]) => void>();
  const calls: Call[] = [];
  const results: Record<string, object> = {
    open: { handle: "test-handle" },
    query: { columns: ["value"], rows: [{ value: 3 }] },
    execute: { changes: 1, lastInsertRowId: 3 },
    transaction: { results: [{ changes: 1, lastInsertRowId: 3 }] },
    close: {},
  };
  globals.lynx = { getJSModule: () => ({
    addListener: (name: string, listener: (...args: unknown[]) => void) => listeners.set(name, listener),
    removeListener: (name: string) => listeners.delete(name),
  }) };
  globals.NativeModules = { LynxSqlitePlugin: {
    perform(requestId: string, operation: string, rawPayload: string) {
      calls.push({ operation, payload: JSON.parse(rawPayload) });
      queueMicrotask(() => listeners.get("lynxAndroidPlugins:sqlite")?.({ requestId, ok: true, data: { payload: JSON.stringify(results[operation]) } }));
    },
  } };
  return {
    calls,
    restore() {
      if (previousModules === undefined) delete globals.NativeModules; else globals.NativeModules = previousModules;
      if (previousLynx === undefined) delete globals.lynx; else globals.lynx = previousLynx;
    },
  };
}

describe("SQLite facade", () => {
  test("sends parameterized calls and returns typed results", async () => {
    const native = mockNative();
    try {
      expect(sqlite.isAvailable()).toBe(true);
      const db = await sqlite.open({ name: "notes", version: 1 });
      expect(await db.execute("INSERT INTO notes VALUES (?, ?)", [sqlite.int64("9007199254740992"), sqlite.blob(new Uint8Array([1, 2, 3]))]))
        .toEqual({ changes: 1, lastInsertRowId: 3 });
      expect(native.calls[1].payload.params).toEqual([{ $int64: "9007199254740992" }, { $blob: "AQID" }]);
      expect(await db.query("SELECT value FROM notes")).toEqual({ columns: ["value"], rows: [{ value: 3 }] });
      expect(await db.transaction([{ sql: "DELETE FROM notes WHERE id = ?", params: [3] }])).toEqual({ results: [{ changes: 1, lastInsertRowId: 3 }] });
      await db.close();
      await expect(db.query("SELECT 1")).rejects.toMatchObject({ code: "CLOSED" });
      expect(native.calls.map((call) => call.operation)).toEqual(["open", "execute", "query", "transaction", "close"]);
    } finally {
      native.restore();
    }
  });

  test("rejects unsafe values before reaching native code", async () => {
    const native = mockNative();
    try {
      await expect(sqlite.open({ name: "../outside", version: 1 })).rejects.toMatchObject({ code: "INVALID_NAME" });
      const db = await sqlite.open({ name: "safe", version: 1 });
      await expect(db.execute("SELECT ?", [Number.NaN])).rejects.toMatchObject({ code: "INVALID_ARGUMENT" });
      await expect(db.execute("SELECT ?", [Number.MAX_SAFE_INTEGER + 1])).rejects.toMatchObject({ code: "INVALID_ARGUMENT" });
      expect(() => sqlite.int64("9223372036854775808")).toThrow();
      expect(sqlite.int64("-9223372036854775808")).toEqual({ $int64: "-9223372036854775808" });
      expect(native.calls.length).toBe(1);
      await db.close();
    } finally {
      native.restore();
    }
  });
});
