# 最终 UI 近 1:1 复刻计划（功能定型版）

> 目标分支：`feat/final-ui`
> 当前基线：v2.0.6 / versionCode 27
> 技术栈：原生 Android，Kotlin + XML Views + Material 3
> 本文是本轮 UI 改造的实施说明；旧的分期、术语库和临时上下文位置方案全部作废。

## 1. 目标

在**不改变翻译功能、数据边界和操作结果**的前提下，让 App 尽量接近 Stitch 导出的 **Luminous Blue Utility** 参考稿。

这次不再只调颜色和间距，而是同时对齐：

1. 设计 token：颜色、字号、圆角、留白、阴影、按钮和输入框。
2. 页面结构：卡片顺序、信息层级、空闲态与运行态。
3. 交互形态：方案弹层、历史详情子页、设置分组、底部固定操作。

本轮最重要的变化是：**同传和视频开始后，隐藏已经无用的配置内容，切换到专注字幕的运行界面；停止后再回到空闲配置界面。**

参考稿没有覆盖到当前真实功能时（方案、本场上下文、视频链接解析等），保留功能，并用同一套 Luminous Blue 视觉语言补齐，不能删功能来追求截图相似。

---

## 2. 参考稿与屏幕映射

### 2.1 原始文件

- 完整基线：`/root/.hermes/cache/documents/doc_0703f1f82c56_ui.zip`
- 较早蓝白参考：`/root/.hermes/cache/documents/doc_10f631ead7d3_stitch_translation_app_ui (1).zip`
- 后补页面：`/root/.hermes/cache/documents/doc_6d2f3ecbd9ef_Download.zip`
- 已解压基线：`/tmp/ui_base/`
- 设计规范：`/tmp/ui_base/luminous_blue_utility.md`
- 已解压后补页面：`/tmp/stitch_ref/`

若 `/tmp/ui_base` 不存在，重新解压 `ui.zip`。**实际 HTML 的 CSS/Tailwind 值优先于规范 prose。** 已确认实际主色为 `#0058BC`，不是 prose 中提到的 `#007AFF`。

### 2.2 逐屏映射

- 同传空闲：`/tmp/ui_base/html (16).html`
- 同传运行：`/tmp/ui_base/html (15).html`
- 同传方案弹层：`/tmp/ui_base/html (10).html`
- 视频空闲：`/tmp/ui_base/html (14).html`
- 视频运行：`/tmp/ui_base/html (13).html`
- 视频方案弹层：`/tmp/ui_base/html (9).html`
- 悬浮字幕普通/紧凑态：`/tmp/ui_base/html (6).html`
- 方案库：`/tmp/ui_base/html (7).html`
- 历史列表：`/tmp/ui_base/html (12).html`
- 历史详情：`/tmp/ui_base/html (8).html`
- 设置首页：`/tmp/ui_base/html (11).html`
- AI 内容分析：`/tmp/ui_base/ai.html`
- 方案配置补充：`/tmp/stitch_ref/html (18).html`

### 2.3 不照抄的内容

以下属于 Web 效果、演示文案或不存在的数据：

- Web 字体、hover、CSS ripple/scale/mask、浏览器 backdrop blur。
- 术语库、词条数、“最后更新 2 天前”、“专业领域模型”等字段。
- OpenAI/Azure/Anthropic 演示提供商列表。
- 示例计时、字幕、会议名称、语言标签和 API Key。
- overlay 的 fullscreen 按钮。
- 账号、订阅、同步、权限管理等当前产品不存在的入口。

---

## 3. 不得破坏的边界

### 3.1 产品模型

- 同传和视频是独立模式，分别保存语言、场景、方案提示词和方案库。
- `TranslationPlan` 是唯一长期配置：名称、场景、自定义场景、方案提示词。
- 本场临时上下文只在同传/视频主页编辑，不写入方案。
- 视频页保留 YouTube 链接 + AI 解析；同传页保留 AI 整理本场背景。
- 术语库不恢复入口，也不重新参与提示词。
- 快捷场景只更新当前 mode 的 draft，不覆盖方案库里的 saved plan。

