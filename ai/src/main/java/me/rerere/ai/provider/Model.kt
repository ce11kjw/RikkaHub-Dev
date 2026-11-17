package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.pricing.ModelPricing
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    // 上下文窗口大小(token)
    val contextLength: Int = 1_000_000,
    /**
     * 手动设置的价格（美元 / 百万 token），填了就用它算费用；
     * 留空时按模型 id 去内置价格表里猜。自定义网关/中转站价格和官方不同时应该填这里。
     */
    val customPricing: ModelPricing? = null,
)

/**
 * 按空格分隔的关键词筛选模型：忽略大小写，每个关键词都要命中模型 ID 或显示名称的一部分，
 * 关键词之间不要求顺序。没有关键词时返回原列表。
 */
fun List<Model>.filterByKeywords(query: String): List<Model> {
    val keywords = query.split(" ").filter { it.isNotBlank() }
    if (keywords.isEmpty()) return this
    return filter { model ->
        keywords.all { keyword ->
            model.modelId.contains(keyword, ignoreCase = true) ||
                model.displayName.contains(keyword, ignoreCase = true)
        }
    }
}

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}
