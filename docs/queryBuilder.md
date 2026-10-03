# QueryBuilder para el plugin SQLite

Documento de diseño e implementación del **QueryBuilder** tipado del plugin `sqlite`.

**Estado: implementado.** Guía de uso en inglés (API pública, ejemplos y reglas anti SQL injection): [`docs/sqlite.md`](./sqlite.md). Código: `packages/js/src/sqlite-query-builder.ts`, exportado como `qb` desde `lynx-android-plugins/sqlite`. Ejemplo de app: `examples/todo/`.

Este archivo conserva el diseño, el modelo de seguridad y el plan de referencia.

---

## 1. Resumen ejecutivo

El plugin SQLite actual ya permite SQL parametrizado (`?`) desde JavaScript y enlaza valores de forma segura en nativo (`SQLiteProgram`). El riesgo restante aparece cuando el código de la app **interpola** en el texto SQL:

- nombres de tabla o columna que vienen de input de usuario,
- direcciones `ORDER BY`,
- listas `IN` armadas a mano,
- fragmentos `WHERE` concatenados.

Un QueryBuilder en la capa JavaScript debe:

1. Generar SQL + array de `params` listos para `db.query` / `db.execute` / `db.transaction`.
2. Validar **identificadores** y **operadores** con allowlists.
3. Enviar **todos los valores** como parámetros enlazados (`?`).
4. Exponer una API fluida que cubra SELECT, INSERT, UPDATE, DELETE, JOIN, GROUP BY, HAVING, ORDER BY, LIMIT/OFFSET, UPSERT y predicados anidados.

**Recomendación de ubicación:** capa **JS pura** sobre el bridge existente. No hace falta cambiar `android-sqlite` en la v1.

---

## 2. Estado actual del plugin

### Capacidades

| Capa | Ubicación | Rol |
| --- | --- | --- |
| JS facade | `packages/js/src/sqlite.ts` | `open`, `query`, `execute`, `transaction`, `close`; tipos `SqlValue`, `int64`, `blob` |
| Nativo | `android-sqlite/.../LynxSqlitePlugin.kt` | Worker serial, `bind` de params, migraciones, límites de resultado |
| Docs | `docs/sqlite.md` | Uso, parámetros, migraciones, trust model |
| Ejemplo | `examples/todo/` | INSERT/SELECT/DELETE con `?` |

### Seguridad de valores (ya cubierta)

```ts
await db.execute("INSERT INTO tasks (title) VALUES (?)", [title]);
await db.query("SELECT id, title FROM tasks WHERE id = ?", [id]);
```

En nativo, cada `?` se enlaza con `bindNull` / `bindString` / `bindLong` / `bindDouble` / `bindBlob`. Un string como `"'; DROP TABLE tasks;--"` se guarda o compara como dato; no se ejecuta como SQL.

### Hueco que cierra el QueryBuilder

```ts
// PELIGROSO si `column` o `direction` vienen del usuario
await db.query(`SELECT * FROM tasks ORDER BY ${column} ${direction}`);
```

El QueryBuilder debe rechazar o sanitizar ese tipo de interpolación.

### Límites del conector que el builder debe respetar

- Máximo **1000 filas** por `query`; respuesta máxima **1 MiB**.
- Un statement por `execute`; batches vía `transaction([{ sql, params }, ...])`.
- Migraciones siguen siendo SQL de confianza del autor de la app (DDL), fuera del QueryBuilder.
- Bundle de confianza: el JS elige el SQL; el builder reduce errores e inyección accidental/maliciosa en inputs dinámicos.

---

## 3. Dónde debe vivir el QueryBuilder

### Opción A — Capa JS pura (recomendada)

El builder vive en `packages/js` y solo produce `{ sql: string; params: SqlValue[] }`. Luego llama a la API existente.

Ventajas:

- Reutiliza el binding nativo ya auditado.
- Tests unitarios en Bun sin emulador.
- Sin cambios en Maven/`LynxSqlitePlugin`.
- Tree-shakeable si se separa en archivo propio y se reexporta desde `./sqlite`.

### Opción B — Bridge nativo + JS

El host recibiría un AST o un JSON de “operaciones tipadas” y armaría el SQL en Kotlin.