### 3.2 核心代码

不要为了 UI 改造重写以下逻辑：

- `CaptureService.kt` 的采集、前台服务和会话快照
- `GeminiLiveClient.kt` 的 setup JSON、轮换和重连
- `PcmProcessor.kt`
- `SubtitleStabilizer.kt` 的稳定策略
- `PromptBuilder.kt` 的提示词语义
- MediaProjection、麦克风和权限流程
- `HistoryStore` 的结构化 JSON 格式

可以修改 XML、资源、`MainActivity.kt` 的页面绑定和状态渲染、`SubtitleOverlay.kt` 的样式，以及为 UI 新增 item layout、纯展示 helper 和 Robolectric 测试。

**当前 Kotlin 已绑定的 ID 必须保留。** 可以增加新容器和新控件，但不要先删旧 ID，再靠编译错误补回。

### 3.3 Git 与签名

- 不推送远端，除非用户明确要求。
- 不删除或替换 `android/app/debug.keystore`。
- 每完成一个大页面切片就构建，避免最后集中处理大量 AAPT/Kotlin 错误。
- 最终统一更新版本号和 `docs/04-dev-log.md`。

---

## 4. 全局设计系统

涉及：

- `android/app/src/main/res/values/colors.xml`
- `android/app/src/main/res/values/dimens.xml`
- `android/app/src/main/res/values/styles.xml`
- `android/app/src/main/res/color/`
- `android/app/src/main/res/drawable/`

### 4.1 颜色

统一使用 token，不在布局里新增硬编码颜色：

- Primary：`#0058BC`（`brand`）
- Primary container：`#0070EB`
- Primary fixed：`#D8E2FF`
- Primary fixed dim：`#ADC6FF`
- Background：`#FCF8FB`
- Card：`#FFFFFF`
- Surface low：`#F6F3F5`
- Surface container：`#F0EDEF`
- Surface high：`#EAE7EA`
- Primary text：`#1B1B1D`
- Secondary text：`#414755`
- Muted：`#717786`
- Outline variant：`#C1C6D7`
- Error：`#BA1A1A`
- Success：`#34C759`；状态点使用该色，不再用字符 `○/●` 模拟。

### 4.2 尺寸

- 页面水平边距：16dp
- 逻辑区块间距：24dp
- 卡片间距：12dp
- 卡片内边距：常规 16dp，主卡可用 20/24dp
- 最小点击范围：48dp
- 主卡圆角：24dp
- 方案库列表卡圆角：16dp
- Sheet 顶部圆角：28dp
- 输入框和小按钮：12dp
- pill/chip：全圆角
- 主按钮高度：56dp
- Sheet footer 按钮：48dp

### 4.3 字体

Android 使用系统字体，不引入网络字体：

- 页面标题：24sp / bold
- Sheet 标题：22sp / semibold
- 卡片标题：16sp / semibold
- 运行态主译文：22–24sp / semibold
- 正文：16sp
- 原文/次级文本：14–16sp
- Label：14sp / semibold
- Metadata：12sp

### 4.4 通用组件

形成可复用样式，不要每页复制一套：

- Luminous 白卡 / outlined 卡
- 灰底 filled 输入框：`surface_low`、12dp 圆角，focus 时 primary 描边
- primary / secondary / danger 按钮
- 状态 pill（独立圆点 + 文本）
- 语言方向胶囊
- scene chip 的选中/未选中态
- 设置分组卡和设置行
- 历史模式徽章

密钥输入框必须默认遮罩，并提供眼睛切换。

---

## 5. 全局壳层

涉及：

- `android/app/src/main/res/layout/activity_main.xml`
- `android/app/src/main/res/drawable/bg_bottom_navigation.xml`
- bottom nav selector / menu icon

要求：

