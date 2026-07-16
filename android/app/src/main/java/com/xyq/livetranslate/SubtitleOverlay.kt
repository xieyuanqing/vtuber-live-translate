package com.xyq.livetranslate

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 最终版悬浮字幕：深色普通态与浅色紧凑态。整窗可拖动；右上角可以暂停音频发送、
 * 收紧/展开。设置页修改字号、透明度、行数后立即生效。
 */
class SubtitleOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var header: LinearLayout? = null
    private var statusLabel: TextView? = null
    private var pauseButton: TextView? = null
    private var compactButton: TextView? = null
    private var tvConfirmed: TextView? = null
    private var tvCurrent: TextView? = null
    private var dot: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var compact = false
    private var appliedStyle = -1
    private var expandedWidth = 0
    private var latestConfirmed = ""
    private var latestCurrent = ""

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        val dm = context.resources.displayMetrics
        expandedWidth = minOf(dm.widthPixels, dm.heightPixels) - dp(24)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            elevation = dp(8).toFloat()
        }
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val stateDot = View(context).apply {
            background = circle(Color.parseColor("#34C759"))
        }
        headerRow.addView(
            stateDot,
            LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) },
        )
        val stateText = TextView(context).apply {
            text = "流译 · 实时"
            setTextColor(Color.parseColor("#D9E8FF"))
            textSize = 12f
            letterSpacing = 0.04f
        }
        headerRow.addView(
            stateText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val pause = controlButton("Ⅱ", "暂停或继续翻译").apply {
            setOnClickListener { togglePause() }
        }
        val compactToggle = controlButton("↙", "切换紧凑字幕").apply {
            setOnClickListener { toggleCompact() }
        }
        headerRow.addView(pause, LinearLayout.LayoutParams(dp(36), dp(36)))
        headerRow.addView(
            compactToggle,
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(4) },
        )
        container.addView(
            headerRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val confirmed = TextView(context).apply {
            setTextColor(Color.parseColor("#B8C5D6"))
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        container.addView(
            confirmed,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val current = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.08f)
            setShadowLayer(3f, 0f, 1f, Color.argb(120, 0, 0, 0))
            setPadding(0, dp(6), 0, 0)
            text = "等待字幕…"
        }
        container.addView(
            current,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val params = WindowManager.LayoutParams(
            expandedWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }

        container.setOnTouchListener(dragListener(container, params))
        wm.addView(container, params)
        root = container
        header = headerRow
        statusLabel = stateText
        pauseButton = pause
        compactButton = compactToggle
        tvConfirmed = confirmed
        tvCurrent = current
        dot = stateDot
        lp = params
        applyStyleNow()
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        header = null
        statusLabel = null
        pauseButton = null
        compactButton = null
        tvConfirmed = null
        tvCurrent = null
        dot = null
        lp = null
        compact = false
    }

    fun setLines(confirmed: String, current: String) {
        latestConfirmed = confirmed.trim()
        latestCurrent = current.trim()
        renderLines()
    }

    fun setStateColor(colorHex: String) {
        val color = runCatching { Color.parseColor(colorHex) }
            .getOrDefault(Color.parseColor("#34C759"))
        (dot?.background as? GradientDrawable)?.setColor(color)
    }

    fun maybeReapplyStyle() {
        if (appliedStyle != StatusBus.styleVersion.get()) applyStyleNow()
        val isPaused = StatusBus.paused
        pauseButton?.text = if (isPaused) "▶" else "Ⅱ"
        statusLabel?.text = if (isPaused) "流译 · 已暂停" else "流译 · 实时"
    }

    private fun togglePause() {
        context.startService(
            Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_TOGGLE_PAUSE),
        )
        pauseButton?.postDelayed({ maybeReapplyStyle() }, 80L)
    }

    private fun toggleCompact() {
        compact = !compact
        applyAppearance()
        renderLines()
    }

    private fun renderLines() {
        tvConfirmed?.apply {
            text = latestConfirmed
            visibility = if (!compact && latestConfirmed.isNotEmpty()) View.VISIBLE else View.GONE
        }
        tvCurrent?.apply {
            text = when {
                latestCurrent.isNotEmpty() -> latestCurrent
                latestConfirmed.isNotEmpty() -> latestConfirmed
                else -> "等待字幕…"
            }
            visibility = View.VISIBLE
        }
    }

    private fun applyStyleNow() {
        appliedStyle = StatusBus.styleVersion.get()
        val font = SettingsStore.fontSizeSp(context)
        tvCurrent?.textSize = if (compact) (font - 1).coerceAtLeast(13).toFloat() else font.toFloat()
        tvCurrent?.maxLines = if (compact) 2 else SettingsStore.overlayMaxLines(context)
        tvConfirmed?.textSize = (font - 4).coerceAtLeast(11).toFloat()
        applyAppearance()
    }

    private fun applyAppearance() {
        val container = root ?: return
        val params = lp ?: return
        val opacity = SettingsStore.bgOpacityPct(context).coerceIn(20, 100)
        if (compact) {
            container.setPadding(dp(12), dp(7), dp(12), dp(10))
            container.background = roundedRect(
                fill = Color.argb(248, 248, 251, 255),
                stroke = Color.argb(90, 0, 88, 188),
                radius = 18,
            )
            statusLabel?.visibility = View.GONE
            pauseButton?.visibility = View.GONE
            compactButton?.apply {
                text = "↗"
                setTextColor(Color.parseColor("#0058BC"))
                contentDescription = "展开字幕"
            }
            tvCurrent?.apply {
                setTextColor(Color.parseColor("#102A43"))
                setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                setPadding(0, dp(2), 0, 0)
            }
            params.width = (expandedWidth * 0.78f).toInt()
        } else {
            container.setPadding(dp(16), dp(12), dp(16), dp(14))
            container.background = roundedRect(
                fill = Color.argb(255 * opacity / 100, 20, 29, 43),
                stroke = Color.argb(55, 255, 255, 255),
                radius = 22,
            )
            statusLabel?.visibility = View.VISIBLE
            pauseButton?.visibility = View.VISIBLE
            compactButton?.apply {
                text = "↙"
                setTextColor(Color.WHITE)
                contentDescription = "收紧字幕"
            }
            tvCurrent?.apply {
                setTextColor(Color.WHITE)
                setShadowLayer(3f, 0f, 1f, Color.argb(120, 0, 0, 0))
                setPadding(0, dp(6), 0, 0)
            }
            params.width = expandedWidth
        }
        val font = SettingsStore.fontSizeSp(context)
        tvCurrent?.textSize = if (compact) (font - 1).coerceAtLeast(13).toFloat() else font.toFloat()
        tvCurrent?.maxLines = if (compact) 2 else SettingsStore.overlayMaxLines(context)
        runCatching { wm.updateViewLayout(container, params) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragListener(
        container: View,
        params: WindowManager.LayoutParams,
    ) = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && dx * dx + dy * dy > dp(6) * dp(6)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(container, params) }
                    }
                }
            }
            return true
        }
    }

    private fun controlButton(label: String, description: String) = TextView(context).apply {
        text = label
        contentDescription = description
        gravity = Gravity.CENTER
        textSize = 15f
        setTextColor(Color.WHITE)
        background = roundedRect(
            fill = Color.argb(36, 255, 255, 255),
            stroke = Color.argb(42, 255, 255, 255),
            radius = 12,
        )
        isClickable = true
        isFocusable = true
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun roundedRect(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }
}
