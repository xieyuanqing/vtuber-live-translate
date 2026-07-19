# UI 去噪与信息架构重构实施方案

本文档是一份可跨会话执行的施工计划：目标是消除当前界面的「个人软件感」，让功能不减的前提下页面安静下来。任何 AI 或维护者拿到本文档，按阶段顺序执行即可，不依赖之前的对话上下文。

**执行前必读**：根目录 `CLAUDE.md`（产品边界与踩坑清单）。本文档与 `CLAUDE.md` 冲突时，以 `CLAUDE.md` 为准。

基线版本：v2.3.1 / versionCode 34（2026-07-18 的界面截图诊断）。若执行时版本已前进，先核对涉及的文件与 View ID 是否仍然存在，行号仅供定位参考，以实际代码为准。

**阶段 A 状态：已完成（2026-07-19）**。**阶段 B0–B4 状态：已完成（2026-07-19）**。B5 / C 待执行。

---

## 1. 诊断：「个人软件感」的四个来源

这不是视觉风格问题（主题、配色、底栏形态都保留），而是信息架构和文案问题：

1. **解释性文案过多，内部术语泄漏**。页面上到处是面向开发者心智模型的说明（「链接与背景只在本场生效，成功开始后清除」「解析结果会写入上方本场背景，不保存进方案」），场景库顶部甚至贴了一段产品边界文档原文。其中「方案 / 方案库」是 v2.3.0 已删除的旧概念，属于漏改。
2. **信息重复、同一功能双控件**。语言方向在同一屏出现两遍（语言胶囊 + 「当前场景」卡）；场景选择有两个控件（「当前场景」卡 + 「快捷场景」chips）；主页直接展示场景 prompt 原文。
3. **可选内容与必选内容同等视觉体积**。「本场临时上下文 / 本场视频」是可选增强，却以两个 minLines=3 的空输入框 + 大色块按钮 + 多层注释常驻摊开；「悬浮字幕已授权」这种已解决状态永久占一张卡。
4. **主操作未锚定与杂项**。视频页的开始按钮排在滚动流末尾、首屏不可见；每个内页有「流译」logo 行 + 超大页标题双头部；设置页多行共用同一个 App logo 图标；空闲态状态点用绿色（绿=运行中的语义冲突）；历史卡标题是按模式写死的固定字符串（所有卡同名，等于没有标题）。

**改造总方针**：删重复、折叠可选、隐藏已解决、锚定主操作、让文案闭嘴。功能一个不减。

---

## 2. 硬约束（任何阶段都不可违反）

- **主题与导航不变**：Luminous Blue 色板、现有字体、圆角卡片语言、iOS 风格悬浮底栏、四 Tab 主导航（同传 / 视频 / 历史 / 设置）全部保留。不引入新配色体系，不添加 Web-only 视觉效果。
- **产品边界不变**：模式隔离、场景库唯一配置层、语言方向不进库条目、本场上下文不写入场景库、启动快照冻结——见 `CLAUDE.md`「不可破坏的产品边界」全部十条。
- **语言胶囊的点击修复不能动**：`bindModeLanguageControls` / `ModeHomeController.setupLanguageControls` 里为每个下拉手动接的 `setOnClickListener { showDropDown() }` 必须保留；不要恢复 `ExposedDropdownMenu + boxBackgroundMode=none` 组合（会 `InflateException`）。
- **空闲/运行是 FrameLayout 兄弟层**：显隐必须作用于完整根容器（如 `interpIdleContent` / `videoIdleContent`），不能只藏内部内容，否则透明层拦截触摸。
- **View ID 与测试同步**：`ModeHomePages.kt` 的 `ModeHomeViews.bind` 按 ID 硬绑定，Robolectric 启动测试（`android/app/src/test/` 下 `MainActivityStartupTest` 等）覆盖关键 ID。改布局时优先保留既有 ID（改形态不改 ID）；确需删除 ID 时，必须在同一提交内更新绑定代码与测试。
- **每阶段独立提交**：一个阶段一个（或一组）提交，交付前执行 `git diff --check`、`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`（本地无 SDK 时由 CI 执行并核对结果），并在 `docs/04-dev-log.md` 顶部追加记录。改可见版本时同步 `build.gradle.kts` 与 README。