1. 保留 4 个 Tab：同传 / 视频 / 历史 / 设置。
2. 底栏继续使用悬浮胶囊：左右 16dp、底部 16dp、64dp 左右高度、白色/半透明白底、轻阴影。
3. 选中项只使用主蓝图标和文字，不加过重色块。
4. 页面滚动内容必须为底栏留足 padding，不能被遮挡。
5. 设置子页和历史详情页隐藏底栏，Toolbar 显示返回箭头；返回后恢复正确来源页。
6. Toolbar 保留“流译”和品牌图标，不照抄参考稿不一致的英文品牌名。

---

## 6. 同传页

涉及：

- `android/app/src/main/res/layout/page_interpretation.xml`
- `android/app/src/main/java/com/xyq/livetranslate/MainActivity.kt`
- 新增必要 drawable/vector

### 6.1 状态拆分

在同一个 `page_interpretation.xml` 内建立两个互斥容器：

- 空闲态：`interpIdleContent`
- 运行态：`interpRunningContent`

`MainActivity.renderStatus()` 根据以下条件切换：

- `serviceRunning && captureMode == MODE_MIC`：显示运行态。
- 其他情况：显示空闲态。
- 如果视频正在运行，同传页仍显示受控提示，不误显示同传运行态。

### 6.2 空闲态

结构从上到下：

1. 页面标题“同传”。
2. 语言方向胶囊，保留 `acInterpSourceLang`、`acInterpTargetLang`。
3. 大圆麦克风按钮和状态 pill。
4. 当前方案卡。
5. 场景快捷宫格。
6. 本场临时上下文卡。

具体要求：

- `btnInterpToggle` 继续作为开始按钮，做成约 128–136dp 圆形主蓝按钮，带蓝色阴影和双层浅蓝光环。
- `tvInterpStatus` 使用真实状态点和文字，不再把 `○/●` 写进文本。
- 空闲态隐藏 `tvInterpAudioLevel`、`pbInterpAudio`、字幕结果卡和记录路径；它们运行前没有有效信息。
- 当前方案卡保留 `cardInterpPlan`、`tvInterpPlanSummary`、`tvInterpProfile`、`btnInterpOpenPlanLibrary`。
- “方案库”和“编辑方案”都可保留，但只让一个成为明显操作，避免视觉抢占。
- 本场上下文保留 `etInterpSessionContext`、`btnInterpAnalyzeContext`、`tvInterpAnalyzeStatus`，输入框改 filled 风格。

### 6.3 场景快捷宫格

本轮包含，但优先级低于运行态。

- 数据来源使用 `ScenePromptCatalog` 的同传预设，不复制第二份中文列表。
- 做成 2×3 或可换行的轻量宫格。
- 选中项浅蓝底/主蓝文字，未选中项低层灰底。
- 点击普通预设：更新同传 draft 的 `scenePresetId`，调用 `TranslationPlanStore.saveDraft()`，刷新方案摘要。
- 点击“自定义”：打开原方案 Bottom Sheet，不保存空的自定义场景。
- 不修改 saved plan，也不影响视频 draft。

建议新增 ID：`chipGroupInterpHomeScenes`。

### 6.4 运行态整屏切换（最高优先级）

当同传正在运行时，隐藏语言、方案、场景、AI 整理和临时上下文，显示：

1. 顶部状态条：绿色状态点、“同传中/连接中/已暂停/出错”、计时器。
2. 轻量音量反馈：细进度条或简化声波，继续读取 `audioLevelPct`。
3. 字幕主体。
4. 语言方向和场景只读 chips。
5. 底部固定红色通栏“停止同传”按钮。

字幕数据只使用已有 `StatusBus.sessionSnapshot()`：

- `confirmedTranslations.takeLast(6)`：已确认译文列表，旧内容逐渐降低透明度。
- `currentTranslation`：当前主译文。
- `sourceTail`：当前原文。
- 没有配对原文时，不为已确认译文虚构原文。

计时使用 `sessionSnapshot.startedAtMs`，在现有每秒 `renderStatus()` 周期中格式化为 `mm:ss`，超过一小时显示 `hh:mm:ss`，不要新增计时线程。

