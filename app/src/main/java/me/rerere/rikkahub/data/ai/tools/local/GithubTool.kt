package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val GITHUB_API = "https://api.github.com"

/**
 * 查询 GitHub 上的公开信息：仓库概况、issue / PR 列表。
 *
 * token 作为调用参数传入，不在 App 里保存 —— 和 ssh_exec 同一个原则：
 * 凭据只在这一次调用里用，用完就从内存里消失了。
 *
 * 不传 token 也能用，只是有每小时 60 次的匿名限额。
 */
internal fun buildGithubTool(client: OkHttpClient): Tool = Tool(
    name = "github_query",
    description = """
        Query GitHub for a repository's overview, or list its issues and pull requests.
        Pass a personal access token only when private data or a higher rate limit is needed;
        the token is used for this call and never stored.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "What to fetch: repo, issues, or pulls")
                    putJsonArray("enum") { add("repo"); add("issues"); add("pulls") }
                }
                putJsonObject("owner") {
                    put("type", "string")
                    put("description", "Repository owner, e.g. rikkahub")
                }
                putJsonObject("repo") {
                    put("type", "string")
                    put("description", "Repository name, e.g. rikkahub")
                }
                putJsonObject("state") {
                    put("type", "string")
                    put("description", "For issues/pulls: open, closed, or all. Defaults to open")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "How many items to return, 1-30, defaults to 10")
                }
                putJsonObject("token") {
                    put("type", "string")
                    put("description", "Optional GitHub token, used for this call only and never stored")
                }
            },
            required = listOf("action", "owner", "repo")
        )
    },
    needsApproval = { false },
    execute = { args ->
        val obj = args.jsonObject
        val action = obj["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "repo"
        val owner = obj["owner"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val repo = obj["repo"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "open"
        val limit = (obj["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 10).coerceIn(1, 30)
        val token = obj["token"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(owner.isNotBlank() && repo.isNotBlank()) { "owner and repo are required" }

        val path = when (action) {
            "issues" -> "/repos/$owner/$repo/issues?state=$state&per_page=$limit"
            "pulls" -> "/repos/$owner/$repo/pulls?state=$state&per_page=$limit"
            else -> "/repos/$owner/$repo"
        }

        val result = withContext(Dispatchers.IO) {
            val scoped = client.newBuilder().callTimeout(25, TimeUnit.SECONDS).build()
            val request = Request.Builder()
                .url("$GITHUB_API$path")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "RikkaHub")
                .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
                .build()

            scoped.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext buildJsonObject {
                        put("ok", false)
                        put("status", resp.code)
                        put("error", body.take(500))
                        if (resp.code == 403 || resp.code == 429) {
                            put("hint", "Rate limited. Retry later or pass a token.")
                        }
                    }
                }
                val root = kotlinx.serialization.json.Json.parseToJsonElement(body)
                buildJsonObject {
                    put("ok", true)
                    when (action) {
                        "issues", "pulls" -> {
                            // issue 列表里也会混进 PR，标注出来免得模型误判
                            putJsonArray("items") {
                                (root as? kotlinx.serialization.json.JsonArray)?.forEach { el ->
                                    val o = el.jsonObject
                                    addJsonObject {
                                        put("number", o["number"]?.jsonPrimitive?.contentOrNull)
                                        put("title", o["title"]?.jsonPrimitive?.contentOrNull)
                                        put("state", o["state"]?.jsonPrimitive?.contentOrNull)
                                        put("user", (o["user"] as? kotlinx.serialization.json.JsonObject)
                                            ?.get("login")?.jsonPrimitive?.contentOrNull)
                                        put("is_pull_request", o.containsKey("pull_request"))
                                        put("url", o["html_url"]?.jsonPrimitive?.contentOrNull)
                                    }
                                }
                            }
                        }
                        else -> {
                            val o = root.jsonObject
                            put("full_name", o["full_name"]?.jsonPrimitive?.contentOrNull)
                            put("description", o["description"]?.jsonPrimitive?.contentOrNull)
                            put("stars", o["stargazers_count"]?.jsonPrimitive?.contentOrNull)
                            put("forks", o["forks_count"]?.jsonPrimitive?.contentOrNull)
                            put("open_issues", o["open_issues_count"]?.jsonPrimitive?.contentOrNull)
                            put("language", o["language"]?.jsonPrimitive?.contentOrNull)
                            put("default_branch", o["default_branch"]?.jsonPrimitive?.contentOrNull)
                            put("updated_at", o["updated_at"]?.jsonPrimitive?.contentOrNull)
                            put("url", o["html_url"]?.jsonPrimitive?.contentOrNull)
                        }
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(result.toString()))
    }
)
