package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_33_34"

/**
 * usage_record 增加费用列，用于在统计页显示花了多少钱。
 *
 * `cost` 存本次请求实际花了多少美元，写入时就按当时的价格算好，之后不随调价变化；
 * `pricing_snapshot` 记录当时用的单价，便于事后核对或按新价格重算。
 *
 * 老记录一律填 0 / 空串：它们当时确实没有记录过价格，
 * 用「今天的价格」去倒推历史花费会得出一个看起来精确、实际错误的数字，不如明确留空。
 * 只加列不改数据，已有统计不受影响。
 */
val Migration_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 33 to 34 (usage cost)")
        DatabaseMigrationTracker.onMigrationStart(33, 34)
        try {
            db.execSQL("ALTER TABLE `usage_record` ADD COLUMN `cost` REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `usage_record` ADD COLUMN `pricing_snapshot` TEXT NOT NULL DEFAULT ''")
            Log.i(TAG, "migrate: migrate from 33 to 34 success")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