建议新增：

- `tvInterpElapsed`
- `interpConfirmedList`
- `btnInterpStop`

`btnInterpStop` 只调用现有 `onModeToggle(StatusBus.MODE_MIC)`，不得复制停止服务逻辑。原 `btnInterpToggle` 保留为空闲态开始按钮。

---

## 7. 视频页

涉及：

- `android/app/src/main/res/layout/page_video.xml`
- `android/app/src/main/java/com/xyq/livetranslate/MainActivity.kt`
- 新增必要 drawable/vector

### 7.1 状态拆分

建立两个互斥容器：

- 空闲态：`videoIdleContent`
- 运行态：`videoRunningContent`

仅当 `serviceRunning && captureMode == MODE_VIDEO` 时显示视频运行态。

### 7.2 空闲态

结构从上到下：

1. 标题“视频”。
2. 语言方向胶囊，保留 `acVideoSourceLang`、`acVideoTargetLang`。
3. 当前方案卡。
4. 场景快捷宫格。
5. 本场视频卡：链接、解析、本场临时上下文。
6. 悬浮字幕权限状态行。
7. 主按钮“开始视频字幕”。

具体要求：

- 保留 `cardVideoPlan`、`tvVideoPlanSummary`、`tvCurrentProfile`、`btnVideoOpenPlanLibrary`。
- 保留 `etVideoSessionUrl` 和 `etVideoSessionContext`。
- `btnVideoAnalyzeContext` 改为灰蓝 tonal 次按钮，增加 AI/解析语义图标。
- `tvVideoAnalyzeStatus` 只显示真实解析状态。
- 新增悬浮字幕权限行，使用 `Settings.canDrawOverlays(this)` 显示真实状态；未授权时提供“去设置”。
- 空闲态隐藏 `tvAudioLevel`、`pbAudio`、实时字幕卡和记录路径。
- `btnToggle` 继续作为开始按钮，做成 56dp 高主蓝按钮，并放在容易触达的底部操作区。

视频场景宫格从 `ScenePromptCatalog` 读取视频预设，行为与同传一致：普通场景更新视频 draft，自定义进入方案编辑，不改 saved plan。

建议新增：

- `chipGroupVideoHomeScenes`
- `rowOverlayPermission`
- `tvOverlayPermissionStatus`
- `btnOverlayPermissionSettings`

### 7.3 运行态整屏切换（最高优先级）

视频运行时隐藏链接、AI 解析、方案、语言选择和临时上下文，显示：

1. 运行状态卡：“其他应用音频”、字幕状态 chip、悬浮字幕状态 chip。
2. 计时器和音量反馈。
3. 字幕流主体：已确认译文 + 当前原文/译文。
4. 当前句左侧 2dp 主蓝竖线。
5. 底部固定红色“停止视频字幕”按钮。

数据仍使用已有 `TranslationSessionSnapshot`，不建立第二套字幕缓存。字幕为空时显示“等待语音…”。

建议新增：

- `tvVideoElapsed`
- `videoConfirmedList`
- `btnVideoStop`

`btnVideoStop` 只调用现有 `onModeToggle(StatusBus.MODE_VIDEO)`。停止后回到空闲态，并保持现有临时上下文生命周期和长期方案行为。

---

## 8. 方案 Bottom Sheet

涉及：

- `android/app/src/main/res/layout/bottom_sheet_translation_plan.xml`
- `android/app/src/main/java/com/xyq/livetranslate/TranslationPlanBottomSheet.kt`
- chip selector / drawable / theme

必须保留：

- `etPlanName`
- `chipGroupPlanScenes`
- `tilPlanCustomScene` / `etPlanCustomScene`
- `tilPlanCustom` / `etPlanCustom`
- `btnSavePlan`

调整：

