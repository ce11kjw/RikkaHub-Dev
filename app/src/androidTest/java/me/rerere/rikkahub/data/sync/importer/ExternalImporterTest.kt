package me.rerere.rikkahub.data.sync.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/**
 * 「从其他应用导入」的真机测试：重点验证各格式都能解析出正确的会话与消息条数。
 * 用最小的手工样本，不依赖真实用户备份。
 */
@RunWith(AndroidJUnit4::class)
class ExternalImporterTest {
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var importer: ExternalImporter
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() {
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "external-import-test-").toFile()
        database = AppDatabaseFactory.create(context, File(directory, "test.db").absolutePath)
        importer = ExternalImporter(context, database)
    }

    @After fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    private fun write(name: String, content: String): File =
        File(directory, name).apply { writeText(content) }

    private fun countConversations(): Int =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM ConversationEntity")
            .use { it.moveToFirst(); it.getInt(0) }

    /**
     * ChatGPT 的 mapping 是树：b1 是编辑前的旧回答，b2 是重新生成的新回答，二者同父不同支。
     * current_node 指向 b2，所以正确的链路是 a -> b2，不含 b1。
     */
    @Test fun chatGptImportsOnlyTheVisibleBranch() = runBlocking {
        val json = """
        [{
          "title": "分支测试",
          "create_time": 1700000000.0,
          "update_time": 1700000100.0,
          "current_node": "b2",
          "mapping": {
            "root": {"id":"root","parent":null,"children":["a"],"message":null},
            "a": {"id":"a","parent":"root","children":["b1","b2"],
                  "message":{"author":{"role":"user"},"create_time":1700000001.0,
                             "content":{"content_type":"text","parts":["问题"]},"metadata":{}}},
            "b1": {"id":"b1","parent":"a","children":[],
                  "message":{"author":{"role":"assistant"},"create_time":1700000002.0,
                             "content":{"content_type":"text","parts":["废弃的旧回答"]},"metadata":{}}},
            "b2": {"id":"b2","parent":"a","children":[],
                  "message":{"author":{"role":"assistant"},"create_time":1700000003.0,
                             "content":{"content_type":"text","parts":["重新生成的新回答"]},"metadata":{}}}
          }
        }]
        """.trimIndent()

        val summary = importer.import(write("conversations.json", json), emptyList())

        assertEquals(ImportSource.CHATGPT, summary.source)
        assertEquals(1, summary.conversations)
        assertEquals(2, summary.messages)

        val raw = database.openHelper.writableDatabase
            .query("SELECT messages FROM message_node").use { it.moveToFirst(); it.getString(0) }
        assertTrue("不该导入被放弃的分支", "废弃的旧回答" !in raw)
        assertTrue("应该导入当前分支", "重新生成的新回答" in raw)
    }

    @Test fun claudeSkipsUnsupportedPlaceholderBlocks() = runBlocking {
        val json = """
        [{
          "uuid": "claude-1",
          "name": "Claude 测试",
          "created_at": "2026-01-01T10:00:00.000Z",
          "updated_at": "2026-01-01T10:05:00.000Z",
          "chat_messages": [
            {"uuid":"m1","sender":"human","created_at":"2026-01-01T10:00:00.000Z",
             "content":[{"type":"text","text":"你好"}]},
            {"uuid":"m2","sender":"assistant","created_at":"2026-01-01T10:01:00.000Z",
             "content":[{"type":"thinking","thinking":"先想想"},
                        {"type":"text","text":"你好，有什么可以帮你"}]}
          ]
        }]
        """.trimIndent()

        val summary = importer.import(write("conversations.json", json), emptyList())

        assertEquals(ImportSource.CLAUDE, summary.source)
        assertEquals(1, summary.conversations)
        assertEquals(2, summary.messages)

        val raw = database.openHelper.writableDatabase
            .query("SELECT messages FROM message_node").use { it.moveToFirst(); it.getString(0) }
        assertTrue("思考过程应作为 reasoning 保留", "先想想" in raw)
        assertTrue("占位语不该出现", "This block is not supported" !in raw)
    }

    @Test fun nextChatGroupsMessagesBySession() = runBlocking {
        val json = """
        {
          "sessions":[{"id":"s1","topic":"NextChat 测试","createdAt":1700000000000,"lastUpdate":1700000100000}],
          "messages":[
            {"id":"m1","sessionId":"s1","role":"user","content":"问题","date":1700000001000},
            {"id":"m2","sessionId":"s1","role":"assistant","content":"回答","date":1700000002000},
            {"id":"m3","sessionId":"other","role":"user","content":"别的会话","date":1700000003000}
          ],
          "masks":{},
          "historyMessageCount":4
        }
        """.trimIndent()

        val summary = importer.import(write("backup.json", json), emptyList())

        assertEquals(ImportSource.NEXTCHAT, summary.source)
        assertEquals(1, summary.conversations)
        assertEquals(2, summary.messages)
    }

    /** 同一份备份导入两次，第二次不该产生重复会话。 */
    @Test fun importingSameFileTwiceDoesNotDuplicate() = runBlocking {
        val json = """
        [{"uuid":"dup-1","name":"重复测试",
          "chat_messages":[{"uuid":"m1","sender":"human","text":"hi","content":[{"type":"text","text":"hi"}]}]}]
        """.trimIndent()
        val file = write("dup.json", json)

        val first = importer.import(file, emptyList())
        assertEquals(1, first.conversations)
        val afterFirst = countConversations()

        val second = importer.import(file, emptyList())
        assertEquals(0, second.conversations)
        assertEquals("重复导入不应新增会话", afterFirst, countConversations())
    }

    @Test fun importingDifferentSourcesKeepsBoth() = runBlocking {
        val claude = """
        [{"uuid":"c1","name":"Claude 的会话",
          "chat_messages":[{"uuid":"m1","sender":"human","text":"hi","content":[{"type":"text","text":"hi"}]}]}]
        """.trimIndent()
        val chatgpt = """
        [{"title":"GPT 的会话","current_node":"a",
          "mapping":{"a":{"id":"a","parent":null,"children":[],
            "message":{"author":{"role":"user"},"create_time":1700000000.0,
                       "content":{"content_type":"text","parts":["hi"]},"metadata":{}}}}}]
        """.trimIndent()

        importer.import(write("a.json", claude), emptyList())
        importer.import(write("b.json", chatgpt), emptyList())

        assertEquals(2, countConversations())
    }

    @Test fun rejectsUnrecognizedFile() = runBlocking {
        val error = runCatching {
            importer.import(write("unknown.json", """{"totally":"unrelated"}"""), emptyList())
        }
        assertTrue("无法识别的文件应当报错而不是静默成功", error.isFailure)
    }
}
