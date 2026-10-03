import { ConnectorError } from "./bridge";
import type {
  ExecuteResult,
  QueryResult,
  SqlOperation,
  SqlRow,
  SqlValue,
  SQLiteDatabase,
} from "./sqlite";

export type CompareOp =
  | "="
  | "!="
  | "<>"
  | "<"
  | ">"
  | "<="
  | ">="
  | "LIKE"
  | "NOT LIKE"
  | "IN"
  | "NOT IN"
  | "BETWEEN"
  | "IS"
  | "IS NOT";

export type SortDir = "ASC" | "DESC";
export type JoinType = "INNER" | "LEFT" | "CROSS";
export type CompiledSql = { sql: string; params: SqlValue[] };
export type ConflictAction = "nothing" | { update: Record<string, SqlValue> };

const COMPARE_OPS = new Set<string>([
  "=", "!=", "<>", "<", ">", "<=", ">=",
  "LIKE", "NOT LIKE", "IN", "NOT IN", "BETWEEN", "IS", "IS NOT",
]);

const IDENT_PART = /^[A-Za-z_][A-Za-z0-9_]*$/;

type SelectColumn =
  | { kind: "star" }
  | { kind: "ident"; name: string }
  | { kind: "count_star"; alias: string };

type OrderItem = { column: string; direction: SortDir };

type JoinClause = {
  type: JoinType;
  table: string;
  onLeft?: string;
  onOp?: CompareOp;
  onRight?: string;
};

type PredNode =
  | { kind: "cmp"; column: string; op: CompareOp; value: SqlValue | SqlValue[] }
  | { kind: "and" | "or"; items: PredNode[] }
  | { kind: "group"; item: PredNode };

type WhereArg =
  | [column: string, op: CompareOp, value: SqlValue | SqlValue[]]
  | [fn: (w: WhereBuilder) => WhereBuilder];

function fail(code: string, message: string): never {
  throw new ConnectorError(code, message);
}

/** Validates a single SQL identifier segment. */
export function assertIdentPart(name: string, label = "Identifier"): string {
  if (typeof name !== "string" || !IDENT_PART.test(name)) {
    fail("INVALID_IDENTIFIER", `${label} must match [A-Za-z_][A-Za-z0-9_]*.`);
  }
  return name;
}

/** Validates `column` or `table.column`. */
export function assertIdent(name: string, label = "Identifier"): string {
  if (typeof name !== "string") fail("INVALID_IDENTIFIER", `${label} must be a string.`);
  const parts = name.split(".");
  if (parts.length === 1) return assertIdentPart(parts[0], label);
  if (parts.length === 2) {
    assertIdentPart(parts[0], label);
    assertIdentPart(parts[1], label);
    return `${parts[0]}.${parts[1]}`;
  }
  fail("INVALID_IDENTIFIER", `${label} must be an unqualified or table.column name.`);
}

function assertOp(op: string): CompareOp {
  if (typeof op === "string" && COMPARE_OPS.has(op)) return op as CompareOp;
  const upper = typeof op === "string" ? op.trim().toUpperCase().replace(/\s+/g, " ") : "";
  if (COMPARE_OPS.has(upper)) return upper as CompareOp;
  fail("INVALID_OPERATOR", `Unsupported comparison operator: ${String(op)}`);
}

function assertDirection(dir: string | undefined): SortDir {
  if (dir === undefined || dir === "ASC") return "ASC";
  if (dir === "DESC") return "DESC";
  fail("INVALID_ARGUMENT", 'ORDER BY direction must be "ASC" or "DESC".');
}

function assertNonNegInt(value: number, label: string): number {
  if (!Number.isSafeInteger(value) || value < 0) {
    fail("INVALID_ARGUMENT", `${label} must be a non-negative safe integer.`);
  }
  return value;
}

function assertTable(name: string): string {
  return assertIdentPart(name, "Table name");
}