1. 顶部把手约 32×4dp。
2. 标题 22sp，右侧关闭按钮，标题下分隔线。
3. 场景 chip 选中态 `#0070EB` + 白字；未选中 `#F6F3F5` + 弱边框；40dp 高、全圆角。
4. 方案名称、自定义场景改 filled 输入框。
5. “方案提示词”放入“高级选项”折叠区，原 `etPlanCustom` ID 不变。
6. 增加真实提示：“仅影响同传模式”或“仅影响视频模式”。
7. 保存按钮 48–52dp 高、12dp 圆角，保持“保存并应用”。
8. footer 固定在滚动区外，上方加弱分隔线。

不把临时上下文或视频链接移回 Sheet；不增加术语入口、恢复默认按钮或虚假解析源卡片。

---

## 9. 历史列表、搜索、筛选与详情

涉及：

- `android/app/src/main/res/layout/page_history.xml`
- 新建 `android/app/src/main/res/layout/item_history_session.xml`
- 新建 `android/app/src/main/res/layout/page_history_detail.xml`
- `android/app/src/main/java/com/xyq/livetranslate/MainActivity.kt`

### 9.1 历史列表

结构：标题 → 搜索框 → “全部/同传/视频”筛选 → 卡片列表 → 空态。

要求：

- 保留 `historyList`，用真实卡片替代当前多行 MaterialButton。
- 每张卡只展示真实字段：模式、标题、语言方向、场景、更新时间、时长、最近译文摘要。
- 同传使用浅蓝麦克风徽章，视频使用浅橙播放徽章。
- 搜索本地即时过滤 `title`、`summary`、scene label、语言方向。
- 筛选使用 `HistoryItem.mode`，可与搜索组合。
- 原 `btnRefreshHistory` 可改为右上角图标或弱化；不要继续占一整行。
- 空列表显示“暂无历史记录”，过滤无结果显示“没有匹配的记录”。

建议新增：

- `etHistorySearch`
- `chipHistoryAll`
- `chipHistoryInterp`
- `chipHistoryVideo`

### 9.2 历史详情

详情改为真正二级页，不再在列表底部展开 `cardHistoryDetail`。

- 进入详情时隐藏历史列表和底栏，Toolbar 显示返回箭头和会话标题。
- 返回后恢复历史页，并保留搜索词和筛选状态。
- 顶部信息卡显示模式、语言方向、场景、开始时间和时长。
- 保留 `btnCopyHistory`。
- 使用 `HistoryStore.load()` 读取 `HistorySession`。
- 按 `HistorySession.segments` 渲染真实分段卡：`elapsedMs`、`sourceText`、`translatedText`。
- 原文为空时不留空白；译文 16–18sp，原文 14–16sp 弱色。
- 无 segment 时显示简洁空态。

建议新详情页继续保留或迁移以下 ID：`tvHistoryTitle`、`btnCopyHistory`、`tvHistoryDetail`。如果改为动态 segment 容器，可新增 `historyDetailSegments`，但不要让旧 Kotlin 绑定崩溃。

本轮不新增导出、卡内搜索和重命名。

---

## 10. 设置首页和子页

涉及：

- `android/app/src/main/res/layout/page_settings.xml`
- `android/app/src/main/res/layout/page_settings_ai.xml`
- 其他 `page_settings_*.xml`
- `styles.xml` 中 `SettingsRow*`
- `MainActivity.kt`

### 10.1 设置首页

把当前一张大卡拆成四组：

- 翻译：翻译服务、方案库
- 内容分析：AI 内容分析
- 字幕与音频：字幕与悬浮窗
- 系统：诊断、关于

每组卡外用主蓝小标题，组内白卡 24dp 圆角。设置行左侧增加语义图标，中间保留必要标题/副标题，右侧使用真正的 chevron drawable。

必须保留 row ID：

- `rowSetTranslate`
- `rowSetSubtitle`
- `rowSetPlanLibrary`
- `rowSetProfileAi`
- `rowSetDiagnostics`
- `rowSetAbout`