**可选的预览流程**：执行阶段 B 前，可以先用现有色板出一版静态 HTML 手机框预览稿供持有者确认。但实现以本文档为准，预览稿不是规范，禁止把 Web 实现细节带进 Android 布局。

---

## 3. 全局设计规则（改任何页面都套用）

- **R1 文案**：输入框的含义由占位符/hint 承载；规则性说明（何时清除、写到哪里）不常驻界面，需要时收进字段获焦后的 helper 或 ⓘ 弹层；禁止「方案」「草稿」「快照」等内部术语出现在用户可见文案；一张卡至多保留一行辅助文字。
- **R2 可选功能折叠**：可选输入默认收成一行入口（标题 + 「可选」+ 摘要 + 展开箭头），点击展开编辑；有内容时行内显示摘要（如「已填写 · 42 字」）。
- **R3 已解决状态不占版面**：权限已授予、配置正常时不显示任何卡片；仅在异常时显示警告横幅（黄/红点 + 一句话 + 去设置按钮）。
- **R4 状态色语义**：绿=运行中，灰=空闲/待开始，黄=警告（缺权限、重连中），红=错误。空闲态一律灰点。
- **R5 按钮权重**：一屏只有一个填充色主 CTA；次级操作（AI 整理、场景库入口）用文字按钮或轻底色，不与主 CTA 抢权重。
- **R6 图标语义化**：每个入口用语义图标，禁止用 App logo（`ic_brand_translate_24`）当占位符复用。
- **R7 列表选择器模式**：「从 N 个里选一个」的列表，交互为点行即选中、当前项行尾打勾；编辑/删除/设默认等管理操作收进行尾「⋯」溢出菜单，不在每行常驻按钮。
- **R8 主 CTA 锚定**：页面的开始/停止按钮固定在底部（底栏上方），不随内容滚动消失。

---

## 4. 阶段 A：文案与图标清理（零结构风险，先做）

只改字符串、图标资源和一处颜色，不动布局结构与绑定。可一次提交完成。

### A1 修复「方案」旧词（文案 bug）

| 位置 | 现文案 | 改为 |
|---|---|---|
| `page_interpretation.xml` `cardTitle=本场临时上下文` 卡内说明（约 :245） | 只在本场生效，成功开始后清除，不写入方案库 | 只对本场翻译生效（阶段 C2 落地后整行删除） |
| `page_video.xml` `tvVideoAnalyzeStatus` 默认文案（约 :230） | 解析结果会写入上方本场背景，不保存进方案 | 置空（见 A2 说明） |

### A2 删除冗余注释

注意：`tvInterpAnalyzeStatus` / `tvVideoAnalyzeStatus` 兼作 AI 整理的进度/结果输出位（`SessionContextController` 会写入状态文本），**View 与 ID 必须保留**，只把空闲态默认文案改为空字符串并 `visibility=gone`，分析进行中再显示。

- `page_interpretation.xml`：删「例如现场主题、说话人、专名提示」以外的重复说明；`tvInterpAnalyzeStatus` 默认文案「可选：把杂乱资料整理成翻译可用的本场背景」→ 置空。
- `page_video.xml`：删 helper「用于解析标题、频道和内容背景」（含义并入 hint：「YouTube 链接（用于 AI 解析背景）」）；删「可手写，也可用 AI 解析视频」；卡内说明「链接与背景只在本场生效，成功开始后清除」→「只对本场翻译生效」。
- `page_scene_library.xml` 约 :54-60：整段删除「场景是唯一的长期配置：名称 + 提示词……正在运行的会话不变。」（无 ID 的静态 TextView，直接移除）。

### A3 图标语义化

新建 vector drawable（Material Symbols 风格，24dp）：`ic_key_24`（钥匙）、`ic_layers_24`（图层）、`ic_sparkle_24`（星芒/魔棒）、`ic_info_24`（信息）、`ic_add_24`（加号）。替换：

- `page_settings.xml`：`rowSetTranslate` → `ic_key_24`；`rowSetSceneLibrary` → `ic_layers_24`；`rowSetProfileAi` → `ic_sparkle_24`；`rowSetAbout` → `ic_info_24`（`rowSetSubtitle` 已有专属图标，`rowSetDiagnostics` 可暂留）。
- `page_scene_library.xml`：`fabNewScene` 的 `app:icon` → `ic_add_24`。