function clonePred(node: PredNode | undefined): PredNode | undefined {
  if (!node) return undefined;
  switch (node.kind) {
    case "cmp":
      return {
        kind: "cmp",
        column: node.column,
        op: node.op,
        value: Array.isArray(node.value) ? [...node.value] : node.value,
      };
    case "and":
    case "or":
      return { kind: node.kind, items: node.items.map((item) => clonePred(item)!) };
    case "group":
      return { kind: "group", item: clonePred(node.item)! };
  }
}

function appendPred(existing: PredNode | undefined, next: PredNode, join: "and" | "or"): PredNode {
  if (!existing) return next;
  if (existing.kind === join) return { kind: join, items: [...existing.items, next] };
  return { kind: join, items: [existing, next] };
}

function predFromArgs(args: WhereArg): PredNode {
  if (args.length === 1) {
    const built = args[0](new WhereBuilder(undefined)).toNode();
    if (!built) fail("INVALID_ARGUMENT", "WHERE group callback produced no predicates.");
    return { kind: "group", item: built };
  }
  const [column, op, value] = args;
  return { kind: "cmp", column: assertIdent(column, "Column"), op: assertOp(op), value };
}

function compilePred(node: PredNode, params: SqlValue[]): string {
  switch (node.kind) {
    case "group":
      return `(${compilePred(node.item, params)})`;
    case "and":
      return node.items.map((item) => compilePred(item, params)).join(" AND ");
    case "or":
      return node.items.map((item) => compilePred(item, params)).join(" OR ");
    case "cmp": {
      const op = node.op;
      if (op === "IN" || op === "NOT IN") {
        if (!Array.isArray(node.value)) {
          fail("INVALID_ARGUMENT", `${op} expects an array of values.`);
        }
        if (node.value.length === 0) {
          // Safe empty-set predicate; never interpolate user text.
          return op === "IN" ? "0 = 1" : "1 = 1";
        }
        const placeholders = node.value.map(() => "?").join(", ");
        for (const item of node.value) params.push(item);
        return `${node.column} ${op} (${placeholders})`;
      }
      if (op === "BETWEEN") {
        if (!Array.isArray(node.value) || node.value.length !== 2) {
          fail("INVALID_ARGUMENT", "BETWEEN expects [low, high].");
        }
        params.push(node.value[0], node.value[1]);
        return `${node.column} BETWEEN ? AND ?`;
      }
      if (op === "IS" || op === "IS NOT") {
        if (node.value !== null && typeof node.value !== "boolean") {
          fail("INVALID_ARGUMENT", `${op} only accepts null or boolean.`);
        }
        if (node.value === null) return `${node.column} ${op} NULL`;
        params.push(node.value);
        return `${node.column} ${op} ?`;
      }
      if (Array.isArray(node.value)) {
        fail("INVALID_ARGUMENT", `${op} expects a single value.`);
      }
      params.push(node.value);
      return `${node.column} ${op} ?`;
    }
  }
}

/** Mutable predicate builder used inside where/and/or callbacks. */
export class WhereBuilder {
  private readonly root: PredNode | undefined;

  constructor(root: PredNode | undefined) {
    this.root = root;
  }

  where(...args: WhereArg): WhereBuilder {
    return new WhereBuilder(appendPred(this.root, predFromArgs(args), "and"));
  }

  and(...args: WhereArg): WhereBuilder {
    return new WhereBuilder(appendPred(this.root, predFromArgs(args), "and"));
  }

  or(...args: WhereArg): WhereBuilder {
    return new WhereBuilder(appendPred(this.root, predFromArgs(args), "or"));
  }

  toNode(): PredNode | undefined {
    return clonePred(this.root);
  }
}

type SelectState = {
  columns: SelectColumn[];
  table?: string;
  joins: JoinClause[];
  where?: PredNode;
  groupBy: string[];
  having?: PredNode;
  orderBy: OrderItem[];
  limit?: number;
  offset?: number;
};

type InsertState = {
  table: string;
  rows: Record<string, SqlValue>[];
  orReplace: boolean;
  conflictTarget?: string[];
  conflictAction?: ConflictAction;
};

type UpdateState = {
  table: string;
  patch: Record<string, SqlValue>;
  where?: PredNode;
  allowAll: boolean;
};

