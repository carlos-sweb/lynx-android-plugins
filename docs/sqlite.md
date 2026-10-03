# SQLite for Lynx on Android

This connector stores structured data in an app-private Android SQLite database. It exposes asynchronous, parameterized SQL to JavaScript running in Lynx's background thread. It does not require an Android permission. Stock Lynx Go does not include the native module; use a generated Android host or the repository's demo host.

The JavaScript package also ships a fluent **`qb` query builder**. Prefer `qb` for application queries: it validates table and column names, comparison operators, and sort directions, and binds every value as a `?` parameter so untrusted input cannot inject SQL.

## Install and run

Create a host with `--android-plugins sqlite`, or run `npx create-mithril-lynx add-android-plugin sqlite` inside an existing generated app. The host needs the `io.github.carlos-sweb:lynx-android-sqlite:0.3.0` Maven artifact; that version must be published before a generated host can resolve it from Maven Central. `all` requires the matching `lynx-android-plugins:0.3.0` aggregate artifact. The JavaScript entry point is `lynx-android-plugins/sqlite`.

For a local repository demo, run `bun install`, `bun run build:packages`, `bun tools/prepare-demo.mjs sqlite`, and `./gradlew :demo-host:installDebug` from the repository root.

The [to-do example](../examples/todo/) is the recommended walkthrough: it adds and deletes persistent tasks with `qb`. Run it with `bun tools/prepare-demo.mjs todo` followed by `./gradlew :demo-host:installDebug`. The demo host loads one example bundle at a time, so preparing another example replaces the bundled screen in the next APK build.

## Basic use with the query builder

```ts
import { sqlite, qb } from "lynx-android-plugins/sqlite";

const db = await sqlite.open({
  name: "notes",
  version: 1,
  migrations: [{ to: 1, statements: [
    "CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL)",
  ] }],
});

try {
  await qb.insertInto("notes").values({ body: "First note" }).execute(db);
  const { rows } = await qb
    .select("id", "body")
    .from("notes")
    .orderBy("id", "DESC")
    .limit(20)
    .query(db);
  console.log(rows);
} finally {
  await db.close();
}
```

`sqlite.isAvailable()` checks whether the Android host registered the module. `open` returns a handle with `query`, `execute`, `transaction`, and `close`. All methods return Promises. A closed handle rejects further operations with `ConnectorError` code `CLOSED`.

## Query builder (`qb`)

Import `qb` from `lynx-android-plugins/sqlite`. Builders are immutable: each chained call returns a new builder. Call `compile()` for `{ sql, params }`, `query(db)` / `execute(db)` to run against an open handle, or `toOperation()` to pass into `db.transaction([...])`.

### CRUD

```ts
await qb.insertInto("tasks").values({ title: "Buy milk" }).execute(db);
await qb.insertInto("tasks").valuesMany([{ title: "A" }, { title: "B" }]).execute(db);
await qb.update("tasks").set({ title: "Done" }).where("id", "=", id).execute(db);
await qb.deleteFrom("tasks").where("id", "=", id).execute(db);

const { rows } = await qb
  .select("id", "title")
  .from("tasks")
  .where("title", "LIKE", `%${search}%`)
  .and("done", "=", 0)
  .orderBy("id", "DESC")
  .limit(50)
  .query(db);
```

`UPDATE` and `DELETE` require `where(...)` unless you call explicit `all()` (unrestricted write).

### Joins, groups, upsert, and nested predicates

```ts
const { rows } = await qb
  .select("t.id", "t.title", "u.name")
  .from("tasks")
  .leftJoin("users", "users.id", "=", "t.assignee_id")
  .where("t.id", "IN", ids)
  .orderBy("t.id", "DESC")
  .query(db);

await qb.count("tasks").where("done", "=", 0).query(db);

await qb.insertInto("tasks").values({ id: 1, title: "A" })
  .onConflict(["id"], { update: { title: "B" } })
  .execute(db);

await qb.insertInto("tasks").orReplace().values({ id: 1, title: "A" }).execute(db);

await qb.select("id", "title").from("tasks").where((w) => w
  .where("done", "=", 0)
  .and((w2) => w2.where("priority", ">=", 2).or("assignee_id", "=", userId))
).query(db);

await db.transaction([
  qb.insertInto("tasks").values({ title: "A" }).toOperation(),
  qb.update("tasks").set({ title: "B" }).where("id", "=", 1).toOperation(),
]);
```

