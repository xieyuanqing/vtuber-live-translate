package com.xyq.livetranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal data class HistoryViews(
    val searchInput: EditText,
    val allChip: Chip,
    val interpretationChip: Chip,
    val videoChip: Chip,
    val emptyText: TextView,
    val list: LinearLayout,
    val detailTitle: TextView,
    val shareButton: Button,
    val copyButton: Button,
    val detailText: TextView,
    val detailMeta: TextView,
    val detailContext: TextView,
    val detailEmptyText: TextView,
    val detailSegments: LinearLayout,
) {
    companion object {
        fun bind(root: View): HistoryViews = HistoryViews(
            searchInput = root.findViewById(R.id.etHistorySearch),
            allChip = root.findViewById(R.id.chipHistoryAll),
            interpretationChip = root.findViewById(R.id.chipHistoryInterp),
            videoChip = root.findViewById(R.id.chipHistoryVideo),
            emptyText = root.findViewById(R.id.tvHistoryEmpty),
            list = root.findViewById(R.id.historyList),
            detailTitle = root.findViewById(R.id.tvHistoryTitle),
            shareButton = root.findViewById(R.id.btnShareHistory),
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
        views.shareButton.setOnClickListener {
            val text = views.detailText.text.toString()
            if (text.isBlank()) {
                toast("暂无可分享内容")
                return@setOnClickListener
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, views.detailTitle.text.toString())
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, "分享历史记录"))
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
        var lastGroupKey: String? = null
        items.forEach { item ->
            val groupKey = dayGroupKey(item.updatedAt)
            if (groupKey != lastGroupKey) {
                views.list.addView(buildDayHeader(dayGroupLabel(item.updatedAt)))
                lastGroupKey = groupKey
            }
            views.list.addView(buildSessionCard(item))
        }
    }

    private fun buildDayHeader(label: String): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(context.getColor(R.color.text_muted))
            textSize = 12f
            setPadding(0, context.resources.getDimensionPixelSize(R.dimen.space_12), 0, context.resources.getDimensionPixelSize(R.dimen.space_8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun buildSessionCard(item: HistoryStore.HistoryItem): View {
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
        mode.visibility = View.GONE
        // 列表标题优先场景名；视频若自定义过会话标题（非「视频 ·」自动名）则用标题。
        title.text = displayTitle(item)
        time.text = formatClock(item.updatedAt)
        val direction = "${TranslationLanguageCatalog.source(item.sourceLanguageCode).label} → " +
            TranslationLanguageCatalog.target(item.targetLanguageCode).label
        meta.text = "$direction · ${HistoryStore.formatDuration(item.durationMs)}"
        summary.text = item.summary.trim().replace('\n', ' ').ifBlank { "暂无字幕摘要" }
        card.setOnClickListener { showDetail(item) }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = context.resources.getDimensionPixelSize(R.dimen.space_12) }
        return card
    }

    private fun displayTitle(item: HistoryStore.HistoryItem): String {
        val scene = item.sceneLabel.trim()
        val rawTitle = item.title.trim()
        val autoPrefix = item.mode.label + " · "
        val looksAuto = rawTitle.startsWith(autoPrefix) || rawTitle.isBlank()
        return when {
            // 视频用户手填标题优先；同传/自动标题则优先场景名。
            item.mode == TranslationMode.VIDEO && !looksAuto -> rawTitle
            scene.isNotEmpty() -> scene
            rawTitle.isNotEmpty() -> rawTitle
            else -> item.mode.label
        }
    }

    private fun dayGroupKey(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))

    private fun dayGroupLabel(ms: Long): String {
        val dayStart = startOfDay(ms)
        val today = startOfDay(System.currentTimeMillis())
        val yesterday = today - 24L * 60L * 60L * 1000L
        return when (dayStart) {
            today -> "今天"
            yesterday -> "昨天"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
        }
    }

    private fun startOfDay(ms: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun formatClock(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))


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
