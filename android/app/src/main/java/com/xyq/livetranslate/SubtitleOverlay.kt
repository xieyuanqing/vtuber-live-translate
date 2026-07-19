package com.xyq.livetranslate

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 视频字幕悬浮层：默认是可拖动的小尺寸字幕面板；收起时贴在屏幕侧边成为小胶囊。
 * 收起仅改变悬浮层外观，不暂停录音、音频发送或翻译会话。
 */
class SubtitleOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: AccentFrameLayout? = null
    private var panel: LinearLayout? = null
    private var collapsedHandle: TextView? = null
    private var statusLabel: TextView? = null
    private var pauseButton: TextView? = null
    private var collapseButton: TextView? = null
    private var tvConfirmed: TextView? = null
    private var tvCurrent: TextView? = null
    private var dot: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var collapsed = false
    private var collapsedOnLeft = false
    private var appliedStyle = -1
    private var expandedWidth = 0
    private var expandedX = 0
    private var expandedY = 0
    private var latestConfirmed = ""
    private var latestCurrent = ""

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (root != null) return true
        val dm = context.resources.displayMetrics
        expandedWidth = SubtitleOverlayGeometry.expandedWidth(dm.widthPixels, density)

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
        val collapse = controlButton("⇥", "收起到屏幕侧边").apply {
            setOnClickListener { toggleCollapsed() }
        }
        headerRow.addView(pause, LinearLayout.LayoutParams(dp(36), dp(36)))
        headerRow.addView(
            collapse,
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

        val shell = AccentFrameLayout(context, density).apply {
            elevation = dp(8).toFloat()
        }
        val sideHandle = TextView(context).apply {
            text = "‹\n译"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
            setLineSpacing(0f, 0.9f)
            contentDescription = "展开悬浮字幕"
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleCollapsed() }
        }
        shell.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        shell.addView(
            sideHandle,
            FrameLayout.LayoutParams(dp(COLLAPSED_WIDTH_DP), dp(COLLAPSED_HEIGHT_DP)).apply {
                gravity = Gravity.CENTER
            },
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

        shell.setOnTouchListener(dragListener(shell, params))
        try {
            wm.addView(shell, params)
        } catch (_: SecurityException) {
            return false
        } catch (_: WindowManager.BadTokenException) {
            return false
        } catch (_: IllegalStateException) {
            return false
        }
        root = shell
        panel = container
        collapsedHandle = sideHandle
        statusLabel = stateText
        pauseButton = pause
        collapseButton = collapse
        tvConfirmed = confirmed
        tvCurrent = current
        dot = stateDot
        lp = params
        expandedX = params.x
        expandedY = params.y
        applyStyleNow()
        return true
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        panel = null
        collapsedHandle = null
        statusLabel = null
        pauseButton = null
        collapseButton = null
        tvConfirmed = null
        tvCurrent = null
        dot = null
        lp = null
        collapsed = false
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

    private fun toggleCollapsed() {
        val params = lp ?: return
        val dm = context.resources.displayMetrics
        if (!collapsed) {
            expandedX = params.x
            expandedY = params.y
            collapsedOnLeft = SubtitleOverlayGeometry.collapseToLeft(
                currentX = params.x,
                currentWidth = params.width,
                displayWidth = dm.widthPixels,
            )
            collapsed = true
        } else {
            collapsed = false
            params.x = expandedX
            params.y = expandedY
        }
        applyAppearance()
        renderLines()
    }

    private fun renderLines() {
        tvConfirmed?.apply {
            text = latestConfirmed
            visibility = if (latestConfirmed.isNotEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }
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
        tvCurrent?.textSize = font.toFloat()
        tvCurrent?.maxLines = SettingsStore.overlayMaxLines(context)
        tvConfirmed?.textSize = (font - 4).coerceAtLeast(11).toFloat()
        applyAppearance()
    }

    private fun applyAppearance() {
        val window = root ?: return
        val container = panel ?: return
        val handle = collapsedHandle ?: return
        val params = lp ?: return
        val dm = context.resources.displayMetrics
        if (collapsed) {
            container.visibility = View.GONE
            handle.visibility = View.VISIBLE
            handle.text = if (collapsedOnLeft) "译\n›" else "‹\n译"
            window.accentVisible = false
            window.background = roundedRect(
                fill = Color.argb(232, 0, 88, 188),
                stroke = Color.argb(80, 255, 255, 255),
                radius = 18,
            )
            params.width = dp(COLLAPSED_WIDTH_DP)
            params.height = dp(COLLAPSED_HEIGHT_DP)
            params.x = SubtitleOverlayGeometry.collapsedX(
                displayWidth = dm.widthPixels,
                collapsedWidth = params.width,
                onLeft = collapsedOnLeft,
            )
        } else {
            handle.visibility = View.GONE
            container.visibility = View.VISIBLE
            container.setPadding(dp(16), dp(12), dp(16), dp(14))
            window.accentVisible = true
            val opacity = SettingsStore.bgOpacityPct(context).coerceIn(20, 100)
            window.background = roundedRect(
                fill = Color.argb(255 * opacity / 100, 20, 29, 43),
                stroke = Color.argb(55, 255, 255, 255),
                radius = 22,
            )
            statusLabel?.visibility = View.VISIBLE
            pauseButton?.visibility = View.VISIBLE
            collapseButton?.apply {
                text = "⇥"
                setTextColor(Color.WHITE)
                contentDescription = "收起到屏幕侧边"
            }
            tvConfirmed?.apply {
                setTextColor(Color.parseColor("#B8C5D6"))
                setPadding(0, dp(8), 0, 0)
            }
            tvCurrent?.apply {
                setTextColor(Color.WHITE)
                setShadowLayer(3f, 0f, 1f, Color.argb(120, 0, 0, 0))
                setPadding(0, dp(6), 0, 0)
            }
            params.width = expandedWidth
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.x = expandedX
            params.y = expandedY
        }
        runCatching { wm.updateViewLayout(window, params) }
        window.post { clampToDisplay(window, params) }
    }

    private fun clampToDisplay(window: View, params: WindowManager.LayoutParams) {
        if (root !== window || lp !== params) return
        val dm = context.resources.displayMetrics
        val width = params.width.takeIf { it > 0 }
            ?: window.width.takeIf { it > 0 }
            ?: dp(72)
        val height = params.height.takeIf { it > 0 }
            ?: window.height.takeIf { it > 0 }
            ?: dp(72)
        if (collapsed) {
            params.x = SubtitleOverlayGeometry.collapsedX(
                displayWidth = dm.widthPixels,
                collapsedWidth = width,
                onLeft = collapsedOnLeft,
            )
        } else {
            params.x = params.x.coerceIn(0, (dm.widthPixels - width).coerceAtLeast(0))
            expandedX = params.x
        }
        params.y = SubtitleOverlayGeometry.clampedY(
            displayHeight = dm.heightPixels,
            windowHeight = height,
            requestedY = params.y,
        )
        if (!collapsed) expandedY = params.y
        runCatching { wm.updateViewLayout(window, params) }
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
                        if (collapsed) {
                            // 收起态只允许纵向拖动，x 保持贴边。
                            params.y = startY + dy.toInt()
                        } else {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                        }
                        runCatching { wm.updateViewLayout(container, params) }
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> if (moved) clampToDisplay(container, params)
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

    private class AccentFrameLayout(
        context: Context,
        density: Float,
    ) : FrameLayout(context) {
        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.primary_container)
        }
        private val accentWidth = (4 * density).roundToInt().toFloat()
        private val accentInset = (12 * density).roundToInt().toFloat()
        private val accentRadius = (2 * density).roundToInt().toFloat()

        var accentVisible: Boolean = true
            set(value) {
                field = value
                invalidate()
            }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (!accentVisible || height <= accentInset * 2) return
            canvas.drawRoundRect(
                0f,
                accentInset,
                accentWidth,
                height - accentInset,
                accentRadius,
                accentRadius,
                accentPaint,
            )
        }
    }

    private companion object {
        const val COLLAPSED_WIDTH_DP = 44
        const val COLLAPSED_HEIGHT_DP = 60
    }
}

internal object SubtitleOverlayGeometry {
    fun expandedWidth(displayWidth: Int, density: Float): Int {
        val margin = (24 * density).roundToInt()
        val available = (displayWidth - margin).coerceAtLeast(1)
        val preferred = (displayWidth * 0.88f).roundToInt()
        val cap = (360 * density).roundToInt()
        val minimum = minOf((240 * density).roundToInt(), available)
        return minOf(preferred, cap, available).coerceAtLeast(minimum)
    }

    fun collapseToLeft(currentX: Int, currentWidth: Int, displayWidth: Int): Boolean =
        currentX + currentWidth / 2 <= displayWidth / 2

    fun collapsedX(displayWidth: Int, collapsedWidth: Int, onLeft: Boolean): Int =
        if (onLeft) 0 else (displayWidth - collapsedWidth).coerceAtLeast(0)

    fun clampedY(displayHeight: Int, windowHeight: Int, requestedY: Int): Int =
        requestedY.coerceIn(0, (displayHeight - windowHeight).coerceAtLeast(0))
}