Supported surface: `select`, `from`, `where` / `and` / `or` (including nested groups), `innerJoin` / `leftJoin` / `crossJoin`, `groupBy` / `having`, `orderBy`, `limit` / `offset`, `insertInto` / `values` / `valuesMany`, `orReplace`, `onConflict`, `update` / `set`, `deleteFrom`, `count`, `compile`, `query`, `execute`, `toOperation`.

Comparison operators: `=`, `!=`, `<>`, `<`, `>`, `<=`, `>=`, `LIKE`, `NOT LIKE`, `IN`, `NOT IN`, `BETWEEN`, `IS`, `IS NOT`.

### SQL injection protection

| Input kind | How `qb` handles it |
| --- | --- |
| Values (strings, numbers, lists, `LIKE` patterns) | Always bound as `?` parameters; never interpolated into SQL text |
| Table / column names | Allowlist `[A-Za-z_][A-Za-z0-9_]*` or `table.column`; invalid names throw `INVALID_IDENTIFIER` |
| Operators and `ASC` / `DESC` | Closed allowlists; invalid input throws `INVALID_OPERATOR` or `INVALID_ARGUMENT` |
| Empty `IN ([])` | Compiles to a safe `0 = 1` predicate |

Native code still binds parameters through Android `SQLiteProgram`. Prefer `qb` whenever table names, column names, sort keys, or filter values come from UI or network input. Schema migrations stay author-trusted DDL strings (see below). Design notes: [`queryBuilder.md`](./queryBuilder.md).

## Parameters and raw SQL

You can still call `db.query` / `db.execute` with SQL text and `?` placeholders. Supported parameters are strings, finite safe JavaScript numbers, booleans (stored as `0` or `1`), `null`, `sqlite.int64(bigint | string)`, and `sqlite.blob(Uint8Array)`. Never concatenate untrusted values into SQL text. Integer results outside JavaScript's safe range return `{ $int64: "..." }`; BLOB results return `{ $blob: "base64..." }`. Other results are numbers, strings, or `null`. `query` returns `{ columns, rows }`, with each row keyed by column name; alias duplicate column names explicitly.

```ts
await db.execute("INSERT INTO notes (body) VALUES (?)", ["First note"]);
const { rows } = await db.query("SELECT id, body FROM notes ORDER BY id DESC LIMIT 20");
```

`execute` accepts one statement and returns `{ changes, lastInsertRowId }`. The row ID is `null` except for `INSERT` or `REPLACE`. Use it for writes and schema changes. `transaction([{ sql, params? }, ...])` executes all statements as one native transaction and returns `{ results }`; any error rolls back the whole batch. Do not pass manual `BEGIN`, `COMMIT`, or `ROLLBACK` statements to this method.

Queries are limited to 1,000 rows and the native response to 1 MiB. Use `LIMIT` and keyset pagination for larger datasets. A `RESULT_TOO_LARGE` error does not return truncated rows.

## Schema upgrades

Use increasing integer versions. Each `migrations` entry targets the version in `to` and contains SQL statements. The connector executes each version in a native transaction and updates SQLite's schema version only after all statements succeed. A missing upgrade path raises `MIGRATION_MISSING`; a lower requested version raises `VERSION_DOWNGRADE`. Neither case deletes the database. Keep all migrations needed by installed versions when shipping updates. Migration SQL is trusted author DDL; it is outside the query builder.

## Trust and lifecycle

This connector is intended for trusted app bundles. Parameter binding and `qb` prevent value and identifier injection for builder-built statements, but JavaScript still chooses which statements to run and can change or delete its own database. Database names are restricted to 1–64 ASCII letters, digits, underscores, or hyphens and files remain in the app-private database directory. Operations run on one native worker, not the Android UI thread. Close handles when finished; a fresh `open` can read data saved by an earlier app session.
