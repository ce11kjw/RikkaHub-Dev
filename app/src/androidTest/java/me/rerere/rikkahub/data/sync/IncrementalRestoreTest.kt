package me.rerere.rikkahub.data.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/**
 * 增量恢复的真机测试。重点是「连续合并多次」——这是 ATTACH/DETACH 最容易出错的地方，
 * 曾经因为事务内 DETACH 失败导致第二次合并报 "database ... is already in use"。
 */
@RunWith(AndroidJUnit4::class)
class IncrementalRestoreTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var liveDatabase: AppDatabase
    private lateinit var restore: IncrementalRestore

    @Before fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "incremental-restore-test-").toFile()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name)
                else File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        liveDatabase = AppDatabaseFactory.create(context)
        restore = IncrementalRestore(
            context = context,
            database = liveDatabase,
            settingsStore = GlobalContext.get().get<SettingsStore>(),
            json = JsonInstant,
        )
    }

    @After fun tearDown() {
        liveDatabase.close()
        directory.deleteRecursively()
    }

    private fun conversation(id: String, title: String, updateAt: Long = 1L) = ConversationEntity(
        id = id,
        assistantId = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e",
        title = title,
        nodes = "[]",
        createAt = updateAt,
        updateAt = updateAt,
        chatSuggestions = "[]",
        isPinned = false,
    )

    /** 造一个只含数据库的备份 zip，入参是「备份库里应该有什么」。 */
    private suspend fun buildBackup(name: String, conversations: List<ConversationEntity>): File {
        // 备份库必须是「独立的空库」，不能复制当前库：复制会把本地已有的会话一起带进去，
        // 再插同 id 的行就会撞 UNIQUE 约束，测试也就不在测「补缺失」了。
        val snapshot = File(directory, "snapshot-$name.db")
        if (snapshot.exists()) snapshot.delete()
        val backupDb = AppDatabaseFactory.create(context, snapshot.absolutePath)
        try {
            backupDb.conversationDao().let { dao ->
                conversations.forEach { runBlocking { dao.insert(it) } }
            }
        } finally {
            // 数据先落在 -wal 里，必须 checkpoint 到主库，否则打出来的 .db 是空的
            DatabaseBackup.checkpoint(backupDb.openHelper.writableDatabase)
            backupDb.close()
        }
        DatabaseBackup.removeSidecars(snapshot)

        val archive = File(directory, "backup-$name.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(DatabaseBackup.ARCHIVE_DATABASE))
            snapshot.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        snapshot.delete()
        return archive
    }

    private fun queryTitles(): List<String> =
        liveDatabase.openHelper.writableDatabase
            .query("SELECT title FROM ConversationEntity ORDER BY title")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    @Test fun mergesMissingConversationsAcrossTwoBackupsInARow() = runBlocking {
        val first = buildBackup("first", listOf(conversation(Uuid.random().toString(), "第一个备份里的会话")))
        val second = buildBackup("second", listOf(conversation(Uuid.random().toString(), "第二个备份里的会话")))

        val firstReport = restore.restore(first, includeDatabase = true, includeFiles = false)
        assertEquals(1, firstReport.conversations)

        // 这一步在修复前会抛 "database backup is already in use (code 1)"
        val secondReport = restore.restore(second, includeDatabase = true, includeFiles = false)
        assertEquals(1, secondReport.conversations)

        assertEquals(listOf("第一个备份里的会话", "第二个备份里的会话"), queryTitles())
    }

    @Test fun mergingSameBackupTwiceAddsNothingTheSecondTime() = runBlocking {
        val archive = buildBackup("dup", listOf(conversation(Uuid.random().toString(), "只该出现一次")))

        val first = restore.restore(archive, includeDatabase = true, includeFiles = false)
        assertEquals(1, first.conversations)

        // 第二次不该报错，只是没有任何新增（同一个备份连按两次属正常操作）
        val second = restore.restore(archive, includeDatabase = true, includeFiles = false)
        assertEquals(0, second.conversations)
        assertTrue(second.isEmpty)
        assertEquals(listOf("只该出现一次"), queryTitles())
    }

    /** 本地已有的会话内容不能被备份里的同名旧版本覆盖。 */
    @Test fun existingConversationIsNeverOverwritten() = runBlocking {
        val id = Uuid.random().toString()
        liveDatabase.openHelper.writableDatabase.execSQL(
            "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, custom_system_prompt, mode_injection_ids, lorebook_ids, workspace_cwd, folder_id, config) " +
                "VALUES ('$id', '0950e2dc-9bd5-4801-afa3-aa887aa36b4e', '本地新版本', '[]', 100, 100, '[]', 0, '', '[]', '[]', '', '', '')"
        )
        val archive = buildBackup("old", listOf(conversation(id, "备份里的旧版本", updateAt = 1L)))

        restore.restore(archive, includeDatabase = true, includeFiles = false)

        assertEquals(listOf("本地新版本"), queryTitles())
    }

    @Test fun mergesMessageNodesOfNewConversation() = runBlocking {
        // 会话 id 必须是合法 UUID：真实库里的 id 都是 UUID，索引逻辑也按 UUID 解析
        val conversationId = Uuid.random().toString()
        val message = UIMessage(
            role = me.rerere.ai.core.MessageRole.USER,
            parts = listOf(UIMessagePart.Text("这条消息应该被搜索到")),
        )
        val node = MessageNodeEntity(
            id = Uuid.random().toString(),
            conversationId = conversationId,
            nodeIndex = 0,
            messages = JsonInstant.encodeToString(listOf(message)),
            selectIndex = 0,
        )
        // 备份里同时插入会话与消息节点
        val snapshot = File(directory, "snapshot-msg.db")
        if (snapshot.exists()) snapshot.delete()
        val backupDb = AppDatabaseFactory.create(context, snapshot.absolutePath)
        try {
            backupDb.conversationDao().insert(conversation(conversationId, "带消息的会话"))
            backupDb.messageNodeDao().insert(node)
        } finally {
            // 数据先落在 -wal 里，必须 checkpoint 到主库，否则打出来的 .db 是空的
            DatabaseBackup.checkpoint(backupDb.openHelper.writableDatabase)
            backupDb.close()
        }
        DatabaseBackup.removeSidecars(snapshot)
        val archive = File(directory, "backup-msg.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(DatabaseBackup.ARCHIVE_DATABASE))
            snapshot.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }

        val report = restore.restore(archive, includeDatabase = true, includeFiles = false)

        assertEquals(1, report.conversations)
        assertEquals(1, report.messages)
        val indexed = liveDatabase.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM message_fts WHERE conversation_id = '$conversationId'")
            .use { it.moveToFirst(); it.getInt(0) }
        assertTrue("新会话的消息应该建立全文索引，实际 $indexed 条", indexed > 0)
    }

    @Test fun emptyArchiveIsRejected() = runBlocking {
        val archive = File(directory, "empty.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("unrelated.txt"))
            zip.write("nothing useful".toByteArray())
            zip.closeEntry()
        }
        val error = runCatching { restore.restore(archive, includeDatabase = true, includeFiles = true) }
        assertTrue("空备份应当报错而不是假装成功", error.isFailure)
        assertTrue(queryTitles().isEmpty())
    }
}