type DeleteState = {
  table: string;
  where?: PredNode;
  allowAll: boolean;
};

function parseSelectColumns(columns: string[]): SelectColumn[] {
  if (columns.length === 0) fail("INVALID_ARGUMENT", "SELECT requires at least one column or *.");
  return columns.map((column) => {
    if (column === "*") return { kind: "star" as const };
    return { kind: "ident" as const, name: assertIdent(column, "Column") };
  });
}

function renderSelectColumns(columns: SelectColumn[]): string {
  return columns.map((column) => {
    switch (column.kind) {
      case "star": return "*";
      case "ident": return column.name;
      case "count_star": return `COUNT(*) AS ${column.alias}`;
    }
  }).join(", ");
}

function withWhere(
  current: PredNode | undefined,
  join: "and" | "or",
  args: WhereArg,
): PredNode {
  return appendPred(current, predFromArgs(args), join);
}

export class SelectBuilder {
  private readonly state: SelectState;

  constructor(state: SelectState) {
    this.state = state;
  }

  private copy(patch: Partial<SelectState>): SelectBuilder {
    return new SelectBuilder({
      columns: this.state.columns.map((column) => ({ ...column })),
      table: this.state.table,
      joins: this.state.joins.map((join) => ({ ...join })),
      where: clonePred(this.state.where),
      groupBy: [...this.state.groupBy],
      having: clonePred(this.state.having),
      orderBy: this.state.orderBy.map((item) => ({ ...item })),
      limit: this.state.limit,
      offset: this.state.offset,
      ...patch,
    });
  }

  from(table: string): SelectBuilder {
    return this.copy({ table: assertTable(table) });
  }

  join(table: string, onLeft: string, onOp: CompareOp, onRight: string): SelectBuilder {
    return this.addJoin("INNER", table, onLeft, onOp, onRight);
  }

  innerJoin(table: string, onLeft: string, onOp: CompareOp, onRight: string): SelectBuilder {
    return this.addJoin("INNER", table, onLeft, onOp, onRight);
  }

  leftJoin(table: string, onLeft: string, onOp: CompareOp, onRight: string): SelectBuilder {
    return this.addJoin("LEFT", table, onLeft, onOp, onRight);
  }

  crossJoin(table: string): SelectBuilder {
    return this.copy({
      joins: [...this.state.joins, { type: "CROSS", table: assertTable(table) }],
    });
  }

  private addJoin(
    type: JoinType,
    table: string,
    onLeft: string,
    onOp: CompareOp,
    onRight: string,
  ): SelectBuilder {
    return this.copy({
      joins: [...this.state.joins, {
        type,
        table: assertTable(table),
        onLeft: assertIdent(onLeft, "Join column"),
        onOp: assertOp(onOp),
        onRight: assertIdent(onRight, "Join column"),
      }],
    });
  }

  where(...args: WhereArg): SelectBuilder {
    return this.copy({ where: withWhere(this.state.where, "and", args) });
  }

  and(...args: WhereArg): SelectBuilder {
    return this.copy({ where: withWhere(this.state.where, "and", args) });
  }

  or(...args: WhereArg): SelectBuilder {
    return this.copy({ where: withWhere(this.state.where, "or", args) });
  }

  groupBy(...columns: string[]): SelectBuilder {
    if (columns.length === 0) fail("INVALID_ARGUMENT", "GROUP BY requires at least one column.");
    return this.copy({ groupBy: columns.map((column) => assertIdent(column, "GROUP BY column")) });
  }

  having(...args: WhereArg): SelectBuilder {
    return this.copy({ having: withWhere(this.state.having, "and", args) });
  }

  orderBy(column: string, direction?: SortDir): SelectBuilder {
    return this.copy({
      orderBy: [...this.state.orderBy, {
        column: assertIdent(column, "ORDER BY column"),
        direction: assertDirection(direction),
      }],
    });
  }

  limit(n: number): SelectBuilder {
    return this.copy({ limit: assertNonNegInt(n, "LIMIT") });
  }

  offset(n: number): SelectBuilder {
    return this.copy({ offset: assertNonNegInt(n, "OFFSET") });
  }