“关于”行尾可从 PackageInfo 显示真实版本号。不增加术语库、账号、同步、订阅或其他演示入口。

### 10.2 AI 内容分析页

保留真实能力：API Key、Base URL、模型、Gemini 原生/OpenAI 兼容格式。

- 顶部增加说明卡：视频页解析本场内容；同传页整理本场背景。文案不得出现术语库。
- 保留 `etSecondAiKey`、`etSecondAiUrl`、`etSecondAiModel`、`btnSecondAiFormat`。
- 输入框改 filled 风格。
- API Key 默认密码遮挡，提供眼睛切换。
- API 格式按钮改为紧凑 segmented/tonal 观感，但行为不变。
- 底部增加锁图标和“密钥仅保存在本机”。
- 不增加总开关、提供商演示下拉、测试连接和显式保存按钮。

### 10.3 其他子页

`page_settings_translate.xml`、`page_settings_subtitle.xml`、`page_settings_diagnostics.xml`、`page_settings_about.xml` 只统一标题、section、输入框、slider 数值、按钮层级和间距，不改变设置含义、默认值或保存方式。

---

## 11. 方案库

涉及：

- `android/app/src/main/res/layout/page_plan_library.xml`
- `android/app/src/main/res/layout/item_saved_plan.xml`
- `MainActivity.kt`

现有交互拓扑已经正确，只做高保真视觉：

1. segmented 使用灰底容器，选中项白色浮起小卡 + 主蓝文字。
2. 方案卡圆角 16dp、内边距 16dp。
3. 左侧徽章改为 32–40dp 圆形 tonal 背景。
4. 场景和语言方向使用轻量 pill，不用厚重默认 Chip 描边。
5. FAB 使用 add 图标和“新建方案”，16dp 圆角。
6. 保留真实的“应用/编辑/删除”，不照抄 more menu。
7. 只显示 name、scene、语言方向和真实提示词摘要；不显示词条数、更新时间或三语标签。

---

## 12. 悬浮字幕 Overlay

涉及：

- `android/app/src/main/java/com/xyq/livetranslate/SubtitleOverlay.kt`

只改样式和普通/紧凑态布局，不改变 WindowManager 类型、权限、拖拽、暂停和翻译会话。

### 12.1 普通态

- 背景改为 `#303032`，默认约 85% 不透明；用户设置仍优先。
- 圆角 24dp，白色描边约 10% alpha。
- 主字幕默认约 22sp、近白；次级文本约 16sp、浅灰 80%。用户字号仍优先。
- 控制按钮约 32dp 圆形轻透明底，去掉厚重描边。
- 顶部增加居中短拖拽把手。
- 状态点继续用绿色表示正常，不照抄红色 LIVE 点。
- 保留 pause 和 compact，不增加 fullscreen。
- Web blur 用半透明底色、elevation 和必要文字阴影近似。

### 12.2 紧凑态

- 改成浅色全圆角胶囊，最大宽度约 280dp。
- 背景使用 `#FCF8FB` 约 85%，轻描边。
- 主文本 14sp、`#1B1B1D`。
- 尽量保留两级文本：主行当前译文，次行已确认/原文摘要，12sp secondary；没有数据不留空行。
- 左侧增加轻量竖向把手，右侧展开按钮做 tonal 圆形图标。
- 切换普通/紧凑态时不重建翻译会话，不改变拖拽位置语义。

---

## 13. 推荐实施顺序

### 阶段 A：基线和 token

1. 确认工作区状态，跑基线构建。
2. 完成 colors/dimens/styles/drawable/vector。
3. 调整 Activity 壳和 bottom nav。

### 阶段 B：核心业务页

1. 同传空闲态。
2. 同传运行态、计时、字幕快照、停止按钮。
3. 视频空闲态和悬浮窗权限状态。
4. 视频运行态、计时、字幕快照、停止按钮。
5. 方案 Bottom Sheet。
6. 场景快捷宫格和 draft 同步。

运行态是本轮最高优先级。不能只完成空闲页换皮就结束。