Desventajas para v1:

- Duplica reglas de validación en dos lenguajes.
- Amplía superficie del módulo nativo y del protocolo JSON.
- No mejora la seguridad de valores (ya está resuelta).
- Más lento de iterar y de probar.

### Decisión

**Implementar solo en JavaScript (opción A).** Exportar desde el entry `lynx-android-plugins/sqlite` para no fragmentar el plugin. Si el tamaño del facade crece demasiado, el código puede vivir en `sqlite-query-builder.ts` y reexportarse desde `sqlite.ts`.

---

## 4. Modelo de seguridad (SQL injection)

### Principios

1. **Valores → parámetros.** Nunca interpolar literales de usuario en el SQL.
2. **Identificadores → allowlist o quoting estricto.**
3. **Operadores y keywords → conjuntos cerrados.**
4. **Sin `raw(sql)` en v1.** Si se agrega después, debe documentarse como *trusted-only* y quedar fuera de rutas alimentadas por input externo.
5. **Fallar cerrado.** Input inválido → `ConnectorError` con código `INVALID_ARGUMENT` (o `INVALID_IDENTIFIER` / `INVALID_OPERATOR` si se quieren códigos más específicos).

### Identificadores

Regla v1 (simple y suficiente para esquemas de app):

```text
IDENT = /^[A-Za-z_][A-Za-z0-9_]*$/
QUALIFIED = IDENT | IDENT.IDENT   // table.column, alias.column
```

- Aceptar: `tasks`, `title`, `t.id`.
- Rechazar: `tasks; DROP`, `title DESC--`, espacios, comillas, `$`, `@`, backticks sueltos, `1=1`.

Opción avanzada (fase 2 si hace falta nombres con caracteres especiales): identificadores entre comillas dobles SQLite, escapando `"` como `""`, y validando que el contenido no contenga NUL ni saltos de línea. La v1 puede quedarse solo con la allowlist ASCII.

### Valores

Reutilizar `SqlValue` y `validateParams` existentes:

- `string | number | boolean | null | { $int64 } | { $blob }`
- Números finitos y safe integers; fuera de rango → `sqlite.int64(...)`.

Para `IN (...)` el builder genera `IN (?,?,?)` y empuja N params. Listas vacías: rechazar o emitir predicado imposible documentado (`0 = 1` para WHERE, nunca SQL vacío ambiguo).

### Operadores de comparación (allowlist)

```text
=  !=  <>  <  >  <=  >=
LIKE  NOT LIKE
IN  NOT IN
BETWEEN
IS  IS NOT
```

`IS` / `IS NOT` solo admiten `null` (o booleanos si se documenta); no concatenar el lado derecho como texto.

### Conectores lógicos

`AND`, `OR`, y grupos `( ... )` construidos por el builder, nunca por string del usuario.

### ORDER BY / GROUP BY

Solo identificadores validados. Dirección solo `ASC` | `DESC` (default `ASC`).

### JOIN

Tipo solo: `INNER` | `LEFT` | `CROSS` (v1). Condición `ON` con el mismo sistema de predicados que `WHERE`.

### Límites numéricos

`LIMIT` y `OFFSET` como enteros no negativos validados en JS; se pueden emitir como literales enteros (no son input libre de string) o como `?` bound. Preferencia: **enteros validados emitidos como literales** (`LIMIT 20`) porque SQLite/Android a veces trata mal binds en LIMIT según versión; la validación `Number.isSafeInteger` + `>= 0` evita inyección.

### Vectores de prueba obligatorios

| Input malicioso | Esperado |
| --- | --- |
| valor `"'; DROP TABLE tasks;--"` en WHERE | SQL con `?` + param; tabla intacta |
| columna `"id; DROP TABLE tasks--"` | rechazo `INVALID_IDENTIFIER` |
| dirección `"ASC; DELETE FROM tasks"` | rechazo |
| `IN` con strings de usuario | solo `?` repetidos |
| tabla `"tasks UNION SELECT ..."` | rechazo |
| operador `"= OR 1=1"` | rechazo |

---

## 5. API propuesta (extensión de posibilidades)