### A4 空闲态状态点改灰

新建 `bg_idle_dot.xml`（灰色圆点，参照 `bg_success_dot` 结构、填充 `text_muted`）。`page_interpretation.xml` 空闲状态 pill（`tvInterpStatus`「待开始」旁，约 :134-137）的 `bg_success_dot` → `bg_idle_dot`。核对 `ModeHomeController` 是否在运行时覆盖该点颜色，保证运行态仍是绿色语义。

**验收**：全部页面不再出现「方案」字样；AI 整理按钮下方空闲时无文字；设置页四行图标各不相同；同传空闲态状态点为灰色。测试与 Lint 通过。

---

## 5. 阶段 B：逐页结构改造

每个子项可独立提交。改造时遵守第 3 节全局规则。

### B0 全局头部：去掉双标题

现状：`activity_main.xml` 的 `MaterialToolbar`（logo + 「流译」标题）与每页超大 `PageTitle` 叠加，内页顶部两层头部。

目标：四个主 Tab 页隐藏 Toolbar（或仅保留系统栏内边距），页面大标题即唯一头部；子页（场景库、设置二级页、历史详情）保留 Toolbar 用于返回箭头与页名。

实施：`MainNavigator` 已按页面切换 Toolbar 状态，扩展为主 Tab 时 `toolbar.visibility=GONE`。注意状态栏高度补偿，避免内容顶进状态栏。

**验收**：主 Tab 页顶部只有一个标题；子页返回箭头正常；Robolectric 启动测试通过。

> 落地：`MainNavigator` 主 Tab 隐藏 Toolbar + `pageContainer` 状态栏补偿；测试见 `MainNavigatorTest.mainTabsHideToolbarWhileSubPagesShowIt`。

### B1 同传页（`page_interpretation.xml`）

现状问题：语言方向两遍、场景双控件、prompt 原文上主页、chips 换行占两行、上下文卡摊开。

目标空闲态结构（自上而下）：

1. 页标题「同传」。
2. 语言胶囊（现有，含 C5 交换按钮位）。
3. 大麦克风按钮 + 状态 pill（灰点「待开始」）+ 副文字（保留现状）。
4. 场景行：小节标签「场景」+ 行尾文字按钮「场景库 ›」（复用 `btnInterpOpenPlanLibrary` ID）；下方 `chipGroupInterpHomeScenes` 改为单行横滑（外包 `HorizontalScrollView`，ChipGroup 设 `app:singleLine="true"`，保留 ID 与点击逻辑）。
5. 本场背景折叠行：一行「本场背景 · 可选 ›」；点击展开原卡内容（`etInterpSessionContext`、`btnInterpAnalyzeContext`、`tvInterpAnalyzeStatus` 全部保留 ID，仅包进可折叠容器）；有内容时行内摘要「已填写 · N 字」。

删除项：「当前场景」卡（`cardInterpPlan`）整卡删除——场景信息由选中的 chip 表达。`cardInterpPlan` / `tvInterpPlanSummary` / `tvInterpProfile` 被 `ModeHomeViews` 绑定且测试覆盖：把这三个 ID 移到场景行的紧凑元素上复用（如 `tvInterpPlanSummary` 变成场景行的小字「场景名 · 方向」，仅在需要时显示），或同提交内改 `ModeHomeViews` 为可空并更新测试。**优先复用 ID，最小化绑定改动。**

运行态不动（阶段 C6/C7 再改）。

**验收**：空闲态一屏能看到语言、按钮、场景 chips；无 prompt 原文；chips 单行横滑；上下文默认折叠、展开可编辑、AI 整理功能不回归；`ModeHomePages` 绑定与启动测试全绿。

> 落地：场景行 + 横滑 chips + 本场折叠；`tvInterpProfile` 默认 gone。

### B2 视频页（`page_video.xml`）

同 B1 的全部动作（对应 ID：`cardVideoPlan` / `tvVideoPlanSummary` / `tvCurrentProfile` / `chipGroupVideoHomeScenes` / `etVideoSessionContext` / `btnVideoAnalyzeContext` / `tvVideoAnalyzeStatus`；折叠行内含 `etVideoSessionUrl`），另加两项：