### 阶段 C：信息与设置

1. 历史卡片 item、搜索、模式筛选。
2. 历史独立详情和 segment 卡片。
3. 设置首页分组。
4. 方案库视觉。
5. AI 分析页和其他设置子页统一。

### 阶段 D：悬浮窗和收尾

1. overlay 普通态。
2. overlay 紧凑态。
3. 清理旧隐藏 UI、重复 shape 和硬编码颜色。
4. 更新版本和开发日志。
5. 完整构建、APK 检查和真机验收。

---

## 14. 自动化验证

当前机器有 Android SDK：`/root/.android-sdk`，`android/local.properties` 已指向它。不要采用旧说明中“本地无法构建”的说法。

### 14.1 每个大阶段

```bash
cd /root/projects/vtuber-live-translate/android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

要求：全部成功，并生成：

`android/app/build/outputs/apk/debug/app-debug.apk`

### 14.2 必须保留的启动回归

`MainActivityStartupTest` 必须通过。改动 `activity_main.xml`、同传/视频页或 always-inflated include 后，至少单独跑：

```bash
cd /root/projects/vtuber-live-translate/android
./gradlew :app:testDebugUnitTest --tests com.xyq.livetranslate.MainActivityStartupTest
```

### 14.3 建议补充的状态测试

为纯 UI 状态增加 Robolectric 测试：

- `serviceRunning=false`：同传/视频空闲容器显示，运行容器隐藏。
- `serviceRunning=true + MODE_MIC`：同传运行容器显示，视频不误显示运行态。
- `serviceRunning=true + MODE_VIDEO`：视频运行容器显示，同传不误显示运行态。
- `startedAtMs` 产生正确计时文本。
- 历史搜索和模式筛选可以组合。
- 历史详情渲染的 segment 数量与 session 一致。

测试结束必须重置 `StatusBus`，避免相互污染。

---

## 15. 真机验收

### 同传

- 空闲态显示语言、麦克风、方案、场景快捷区和本场背景。
- 点击开始后立即切到运行页，配置内容不再出现。
- 状态、计时、音量和字幕持续刷新。
- 红色停止按钮可停止，停止后恢复空闲页。
- 切到其他 Tab 再回来仍保持正确状态。

### 视频

- URL 解析、本场上下文、方案和场景快捷区正常。
- 悬浮窗权限状态据实显示，“去设置”可用。
- 开始后切到运行页，显示运行/悬浮窗状态、计时和字幕。
- 停止后恢复输入页，不影响长期方案。

### 历史

- 搜索与“全部/同传/视频”可以组合。
- 卡片只展示真实字段。
- 点击进入独立详情，真实 segment 的时间、原文、译文正确。
- 复制全文仍可用。
- 返回后保留搜索和筛选状态。

### 设置、方案与 overlay

- 所有设置入口正常进入和返回。
- 方案库同传/视频隔离；应用、编辑、删除、新建正常。
- 场景宫格只更新对应 draft，不修改另一模式或 saved plan。
- 方案提示词折叠展开不丢内容。
- AI Key 默认不明文。
- overlay 普通/紧凑切换、拖动、暂停、透明度和字号设置仍有效。

---

## 16. 完成定义

只有同时满足以下条件才算完成：

1. 参考稿覆盖的主要屏幕和状态都有对应原生实现。
2. 同传和视频真正具备独立运行态，不是只让按钮变红。
3. 历史卡片、搜索、筛选、独立详情全部可用。
4. 设置、方案库、AI 页和 overlay 使用同一套视觉系统。
5. 不恢复术语库，不展示假数据，不改变核心翻译链路。
6. unit test、lint、assemble 全部通过，最终 APK 已生成。
7. `docs/04-dev-log.md` 已记录改动和真实验证结果。

遇到参考稿与现有功能冲突时，优先级为：

**已确认的产品功能和数据边界 > 参考 HTML 的结构意图 > 实际 HTML token > 设计规范 prose > 实现者个人偏好。**