### Forma general

```ts
import { sqlite, qb } from "lynx-android-plugins/sqlite";

const db = await sqlite.open({ name: "app", version: 1, migrations: [...] });

const { rows } = await qb
  .select("id", "title")
  .from("tasks")
  .where("title", "LIKE", `%${q}%`)  // valor → param
  .orderBy("id", "DESC")
  .limit(50)
  .query(db);

const compiled = qb.insertInto("tasks").values({ title: "Buy milk" }).compile();
// { sql: "INSERT INTO tasks (title) VALUES (?)", params: ["Buy milk"] }
```

Nombre exportado sugerido: `qb` (corto) + tipos `QueryBuilder` / builders por verbo. Alternativa: `sqlite.qb` colgando del namespace.

### Superficie por verbo

#### SELECT

```ts
qb.select(...columns: string[] | ["*"])
  .from(table: string)
  .join / .innerJoin / .leftJoin / .crossJoin(table, onLeft, onOp?, onRight?)
  .where(column, op, value) | .where(group => ...)
  .and(...) / .or(...)
  .groupBy(...columns)
  .having(column, op, value)
  .orderBy(column, direction?: "ASC" | "DESC")
  .limit(n)
  .offset(n)
  .compile()
  .query(db)
  .toOperation()  // para transaction
```

Helpers:

- `qb.count(table).where(...).query(db)` → `SELECT COUNT(*) AS count FROM ...`
- `qb.exists(subselect)` como predicado (fase 2)

#### INSERT

```ts
qb.insertInto(table)
  .values(row: Record<string, SqlValue>)
  .valuesMany(rows: Record<string, SqlValue>[])  // multi-row, misma clave
  .orReplace()          // INSERT OR REPLACE
  .onConflict(targetColumns, action)  // fase 2: DO UPDATE / DO NOTHING
  .compile()
  .execute(db)
```

#### UPDATE

```ts
qb.update(table)
  .set(patch: Record<string, SqlValue>)
  .where(...)
  .compile()
  .execute(db)
```

Exigir al menos un `where` en v1 (o método explícito `.all()` documentado como peligroso) para evitar updates globales accidentales.

#### DELETE

```ts
qb.deleteFrom(table)
  .where(...)
  .compile()
  .execute(db)
```

Misma regla: `where` obligatorio salvo `.all()` explícito.

### Predicados anidados

```ts
.where((w) => w
  .where("done", "=", 0)
  .and((w2) => w2.where("priority", ">=", 2).or("assignee", "=", userId))
)
```

El builder mantiene un árbol de nodos `{ type: "cmp" | "and" | "or" | "group", ... }` y al compilar emite SQL + params en orden.

### `compile()` y ejecución

```ts
type CompiledSql = { sql: string; params: SqlValue[] };

interface ExecutableSelect {
  compile(): CompiledSql;
  query<T extends SqlRow>(db: SQLiteDatabase): Promise<QueryResult<T>>;
  toOperation(): SqlOperation;
}

interface ExecutableWrite {
  compile(): CompiledSql;
  execute(db: SQLiteDatabase): Promise<ExecuteResult>;
  toOperation(): SqlOperation;
}
```

`transaction` queda así:

```ts
await db.transaction([
  qb.insertInto("tasks").values({ title: "A" }).toOperation(),
  qb.update("tasks").set({ title: "B" }).where("id", "=", 1).toOperation(),
]);
```

### Fuera de v1 (documentar como backlog)

| Feature | Motivo |
| --- | --- |
| DDL (`CREATE`/`ALTER`/`DROP`) | Ya cubierto por migraciones de confianza |
| CTE / `WITH RECURSIVE` | Complejidad alta; pocos casos en apps móviles típicas |
| Window functions | Poco frecuente; se puede usar SQL crudo confiable |
| `RETURNING` | Depende de versión SQLite del dispositivo Android |
| Subqueries arbitrarias en FROM | Se puede añadir en fase 2 con el mismo compilador |
| `raw()` | Abre inyección; solo trusted-only si algún día se necesita |

---

## 6. Ejemplos

### To-do (equivalente a `examples/todo`)

