package com.xyq.livetranslate

/** 本场视频的公开元数据；URL 仅用于抓取，不写入翻译提示词或历史正文。 */
data class YouTubeVideoInfo(
    val url: String,
    val title: String,
    val authorName: String,
)
