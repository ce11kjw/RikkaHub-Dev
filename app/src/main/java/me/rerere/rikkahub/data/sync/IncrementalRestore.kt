package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.uuid.Uuid

private const val TAG = "IncrementalRestore"

/** 增量恢复的结果，用来在界面上告诉用户补回了多少东西。 */
data class IncrementalRestoreReport(
    val conversations: Int = 0,
    val messages: Int = 0,
    val folders: Int = 0,
    val favorites: Int = 0,
    val workspaces: Int = 0,
    val usageRecords: Int = 0,
    val otherRows: Int = 0,
    val files: Int = 0,
    val skippedFiles: Int = 0,
    val settings: Int = 0,
) {
    val totalRows: Int get() = conversations + messages + folders + favorites + workspaces + usageRecords + otherRows

    /** 解析到了备份内容，但本地已经全有，没有任何新增。 */
    val isEmpty: Boolean get() = totalRows == 0 && files == 0 && settings == 0
}

/**
 * 增量恢复：把备份里的内容「补」进现有数据库，已有的行一律不覆盖。
 *
 * 和 [BackupManager.stageRestore] 的整库替换不同，这里不碰现有数据、不需要重启：
 * 备份库被 ATTACH 上来当成来源，逐表按 [TABLES] 的顺序用 INSERT OR IGNORE 合并。
 * 主键冲突时 SQLite 直接跳过，天然满足「已有的绝不覆盖」。
 *
 * 唯一一张不做纯 INSERT 的表是 message_node：备份里同一个会话可能多出新的分支节点，
 * 但同一 node id 的内容不会变，所以还是按主键跳过即可。
 */