```ts
import { sqlite, qb } from "lynx-android-plugins/sqlite";

async function readTasks(db: SQLiteDatabase) {
  const { rows } = await qb
    .select("id", "title")
    .from("tasks")
    .orderBy("id", "DESC")
    .limit(1000)
    .query<{ id: number; title: string }>(db);
  return rows;
}

await qb.insertInto("tasks").values({ title }).execute(db);
await qb.deleteFrom("tasks").where("id", "=", id).execute(db);
```

### Filtros dinámicos seguros

```ts
let builder = qb.select("id", "title", "done").from("tasks");

if (status !== "all") {
  builder = builder.where("done", "=", status === "done" ? 1 : 0);
}
if (search) {
  builder = builder.and("title", "LIKE", `%${search}%`);
}
if (allowedSort.has(sortColumn)) {
  builder = builder.orderBy(sortColumn, sortDir === "ASC" ? "ASC" : "DESC");
}

const { rows } = await builder.limit(50).query(db);
```

Incluso si `sortColumn` se valida dos veces (app + builder), el builder es la última línea de defensa.

### JOIN + IN

```ts
const { rows } = await qb
  .select("t.id", "t.title", "u.name")
  .from("tasks")
  .leftJoin("users", "users.id", "=", "t.assignee_id")
  .where("t.id", "IN", ids)
  .orderBy("t.id", "DESC")
  .query(db);
```

Compilado esperado (esquema):

```sql
SELECT t.id, t.title, u.name
FROM tasks
LEFT JOIN users ON users.id = t.assignee_id
WHERE t.id IN (?, ?, ?)
ORDER BY t.id DESC
```

con `params = [...ids]`. Nota: en `ON` ambos lados son identificadores; en `WHERE ... IN` los elementos son valores.

---

## 7. Diseño interno (implementación)

### Módulos sugeridos

```text
packages/js/src/sqlite-query-builder.ts   # builders + compile + validateIdentifier
packages/js/src/sqlite.ts                 # reexport qb + tipos
packages/js/tests/sqlite-query-builder.test.ts
```

### Núcleo de validación

```ts
function assertIdent(name: string): string { /* IDENT o QUALIFIED */ }
function assertOp(op: string): string { /* allowlist */ }
function assertDirection(dir: string): "ASC" | "DESC" { ... }
```

### Compilación de predicados

Recorrido in-order del árbol:

1. Empujar fragmentos SQL a un array de strings.
2. Empujar valores a `params`.
3. `sql = parts.join(" ")`.

### Inmutabilidad

Cada método del fluent API debe devolver una **nueva** instancia (o copia profunda del estado) para poder reutilizar builders parciales sin mutación sorpresa:

```ts
const base = qb.select("id", "title").from("tasks");
const open = base.where("done", "=", 0);
const done = base.where("done", "=", 1);
```

### Integración con `open`

Opcional en fase 3: métodos de conveniencia en el handle:

```ts
db.select(...).from(...).query();  // sugar sobre qb + this
```

No es obligatorio; `qb.*(db)` basta para v1.

---

## 8. Plan de implementación por fases

### Fase 1 — Núcleo seguro (MVP)

1. Crear `sqlite-query-builder.ts` con:
   - `select` / `from` / `where` / `and` / `orderBy` / `limit` / `offset`
   - `insertInto` / `values`
   - `update` / `set` / `where`
   - `deleteFrom` / `where`
   - `compile`, `query`, `execute`, `toOperation`
   - validación de identificadores, operadores, direcciones, LIMIT/OFFSET
2. Reexportar `qb` desde `sqlite.ts`.
3. Incluir el archivo en el script `build` de `packages/js/package.json` si el bundler no lo arrastra solo (hoy se listan entries explícitas).
4. Tests Bun:
   - SQL + params esperados para cada verbo.
   - rechazo de identificadores/operadores maliciosos.
   - valor con comillas y `;` solo aparece en `params`.
5. Documentar en `docs/sqlite.md` una sección corta “Query builder” que enlace a este archivo o resuma el uso.

**Criterio de salida:** todos los tests verdes; cero cambios en Kotlin.

### Fase 2 — Extensión completa

