package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity

@Dao
interface UsageRecordDAO {
    @Insert
    suspend fun insert(record: UsageRecordEntity)

    @Query(
        "SELECT COALESCE(SUM(prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(cached_tokens), 0) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since"
    )
    suspend fun getTotals(since: Long = 0): UsageTotals

    /** 一段时间内的总花费（美元）。没有价格数据的记录 cost 为 0，不计入。 */
    @Query("SELECT COALESCE(SUM(cost), 0) FROM usage_record WHERE created_at >= :since")
    suspend fun getTotalCost(since: Long = 0): Double

    /** 按时间桶汇总花费，格式与 [getTotalsByBucket] 保持一致。 */
    @Query(
        "SELECT strftime(:format, created_at / 1000, 'unixepoch', 'localtime') AS bucket, " +
            "COALESCE(SUM(cost), 0) AS cost " +
            "FROM usage_record WHERE created_at >= :since GROUP BY bucket ORDER BY bucket"
    )
    suspend fun getCostByBucket(format: String, since: Long): List<CostBucket>

    /** 按模型汇总花费，和 [getTotalsByModel] 同样按 model_id 归并。 */
    @Query(
        "SELECT COALESCE(model_id, model_name) AS modelId, MAX(model_name) AS modelName, " +
            "COALESCE(SUM(cost), 0) AS cost " +
            "FROM usage_record WHERE created_at >= :since " +
            "GROUP BY COALESCE(model_id, model_name) ORDER BY SUM(cost) DESC"
    )
    suspend fun getCostByModel(since: Long): List<ModelCost>

    /** 有费用的记录数 vs 总记录数，用于告诉用户「多少请求没有价格数据」。 */
    @Query("SELECT COUNT(*) FROM usage_record WHERE created_at >= :since AND cost > 0")
    suspend fun countPricedRecords(since: Long): Int

    @Query("SELECT COUNT(*) FROM usage_record WHERE created_at >= :since")
    suspend fun countRecords(since: Long): Int

    /** 按本地时间分桶汇总，[format] 是 strftime 格式，比如按天用 `%Y-%m-%d`、按月用 `%Y-%m` */
    @Query(
        "SELECT strftime(:format, created_at / 1000, 'unixepoch', 'localtime') AS bucket, " +
            "SUM(prompt_tokens) AS promptTokens, SUM(completion_tokens) AS completionTokens, " +
            "SUM(cached_tokens) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since GROUP BY bucket ORDER BY bucket"
    )
    suspend fun getTotalsByBucket(format: String, since: Long): List<UsageBucket>

    /** 每天（本地日期 `yyyy-MM-dd`）某类请求的次数 */
    @Query(
        "SELECT strftime('%Y-%m-%d', created_at / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count " +
            "FROM usage_record WHERE source = :source AND created_at >= :since GROUP BY day"
    )
    suspend fun getRequestCountPerDay(source: String, since: Long): List<DayCount>

    // 回填的旧记录可能只有 model_id 没有 model_name，按 model_id 归并，名称取任意一条非空的
    @Query(
        "SELECT model_id AS modelId, MAX(model_name) AS modelName, " +
            "SUM(prompt_tokens) AS promptTokens, SUM(completion_tokens) AS completionTokens, " +
            "SUM(cached_tokens) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since " +
            "GROUP BY COALESCE(model_id, model_name) " +
            "ORDER BY SUM(prompt_tokens + completion_tokens) DESC"
    )
    suspend fun getTotalsByModel(since: Long): List<ModelUsage>

    @Query(
        "SELECT source, SUM(prompt_tokens) AS promptTokens, SUM(completion_tokens) AS completionTokens, " +
            "SUM(cached_tokens) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since " +
            "GROUP BY source ORDER BY SUM(prompt_tokens + completion_tokens) DESC"
    )
    suspend fun getTotalsBySource(since: Long): List<SourceUsage>
}

data class UsageTotals(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)

data class DayCount(
    val day: String,
    val count: Int,
)

data class UsageBucket(
    val bucket: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)

data class ModelUsage(
    val modelId: String?,
    val modelName: String?,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)

/** 某个时间桶的总花费 */
data class CostBucket(
    val bucket: String,
    val cost: Double,
)

/** 某个模型的累计花费 */
data class ModelCost(
    val modelId: String?,
    val modelName: String?,
    val cost: Double,
)

data class SourceUsage(
    val source: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)
