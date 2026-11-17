package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import java.io.File

private const val TAG = "DatabaseDowngrade"

/**
 * 备份库版本比当前 App 新时的降级处理。
 *
 * 场景：用开发版（数据库 v34）导出的备份，拿到正式版（v32）里恢复。
 * Room 只支持向上迁移，遇到比自己新的库会直接抛
 * 「A migration from 34 to 32 was required but not found」。
 *
 * 但新版本对旧版本往往只是「加了列」——这些列旧版本完全不认识，
 * 只要把它们去掉，剩下的数据旧版本全都能正常读。
 * 所以这里把备份库的 schema 退回旧版本的结构，让恢复能成功；
 * 只丢弃新版本独有的字段，不会丢任何双方共有的数据。
 */
internal object DatabaseDowngrade {

    /** 读取备份库的 schema 版本（PRAGMA user_version），读不出来返回 null。 */
    fun readVersion(context: Context, databaseFile: File): Int? {
        if (!databaseFile.isFile) return null
        return runCatching {
            val configuration = SQLiteConfiguration.configure(
                context,
                SQLiteDatabaseConfiguration(databaseFile.absolutePath, SQLiteDatabase.OPEN_READONLY),
            )
            SQLiteDatabase.openDatabase(configuration, null) { null }.use { db ->
                db.query("PRAGMA user_version").use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else null
                }
            }
        }.onFailure { Log.w(TAG, "readVersion failed", it) }.getOrNull()
    }

    /**
     * 把备份库降级到 [targetVersion]。
     *
     * 只处理「新版本只是加了列」的情况：逐表对比当前表结构与目标结构，
     * 把多出来的列用「建新表 → 拷数据 → 换名」的方式去掉（SQLite 3.35 之前不支持 DROP COLUMN，
     * Android 各版本差异大，重建表最稳）。
     *
     * @param columnsToDrop 表名 → 该表需要丢弃的列名集合
     * @return 是否成功降级；false 时调用方应放弃恢复而不是冒险写坏数据
     */
    fun downgrade(
        context: Context,
        databaseFile: File,
        targetVersion: Int,
        columnsToDrop: Map<String, Set<String>>,
    ): Boolean {
        if (!databaseFile.isFile) return false
        return runCatching {
            val configuration = SQLiteConfiguration.configure(
                context,
                SQLiteDatabaseConfiguration(databaseFile.absolutePath, SQLiteDatabase.OPEN_READWRITE),
            )
            SQLiteDatabase.openDatabase(configuration, null) { null }.use { db ->
                db.beginTransaction()
                try {
                    columnsToDrop.forEach { (table, dropColumns) ->
                        val keepColumns = tableColumns(db, table)
                            .filterNot { it.name in dropColumns }
                        if (keepColumns.isEmpty()) {
                            Log.w(TAG, "downgrade: $table 没有可保留的列，跳过")
                            return@forEach
                        }
                        rebuildWithoutColumns(db, table, keepColumns)
                    }
                    db.execSQL("PRAGMA user_version = $targetVersion")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            Log.i(TAG, "downgrade: 已把备份库降到 v$targetVersion")
            true
        }.onFailure { Log.e(TAG, "downgrade failed", it) }.getOrDefault(false)
    }

    private class ColumnSpec(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKeyOrder: Int,
    )

    private fun tableColumns(db: SQLiteDatabase, table: String): List<ColumnSpec> {
        val result = mutableListOf<ColumnSpec>()
        db.query("PRAGMA table_info(\"$table\")").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            val typeIdx = cursor.getColumnIndexOrThrow("type")
            val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
            val dfltIdx = cursor.getColumnIndexOrThrow("dflt_value")
            val pkIdx = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                result.add(
                    ColumnSpec(
                        name = cursor.getString(nameIdx),
                        type = cursor.getString(typeIdx).orEmpty(),
                        notNull = cursor.getInt(notNullIdx) != 0,
                        defaultValue = if (cursor.isNull(dfltIdx)) null else cursor.getString(dfltIdx),
                        primaryKeyOrder = cursor.getInt(pkIdx),
                    )
                )
            }
        }
        return result
    }

    /** 建一个只含 [keepColumns] 的新表，把数据拷过去再改名，从而丢弃多余列。 */
    private fun rebuildWithoutColumns(db: SQLiteDatabase, table: String, keepColumns: List<ColumnSpec>) {
        val tempTable = "${table}__downgrade"
        db.execSQL("DROP TABLE IF EXISTS \"$tempTable\"")

        val columnDefs = keepColumns.joinToString(", ") { column ->
            buildString {
                append("\"${column.name}\"")
                if (column.type.isNotBlank()) append(" ${column.type}")
                if (column.notNull) append(" NOT NULL")
                column.defaultValue?.let { append(" DEFAULT $it") }
            }
        }
        // 主键单独声明：单列主键在 columnDefs 里已带 NOT NULL，这里只补 PRIMARY KEY
        val primaryKeys = keepColumns.filter { it.primaryKeyOrder > 0 }
            .sortedBy { it.primaryKeyOrder }
            .map { "\"${it.name}\"" }
        val primaryKeyClause = if (primaryKeys.isNotEmpty()) {
            ", PRIMARY KEY (${primaryKeys.joinToString(", ")})"
        } else ""

        db.execSQL("CREATE TABLE \"$tempTable\" ($columnDefs$primaryKeyClause)")

        val columnList = keepColumns.joinToString(", ") { "\"${it.name}\"" }
        db.execSQL("INSERT INTO \"$tempTable\" ($columnList) SELECT $columnList FROM \"$table\"")
        db.execSQL("DROP TABLE \"$table\"")
        db.execSQL("ALTER TABLE \"$tempTable\" RENAME TO \"$table\"")
    }
}