1. `or`, grupos anidados `where(cb)`.
2. `innerJoin` / `leftJoin` / `crossJoin`.
3. `groupBy` / `having`.
4. `valuesMany`, `orReplace`, `onConflict` (DO NOTHING / DO UPDATE SET).
5. Helpers `count`, predicado `IN` / `BETWEEN` / `LIKE` / `IS NULL`.
6. Regla “WHERE obligatorio” en UPDATE/DELETE + `.all()`.
7. Tests de compilación de JOINs y de árboles AND/OR.

### Fase 3 — Pulido de producto

1. Tipos genéricos de fila en `query<T>()`.
2. Mención en README tabla de APIs.
3. (Opcional) refactor suave de `examples/todo` al builder.
4. Sync de docs (`packages/js/docs/sqlite.md`) con el flujo existente del repo.
5. Bump de versión del paquete JS cuando se publique.

### Fuera de este plan

- Cambios en `LynxSqlitePlugin.kt`.
- Nuevo artefacto Maven.
- QueryBuilder para otros motores (solo SQLite Android del plugin).

---

## 9. Archivos a tocar al implementar

| Archivo | Acción |
| --- | --- |
| `packages/js/src/sqlite-query-builder.ts` | **Crear** — API + compile + validación |
| `packages/js/src/sqlite.ts` | Reexportar `qb` y tipos públicos del builder |
| `packages/js/tests/sqlite-query-builder.test.ts` | **Crear** — unit tests + casos de inyección |
| `packages/js/package.json` | Añadir entry al `build` si hace falta |
| `docs/sqlite.md` | Sección de uso del QueryBuilder |
| `packages/js/docs/sqlite.md` | Sync / misma sección |
| `README.md` | Una línea en la tabla de API si aplica |
| `android-sqlite/**` | **Sin cambios en v1** |

---

## 10. Criterios de aceptación

- [ ] Todo valor de usuario termina en `params` con placeholder `?`.
- [ ] Identificadores fuera de la allowlist lanzan error antes de llamar al nativo.
- [ ] Operadores y `ASC`/`DESC` fuera de allowlist lanzan error.
- [ ] `SELECT` / `INSERT` / `UPDATE` / `DELETE` compilan SQL válido para el conector actual.
- [ ] `compile()` es puro (sin I/O); `query`/`execute` delegan en `SQLiteDatabase`.
- [ ] `toOperation()` integra con `db.transaction`.
- [ ] Tests cubren al menos los vectores de la tabla de seguridad.
- [ ] JOIN, GROUP BY, HAVING, UPSERT están implementados o listados explícitamente como fase 2 pendientes.
- [ ] No hay cambios en el módulo Kotlin para la v1.
- [ ] `docs/sqlite.md` describe el uso seguro y apunta a este diseño.

---

## 11. Esqueleto de API (referencia rápida)

```ts
// packages/js/src/sqlite-query-builder.ts (contrato objetivo)

import type { SqlValue, SqlRow, SqlOperation, QueryResult, ExecuteResult, SQLiteDatabase } from "./sqlite";

export type CompareOp =
  | "=" | "!=" | "<>" | "<" | ">" | "<=" | ">="
  | "LIKE" | "NOT LIKE"
  | "IN" | "NOT IN"
  | "BETWEEN"
  | "IS" | "IS NOT";

export type SortDir = "ASC" | "DESC";
export type CompiledSql = { sql: string; params: SqlValue[] };

export declare const qb: {
  select(...columns: string[]): SelectBuilder;
  insertInto(table: string): InsertBuilder;
  update(table: string): UpdateBuilder;
  deleteFrom(table: string): DeleteBuilder;
  count(table: string): SelectBuilder;
};
```

---

## 12. Resumen de la recomendación

Agregar el QueryBuilder como **biblioteca fluida en JavaScript** que compila a SQL parametrizado y reutiliza el plugin nativo actual. La protección contra SQL injection se apoya en tres capas: allowlist de identificadores, allowlist de operadores/keywords, y binding exclusivo de valores. La v1 cubre CRUD + WHERE + ORDER/LIMIT; la fase 2 completa JOINs, agregaciones, UPSERT y predicados anidados. El conector Kotlin permanece igual.
