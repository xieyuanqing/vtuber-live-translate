package com.xyq.livetranslate.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.xyq.livetranslate.R

/** 壳层 View 的唯一绑定真源。页面 controller 只从这里取得自己的 root 或子控件。 */
internal data class MainNavigatorViews(
    val toolbar: MaterialToolbar,
    val bottomNav: BottomNavigationView,
    val pageContainer: View,
    val pageInterp: View,
    val pageVideo: View,
    val pageHistory: View,
    val pageSettings: View,
    val pageHistoryDetail: View,
    val pageSettingsTranslate: View,
    val pageSettingsSubtitle: View,
    val pageSettingsProfileAi: View,
    val pageSettingsDiagnostics: View,
    val pageSettingsAbout: View,
    val pageSceneLibrary: View,
) {
    val mainPages: Map<Int, View> = linkedMapOf(
        R.id.nav_interp to pageInterp,
        R.id.nav_video to pageVideo,
        R.id.nav_history to pageHistory,
        R.id.nav_settings to pageSettings,
    )
    val subPages: Map<Int, View> = linkedMapOf(
        R.id.pageHistoryDetail to pageHistoryDetail,
        R.id.pageSettingsTranslate to pageSettingsTranslate,
        R.id.pageSettingsSubtitle to pageSettingsSubtitle,
        R.id.pageSettingsProfileAi to pageSettingsProfileAi,
        R.id.pageSettingsDiagnostics to pageSettingsDiagnostics,
        R.id.pageSettingsAbout to pageSettingsAbout,
        R.id.pageSceneLibrary to pageSceneLibrary,
    )

    companion object {
        fun bind(root: View): MainNavigatorViews = MainNavigatorViews(
            toolbar = root.findViewById(R.id.toolbar),
            bottomNav = root.findViewById(R.id.bottomNav),
            pageContainer = root.findViewById(R.id.pageContainer),
            pageInterp = root.findViewById(R.id.pageInterp),
            pageVideo = root.findViewById(R.id.pageVideo),
            pageHistory = root.findViewById(R.id.pageHistory),
            pageSettings = root.findViewById(R.id.pageSettings),
            pageHistoryDetail = root.findViewById(R.id.pageHistoryDetail),
            pageSettingsTranslate = root.findViewById(R.id.pageSettingsTranslate),
            pageSettingsSubtitle = root.findViewById(R.id.pageSettingsSubtitle),
            pageSettingsProfileAi = root.findViewById(R.id.pageSettingsProfileAi),
            pageSettingsDiagnostics = root.findViewById(R.id.pageSettingsDiagnostics),
            pageSettingsAbout = root.findViewById(R.id.pageSettingsAbout),
            pageSceneLibrary = root.findViewById(R.id.pageSceneLibrary),
        )
    }
}

/**
 * 负责主页面、设置式子页、toolbar、bottom navigation、insets 与导航 saved state。
 * 不持有 Activity；跨页面行为只通过窄 hook 接入。
 */
