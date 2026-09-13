package com.xyq.livetranslate

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 视频字幕悬浮层：默认是可拖动的小尺寸字幕面板；收起时贴在屏幕侧边成为小胶囊。
 * 收起仅改变悬浮层外观，不暂停录音、音频发送或翻译会话。
 *
 * 控制条（状态点 + 暂停/打开/收起三个小图标）由用户手动开关：点面板空白处收起，
 * 再点一下复原。不做定时自动隐藏——字幕是持续刷新的，任何「无操作计时」都会被
 * 每条新字幕重置，结果就是正在看的时候永远不收。
 */
class SubtitleOverlay(
    private val context: Context,
    private val sessionMode: String,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: AccentFrameLayout? = null
    private var panel: LinearLayout? = null
    private var headerRow: LinearLayout? = null
    private var collapsedHandle: TextView? = null
    private var pauseButton: ImageView? = null
    private var openButton: ImageView? = null
    private var collapseButton: ImageView? = null
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
    private var controlsVisible = false

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (root != null) return true
        val dm = context.resources.displayMetrics
        expandedWidth = SubtitleOverlayGeometry.expandedWidth(dm.widthPixels, density)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            elevation = dp(8).toFloat()
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val stateDot = View(context).apply {
            background = circle(Color.parseColor("#34C759"))
        }
        header.addView(
            stateDot,
            LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) },
        )
        // 状态只由左侧圆点表达；暂停由暂停图标翻成播放图标表达，不再占一行文字。
        header.addView(
            View(context),
            LinearLayout.LayoutParams(0, dp(1), 1f),
        )
        val pause = controlButton(R.drawable.ic_overlay_pause_24, context.getString(R.string.rt_overlay_action_pause)).apply {
            setOnClickListener { togglePause() }
        }
        val open = controlButton(R.drawable.ic_overlay_open_24, context.getString(R.string.rt_overlay_action_open)).apply {
            setOnClickListener { openMainApp() }
        }
        val collapse = controlButton(
            R.drawable.ic_overlay_collapse_24,
            context.getString(R.string.rt_overlay_action_collapse),
        ).apply {
            setOnClickListener { toggleCollapsed() }
        }
        header.addView(pause, LinearLayout.LayoutParams(dp(CONTROL_SIZE_DP), dp(CONTROL_SIZE_DP)))
        header.addView(
            open,
            LinearLayout.LayoutParams(dp(CONTROL_SIZE_DP), dp(CONTROL_SIZE_DP)).apply {
                leftMargin = dp(4)
            },
        )
        header.addView(
            collapse,
            LinearLayout.LayoutParams(dp(CONTROL_SIZE_DP), dp(CONTROL_SIZE_DP)).apply {
                leftMargin = dp(4)
            },
        )
        container.addView(
            header,
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
            text = context.getString(R.string.rt_overlay_waiting_subtitles)
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
        // 收起后是一根贴边小蓝条，不放文字。胶囊本体铺满整个触摸宽度（含半透明晕），
        // OnClickListener 只留给无障碍的 performClick，真实触摸走下面挂的 dragListener
        // （否则胶囊会吃掉触摸，纵向拖不动）。
        val sideHandle = TextView(context).apply {
            contentDescription = context.getString(R.string.rt_overlay_action_expand)
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
            FrameLayout.LayoutParams(dp(COLLAPSED_TOUCH_WIDTH_DP), dp(COLLAPSED_HEIGHT_DP)).apply {
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
        sideHandle.setOnTouchListener(dragListener(shell, params))
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
        headerRow = header
        collapsedHandle = sideHandle
        pauseButton = pause
        openButton = open
        collapseButton = collapse
        tvConfirmed = confirmed
        tvCurrent = current
        dot = stateDot
        lp = params
        expandedX = params.x
        expandedY = params.y
        applyStyleNow()
        setControlsVisible(true)
        return true
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        panel = null
        headerRow = null
        collapsedHandle = null
        pauseButton = null
        openButton = null
        collapseButton = null
        tvConfirmed = null
        tvCurrent = null
        dot = null
        lp = null
        collapsed = false
        controlsVisible = false
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
        pauseButton?.setImageResource(
            if (isPaused) R.drawable.ic_overlay_play_24 else R.drawable.ic_overlay_pause_24,
        )
        pauseButton?.contentDescription = if (isPaused) {
            context.getString(R.string.rt_overlay_action_resume)
        } else {
            context.getString(R.string.rt_overlay_action_pause)
        }
    }

    /** 点面板空白处切换控制条；收起态则整体展开。 */
    private fun onOverlayTap() {
        if (collapsed) toggleCollapsed() else setControlsVisible(!controlsVisible)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val header = headerRow ?: return
        header.visibility = if (visible && !collapsed) View.VISIBLE else View.GONE
    }

    private fun togglePause() {
        context.startService(
            Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_TOGGLE_PAUSE),
        )
        pauseButton?.postDelayed({ maybeReapplyStyle() }, 80L)
    }

    private fun openMainApp() {
        val intent = Intent(context, MainActivity::class.java).apply {
            // SINGLE_TOP 让已在前台栈顶的实例经 onNewIntent 收到落点并切页，否则意图会被丢弃。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_SESSION_TAB, sessionMode)
        }
        context.startActivity(intent)
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
        if (!collapsed) setControlsVisible(true)
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
                else -> context.getString(R.string.rt_overlay_waiting_subtitles)
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
            window.accentVisible = false
            window.background = collapsedBar(onLeft = collapsedOnLeft)
            params.width = dp(COLLAPSED_TOUCH_WIDTH_DP)
            params.height = dp(COLLAPSED_HEIGHT_DP)
            params.x = SubtitleOverlayGeometry.collapsedX(
                displayWidth = dm.widthPixels,
                collapsedWidth = params.width,
                onLeft = collapsedOnLeft,
            )
        } else {
            handle.visibility = View.GONE
            container.visibility = View.VISIBLE
            container.setPadding(dp(12), dp(8), dp(12), dp(10))
            window.accentVisible = true
            val opacity = SettingsStore.bgOpacityPct(context).coerceIn(20, 100)
            window.background = roundedRect(
                fill = Color.argb(255 * opacity / 100, 20, 29, 43),
                stroke = Color.argb(55, 255, 255, 255),
                radius = 22,
            )
            headerRow?.visibility = if (controlsVisible) View.VISIBLE else View.GONE
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
                MotionEvent.ACTION_UP -> if (moved) clampToDisplay(container, params) else onOverlayTap()
                MotionEvent.ACTION_CANCEL -> if (moved) clampToDisplay(container, params)
            }
            return true
        }
    }

    private fun controlButton(iconRes: Int, description: String) = ImageView(context).apply {
        setImageResource(iconRes)
        contentDescription = description
        scaleType = ImageView.ScaleType.FIT_CENTER
        val inset = dp(5)
        setPadding(inset, inset, inset, inset)
        background = roundedRect(
            fill = Color.argb(28, 255, 255, 255),
            stroke = Color.TRANSPARENT,
            radius = 8,
        )
        isClickable = true
        isFocusable = true
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /**
     * 收起态背景：贴边一根实心蓝条 + 朝屏幕内侧渐隐的半透明晕。
     * 晕只为把触摸区做大，视觉上仍然只看得见那根细条。
     */
    private fun collapsedBar(onLeft: Boolean): Drawable {
        val solid = Color.argb(232, 0, 88, 188)
        val fadeColors = if (onLeft) {
            intArrayOf(Color.argb(64, 0, 88, 188), Color.TRANSPARENT)
        } else {
            intArrayOf(Color.TRANSPARENT, Color.argb(64, 0, 88, 188))
        }
        val halo = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, fadeColors).apply {
            cornerRadius = dp(COLLAPSED_BAR_WIDTH_DP).toFloat() / 2
        }
        val bar = GradientDrawable().apply {
            cornerRadius = dp(COLLAPSED_BAR_WIDTH_DP).toFloat() / 2
            setColor(solid)
        }
        val inward = dp(COLLAPSED_TOUCH_WIDTH_DP - COLLAPSED_BAR_WIDTH_DP)
        return LayerDrawable(arrayOf(halo, bar)).apply {
            // 蓝条永远压在贴屏幕边的那一侧，多出来的宽度全部让给内侧的晕。
            setLayerInset(1, if (onLeft) 0 else inward, 0, if (onLeft) inward else 0, 0)
        }
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
        /** 收起后看得见的那根贴边小蓝条有多宽。 */
        const val COLLAPSED_BAR_WIDTH_DP = 10

        /**
         * 收起态窗口的实际宽度。比蓝条宽出来的部分朝屏幕内侧、画成渐隐的半透明晕：
         * 看着还是一根细条，但手指有得点。代价是这段宽度会挡住底下 App 的触摸。
         */
        const val COLLAPSED_TOUCH_WIDTH_DP = 28
        const val COLLAPSED_HEIGHT_DP = 48

        /**
         * 头部三个控制按钮的触摸区。悬浮窗是盖在别人画面上的，按钮越大越碍事：
         * 28dp 是「看得见、点得中、不抢画面」的折中，别再往 44dp 放大。
         */
        const val CONTROL_SIZE_DP = 28
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
