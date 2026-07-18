package com.xyq.livetranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.xyq.livetranslate.HistoryStore
import com.xyq.livetranslate.R
import com.xyq.livetranslate.TranslationLanguageCatalog
import com.xyq.livetranslate.TranslationMode

internal data class HistoryViews(
    val refreshButton: Button,
    val searchInput: EditText,
    val allChip: Chip,
    val interpretationChip: Chip,
    val videoChip: Chip,
    val emptyText: TextView,
    val list: LinearLayout,
    val detailTitle: TextView,
    val copyButton: Button,
    val detailText: TextView,
    val detailMeta: TextView,
    val detailContext: TextView,
    val detailEmptyText: TextView,
    val detailSegments: LinearLayout,
) {
    companion object {
        fun bind(root: View): HistoryViews = HistoryViews(
            refreshButton = root.findViewById(R.id.btnRefreshHistory),
            searchInput = root.findViewById(R.id.etHistorySearch),
            allChip = root.findViewById(R.id.chipHistoryAll),
            interpretationChip = root.findViewById(R.id.chipHistoryInterp),
            videoChip = root.findViewById(R.id.chipHistoryVideo),
            emptyText = root.findViewById(R.id.tvHistoryEmpty),
            list = root.findViewById(R.id.historyList),
            detailTitle = root.findViewById(R.id.tvHistoryTitle),
            copyButton = root.findViewById(R.id.btnCopyHistory),
            detailText = root.findViewById(R.id.tvHistoryDetail),
            detailMeta = root.findViewById(R.id.tvHistoryDetailMeta),
            detailContext = root.findViewById(R.id.tvHistoryDetailContext),
            detailEmptyText = root.findViewById(R.id.tvHistoryDetailEmpty),
            detailSegments = root.findViewById(R.id.historyDetailSegments),
        )
    }
}

internal class HistoryController(
    private val context: Context,
    private val views: HistoryViews,
    private val openDetailPage: (returnTabId: Int) -> Unit,
    private val toast: (String) -> Unit,
) {
    private val layoutInflater = LayoutInflater.from(context)
    private var allItems: List<HistoryStore.HistoryItem> = emptyList()
    private var modeFilter: TranslationMode? = null

    fun setup() {
        views.refreshButton.setOnClickListener { reload() }
        views.searchInput.doAfterTextChanged { renderList() }
        views.allChip.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                modeFilter = null
                renderList()
            }
        }
        views.interpretationChip.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                modeFilter = TranslationMode.INTERPRETATION
                renderList()
            }
        }
        views.videoChip.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                modeFilter = TranslationMode.VIDEO
                renderList()
            }
        }
        views.copyButton.setOnClickListener {
            val text = views.detailText.text.toString()
            if (text.isBlank()) return@setOnClickListener
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("transcript", text))
            toast("已复制全文")
        }
    }

    fun reload() {
        allItems = HistoryStore.list(context)
        renderList()
    }

    private fun renderList() {
        val query = views.searchInput.text?.toString().orEmpty().trim().lowercase()
        val items = allItems.filter { item ->
            val direction = "${TranslationLanguageCatalog.source(item.sourceLanguageCode).label} → " +
                TranslationLanguageCatalog.target(item.targetLanguageCode).label
            val matchesMode = modeFilter == null || item.mode == modeFilter
            val haystack = listOf(item.title, item.summary, direction, item.sceneLabel)
                .joinToString(" ")
                .lowercase()
            matchesMode && (query.isEmpty() || query in haystack)
        }
        views.list.removeAllViews()
        views.emptyText.text = if (allItems.isEmpty()) "暂无历史记录" else "没有匹配的记录"
        views.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.forEach { item ->
            val card = layoutInflater.inflate(R.layout.item_history_session, views.list, false)
            val icon = card.findViewById<TextView>(R.id.tvHistoryItemIcon)
            val mode = card.findViewById<TextView>(R.id.tvHistoryItemMode)
            val title = card.findViewById<TextView>(R.id.tvHistoryItemTitle)
            val time = card.findViewById<TextView>(R.id.tvHistoryItemTime)
            val meta = card.findViewById<TextView>(R.id.tvHistoryItemMeta)
            val summary = card.findViewById<TextView>(R.id.tvHistoryItemSummary)
            val isInterpretation = item.mode == TranslationMode.INTERPRETATION
            icon.text = if (isInterpretation) "麦" else "播"
            icon.setTextColor(context.getColor(if (isInterpretation) R.color.brand else R.color.warning))
            ViewCompat.setBackgroundTintList(
                icon,
                context.getColorStateList(
                    if (isInterpretation) R.color.primary_fixed else R.color.warning_container,
                ),
            )
            mode.text = item.mode.label
            mode.setTextColor(context.getColor(if (isInterpretation) R.color.brand else R.color.warning))
            title.text = item.title
            time.text = HistoryStore.formatTime(item.updatedAt)
            val direction = "${TranslationLanguageCatalog.source(item.sourceLanguageCode).label} → " +
                TranslationLanguageCatalog.target(item.targetLanguageCode).label
            meta.text = "$direction · ${item.sceneLabel} · ${HistoryStore.formatDuration(item.durationMs)}"
            summary.text = item.summary.trim().replace('\n', ' ').ifBlank { "暂无字幕摘要" }
            card.setOnClickListener { showDetail(item) }
            card.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = context.resources.getDimensionPixelSize(R.dimen.space_12) }
            views.list.addView(card)
        }
    }

    private fun showDetail(item: HistoryStore.HistoryItem) {
        val session = HistoryStore.load(context, item.fileName)
        if (session == null) {
            toast("记录不存在或已损坏")
            reload()
            return
        }
        views.detailTitle.text = session.title
        views.detailText.text = HistoryStore.toMarkdown(session)
        views.detailMeta.text = buildString {
            append(session.mode.label).append(" · ").append(session.directionLabel)
            append("\n").append(session.sceneLabel)
            append(" · ").append(HistoryStore.formatTime(session.startedAt))
            append(" · ").append(HistoryStore.formatDuration(session.durationMs))
        }
        views.detailContext.text = "本场背景\n${session.contextSummary}"
        views.detailContext.visibility = if (session.contextSummary.isBlank()) View.GONE else View.VISIBLE
        views.detailSegments.removeAllViews()
        views.detailEmptyText.visibility = if (session.segments.isEmpty()) View.VISIBLE else View.GONE
        session.segments.forEach { segment ->
            val row = layoutInflater.inflate(R.layout.item_history_segment, views.detailSegments, false)
            val elapsed = row.findViewById<TextView>(R.id.tvHistorySegmentTime)
            val source = row.findViewById<TextView>(R.id.tvHistorySegmentSource)
            val translation = row.findViewById<TextView>(R.id.tvHistorySegmentTranslation)
            elapsed.text = HistoryStore.formatDuration(segment.elapsedMs)
            source.text = segment.sourceText
            source.visibility = if (segment.sourceText.isBlank()) View.GONE else View.VISIBLE
            translation.text = segment.translatedText
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = context.resources.getDimensionPixelSize(R.dimen.space_12) }
            views.detailSegments.addView(row)
        }
        openDetailPage(R.id.nav_history)
    }
}
