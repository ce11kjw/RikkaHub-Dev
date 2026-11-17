package me.rerere.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.SourceUsage
import me.rerere.rikkahub.data.db.dao.UsageBucket
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.db.dao.UsageTotals
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.uuid.Uuid

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    // 热力图：每天的对话请求次数
    val requestsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
)

/** 用量统计的时间范围；[days] 为空表示全部，按月分桶 */
enum class UsageRange(val days: Int?) {
    WEEK(7),
    MONTH(30),
    QUARTER(90),
    ALL(null),
}

data class UsagePoint(
    // 按天时是当天，按月时是当月 1 号
    val date: LocalDate,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    // 这一天的花费（美元）；没有价格数据时为 0
    val cost: Double = 0.0,
) {
    val totalTokens get() = promptTokens + completionTokens
}

data class ModelUsageItem(
    // 模型已被删除且没有名称快照时为空
    val name: String?,
    val totalTokens: Long,
    val cost: Double = 0.0,
)

data class UsageStats(
    val range: UsageRange,
    val totals: UsageTotals,
    val points: List<UsagePoint>,
    val byMonth: Boolean,
    val models: List<ModelUsageItem>,
    val sources: List<SourceUsage>,
    /** 区间内总花费（美元）。 */
    val totalCost: Double = 0.0,
    /** 有价格数据的请求数 / 总请求数，用来提示「有些请求没算钱」。 */
    val pricedRecords: Int = 0,
    val totalRecords: Int = 0,
) {
    /** 不是所有请求都有价格时，界面上要把「未估算」说清楚，别让用户以为是全部花费。 */
    val costIncomplete: Boolean get() = pricedRecords < totalRecords
}

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val usageRecordDAO: UsageRecordDAO,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    // 切换范围时先保留旧数据，查完再替换，避免页面闪成加载状态
    private val _usage = MutableStateFlow<UsageStats?>(null)
    val usage = _usage.asStateFlow()

    private val _range = MutableStateFlow(UsageRange.MONTH)
    val range = _range.asStateFlow()

    private var usageJob: Job? = null

    init {
        viewModelScope.launch { loadStats() }
        loadUsage(_range.value)
    }

    fun setRange(range: UsageRange) {
        if (range == _range.value) return
        _range.value = range
        loadUsage(range)
    }

    private suspend fun loadStats() {
        delay(50)

        val today = LocalDate.now()

        // 热力图起始日期（52 周前的周日）
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
        val since = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // 按对话请求数统计活跃度，走 usage_record 的 created_at 索引，不用解析消息 JSON；
        // 多步工具调用和重试会多算几次，热力图按四分位分档，只看相对活跃程度
        val requestsPerDay = usageRecordDAO.getRequestCountPerDay(UsageSource.CHAT.name, since)
            .mapNotNull { entry ->
                runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
            }
            .toMap()

        val totalConversations = conversationDAO.countAll()
        val totalMessages = messageNodeDAO.countAll()
        val launchCount = settingsStore.launchCountFlow.first()

        _stats.value = AppStats(
            isLoading = false,
            totalConversations = totalConversations,
            totalMessages = totalMessages,
            requestsPerDay = requestsPerDay,
            launchCount = launchCount,
        )
    }

    private fun loadUsage(range: UsageRange) {
        usageJob?.cancel()
        usageJob = viewModelScope.launch {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val startDate = range.days?.let { today.minusDays(it - 1L) }
            val since = startDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: 0L
            // 用量流水包含标题、翻译等不落到消息里的请求，删掉的会话也还算在内
            val totals = usageRecordDAO.getTotals(since)
            val byMonth = range.days == null
            val bucketFormat = if (byMonth) "%Y-%m" else "%Y-%m-%d"
            val buckets = usageRecordDAO.getTotalsByBucket(bucketFormat, since)
            // 费用按同样的时间桶查一次，之后和 token 桶按 key 合并
            val costByBucket = usageRecordDAO.getCostByBucket(bucketFormat, since)
                .associate { it.bucket to it.cost }
            val points = if (byMonth) {
                monthlyPoints(buckets, today, costByBucket)
            } else {
                dailyPoints(buckets, startDate!!, today, costByBucket)
            }
            val settings = settingsStore.awaitLoaded()
            // 按模型归并的费用，key 与 getTotalsByModel 的归并键一致
            val costByModel = usageRecordDAO.getCostByModel(since)
                .associateBy { it.modelId ?: it.modelName.orEmpty() }
            val models = usageRecordDAO.getTotalsByModel(since).map { usage ->
                val model = usage.modelId
                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?.let { settings.findModelById(it) }
                ModelUsageItem(
                    name = model?.displayName?.ifBlank { null } ?: model?.modelId ?: usage.modelName,
                    totalTokens = usage.promptTokens + usage.completionTokens,
                    cost = costByModel[usage.modelId ?: usage.modelName.orEmpty()]?.cost ?: 0.0,
                )
            }
            _usage.value = UsageStats(
                range = range,
                totals = totals,
                points = points,
                byMonth = byMonth,
                models = models,
                sources = usageRecordDAO.getTotalsBySource(since),
                totalCost = usageRecordDAO.getTotalCost(since),
                pricedRecords = usageRecordDAO.countPricedRecords(since),
                totalRecords = usageRecordDAO.countRecords(since),
            )
        }
    }

    // 没有用量的日子补 0，柱子才能按日期均匀排开
    private fun dailyPoints(
        buckets: List<UsageBucket>,
        start: LocalDate,
        today: LocalDate,
        costByBucket: Map<String, Double>,
    ): List<UsagePoint> {
        val byDate = buckets.associateBy { it.bucket }
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .map { date ->
                val key = date.toString()
                byDate[key].toPoint(date, costByBucket[key] ?: 0.0)
            }
            .toList()
    }

    // 全部范围按月，从最早有用量的月份排到本月
    private fun monthlyPoints(
        buckets: List<UsageBucket>,
        today: LocalDate,
        costByBucket: Map<String, Double>,
    ): List<UsagePoint> {
        val byMonth = buckets.mapNotNull { bucket ->
            runCatching { YearMonth.parse(bucket.bucket) to bucket }.getOrNull()
        }.toMap()
        val first = byMonth.keys.minOrNull() ?: return emptyList()
        val current = YearMonth.from(today)
        return generateSequence(first) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(current) }
            .map { month ->
                val key = month.toString()
                byMonth[month].toPoint(month.atDay(1), costByBucket[key] ?: 0.0)
            }
            .toList()
    }

    private fun UsageBucket?.toPoint(date: LocalDate, cost: Double) = UsagePoint(
        date = date,
        promptTokens = this?.promptTokens ?: 0,
        completionTokens = this?.completionTokens ?: 0,
        cachedTokens = this?.cachedTokens ?: 0,
        cost = cost,
    )
}
