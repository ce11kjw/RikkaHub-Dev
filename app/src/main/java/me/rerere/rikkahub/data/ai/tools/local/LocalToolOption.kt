package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("chart_display")
    data object ChartDisplay : LocalToolOption()

    @Serializable
    @SerialName("phone_use")
    data object PhoneUse : LocalToolOption()

    /** 通用 HTTP 请求 + 网页正文抓取（http_request / fetch_url） */
    @Serializable
    @SerialName("http_request")
    data object HttpRequest : LocalToolOption()

    /** 本机文件读写（list_files / read_file / write_file） */
    @Serializable
    @SerialName("file_access")
    data object FileAccess : LocalToolOption()

    /** SSH 远程执行（服务器信息每次调用传入，不保存） */
    @Serializable
    @SerialName("ssh_exec")
    data object SshExec : LocalToolOption()

    /** 二维码生成 / 站点探测 / 天气查询 */
    @Serializable
    @SerialName("utilities")
    data object Utilities : LocalToolOption()

    /** GitHub 查询（token 每次调用传入，不保存） */
    @Serializable
    @SerialName("github_query")
    data object GithubQuery : LocalToolOption()
}