  compile(): CompiledSql {
    if (!this.state.table) fail("INVALID_ARGUMENT", "SELECT requires from(table).");
    const params: SqlValue[] = [];
    const parts = [
      `SELECT ${renderSelectColumns(this.state.columns)}`,
      `FROM ${this.state.table}`,
    ];
    for (const join of this.state.joins) {
      if (join.type === "CROSS") {
        parts.push(`CROSS JOIN ${join.table}`);
        continue;
      }
      parts.push(`${join.type} JOIN ${join.table} ON ${join.onLeft} ${join.onOp} ${join.onRight}`);
    }
    if (this.state.where) parts.push(`WHERE ${compilePred(this.state.where, params)}`);
    if (this.state.groupBy.length > 0) parts.push(`GROUP BY ${this.state.groupBy.join(", ")}`);
    if (this.state.having) parts.push(`HAVING ${compilePred(this.state.having, params)}`);
    if (this.state.orderBy.length > 0) {
      parts.push(`ORDER BY ${this.state.orderBy.map((item) => `${item.column} ${item.direction}`).join(", ")}`);
    }
    if (this.state.limit !== undefined) parts.push(`LIMIT ${this.state.limit}`);
    if (this.state.offset !== undefined) {
      if (this.state.limit === undefined) fail("INVALID_ARGUMENT", "OFFSET requires LIMIT.");
      parts.push(`OFFSET ${this.state.offset}`);
    }
    return { sql: parts.join(" "), params };
  }

  toOperation(): SqlOperation {
    const compiled = this.compile();
    return { sql: compiled.sql, params: compiled.params };
  }

  query<T extends SqlRow = SqlRow>(db: SQLiteDatabase): Promise<QueryResult<T>> {
    const compiled = this.compile();
    return db.query<T>(compiled.sql, compiled.params);
  }
}

export class InsertBuilder {
  private readonly state: InsertState;

  constructor(state: InsertState) {
    this.state = state;
  }

  private copy(patch: Partial<InsertState>): InsertBuilder {
    return new InsertBuilder({
      table: this.state.table,
      rows: this.state.rows.map((row) => ({ ...row })),
      orReplace: this.state.orReplace,
      conflictTarget: this.state.conflictTarget ? [...this.state.conflictTarget] : undefined,
      conflictAction: this.state.conflictAction && typeof this.state.conflictAction === "object"
        ? { update: { ...this.state.conflictAction.update } }
        : this.state.conflictAction,
      ...patch,
    });
  }

  values(row: Record<string, SqlValue>): InsertBuilder {
    if (!row || typeof row !== "object" || Array.isArray(row)) {
      fail("INVALID_ARGUMENT", "INSERT values must be an object.");
    }
    const keys = Object.keys(row);
    if (keys.length === 0) fail("INVALID_ARGUMENT", "INSERT values must not be empty.");
    for (const key of keys) assertIdentPart(key, "Column");
    return this.copy({ rows: [...this.state.rows, { ...row }] });
  }

  valuesMany(rows: Record<string, SqlValue>[]): InsertBuilder {
    if (!Array.isArray(rows) || rows.length === 0) {
      fail("INVALID_ARGUMENT", "valuesMany requires a non-empty array.");
    }
    let builder: InsertBuilder = this;
    for (const row of rows) builder = builder.values(row);
    const keys = Object.keys(builder.state.rows[0]);
    for (const row of builder.state.rows) {
      if (Object.keys(row).length !== keys.length ||
        keys.some((key) => !Object.prototype.hasOwnProperty.call(row, key))) {
        fail("INVALID_ARGUMENT", "All INSERT rows must share the same columns.");
      }
    }
    return builder;
  }

  orReplace(): InsertBuilder {
    if (this.state.conflictTarget) {
      fail("INVALID_ARGUMENT", "orReplace cannot be combined with onConflict.");
    }
    return this.copy({ orReplace: true });
  }

