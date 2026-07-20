package com.xyq.livetranslate

/** 支持内容分析的视频平台。 */
enum class VideoPlatform(val label: String) {
    YOUTUBE("YouTube"),
    BILIBILI("哔哩哔哩"),
    TWITCH("Twitch"),
}

/** 本场视频或直播的公开元数据；URL 仅用于抓取，不写入翻译提示词或历史正文。 */
data class VideoMetadata(
    val platform: VideoPlatform,
    val url: String,
    val title: String,
    val authorName: String,
    val category: String = "",
    val description: String = "",
)
