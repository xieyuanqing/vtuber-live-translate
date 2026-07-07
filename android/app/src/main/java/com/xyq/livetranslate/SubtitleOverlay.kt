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
 * 悬浮字幕窗：半透明黑底白字，可拖动；点一下整体收起成小圆点，再点展开。
 * 小圆点颜色 = 连接状态（绿=已连接 黄=连接中/重连 红=出错）。
 * 字号/背景不透明度/行数从 SettingsStore 读取，styleVersion 变化时自动重新应用。
 * 所有方法必须在主线程调用。
 */
class SubtitleOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var tvText: TextView? = null
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
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(170, 0, 0, 0))
            }
        }

        val d = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFC107"))
            }
        }
        container.addView(d, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })

        val tv = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            text = "等待字幕…"
        }
        container.addView(
            tv,
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
        tvText = tv
        dot = d
        lp = params
        applyStyleNow()
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        tvText = null
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
            tvText?.visibility = View.GONE
            dotLp?.apply { width = dp(18); height = dp(18); rightMargin = 0 }
            r.setPadding(dp(10), dp(10), dp(10), dp(10))
            p.width = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            tvText?.visibility = View.VISIBLE
            dotLp?.apply { width = dp(10); height = dp(10); rightMargin = dp(8) }
            r.setPadding(dp(12), dp(8), dp(12), dp(8))
            p.width = expandedWidth
        }
        dot?.requestLayout()
        runCatching { wm.updateViewLayout(r, p) }
    }

    /** 稳定器输出：上一句确认行 + 正在生成的当前行。 */
    fun setLines(confirmed: String, current: String) {
        val t = buildString {
            if (confirmed.isNotEmpty()) append(confirmed)
            if (current.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(current)
            }
        }
        tvText?.text = t.ifEmpty { "等待字幕…" }
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
        val tv = tvText ?: return
        tv.textSize = SettingsStore.fontSizeSp(context).toFloat()
        tv.maxLines = SettingsStore.overlayMaxLines(context)
        val alpha = 255 * SettingsStore.bgOpacityPct(context).coerceIn(10, 100) / 100
        (root?.background as? GradientDrawable)?.setColor(Color.argb(alpha, 0, 0, 0))
    }
}