  onConflict(targetColumns: string[], action: ConflictAction): InsertBuilder {
    if (this.state.orReplace) {
      fail("INVALID_ARGUMENT", "onConflict cannot be combined with orReplace.");
    }
    if (!Array.isArray(targetColumns) || targetColumns.length === 0) {
      fail("INVALID_ARGUMENT", "onConflict requires at least one target column.");
    }
    const target = targetColumns.map((column) => assertIdentPart(column, "Conflict column"));
    if (action !== "nothing") {
      if (!action || typeof action !== "object" || !action.update || typeof action.update !== "object") {
        fail("INVALID_ARGUMENT", 'onConflict action must be "nothing" or { update: {...} }.');
      }
      const keys = Object.keys(action.update);
      if (keys.length === 0) fail("INVALID_ARGUMENT", "onConflict DO UPDATE requires at least one column.");
      for (const key of keys) assertIdentPart(key, "Column");
    }
    return this.copy({ conflictTarget: target, conflictAction: action });
  }

  compile(): CompiledSql {
    if (this.state.rows.length === 0) fail("INVALID_ARGUMENT", "INSERT requires values(...).");
    const columns = Object.keys(this.state.rows[0]);
    for (const row of this.state.rows) {
      for (const column of columns) {
        if (!Object.prototype.hasOwnProperty.call(row, column)) {
          fail("INVALID_ARGUMENT", "All INSERT rows must share the same columns.");
        }
      }
      if (Object.keys(row).length !== columns.length) {
        fail("INVALID_ARGUMENT", "All INSERT rows must share the same columns.");
      }
    }
    const params: SqlValue[] = [];
    const verb = this.state.orReplace ? "INSERT OR REPLACE" : "INSERT";
    const rowSql = this.state.rows.map((row) => {
      const placeholders = columns.map((column) => {
        params.push(row[column]);
        return "?";
      });
      return `(${placeholders.join(", ")})`;
    }).join(", ");
    let sql = `${verb} INTO ${this.state.table} (${columns.join(", ")}) VALUES ${rowSql}`;
    if (this.state.conflictTarget && this.state.conflictAction) {
      sql += ` ON CONFLICT (${this.state.conflictTarget.join(", ")})`;
      if (this.state.conflictAction === "nothing") {
        sql += " DO NOTHING";
      } else {
        const updates = this.state.conflictAction.update;
        const assignments = Object.keys(updates).map((column) => {
          params.push(updates[column]);
          return `${column} = ?`;
        });
        sql += ` DO UPDATE SET ${assignments.join(", ")}`;
      }
    }
    return { sql, params };
  }

  toOperation(): SqlOperation {
    const compiled = this.compile();
    return { sql: compiled.sql, params: compiled.params };
  }

  execute(db: SQLiteDatabase): Promise<ExecuteResult> {
    const compiled = this.compile();
    return db.execute(compiled.sql, compiled.params);
  }
}

export class UpdateBuilder {
  private readonly state: UpdateState;

  constructor(state: UpdateState) {
    this.state = state;
  }

  private copy(patch: Partial<UpdateState>): UpdateBuilder {
    return new UpdateBuilder({
      table: this.state.table,
      patch: { ...this.state.patch },
      where: clonePred(this.state.where),
      allowAll: this.state.allowAll,
      ...patch,
    });
  }

  set(patch: Record<string, SqlValue>): UpdateBuilder {
    if (!patch || typeof patch !== "object" || Array.isArray(patch)) {
      fail("INVALID_ARGUMENT", "UPDATE set() requires an object.");
    }
    const keys = Object.keys(patch);
    if (keys.length === 0) fail("INVALID_ARGUMENT", "UPDATE set() requires at least one column.");
    for (const key of keys) assertIdentPart(key, "Column");
    return this.copy({ patch: { ...this.state.patch, ...patch } });
  }

  where(...args: WhereArg): UpdateBuilder {
    return this.copy({ where: withWhere(this.state.where, "and", args), allowAll: false });
  }

  and(...args: WhereArg): UpdateBuilder {
    return this.copy({ where: withWhere(this.state.where, "and", args) });
  }

  or(...args: WhereArg): UpdateBuilder {
    return this.copy({ where: withWhere(this.state.where, "or", args) });
  }