- **主 CTA 锚定**：`videoIdleContent` 根从 `NestedScrollView` 改为垂直 `LinearLayout`（**根容器保留 `videoIdleContent` ID**，显隐整根切换）：内部 = `NestedScrollView`（weight 1，承载滚动内容）+ 底部固定条（`btnToggle` 保留 ID）。
- **权限卡改警告横幅**：`rowOverlayPermission` 仅在未授权时可见（黄底横幅样式：警告点 + 「悬浮字幕未授权」+「去设置」）；已授权时 `visibility=GONE`。改 `ModeHomeController.renderOverlayPermission`：`allowed` 时隐藏整行。保留全部相关 ID。

**验收**：空手进入视频页，首屏可见语言、场景 chips、折叠行与开始按钮；权限正常时页面无权限卡；缺权限时横幅出现且可跳设置；启动流程（快照、投屏授权）不回归。

> 落地：固定底部 CTA + 场景行/横滑 chips + 本场折叠 + 权限警告横幅。

### B3 场景库（`page_scene_library.xml` + `item_scene_preset.xml`）

现状问题：顶部文档腔说明段（A2 已删）、每卡常驻头像 + 双徽章 + prompt 四行 + 四个操作按钮，一屏只容一张半卡；「恢复默认」占黄金位；FAB 用 logo。

目标（R7 列表选择器模式）：

- 模式切换 segmented control 保留。
- 「恢复该模式默认场景」（`btnResetSceneLibrary`）移到列表底部小字链接或 Toolbar 溢出菜单，不再排在列表前面。
- `item_scene_preset.xml` 重做为紧凑行：
  - 删 `tvSceneIcon` 首字头像。
  - 左侧：`tvSceneName`（单行）+ `tvSceneInstruction`（提示词预览，`maxLines=1`，ellipsize）。
  - 行尾：使用中 → 勾选图标（可复用 `chipSceneInUse` ID 改形态）；默认 → 小星标（复用 `chipSceneDefault`）；「⋯」按钮（新 ID `btnSceneMore`）。
  - **整行点击 = 使用**（替代 `btnUseScene`）；「⋯」弹 `PopupMenu`：编辑 / 设为默认 / 删除（删除保留红色 + 确认对话框）。
  - `SceneLibraryController` 同步改绑定：`btnUseScene` / `btnSetDefaultScene` / `btnEditScene` / `btnDeleteScene` 四个常驻按钮删除，动作迁入行点击与菜单回调。相关测试同提交更新。
- 编辑仍用现有 `dialog_scene_editor`，按 ID 原位更新、取消不污染（边界第 8 条）。

**验收**：一屏可见 5 行以上场景；点行立即切换当前场景并有选中反馈；编辑/设默认/删除全部可达且行为与旧版一致；「使用」「设为默认」「编辑」三动作语义不变（边界第 8 条）；模式隔离不破。

> 落地：场景库紧凑行 + 行点击使用 + ⋯ 菜单；恢复默认沉底。

### B4 历史页（`page_history.xml` + `item_history_session.xml` + `HistoryController`）

现状问题：卡片标题是固定字符串（「同传记录」「视频翻译」），模式信息重复三次（图标 + 标题 + `tvHistoryItemMode` 标签）；绝对时间戳难扫描；常驻手动刷新按钮。

目标：

- **标题有信息量**：`tvHistoryItemTitle` = 场景名（视频模式如有已解析的视频标题则优先用视频标题）。模式只由左侧图标表达，`tvHistoryItemMode` 删除或 `gone`。`tvHistoryItemMeta` 保留「语言方向 · 时长」（场景名已上标题，不重复）。
- **日期分组**：列表按 今天 / 昨天 / 具体日期 插入分组小节头；卡内 `tvHistoryItemTime` 只显示 `HH:mm`。
- **删手动刷新**：`btnRefreshHistory` 移除；进入页面自动刷新（现有逻辑确认即可），可选加下拉刷新。

**验收**：列表扫一眼能区分每场会话讲了什么；无重复模式标签；分组头正确；搜索与模式筛选不回归。

> 落地：历史日期分组 + 场景标题 + 去掉手动刷新。

### B5 设置页（`page_settings.xml`）

现状问题：4 个分组装 6 行，分组比内容密度还高（图标问题 A3 已解决）。

