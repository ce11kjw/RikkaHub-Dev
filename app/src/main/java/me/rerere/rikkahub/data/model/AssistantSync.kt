package me.rerere.rikkahub.data.model

/**
 * 助手全局同步：一个助手的配置改了，其他助手跟着变。
 *
 * 只同步**行为配置**（模型、提示词、工具、开关），不同步**身份和外观**
 * （名字、头像、背景）—— 全同步的话所有助手会变成一模一样，根本分不清谁是谁。
 *
 * 用法：拿改动前后的助手列表做 diff，找出变的那一个，把它的行为字段广播出去。
 */
object AssistantSync {

    /**
     * 把 [changed] 的行为配置同步给 [others] 里的每个助手。
     *
     * @param changed 用户刚改过的那个助手（新值）
     * @param others 其他助手（保留各自的身份字段）
     * @return 同步后的完整列表，包含 changed 自己
     */
    fun broadcast(changed: Assistant, others: List<Assistant>): List<Assistant> {
        val synced = others.map { it.applyBehaviorFrom(changed) }
        // 保持原顺序：把 changed 放回它原来的位置
        return synced.map { if (it.id == changed.id) changed else it }
            .ifEmpty { listOf(changed) }
    }

    /**
     * 从改动前后两份列表里找出「哪个助手变了」。
     *
     * 返回新值；找不到（比如新增/删除助手）时返回 null —— 那种情况不该触发同步，
     * 否则新增一个助手会把所有老助手的配置冲掉。
     */
    fun findChanged(before: List<Assistant>, after: List<Assistant>): Assistant? {
        if (before.size != after.size) return null
        for (i in after.indices) {
            val old = before.getOrNull(i) ?: return null
            val new = after[i]
            // 只比较行为字段：改名字/头像不算「配置变更」，不该触发同步
            if (old.behaviorDiffersFrom(new)) return new
        }
        return null
    }

    /** 两个助手的**行为配置**是否不同（忽略身份和外观）。 */
    fun Assistant.behaviorDiffersFrom(other: Assistant): Boolean =
        behaviorSignature() != other.behaviorSignature()

    /**
     * 行为配置的指纹。
     *
     * 手动列举字段而不是用反射：反射会把 id/name 也算进去，
     * 而且以后加字段时不会自动包含，容易漏同步却查不出来。
     */
    private fun Assistant.behaviorSignature(): List<Any?> = listOf(
        chatModelId,
        systemPrompt,
        presetMessages,
        temperature,
        topP,
        maxTokens,
        contextMessageLimit,
        reasoningLevel,
        streamOutput,
        localTools,
        mcpServers,
        enabledSkills,
        regexes,
        modeInjectionIds,
        lorebookIds,
        quickMessageIds,
        enableMemory,
        useGlobalMemory,
        autoConsolidateMemory,
        enableRecentChatsReference,
        enableCrossConversationContext,
        enableWebSearch,
        enableTimeReminder,
        timeReminderIntervalMinutes,
        allowConversationSystemPrompt,
        workspaceId,
        messageTemplate,
        customHeaders,
        customBodies,
    )

    /**
     * 把另一个助手的行为配置搬过来，保留自己的身份字段。
     *
     * 记忆不用单独处理：Assistant 上没有 memoryId 字段，
     * 记忆库由 useGlobalMemory 决定（true 用全局库，false 用 assistant.id），
     * 所以同步 useGlobalMemory 就等于同步了记忆。
     */
    fun Assistant.applyBehaviorFrom(source: Assistant): Assistant = copy(
        chatModelId = source.chatModelId,
        systemPrompt = source.systemPrompt,
        presetMessages = source.presetMessages,
        temperature = source.temperature,
        topP = source.topP,
        maxTokens = source.maxTokens,
        contextMessageLimit = source.contextMessageLimit,
        reasoningLevel = source.reasoningLevel,
        streamOutput = source.streamOutput,
        localTools = source.localTools,
        mcpServers = source.mcpServers,
        enabledSkills = source.enabledSkills,
        regexes = source.regexes,
        modeInjectionIds = source.modeInjectionIds,
        lorebookIds = source.lorebookIds,
        quickMessageIds = source.quickMessageIds,
        enableMemory = source.enableMemory,
        useGlobalMemory = source.useGlobalMemory,
        autoConsolidateMemory = source.autoConsolidateMemory,
        enableRecentChatsReference = source.enableRecentChatsReference,
        enableCrossConversationContext = source.enableCrossConversationContext,
        enableWebSearch = source.enableWebSearch,
        enableTimeReminder = source.enableTimeReminder,
        timeReminderIntervalMinutes = source.timeReminderIntervalMinutes,
        allowConversationSystemPrompt = source.allowConversationSystemPrompt,
        workspaceId = source.workspaceId,
        messageTemplate = source.messageTemplate,
        customHeaders = source.customHeaders,
        customBodies = source.customBodies,
    )

    /**
     * 新建助手时继承配置。
     *
     * 从 [template] 继承全部行为配置，但用自己的 id 和传入的名字 ——
     * 新助手必须有自己的 id，否则会和模板撞。
     */
    fun newAssistantLike(template: Assistant, id: kotlin.uuid.Uuid, name: String): Assistant =
        Assistant(id = id, name = name).applyBehaviorFrom(template)
}
