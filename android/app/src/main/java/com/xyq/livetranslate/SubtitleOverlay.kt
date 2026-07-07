package com.xyq.livetranslate

import android.annotation.SuppressLint
import android.content.Context
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
 * 悬浮字幕窗。
 * 结构：圆角黑底 [ 状态点 | 确认行(暗一档、小一号) / 当前行(亮、带阴影) ]
 * 交互：拖动换位置；点一下收起成小圆点，再点展开。
 * 字号/背景不透明度/行数从 SettingsStore 读取，styleVersion 变化时自动重新应用。
 * 所有方法必须在主线程调用。
 */
class SubtitleOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var textColumn: LinearLayout? = null
    private var tvConfirmed: TextView? = null
    private var tvCurrent: TextView? = null
    private var dot: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var collapsed = false
    private var appliedStyle = -1
    private var expandedWidth = 0

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        val dm = context.resources.displayMetrics
        // 取宽高中较小者做窗宽基准：横竖屏都不会超出屏幕
        expandedWidth = minOf(dm.widthPixels, dm.heightPixels) - dp(24)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(170, 0, 0, 0))
                setStroke(dp(1), Color.argb(50, 255, 255, 255))
            }
        }

        val d = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFC107"))
            }
        }
        container.addView(d, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) })

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val confirmed = TextView(context).apply {
            setTextColor(Color.argb(175, 255, 255, 255))
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        column.addView(
            confirmed,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val current = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(4f, 0f, 1f, Color.argb(140, 0, 0, 0))
            text = "等待字幕…"
        }
        column.addView(
            current,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(
            column,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val params = WindowManager.LayoutParams(
            expandedWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }

        container.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = params.x; startY = params.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moved && (dx * dx + dy * dy) > dp(6) * dp(6)) moved = true
                        if (moved) {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                            runCatching { wm.updateViewLayout(container, params) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) toggleCollapse()
                    }
                }
                return true
            }
        })

        wm.addView(container, params)
        root = container
        textColumn = column
        tvConfirmed = confirmed
        tvCurrent = current
        dot = d
        lp = params
        applyStyleNow()
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        textColumn = null
        tvConfirmed = null
        tvCurrent = null
        dot = null
        lp = null
        collapsed = false
    }

    /** 点一下：字幕条 ⇆ 小圆点。 */
    private fun toggleCollapse() {
        val r = root ?: return
        val p = lp ?: return
        collapsed = !collapsed
        val dotLp = dot?.layoutParams as? LinearLayout.LayoutParams
        if (collapsed) {
            textColumn?.visibility = View.GONE
            dotLp?.apply { width = dp(18); height = dp(18); rightMargin = 0 }
            r.setPadding(dp(10), dp(10), dp(10), dp(10))
            p.width = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            textColumn?.visibility = View.VISIBLE
            dotLp?.apply { width = dp(10); height = dp(10); rightMargin = dp(10) }
            r.setPadding(dp(14), dp(10), dp(14), dp(10))
            p.width = expandedWidth
        }
        dot?.requestLayout()
        runCatching { wm.updateViewLayout(r, p) }
    }

    /** 稳定器输出：上一句确认行 + 正在生成的当前行。 */
    fun setLines(confirmed: String, current: String) {
        tvConfirmed?.apply {
            text = confirmed
            visibility = if (confirmed.isEmpty()) View.GONE else View.VISIBLE
        }
        tvCurrent?.apply {
            when {
                current.isNotEmpty() -> {
                    text = current
                    visibility = View.VISIBLE
                }
                confirmed.isEmpty() -> {
                    text = "等待字幕…"
                    visibility = View.VISIBLE
                }
                else -> visibility = View.GONE
            }
        }
    }

    fun setStateColor(colorHex: String) {
        (dot?.background as? GradientDrawable)?.setColor(Color.parseColor(colorHex))
    }

    /** 设置页改了样式（styleVersion 变化）就重新应用，无需重启会话。 */
    fun maybeReapplyStyle() {
        if (appliedStyle == StatusBus.styleVersion.get()) return
        applyStyleNow()
    }

    private fun applyStyleNow() {
        appliedStyle = StatusBus.styleVersion.get()
        val font = SettingsStore.fontSizeSp(context)
        tvCurrent?.textSize = font.toFloat()
        tvCurrent?.maxLines = SettingsStore.overlayMaxLines(context)
        tvConfirmed?.textSize = (font - 3).coerceAtLeast(11).toFloat()
        val alpha = 255 * SettingsStore.bgOpacityPct(context).coerceIn(10, 100) / 100
        (root?.background as? GradientDrawable)?.setColor(Color.argb(alpha, 0, 0, 0))
    }
}
