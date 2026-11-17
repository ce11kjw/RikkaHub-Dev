package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_34_35"

/**
 * conversationentity 增加 context_summary / prior_context 两列。
 *
 * context_summary 持久化上下文压缩摘要。
 *
 * 之前摘要只活在内存里，进程一被杀（长会话时很常见）就丢了，
 * 下次打开会话又得把整段历史重新压一遍。摘要不参与聊天记录展示，
 * 所以单独一列，老会话统一填空串表示「没有摘要」，行为与之前一致。
 */
val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 34 to 35 (context summary)")
        DatabaseMigrationTracker.onMigrationStart(34, 35)
        try {
            db.execSQL(
                "ALTER TABLE `conversationentity` ADD COLUMN `context_summary` TEXT NOT NULL DEFAULT ''"
            )
            db.execSQL(
                "ALTER TABLE `conversationentity` ADD COLUMN `prior_context` TEXT NOT NULL DEFAULT ''"
            )
            Log.i(TAG, "migrate: migrate from 34 to 35 success")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
