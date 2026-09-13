package com.xyq.livetranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    val deleteButton: Button,
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
            deleteButton = root.findViewById(R.id.btnDeleteHistory),
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
    private val closeDetailPage: () -> Unit,
    private val toast: (String) -> Unit,
) {
    private val layoutInflater = LayoutInflater.from(context)
    private var allItems: List<HistoryStore.HistoryItem> = emptyList()
    private var modeFilter: TranslationMode? = null
    private var currentDetailFileName: String? = null
    private var currentDetailIsActive = false

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
            toast(context.getString(R.string.rt_toast_copied_full_text))
        }
        views.shareButton.setOnClickListener {
            val text = views.detailText.text.toString()
            if (text.isBlank()) {
                toast(context.getString(R.string.rt_toast_no_shareable_content))
                return@setOnClickListener
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, views.detailTitle.text.toString())
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, context.getString(R.string.rt_history_share_title)))
        }
        views.deleteButton.setOnClickListener {
            val fileName = currentDetailFileName ?: return@setOnClickListener
            if (currentDetailIsActive) {
                toast(context.getString(R.string.rt_toast_cannot_delete_active_session))
                return@setOnClickListener
            }
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.rt_dialog_delete_history_title))
                .setMessage(context.getString(R.string.rt_dialog_delete_history_message))
                .setNegativeButton(context.getString(R.string.rt_action_cancel), null)
                .setPositiveButton(context.getString(R.string.rt_action_delete)) { _, _ ->
                    if (HistoryStore.delete(context, fileName)) {
                        currentDetailFileName = null
                        currentDetailIsActive = false
                        views.detailText.text = ""
                        reload()
                        closeDetailPage()
                        toast(context.getString(R.string.rt_toast_history_deleted))
                    } else {
                        toast(context.getString(R.string.rt_toast_history_delete_failed))
                        reload()
                    }
                }
                .create()
            dialog.setOnShowListener {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                    .setTextColor(context.getColor(R.color.error))
            }
            dialog.show()
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
        views.emptyText.text = if (allItems.isEmpty()) {
            context.getString(R.string.rt_history_empty)
        } else {
            context.getString(R.string.rt_history_no_match)
        }
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
        icon.text = if (isInterpretation) {
            context.getString(R.string.rt_history_icon_mic)
        } else {
            context.getString(R.string.rt_history_icon_broadcast)
        }
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
        summary.text = item.summary.trim().replace('\n', ' ').ifBlank { context.getString(R.string.rt_history_no_summary) }
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
            else -> if (item.mode == TranslationMode.INTERPRETATION) {
                context.getString(R.string.rt_mode_interpretation)
            } else {
                context.getString(R.string.rt_mode_video)
            }
        }
    }

    private fun dayGroupKey(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))

    private fun dayGroupLabel(ms: Long): String {
        val dayStart = startOfDay(ms)
        val today = startOfDay(System.currentTimeMillis())
        val yesterday = today - 24L * 60L * 60L * 1000L
        return when (dayStart) {
            today -> context.getString(R.string.rt_date_today)
            yesterday -> context.getString(R.string.rt_date_yesterday)
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
            currentDetailFileName = null
            currentDetailIsActive = false
            toast(context.getString(R.string.rt_toast_history_not_found_or_corrupt))
            reload()
            return
        }
        currentDetailFileName = item.fileName
        currentDetailIsActive = session.endedAt == null
        views.detailTitle.text = session.title
        views.detailText.text = HistoryStore.toMarkdown(session, context)
        val modeLabel = if (session.mode == TranslationMode.INTERPRETATION) {
            context.getString(R.string.rt_mode_interpretation)
        } else {
            context.getString(R.string.rt_mode_video)
        }
        views.detailMeta.text = buildString {
            append(modeLabel).append(" · ").append(session.directionLabel)
            append("\n").append(session.sceneLabel)
            append(" · ").append(HistoryStore.formatTime(session.startedAt))
            append(" · ").append(HistoryStore.formatDuration(session.durationMs))
        }
        views.detailContext.text = context.getString(R.string.rt_history_detail_context_prefix, session.contextSummary)
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