class IncrementalRestore(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    suspend fun restore(archive: File, includeDatabase: Boolean, includeFiles: Boolean): IncrementalRestoreReport =
        withContext(Dispatchers.IO) {
            val staging = File(context.cacheDir, "incremental-restore-${System.currentTimeMillis()}")
            check(staging.mkdirs()) { "Cannot create restore staging directory" }
            try {
                val backupFile = extract(archive, staging, includeDatabase)
                val hasSettings = runCatching {
                    ZipFile(archive).use { it.getEntry("settings.json") != null }
                }.getOrDefault(false)
                if (includeDatabase && backupFile == null && !hasSettings) {
                    throw IllegalArgumentException("这个文件不是有效的 RikkaHub 备份")
                }
                var report = IncrementalRestoreReport()
                if (includeDatabase && backupFile != null) {
                    report = mergeDatabase(backupFile)
                }
                if (includeFiles) {
                    val (copied, skipped) = mergeFiles(staging)
                    report = report.copy(files = copied, skippedFiles = skipped)
                }
                report = report.copy(settings = mergeSettings(archive))
                // 没补到东西不是错误：同一个备份连按两次、或本地已经全有，都属于正常情况，
                // 交给界面提示「已是最新」即可，不要弹红色报错。
                report
            } finally {
                staging.deleteRecursively()
            }
        }

    /**
     * 取出备份库（ARCHIVE_DATABASE）与附件。
     * - 丢掉 shm：SQLite 会自己重建，恢复它反而可能带来坏页
     * - 丢掉 settings.json：设置走 App 层的字段级合并，不在这里读
     */
    private fun extract(archive: File, staging: File, includeDatabase: Boolean): File? {
        var databaseFile: File? = null
        val filesRoot = File(staging, "files")
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                when {
                    entry.name == DatabaseBackup.SHM -> continue
                    entry.name == DatabaseBackup.WAL -> continue
                    entry.name == "settings.json" -> continue
                    entry.name == DatabaseBackup.ARCHIVE_DATABASE -> {
                        if (!includeDatabase) continue
                        val target = File(staging, DatabaseBackup.ARCHIVE_DATABASE)
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(target).use { output ->
                                input.copyTo(output)
                                output.fd.sync()
                            }
                        }
                        databaseFile = target
                    }
                    isAttachment(entry.name) -> {
                        val target = PendingRestore.resolveInside(filesRoot, entry.name)
                        val parent = target.parentFile
                        check(parent != null && (parent.isDirectory || parent.mkdirs())) {
                            "Cannot create restore staging directory"
                        }
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(target).use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
        }
        return databaseFile
    }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in ATTACHMENT_FOLDERS || '/' !in name) return false
        val relative = name.substringAfter('/')
        if (relative.isBlank()) return false
        return folder in NESTED_ATTACHMENT_FOLDERS || '/' !in relative
    }

    /** 把备份库挂到当前库上逐表合并；整个过程一个事务，中途失败全部回滚。 */
    /**
     * 合并备份库。
     *
     * 关键点：ATTACH 是**连接级状态**，Room 用连接池，分两次调用很可能拿到不同连接，
     * 于是 SQLite 会报 "database ... is already in use"。
     * 所以这里不走 Room 的连接池，而是用 requery 的 SQLiteDatabase 自己开一条专用连接，
     * 把「实时库」和「备份库」都挂在这条连接上（一个作 main、一个 ATTACH 进来）。
     */
    private suspend fun mergeDatabase(backupFile: File): IncrementalRestoreReport {
        val attachFile = File(backupFile.parentFile, "attach-${System.nanoTime()}.db")
        backupFile.copyTo(attachFile, overwrite = true)

        val addedConversationIds = mutableListOf<String>()
        val counts = mutableMapOf<String, Int>()

        // 先把实时库刷盘，保证专用连接读到的是最新数据
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }

        val configuration = SQLiteConfiguration.configure(
            context,
            SQLiteDatabaseConfiguration(database.openHelper.writableDatabase.path!!, SQLiteDatabase.OPEN_READWRITE),
        )
        try {
            SQLiteDatabase.openDatabase(configuration, null) { error("无法打开数据库做增量恢复") }.use { live ->
                mergeOnConnection(live, attachFile, addedConversationIds, counts)
            }
        } finally {
            attachFile.delete()
        }

        val report = TABLES.fold(IncrementalRestoreReport()) { acc, table ->
            acc.withTable(table, counts[table] ?: 0)
        }
        indexNewConversations(addedConversationIds)
        return report
    }

    /** 在给定的单条连接上完成 ATTACH → 合并 → DETACH，返回各表增量。 */
    private fun mergeOnConnection(
        live: SQLiteDatabase,
        attachFile: File,
        addedConversationIds: MutableList<String>,
        counts: MutableMap<String, Int>,
    ) {
        // 残留挂载会让新的 ATTACH 直接失败，先清干净
        detachQuietly(live, ATTACH_ALIAS)
        live.execSQL("ATTACH DATABASE ? AS $ATTACH_ALIAS", arrayOf(attachFile.absolutePath))
        try {
            // 自查：能读出 sqlite_master 就说明这个库不是坏的
            val backupTables = tableNames(live, ATTACH_ALIAS)
            require(backupTables.isNotEmpty()) { "备份数据库里没有表，可能已损坏" }
            val liveTables = tableNames(live, "main")

            live.beginTransaction()
            try {
                for (table in TABLES) {
                    if (table !in liveTables || table !in backupTables) continue
                    // 只取两张表都有的列，备份来自更旧/更新的版本时也不会因多列少列失败
                    val columns = sharedColumns(live, table)
                    if (columns.isEmpty()) {
                        Log.w(TAG, "merge: skip $table, no shared columns")
                        continue
                    }
                    val columnList = columns.joinToString(", ") { "\"$it\"" }
                    val added = if (table == CONVERSATION_TABLE) {
                        mergeConversations(live, columnList, addedConversationIds)
                    } else {
                        val before = countOf(live, table)
                        live.execSQL(
                            "INSERT OR IGNORE INTO main.\"$table\" ($columnList) " +
                                "SELECT $columnList FROM \"$ATTACH_ALIAS\".\"$table\""
                        )
                        (countOf(live, table) - before).toInt()
                    }
                    Log.i(TAG, "merge: $table +$added (live=$liveTables, backup=$backupTables)")
                    counts[table] = added
                }
                live.setTransactionSuccessful()
            } finally {
                live.endTransaction()
            }
        } finally {
            // DETACH 必须放在事务外
            detachQuietly(live, ATTACH_ALIAS)
        }
    }

    /** 会话表顺手把「真正新增的那批 id」记下来，稍后只给它们补 FTS 索引。 */
    private fun mergeConversations(
        live: SQLiteDatabase,
        columnList: String,
        addedIds: MutableList<String>,
    ): Int {
        val existing = mutableSetOf<String>()
        live.query("SELECT id FROM main.\"$CONVERSATION_TABLE\"").use { cursor ->
            while (cursor.moveToNext()) existing += cursor.getString(0)
        }
        live.execSQL(
            "INSERT OR IGNORE INTO main.\"$CONVERSATION_TABLE\" ($columnList) " +
                "SELECT $columnList FROM \"$ATTACH_ALIAS\".\"$CONVERSATION_TABLE\""
        )
        var added = 0
        live.query("SELECT id FROM \"$ATTACH_ALIAS\".\"$CONVERSATION_TABLE\"").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                if (id !in existing) {
                    addedIds += id
                    added++
                }
            }
        }
        return added
    }

    /**
     * 新进来的会话还没有 FTS 索引，不补的话搜不到。
     * 直接把 message_node 的行还原成 Conversation 再走 [MessageFtsManager.indexConversation]，
     * 不经过 Repository（那边为了取收藏标记要读好几张表，这里用不上）。
     */
    private suspend fun indexNewConversations(conversationIds: List<String>) {
        if (conversationIds.isEmpty()) return
        val fts = MessageFtsManager(database)
        val conversationDao = database.conversationDao()
        val nodeDao = database.messageNodeDao()
        for (id in conversationIds) {
            runCatching {
                val entity = conversationDao.getConversationById(id) ?: return@runCatching
                val nodes = nodeDao.getNodesOfConversation(id).map { node ->
                    MessageNode(
                        id = Uuid.parse(node.id),
                        messages = JsonInstant.decodeFromString<List<UIMessage>>(node.messages),
                        selectIndex = node.selectIndex,
                    )
                }.filter { it.messages.isNotEmpty() }
                if (nodes.isEmpty()) return@runCatching
                fts.indexConversation(
                    Conversation(
                        id = Uuid.parse(entity.id),
                        title = entity.title,
                        messageNodes = nodes,
                        createAt = Instant.ofEpochMilli(entity.createAt),
                        updateAt = Instant.ofEpochMilli(entity.updateAt),
                        assistantId = Uuid.parse(entity.assistantId),
                    )
                )
            }.onFailure { Log.w(TAG, "indexNewConversations: failed for $id", it) }
        }
    }

    /** 文件只在目标不存在时拷贝，已有的不覆盖，避免冲掉当前更近的附件。 */
    private fun mergeFiles(staging: File): Pair<Int, Int> {
        val source = File(staging, "files")
        if (!source.isDirectory) return 0 to 0
        var copied = 0
        var skipped = 0
        source.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(source).invariantSeparatorsPath
            val target = runCatching { PendingRestore.resolveInside(context.filesDir, relative) }.getOrNull()
                ?: return@forEach
            if (target.exists()) {
                skipped++
            } else {
                val parent = target.parentFile
                if (parent != null && (parent.isDirectory || parent.mkdirs())) {
                    file.copyTo(target, overwrite = false)
                    copied++
                }
            }
        }
        return copied to skipped
    }

    /**
     * 设置合并：把备份 settings.json 里当前还没有的提供商/助手等条目补上来。
     * 读不出来（旧格式或损坏）就跳过，不影响数据合并的结果。
     */
    private suspend fun mergeSettings(archive: File): Int {
        val raw = runCatching {
            ZipFile(archive).use { zip ->
                zip.getEntry("settings.json")?.let { entry ->
                    zip.getInputStream(entry).readBytes().decodeToString()
                }
            }
        }.getOrNull() ?: return 0
        val migrated = runCatching { SettingsJsonMigrator.migrate(raw) }.getOrDefault(raw)
        val backup = runCatching { json.decodeFromString<Settings>(migrated) }.getOrNull() ?: return 0
        if (backup.init) return 0
        var added = 0
        settingsStore.update { current ->
            val (merged, count) = SettingsMerge.merge(current, backup)
            added = count
            merged
        }
        return added
    }

    /** 先查 sqlite_master 里有没有这个挂载，有才 DETACH，避免 "no such database" 噪音。 */
    private fun detachQuietly(db: SQLiteDatabase, alias: String) {
        val attached = runCatching {
            db.query("PRAGMA database_list").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                buildSet { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
            }
        }.getOrDefault(emptySet())
        if (alias in attached) {
            runCatching { db.execSQL("DETACH DATABASE \"$alias\"") }
                .onFailure { Log.w(TAG, "detachQuietly: failed to detach $alias", it) }
        }
    }

    private fun countOf(db: SQLiteDatabase, table: String): Long =
        db.query("SELECT COUNT(*) FROM main.\"$table\"").use { it.moveToFirst(); it.getLong(0) }

    private fun tableNames(db: SQLiteDatabase, schema: String): Set<String> =
        db.query("SELECT name FROM \"$schema\".sqlite_master WHERE type = 'table'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun sharedColumns(db: SQLiteDatabase, table: String): List<String> {
        fun columnsOf(schema: String): List<String> =
            db.query("PRAGMA \"$schema\".table_info(\"$table\")").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                buildList { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
            }
        val backupColumns = columnsOf(ATTACH_ALIAS).toSet()
        return columnsOf("main").filter { it in backupColumns }
    }

    private fun IncrementalRestoreReport.withTable(table: String, added: Int): IncrementalRestoreReport =
        when (table) {
            CONVERSATION_TABLE -> copy(conversations = conversations + added)
            "message_node" -> copy(messages = messages + added)
            "conversation_folder" -> copy(folders = folders + added)
            "favorites" -> copy(favorites = favorites + added)
            "workspaces" -> copy(workspaces = workspaces + added)
            "usage_record" -> copy(usageRecords = usageRecords + added)
            else -> copy(otherRows = otherRows + added)
        }

    companion object {
        private const val CONVERSATION_TABLE = "ConversationEntity"

        /** ATTACH 用的库别名；用 PRAGMA database_list 判残留，避免反复挂载冲突。 */
        private const val ATTACH_ALIAS = "backupdb"

        /** 父表排在子表前面，否则子行会因父行还没进来而被外键拒绝。 */
        private val TABLES = listOf(
            CONVERSATION_TABLE,
            "message_node",
            "memory_consolidation",
            "conversation_folder",
            "favorites",
            "workspaces",
            "memory_file",
            "managed_files",
            "GenMediaEntity",
            "media_creation_session",
            "media_creation_node",
            "media_creation_record",
            "translation_history",
            "usage_record",
        )

        private val ATTACHMENT_FOLDERS = listOf(
            FileFolders.UPLOAD,
            FileFolders.SKILLS,
            FileFolders.FONTS,
            FileFolders.MEDIA_CREATION,
        )

        /** 带子目录备份的两个目录，其余目录只备份顶层文件。 */
        private val NESTED_ATTACHMENT_FOLDERS = setOf(
            FileFolders.SKILLS,
            FileFolders.MEDIA_CREATION,
        )
    }
}

