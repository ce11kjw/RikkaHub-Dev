package me.rerere.rikkahub.data.sync

/**
 * 各版本之间的 schema 差异，用于把「更新的备份」降级到旧版本。
 *
 * 只记录「加列」这类可安全回退的差异：新版本多出来的列旧版本不认识，
 * 去掉后不影响任何双方共有的数据。如果将来出现删表、改类型这类不可逆变更，
 * 这里返回 null，让恢复流程明确报错，而不是冒险写入不兼容的数据。
 */
internal object DatabaseDowngradePlan {

    /**
     * 返回从 [fromVersion] 降到 [toVersion] 需要丢弃的列：表名 → 列名集合。
     * 无法安全降级时返回 null。
     */
    fun columnsToDropFor(fromVersion: Int, toVersion: Int): Map<String, Set<String>>? {
        if (fromVersion != 34 || toVersion > 34) return null
        return when (toVersion) {
            // v34 相对 v33 只在 usage_record 上加了费用相关列
            33 -> mapOf("usage_record" to setOf("cost", "pricing_snapshot"))
            // 再往前的版本同样不认识这两列
            in 0..32 -> mapOf("usage_record" to setOf("cost", "pricing_snapshot"))
            else -> null
        }
    }
}
