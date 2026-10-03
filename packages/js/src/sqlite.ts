import { ConnectorError, invoke, isAvailable } from "./bridge";
export {
  qb,
  assertIdent,
  assertIdentPart,
  WhereBuilder,
  SelectBuilder,
  InsertBuilder,
  UpdateBuilder,
  DeleteBuilder,
} from "./sqlite-query-builder";
export type {
  CompareOp,
  SortDir,
  JoinType,
  CompiledSql,
  ConflictAction,
} from "./sqlite-query-builder";

export { ConnectorError };

export type SqlBlob = { $blob: string };
export type SqlInt64 = { $int64: string };
export type SqlValue = string | number | boolean | null | SqlBlob | SqlInt64;
export type SqlRow = Record<string, SqlValue>;
export type SqlOperation = { sql: string; params?: SqlValue[] };
export type SqlMigration = { to: number; statements: string[] };
export type OpenOptions = { name: string; version: number; migrations?: SqlMigration[] };
export type QueryResult<T extends SqlRow = SqlRow> = { columns: string[]; rows: T[] };
export type ExecuteResult = { changes: number; lastInsertRowId: number | SqlInt64 | null };

export interface SQLiteDatabase {
  query<T extends SqlRow = SqlRow>(sql: string, params?: SqlValue[]): Promise<QueryResult<T>>;
  execute(sql: string, params?: SqlValue[]): Promise<ExecuteResult>;
  transaction(operations: SqlOperation[]): Promise<{ results: ExecuteResult[] }>;
  close(): Promise<void>;
}

const moduleName = "LynxSqlitePlugin";
const eventName = "lynxAndroidPlugins:sqlite";

function validateParams(params: SqlValue[]): void {
  if (!Array.isArray(params)) throw new ConnectorError("INVALID_ARGUMENT", "SQL parameters must be an array.");
  for (const value of params) {
    if (typeof value === "number" && !Number.isFinite(value)) {
      throw new ConnectorError("INVALID_ARGUMENT", "SQL numbers must be finite.");
    }
    if (typeof value === "number" && Number.isInteger(value) && !Number.isSafeInteger(value)) {
      throw new ConnectorError("INVALID_ARGUMENT", "Use sqlite.int64() for integers outside the safe JavaScript range.");
    }
    if (value !== null && typeof value === "object" &&
      !(typeof (value as SqlBlob).$blob === "string" || typeof (value as SqlInt64).$int64 === "string")) {
      throw new ConnectorError("INVALID_ARGUMENT", "Unsupported SQL parameter.");
    }
  }
}

async function call<T>(operation: string, payload: object): Promise<T> {
  const result = await invoke<{ payload: string }>(moduleName, eventName, "perform", [operation, JSON.stringify(payload)], 30_000);
  if (typeof result.payload !== "string") throw new ConnectorError("INVALID_RESPONSE", "SQLite returned no payload.");
  try { return JSON.parse(result.payload) as T; }
  catch { throw new ConnectorError("INVALID_RESPONSE", "SQLite returned invalid JSON."); }
}

function base64(bytes: Uint8Array): string {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  let output = "";
  for (let i = 0; i < bytes.length; i += 3) {
    const first = bytes[i];
    const second = bytes[i + 1];
    const third = bytes[i + 2];
    output += alphabet[first >> 2];
    output += alphabet[((first & 3) << 4) | ((second ?? 0) >> 4)];
    output += second === undefined ? "=" : alphabet[((second & 15) << 2) | ((third ?? 0) >> 6)];
    output += third === undefined ? "=" : alphabet[third & 63];
  }
  return output;
}

/** A typed, app-private SQLite bridge. Calls require a generated Android host. */
export const sqlite = {
  isAvailable: (): boolean => isAvailable(moduleName, "perform"),
  int64(value: bigint | string): SqlInt64 {
    const text = String(value);
    const digits = text.startsWith("-") ? text.slice(1) : text;
    const limit = text.startsWith("-") ? "9223372036854775808" : "9223372036854775807";
    if (!/^-?(0|[1-9][0-9]*)$/.test(text) || digits.length > limit.length ||
      (digits.length === limit.length && digits > limit)) {
      throw new ConnectorError("INVALID_ARGUMENT", "Expected a signed 64-bit integer.");
    }
    return { $int64: text };
  },
  blob(value: Uint8Array): SqlBlob {
    if (!(value instanceof Uint8Array)) throw new ConnectorError("INVALID_ARGUMENT", "Expected Uint8Array.");
    return { $blob: base64(value) };
  },
  async open(options: OpenOptions): Promise<SQLiteDatabase> {
    if (!options || typeof options.name !== "string" || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(options.name)) {
      throw new ConnectorError("INVALID_NAME", "Database name must be 1-64 ASCII letters, digits, underscores or hyphens.");
    }
    if (!Number.isSafeInteger(options.version) || options.version < 1) {
      throw new ConnectorError("INVALID_VERSION", "Database version must be a positive integer.");
    }
    const { handle } = await call<{ handle: string }>("open", options);
    let closed = false;
    const requireOpen = () => {
      if (closed) throw new ConnectorError("CLOSED", "Database is already closed.");
    };
    return {
      async query<T extends SqlRow = SqlRow>(sql: string, params: SqlValue[] = []): Promise<QueryResult<T>> {
        requireOpen(); validateParams(params);
        return call<QueryResult<T>>("query", { handle, sql, params });
      },
      async execute(sql: string, params: SqlValue[] = []): Promise<ExecuteResult> {
        requireOpen(); validateParams(params);
        return call<ExecuteResult>("execute", { handle, sql, params });
      },
      async transaction(operations: SqlOperation[]): Promise<{ results: ExecuteResult[] }> {
        requireOpen();
        if (!Array.isArray(operations)) throw new ConnectorError("INVALID_ARGUMENT", "Operations must be an array.");
        for (const operation of operations) validateParams(operation.params ?? []);
        return call<{ results: ExecuteResult[] }>("transaction", { handle, operations });
      },
      async close(): Promise<void> {
        if (closed) return;
        await call("close", { handle });
        closed = true;
      },
    };
  },
};