internal class MainNavigator(
    private val views: MainNavigatorViews,
    private val onMainPageShown: (Int) -> Unit = {},
    private val onSubPageShown: (Int) -> Unit = {},
    private val beforeSubPageClosed: (Int) -> Unit = {},
) {
    companion object {
        const val STATE_MAIN_TAB = "main_tab"
        const val STATE_SETTINGS_SUB = "settings_sub"
        const val STATE_SETTINGS_RETURN_TAB = "settings_return_tab"

        val MAIN_PAGE_IDS: Set<Int> = setOf(
            R.id.nav_interp,
            R.id.nav_video,
            R.id.nav_history,
            R.id.nav_settings,
        )
        val SUB_PAGE_IDS: Set<Int> = setOf(
            R.id.pageHistoryDetail,
            R.id.pageSettingsTranslate,
            R.id.pageSettingsSubtitle,
            R.id.pageSettingsProfileAi,
            R.id.pageSettingsDiagnostics,
            R.id.pageSettingsAbout,
            R.id.pageSceneLibrary,
        )
        private val RESTORABLE_SUB_PAGE_IDS = SUB_PAGE_IDS - R.id.pageHistoryDetail
        private val SUB_PAGE_TITLES = mapOf(
            R.id.pageHistoryDetail to "历史详情",
            R.id.pageSettingsTranslate to "翻译服务",
            R.id.pageSettingsSubtitle to "字幕与悬浮窗",
            R.id.pageSettingsProfileAi to "内容分析 AI",
            R.id.pageSettingsDiagnostics to "诊断",
            R.id.pageSettingsAbout to "关于",
            R.id.pageSceneLibrary to "场景库",
        )
    }

    private var currentMainTabId = R.id.nav_interp
    private var settingsSubId = 0
    private var settingsReturnTabId = R.id.nav_settings
    private var suppressBottomCallback = false
    private var setupComplete = false

    /** listener 和恢复均延迟到所有 controller 已完成构造与接线后统一执行。 */
    fun setup(savedState: Bundle?) {
        check(!setupComplete) { "MainNavigator.setup 只能调用一次" }
        setupComplete = true
        applyWindowInsets()
        views.toolbar.setNavigationOnClickListener { closeSub() }
        views.toolbar.navigationIcon = null
        views.bottomNav.setOnItemSelectedListener { item ->
            if (suppressBottomCallback) true else showMainFromBottom(item.itemId)
        }

        val restoredMain = savedState?.getInt(STATE_MAIN_TAB)
            ?.takeIf { it in MAIN_PAGE_IDS }
            ?: R.id.nav_interp
        val restoredReturn = savedState?.getInt(STATE_SETTINGS_RETURN_TAB)
            ?.takeIf { it in MAIN_PAGE_IDS }
            ?: R.id.nav_settings
        val restoredSub = savedState?.getInt(STATE_SETTINGS_SUB)
            ?.takeIf { it in RESTORABLE_SUB_PAGE_IDS }
            ?: 0

        if (!showMain(restoredMain) && restoredMain != R.id.nav_interp) {
            showMain(R.id.nav_interp)
        }
        if (restoredSub != 0) openSub(restoredSub, restoredReturn)
    }

    fun showMain(pageId: Int): Boolean {
        if (pageId !in MAIN_PAGE_IDS) return false
        closeCurrentSubForNavigation()
        syncBottomSelection(pageId)
        renderMain(pageId)
        return true
    }

    fun openSub(pageId: Int, returnTabId: Int = R.id.nav_settings): Boolean {
        if (pageId !in SUB_PAGE_IDS || returnTabId !in MAIN_PAGE_IDS) return false
        if (settingsSubId != 0 && settingsSubId != pageId) {
            beforeSubPageClosed(settingsSubId)
        }
        settingsSubId = pageId
        settingsReturnTabId = returnTabId
        views.mainPages.values.forEach { it.visibility = View.GONE }
        views.subPages.forEach { (id, page) ->
            page.visibility = if (id == pageId) View.VISIBLE else View.GONE
        }
        views.toolbar.visibility = View.VISIBLE
        views.toolbar.title = SUB_PAGE_TITLES.getValue(pageId)
        views.toolbar.logo = null
        views.toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24)
        applyStatusBarCompensation(toolbarVisible = true)
        views.bottomNav.visibility = View.GONE
        onSubPageShown(pageId)
        return true
    }

    fun closeSub(): Boolean {
        if (settingsSubId == 0) return false
        val returnTabId = settingsReturnTabId
        beforeSubPageClosed(settingsSubId)
        settingsSubId = 0
        settingsReturnTabId = R.id.nav_settings
        syncBottomSelection(returnTabId)
        renderMain(returnTabId)
        return true
    }

    fun handleBack(): Boolean = closeSub()

    fun saveState(outState: Bundle) {
        outState.putInt(STATE_MAIN_TAB, currentMainTabId)
        outState.putInt(STATE_SETTINGS_SUB, settingsSubId)
        outState.putInt(STATE_SETTINGS_RETURN_TAB, settingsReturnTabId)
    }

    private fun showMainFromBottom(pageId: Int): Boolean {
        if (pageId !in MAIN_PAGE_IDS) return false
        closeCurrentSubForNavigation()
        renderMain(pageId)
        return true
    }

    private fun closeCurrentSubForNavigation() {
        if (settingsSubId != 0) beforeSubPageClosed(settingsSubId)
        settingsSubId = 0
        settingsReturnTabId = R.id.nav_settings
    }

    private fun renderMain(pageId: Int) {
        views.subPages.values.forEach { it.visibility = View.GONE }
        views.mainPages.forEach { (id, page) ->
            page.visibility = if (id == pageId) View.VISIBLE else View.GONE
        }
        // 主 Tab 只保留页面大标题，避免 Toolbar + PageTitle 双头部。
        views.toolbar.visibility = View.GONE
        views.toolbar.title = "流译"
        views.toolbar.setLogo(R.drawable.ic_brand_translate_24)
        views.toolbar.navigationIcon = null
        applyStatusBarCompensation(toolbarVisible = false)
        views.bottomNav.visibility = View.VISIBLE
        currentMainTabId = pageId
        onMainPageShown(pageId)
    }

    private fun syncBottomSelection(pageId: Int) {
        if (views.bottomNav.selectedItemId == pageId) return
        suppressBottomCallback = true
        try {
            views.bottomNav.selectedItemId = pageId
        } finally {
            suppressBottomCallback = false
        }
    }

    private var statusBarTop = 0

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(views.toolbar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarTop = bars.top
            view.updatePadding(top = bars.top)
            applyStatusBarCompensation(toolbarVisible = views.toolbar.visibility == View.VISIBLE)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(views.bottomNav) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            insets
        }
        // 主动请求一次，避免首帧主 Tab 在 toolbar 隐藏后顶进状态栏。
        ViewCompat.requestApplyInsets(views.toolbar)
    }

    /** 主 Tab 隐藏 Toolbar 时，把状态栏高度补偿到 pageContainer。 */
    private fun applyStatusBarCompensation(toolbarVisible: Boolean) {
        views.pageContainer.updatePadding(top = if (toolbarVisible) 0 else statusBarTop)
    }
}