/**
 * 设置项的增量合并：只补现有设置里缺的条目，同名/同 id 的一律以当前为准。
 *
 * 只合并这几类「条目集合」——它们才是「备份里有、现在没有」的主要来源：
 * 提供商、助手、助手标签、MCP 服务器、模式注入、世界书、快捷消息、媒体/语音/识别提供商。
 * 标量设置（默认模型、主题、端口等）不碰：那些是用户当下的选择，备份里的旧值不该盖上来。
 */
internal object SettingsMerge {
    fun merge(current: Settings, backup: Settings): Pair<Settings, Int> {
        var added = 0
        // 用 toString() 做键：各个条目的 id 类型不统一（Uuid / Int / String），字符串比较最稳
        fun <T> mergeById(cur: List<T>, bak: List<T>, id: (T) -> Any?): List<T> {
            if (bak.isEmpty()) return cur
            val existing = cur.mapTo(HashSet()) { id(it).toString() }
            val missing = bak.filter { id(it).toString() !in existing }
            added += missing.size
            return cur + missing
        }

        val merged = current.copy(
            providers = mergeById(current.providers, backup.providers) { it.id },
            assistants = mergeById(current.assistants, backup.assistants) { it.id },
            assistantTags = mergeById(current.assistantTags, backup.assistantTags) { it.id },
            searchServices = mergeById(current.searchServices, backup.searchServices) { it.id },
            mcpServers = mergeById(current.mcpServers, backup.mcpServers) { it.id },
            mediaGenerationProviders = mergeById(current.mediaGenerationProviders, backup.mediaGenerationProviders) { it.id },
            ttsProviders = mergeById(current.ttsProviders, backup.ttsProviders) { it.id },
            asrProviders = mergeById(current.asrProviders, backup.asrProviders) { it.id },
            modeInjections = mergeById(current.modeInjections, backup.modeInjections) { it.id },
            lorebooks = mergeById(current.lorebooks, backup.lorebooks) { it.id },
            quickMessages = mergeById(current.quickMessages, backup.quickMessages) { it.id },
        )
        return merged to added
    }
}