  /** Explicit unrestricted UPDATE. Prefer where(...) whenever possible. */
  all(): UpdateBuilder {
    return this.copy({ allowAll: true, where: undefined });
  }

  compile(): CompiledSql {
    const columns = Object.keys(this.state.patch);
    if (columns.length === 0) fail("INVALID_ARGUMENT", "UPDATE requires set(...).");
    if (!this.state.where && !this.state.allowAll) {
      fail("INVALID_ARGUMENT", "UPDATE requires where(...) or explicit all().");
    }
    const params: SqlValue[] = [];
    const assignments = columns.map((column) => {
      params.push(this.state.patch[column]);
      return `${column} = ?`;
    });
    const parts = [`UPDATE ${this.state.table}`, `SET ${assignments.join(", ")}`];
    if (this.state.where) parts.push(`WHERE ${compilePred(this.state.where, params)}`);
    return { sql: parts.join(" "), params };
  }

  toOperation(): SqlOperation {
    const compiled = this.compile();
    return { sql: compiled.sql, params: compiled.params };
  }

  execute(db: SQLiteDatabase): Promise<ExecuteResult> {
    const compiled = this.compile();
    return db.execute(compiled.sql, compiled.params);
  }
}

export class DeleteBuilder {
  private readonly state: DeleteState;

  constructor(state: DeleteState) {
    this.state = state;
  }

  private copy(patch: Partial<DeleteState>): DeleteBuilder {
    return new DeleteBuilder({
      table: this.state.table,
      where: clonePred(this.state.where),
      allowAll: this.state.allowAll,
      ...patch,
    });
  }

  where(...args: WhereArg): DeleteBuilder {
    return this.copy({ where: withWhere(this.state.where, "and", args), allowAll: false });
  }

  and(...args: WhereArg): DeleteBuilder {
    return this.copy({ where: withWhere(this.state.where, "and", args) });
  }

  or(...args: WhereArg): DeleteBuilder {
    return this.copy({ where: withWhere(this.state.where, "or", args) });
  }

  /** Explicit unrestricted DELETE. Prefer where(...) whenever possible. */
  all(): DeleteBuilder {
    return this.copy({ allowAll: true, where: undefined });
  }

  compile(): CompiledSql {
    if (!this.state.where && !this.state.allowAll) {
      fail("INVALID_ARGUMENT", "DELETE requires where(...) or explicit all().");
    }
    const params: SqlValue[] = [];
    const parts = [`DELETE FROM ${this.state.table}`];
    if (this.state.where) parts.push(`WHERE ${compilePred(this.state.where, params)}`);
    return { sql: parts.join(" "), params };
  }

  toOperation(): SqlOperation {
    const compiled = this.compile();
    return { sql: compiled.sql, params: compiled.params };
  }

  execute(db: SQLiteDatabase): Promise<ExecuteResult> {
    const compiled = this.compile();
    return db.execute(compiled.sql, compiled.params);
  }
}

/** Fluent SQL builder that compiles to parameterized statements for the SQLite plugin. */
export const qb = {
  select(...columns: string[]): SelectBuilder {
    return new SelectBuilder({
      columns: parseSelectColumns(columns.length === 0 ? ["*"] : columns),
      joins: [],
      groupBy: [],
      orderBy: [],
    });
  },

  count(table: string): SelectBuilder {
    return new SelectBuilder({
      columns: [{ kind: "count_star", alias: assertIdentPart("count", "Alias") }],
      table: assertTable(table),
      joins: [],
      groupBy: [],
      orderBy: [],
    });
  },

  insertInto(table: string): InsertBuilder {
    return new InsertBuilder({
      table: assertTable(table),
      rows: [],
      orReplace: false,
    });
  },

  update(table: string): UpdateBuilder {
    return new UpdateBuilder({
      table: assertTable(table),
      patch: {},
      allowAll: false,
    });
  },

  deleteFrom(table: string): DeleteBuilder {
    return new DeleteBuilder({
      table: assertTable(table),
      allowAll: false,
    });
  },
};
