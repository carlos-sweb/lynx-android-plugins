package dev.lynx.android.plugins.sqlite

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.lynx.jsbridge.LynxMethod
import com.lynx.react.bridge.JavaOnlyMap
import com.lynx.tasm.LynxViewBuilder
import dev.lynx.android.plugins.core.PluginModule
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** Registers app-private SQLite access for trusted Lynx bundles. */
object LynxSqlitePlugin {
    fun register(builder: LynxViewBuilder) {
        builder.registerModule("LynxSqlitePlugin", SqlitePlugin::class.java)
    }
}

private class SqliteFailure(val code: String, message: String) : Exception(message)

/** A single worker serializes requests and keeps transactions isolated. */
class SqlitePlugin(context: Context) : PluginModule(context, "lynxAndroidPlugins:sqlite") {
    companion object {
        private const val MAX_ROWS = 1_000
        private const val MAX_RESULT_BYTES = 1_048_576
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        private val worker = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())
        private val connections = mutableMapOf<String, SQLiteDatabase>()

        private fun bind(program: SQLiteProgram, params: JSONArray) {
            for (index in 0 until params.length()) {
                val value = params.get(index)
                when (value) {
                    JSONObject.NULL -> program.bindNull(index + 1)
                    is Boolean -> program.bindLong(index + 1, if (value) 1 else 0)
                    is String -> program.bindString(index + 1, value)
                    is Number -> {
                        val number = value.toDouble()
                        if (!number.isFinite()) throw SqliteFailure("INVALID_ARGUMENT", "Numbers must be finite.")
                        if (number % 1.0 == 0.0 && number in -MAX_SAFE_INTEGER.toDouble()..MAX_SAFE_INTEGER.toDouble()) {
                            program.bindLong(index + 1, number.toLong())
                        } else program.bindDouble(index + 1, number)
                    }
                    is JSONObject -> when {
                        value.has("\$int64") && value.length() == 1 -> program.bindLong(index + 1, value.getString("\$int64").toLong())
                        value.has("\$blob") && value.length() == 1 -> program.bindBlob(index + 1, Base64.decode(value.getString("\$blob"), Base64.DEFAULT))
                        else -> throw SqliteFailure("INVALID_ARGUMENT", "Unsupported SQL parameter object.")
                    }
                    else -> throw SqliteFailure("INVALID_ARGUMENT", "Unsupported SQL parameter type.")
                }
            }
        }

