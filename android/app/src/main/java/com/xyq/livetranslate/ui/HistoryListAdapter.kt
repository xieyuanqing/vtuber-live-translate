package com.xyq.livetranslate.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.xyq.livetranslate.HistoryStore
import com.xyq.livetranslate.R
import com.xyq.livetranslate.TranslationLanguageCatalog
import com.xyq.livetranslate.TranslationMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal sealed class HistoryRow {
    data class Day(val label: String) : HistoryRow()
    data class Session(val item: HistoryStore.HistoryItem) : HistoryRow()
}

internal class HistoryListAdapter(
    private val onSessionClick: (HistoryStore.HistoryItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val rows = mutableListOf<HistoryRow>()

    fun submit(next: List<HistoryRow>) {
        rows.clear()
        rows.addAll(next)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is HistoryRow.Day -> VIEW_DAY
        is HistoryRow.Session -> VIEW_SESSION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_DAY) {
            DayHolder(inflater.inflate(R.layout.item_history_day_header, parent, false))
        } else {
            SessionHolder(inflater.inflate(R.layout.item_history_session, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HistoryRow.Day -> (holder as DayHolder).bind(row.label)
            is HistoryRow.Session -> (holder as SessionHolder).bind(row.item, onSessionClick)
        }
    }

    private class DayHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label = view.findViewById<TextView>(R.id.tvHistoryDayHeader)
        fun bind(text: String) {
            label.text = text
        }
    }

    private class SessionHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon = view.findViewById<TextView>(R.id.tvHistoryItemIcon)
        private val mode = view.findViewById<TextView>(R.id.tvHistoryItemMode)
        private val title = view.findViewById<TextView>(R.id.tvHistoryItemTitle)
        private val time = view.findViewById<TextView>(R.id.tvHistoryItemTime)
        private val meta = view.findViewById<TextView>(R.id.tvHistoryItemMeta)
        private val summary = view.findViewById<TextView>(R.id.tvHistoryItemSummary)

        fun bind(item: HistoryStore.HistoryItem, onClick: (HistoryStore.HistoryItem) -> Unit) {
            val context = itemView.context
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
            title.text = displayTitle(item)
            time.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.updatedAt))
            val direction = "${TranslationLanguageCatalog.source(item.sourceLanguageCode).label} → " +
                TranslationLanguageCatalog.target(item.targetLanguageCode).label
            meta.text = "$direction · ${HistoryStore.formatDuration(item.durationMs)}"
            summary.text = item.summary.trim().replace('\n', ' ').ifBlank {
                context.getString(R.string.rt_history_no_summary)
            }
            itemView.setOnClickListener { onClick(item) }
            (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin =
                context.resources.getDimensionPixelSize(R.dimen.space_12)
        }

        private fun displayTitle(item: HistoryStore.HistoryItem): String {
            val context = itemView.context
            val scene = item.sceneLabel.trim()
            val rawTitle = item.title.trim()
            val autoPrefix = item.mode.label + " · "
            val looksAuto = rawTitle.startsWith(autoPrefix) || rawTitle.isBlank()
            return when {
                item.mode == TranslationMode.VIDEO && !looksAuto -> rawTitle
                scene.isNotEmpty() -> scene
                rawTitle.isNotEmpty() -> rawTitle
                item.mode == TranslationMode.INTERPRETATION -> context.getString(R.string.rt_mode_interpretation)
                else -> context.getString(R.string.rt_mode_video)
            }
        }
    }

    private companion object {
        const val VIEW_DAY = 1
        const val VIEW_SESSION = 2
    }
}