目标：合并为两组——「翻译」= 翻译服务 / 场景库 / AI 内容分析；「系统」= 字幕与悬浮窗 / 诊断 / 关于。行副标题保留。全部 `rowSet*` ID 与 `settingsSubViews` 返回栈不动。

**验收**：两组六行；所有子页入口可达；返回栈正常。

---

## 6. 阶段 C：交互增强（独立项，可单独排期与提交）

与视觉无关但同样影响「成品感」的操作逻辑问题。每项独立，可按价值挑选。

- **C1 同传运行页加暂停**：暂停能力已存在（`CaptureService.ACTION_TOGGLE_PAUSE`），但同传模式无悬浮窗、App 内运行页无入口，想临时停只能断会话。运行页（两模式）加暂停/继续按钮，状态从 `StatusBus.paused` 读取并反映在状态 pill。
- **C2 本场上下文改为「会话结束后保留」**：现状 `SessionCoordinator.consumeStartedSession` 启动成功即清（`clearAfterSuccessfulStart`），断线/误停后重开同一场要重写背景。改为启动后不清除，由折叠行（B1/B2）提供摘要 + 一键清除（×）。边界不变：仍只存于主页、不入场景库。同步删除 A1 遗留的「只对本场翻译生效」说明行。
- **C3 停止需确认**：`btnInterpStop` / `btnVideoStop` 单击即断，误触代价高（视频模式还需重走投屏授权）。加确认对话框或改长按停止（二选一，倾向确认框）。
- **C4 悬浮窗权限返回自动续跑**：现状缺悬浮窗权限时 toast + 跳设置，回来要手动再点开始。录音权限路径已有快照续跑机制，对齐之：`PendingSessionStage` 加 `WAITING_OVERLAY`，`onResume` 检测到已授权且快照处于该态时自动继续。
- **C5 语言方向交换按钮**：语言胶囊中间的「→」改为可点击交换源/目标。源为「自动检测」时禁用并 toast 说明。写入对应模式草稿（`TranslationPlanStore`），不碰 showDropDown workaround。
- **C6 运行态字幕完整回看**：现状 `renderConfirmedTranslations` 只保留最后 6 条（`takeLast(6)`），长会话无法回看。确认行列表改 `RecyclerView`：全量（或分页）展示、自动吸底、用户上滑时暂停跟随、回到底部恢复。需要 `StatusBus` 快照承载更多确认行或提供增量通道。
- **C7 静音/聆听状态提示**：静音期长时间无输出是正常情况（`CLAUDE.md`），但界面无任何传达，用户会疑心断线。在运行态副状态派生显示：电平持续≈0 →「静音中」；有电平但超过 N 秒无新字幕 →「聆听中…」。只改 UI 派生逻辑，不动连接判定。
- **C8 悬浮字幕收起态可纵向拖动**：`SubtitleOverlay.dragListener` 现为 `moved && !collapsed`，收起胶囊 y 锁死，挡住目标 App 控件时必须展开-拖-再收。放开 collapsed 态的纵向拖动（x 仍贴边），拖完 clamp。
- **C9 历史落盘与导出**：① 空会话（无确认行且时长 < 10s）停止时不落盘，避免历史堆「00:03 · 暂无字幕摘要」垃圾条目；② 历史详情页加系统分享（`ACTION_SEND`，Markdown 文本），与「不自动写公共 Downloads」边界不冲突（用户主动动作）；③ 历史列表 `LinearLayout` 全量 addView 改 `RecyclerView`（会话多时的性能与 B4 分组一起做亦可）。

---

## 7. 建议执行顺序与交付检查单

```text
A（一次提交）→ B0 → B1 → B2 → B3 → B4 → B5 → C 按需挑选
```

每次交付：

1. `git diff --check` 无残留冲突/空白错误；
2. `cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`（容器无 SDK 时推远端由 CI 执行，核对 Run 的 `headSha` 与结论）；
3. 删除/改动过 View ID 时，确认 `ModeHomePages.kt`、各 Controller 与 Robolectric 测试同步；
4. `docs/04-dev-log.md` 顶部追加：改动、原因、版本、真实验证结果；
5. 改可见版本时同步 `android/app/build.gradle.kts` 与 README 当前状态；
6. 触摸交互类改动（下拉、折叠、拖动）注明「需真机确认」，CI 只能证明编译与绑定存在。