        private fun scalar(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

        private fun numberValue(value: Long): Any = if (value in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) value
            else JSONObject().put("\$int64", value.toString())

        private fun execute(db: SQLiteDatabase, sql: String, params: JSONArray): JSONObject {
            val kind = Regex("^\\s*([A-Za-z]+)").find(sql)?.groupValues?.get(1)?.uppercase()
            db.compileStatement(sql).use { statement ->
                bind(statement, params)
                when (kind) {
                    "INSERT", "REPLACE" -> statement.executeInsert()
                    "UPDATE", "DELETE" -> statement.executeUpdateDelete()
                    else -> statement.execute()
                }
            }
            val changes = scalar(db, "SELECT changes()")
            return JSONObject()
                .put("changes", changes)
                .put("lastInsertRowId", if (changes > 0 && (kind == "INSERT" || kind == "REPLACE")) numberValue(scalar(db, "SELECT last_insert_rowid()")) else JSONObject.NULL)
        }

        private fun query(db: SQLiteDatabase, sql: String, params: JSONArray): JSONObject {
            val factory = SQLiteDatabase.CursorFactory { _, driver, editTable, prepared ->
                bind(prepared, params)
                SQLiteCursor(driver, editTable, prepared)
            }
            db.rawQueryWithFactory(factory, sql, emptyArray(), "").use { cursor ->
                val columns = JSONArray()
                val rows = JSONArray()
                for (index in 0 until cursor.columnCount) columns.put(cursor.getColumnName(index))
                while (cursor.moveToNext()) {
                    if (rows.length() == MAX_ROWS) throw SqliteFailure("RESULT_TOO_LARGE", "Query exceeds $MAX_ROWS rows; paginate with LIMIT/OFFSET.")
                    val row = JSONObject()
                    for (index in 0 until cursor.columnCount) {
                        val value: Any = when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> numberValue(cursor.getLong(index))
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                            Cursor.FIELD_TYPE_BLOB -> JSONObject().put("\$blob", Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP))
                            else -> cursor.getString(index)
                        }
                        row.put(cursor.getColumnName(index), value)
                    }
                    rows.put(row)
                }
                return JSONObject().put("columns", columns).put("rows", rows)
            }
        }

        private fun transaction(db: SQLiteDatabase, operations: JSONArray): JSONObject {
            db.beginTransaction()
            try {
                val results = JSONArray()
                for (index in 0 until operations.length()) {
                    val operation = operations.getJSONObject(index)
                    results.put(execute(db, operation.getString("sql"), operation.optJSONArray("params") ?: JSONArray()))
                }
                db.setTransactionSuccessful()
                return JSONObject().put("results", results)
            } finally {
                db.endTransaction()
            }
        }

        private fun open(context: Context, payload: JSONObject): JSONObject {
            val name = payload.getString("name")
            if (!Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$").matches(name)) {
                throw SqliteFailure("INVALID_NAME", "Database name must be 1-64 ASCII letters, digits, underscores or hyphens.")
            }
            val version = payload.getInt("version")
            if (version < 1) throw SqliteFailure("INVALID_VERSION", "Database version must be positive.")
            val file = context.getDatabasePath("$name.db")
            file.parentFile?.mkdirs()
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            try {
                db.setForeignKeyConstraintsEnabled(true)
                val current = db.version
                if (current > version) throw SqliteFailure("VERSION_DOWNGRADE", "Database version $current is newer than requested version $version.")
                val migrations = payload.optJSONArray("migrations") ?: JSONArray()
                for (next in (current + 1)..version) {
                    val migration = (0 until migrations.length()).map { migrations.getJSONObject(it) }
                        .singleOrNull { it.getInt("to") == next }
                    if (migration == null && next != 1) {
                        throw SqliteFailure("MIGRATION_MISSING", "Migration to version $next is missing.")
                    }
                    db.beginTransaction()
                    try {
                        val statements = migration?.optJSONArray("statements") ?: JSONArray()
                        for (index in 0 until statements.length()) db.execSQL(statements.getString(index))
                        db.version = next
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
                val handle = UUID.randomUUID().toString()
                connections[handle] = db
                return JSONObject().put("handle", handle)
            } catch (error: Exception) {
                db.close()
                throw error
            }
        }

        private fun perform(context: Context, operation: String, payload: JSONObject): JSONObject {
            if (operation == "open") return open(context, payload)
            val handle = payload.getString("handle")
            val db = connections[handle] ?: throw SqliteFailure("CLOSED", "Database handle is closed or unknown.")
            return when (operation) {
                "query" -> query(db, payload.getString("sql"), payload.optJSONArray("params") ?: JSONArray())
                "execute" -> execute(db, payload.getString("sql"), payload.optJSONArray("params") ?: JSONArray())
                "transaction" -> transaction(db, payload.getJSONArray("operations"))
                "close" -> {
                    connections.remove(handle)
                    db.close()
                    JSONObject()
                }
                else -> throw SqliteFailure("INVALID_OPERATION", "Unknown SQLite operation.")
            }
        }
    }

    @LynxMethod
    fun perform(requestId: String, operation: String, payload: String) {
        worker.execute {
            try {
                val result = perform(appContext, operation, JSONObject(payload)).toString()
                if (result.toByteArray(Charsets.UTF_8).size > MAX_RESULT_BYTES) {
                    throw SqliteFailure("RESULT_TOO_LARGE", "SQLite result exceeds 1 MiB; paginate the query.")
                }
                main.post { success(requestId, JavaOnlyMap().apply { putString("payload", result) }) }
            } catch (error: Exception) {
                val code = (error as? SqliteFailure)?.code ?: "SQLITE_ERROR"
                main.post { failure(requestId, code, error.message ?: "SQLite operation failed.") }
            }
        }
    }
}
