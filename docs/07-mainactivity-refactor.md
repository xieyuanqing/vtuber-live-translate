# 07 · MainActivity 拆分重构执行文档

本文档是一份可以直接照做的施工图:背景与决策原因、硬性约束、目标结构、六个执行步骤(含每步搬迁清单与验证)、交付要求、以及这次重构在未来 SaaS 演进中的位置。执行者(人或 AI)只需按顺序完成,不需要重新做设计决策。

状态:待执行。分支:`claude/saas-architecture-review-bkde2s`。

---

## 1. 背景与为什么

### 1.1 起因

对"未来把流译做成 SaaS"做过一次架构评估,结论:

- 实时管线(`CaptureService → PcmProcessor → GeminiLiveClient → SubtitleStabilizer`)分层干净,SaaS 化时基本原样保留;
- `ApiCredentialMode`(个人 Key 直连 / 好友网关代理)这条缝留得对,是未来把 Key 移到服务端的现成通道;
- **客户端最大的技术债是 `MainActivity.kt`(约 1850 行)**:四个主页、全部设置子页、场景库、历史、权限流、会话快照、状态渲染全部在一个类里。未来任何新页面(登录、订阅、同步)都只能继续往里堆。

本次重构只解决第三条:把 MainActivity 按页面拆成协作类。**不改任何可见行为,不改任何功能。**

### 1.2 为什么这样拆(决策已定,不要重新讨论)

- **不用 Fragment**:四个主页面必须继续由 `activity_main.xml` 静态引入(CLAUDE.md 代码结构约定),设置子页必须留在 `settingsSubViews` 显隐式返回栈里。改 Fragment 会同时破坏布局约定、返回栈逻辑和 Robolectric 启动测试。
- **不上 Compose、不引入 DI 框架、不加事件总线**:个人项目,依赖越少越好;普通 Kotlin 类 + 构造参数 + 回调 lambda 足够。
- **拆成普通 controller 类**:每个类持有自己页面的视图引用与逻辑,MainActivity 只剩生命周期、ActivityResult 启动器注册和接线。仓库里已有可参照的模式:`FriendGatewayBindingViewModel`(backend 接口 + 可单测状态机)。

---

## 2. 硬性约束(违反任何一条即返工)

1. **不改 `activity_main.xml`,不动任何 View ID**,不改 `res/layout/` 下任何文件。
2. **纯行为等价**:只搬代码,不"顺手优化"逻辑、不改文案、不改任何 Store / Service / 实时管线层的类。
3. 以下几个"坑"的现状必须原样保留(CLAUDE.md「容易踩的坑」+ 代码内注释):
   - 语言下拉框没有下拉委托,必须保留每个下拉 `setOnClickListener { showDropDown() }` 的手动接线(`bindModeLanguageControls`,有测试盯着)。
   - 同传/视频的空闲态与运行态是 FrameLayout 兄弟层,显隐必须作用于 `interpIdleContent` / `interpRunningContent` / `videoIdleContent` / `videoRunningContent` 完整根容器。
   - **启动快照冻结(产品边界 7)**:`pendingSession*` 一旦在 `prepareSessionSettings` 里冻结,权限回调、MediaProjection 授权回来、Activity 重建后只能消费快照,不能重新读当前配置。`startCapture` 开头的 `reusePendingSnapshot` 判断逻辑逐字保留。
   - `showPage` 内不能写 `bottomNav.selectedItemId = ...`(会无限重入栈溢出,代码里有注释说明)。
   - `consumeStartedSession` 的调用时机(mic 启动后立即;video 在投屏授权成功后)不变。
4. **测试只许改调用方式,不许删断言**:`MainActivityStartupTest.kt` 目前用反射访问私有成员,拆分后改为直接调用新类的 `internal` 成员;每个测试用例和断言都必须保留等价物。
5. 注释、文档、提交说明用中文;不改 `versionName`/`versionCode`(纯内部重构不是可见版本变更)。
6. 交付前:`git diff --check`、`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug` 全部通过。

---

## 3. 目标结构

新建包 `com.xyq.livetranslate.ui`(目录 `android/app/src/main/java/com/xyq/livetranslate/ui/`)。

| 新文件 | 职责(吸收的现有代码) | 预估行数 |
|---|---|---|
| `ui/MainNavigator.kt` | 壳子导航:`showPage` / `openSettingsSub` / `closeSettingsSub` / bottomNav 与 toolbar 接线 / `applyWindowInsets` / tab 与子页的 saved-state | ~180 |
| `ui/ModeHomePages.kt` | 同传+视频主页。`ModeHomeViews`(按模式绑定视图的持有类,每模式一实例)+ `ModeHomeController`(场景 chips、语言胶囊、方案摘要、运行态渲染) | ~380 |
| `ui/SessionCoordinator.kt` | 启动流程与快照:`pendingSession*` 全部字段、`prepareSessionSettings` / `captureStartIntent` / `startCapture` / `onModeToggle` / `consumeStartedSession` 与对应 saved-state | ~230 |
| `ui/SessionContextController.kt` | 本场临时上下文 + AI 解析:`analyzeSessionContext` 整块与 requestId 守卫 | ~180 |
| `ui/HistoryController.kt` | 历史页 + 详情渲染 | ~170 |
| `ui/SceneLibraryController.kt` | 场景库页 + 场景编辑对话框 | ~200 |
| `ui/SettingsController.kt` | 设置首页 + 翻译服务(含好友网关 UI)+ 字幕滑杆 + 内容分析 AI + 诊断 + 关于 + 电池白名单 | ~330 |

拆完后 `MainActivity.kt` 目标 250–300 行,只剩:

- `onCreate`:恢复 saved-state → `setContentView` → 构造各 controller 并接线;
- 两个 `ActivityResultLauncher`(必须注册在 Activity 上,回调转发给 `SessionCoordinator`);
- 生命周期:`onResume`/`onPause` 的 300ms 刷新循环与草稿保存、`onSaveInstanceState` 汇总各 controller、`onBackPressed` 委托 navigator;
- 若干 `internal` 属性暴露 controller 供测试直调。

### 3.1 接线原则

- 每个 controller 构造时拿 `MainActivity`(用作 Context / layoutInflater / findViewById),自己 `findViewById` 自己页面的视图;跨类协作全部走构造参数里的 lambda,**不引入事件总线**。
- 需要的回调关系(全景):
  - `SceneLibraryController.onSceneChanged(mode)` → 通知两个 `ModeHomeController` 刷新 chips 与方案摘要(即现有 `refreshSceneDependents`);
  - `HistoryController` 打开详情 → 调 navigator 的 `openSettingsSub(R.id.pageHistoryDetail, "历史详情", R.id.nav_history)`;
  - navigator 切到历史 tab → `HistoryController.reload()`;打开场景库子页 → `SceneLibraryController.reload()`;关闭内容分析 AI 子页 → `SettingsController.saveSecondAiSettings()`;
  - `SessionCoordinator` 校验失败(没 Key / 好友凭据失效)→ 调 navigator 跳设置子页;
  - `ModeHomeController` 主页场景卡片 → `SceneLibraryController` 的打开入口(带 returnTab)。
- 步骤 1–5 期间 navigator 还没抽出来,回调先指向 MainActivity 上的现有方法引用,步骤 6 再改指 navigator;这样每一步都可编译可测。

### 3.2 同传/视频视图对照表(`ModeHomeViews` 的绑定依据)

现在 `renderStatus` 等方法对两个模式各写一份,拆分时用一个数据类按模式绑定。ID 对照:

| 语义 | 同传(INTERPRETATION) | 视频(VIDEO) |
|---|---|---|
| 空闲态根容器 | `interpIdleContent` | `videoIdleContent` |
| 运行态根容器 | `interpRunningContent` | `videoRunningContent` |
| 主页场景 chips | `chipGroupInterpHomeScenes` | `chipGroupVideoHomeScenes` |
| 运行态状态文字 | `tvInterpRunningStatus` | `tvHeroStatus` |
| 运行态状态圆点 | `viewInterpRunningStatusDot` | `viewVideoRunningStatusDot` |
| 运行态副标题 | (无,见下方"不对称项") | `tvHeroSubStatus` |
| 已用时长 | `tvInterpElapsed` | `tvVideoElapsed` |
| 运行态 meta(方向·场景) | `tvInterpRunningMeta` | `tvVideoRunningMeta` |
| 确认行列表容器 | `interpConfirmedList` | `videoConfirmedList` |
| 音量百分比 / 进度条 | `tvInterpAudioLevel` / `pbInterpAudio` | `tvAudioLevel` / `pbAudio` |
| 开始按钮 / 停止按钮 | `btnInterpToggle` / `btnInterpStop` | `btnToggle` / `btnVideoStop` |
| 目标译文标签 | `tvInterpTargetLanguageLabel` | `tvVideoTargetLanguageLabel` |
| 当前译文 / 原文尾巴 | `tvInterpZh` / `tvInterpJa` | `tvLiveZh` / `tvLiveJa` |
| 记录路径 | `tvInterpTranscriptPath` | `tvTranscriptPath` |
| 源/目标语言下拉 | `acInterpSourceLang` / `acInterpTargetLang` | `acVideoSourceLang` / `acVideoTargetLang` |
| 方案摘要卡 | `cardInterpPlan`、`tvInterpPlanSummary`、`tvInterpProfile` | `cardVideoPlan`、`tvVideoPlanSummary`、`tvCurrentProfile` |
| 打开场景库按钮 | `btnInterpOpenPlanLibrary` | `btnVideoOpenPlanLibrary` |
| 本场上下文输入 | `etInterpSessionContext` | `etVideoSessionContext` |
| AI 解析按钮 / 状态 | `btnInterpAnalyzeContext` / `tvInterpAnalyzeStatus` | `btnVideoAnalyzeContext` / `tvVideoAnalyzeStatus` |

**不对称项(用可空字段处理,不要硬造对称)**:

- 同传独有:空闲态状态文案 `tvInterpStatus` / `tvInterpSubStatus`(内容还依赖"视频是否正在运行",见 `renderStatus` 1765–1770 行附近的现状)。
- 视频独有:悬浮窗权限行 `rowOverlayPermission` / `viewOverlayPermissionDot` / `tvOverlayPermissionStatus` / `btnOverlayPermissionSettings`、视频链接输入 `etVideoSessionUrl`、运行态副标题 `tvHeroSubStatus`(内容含悬浮窗状态与当前 Key 标签)。

---

## 4. 执行步骤

总原则:**一步一验一提交**。每步做完跑 `cd android && ./gradlew :app:testDebugUnitTest`,通过后单独 commit(中文说明,如 `refactor: 抽出 HistoryController`),再进行下一步。任何一步测试红了,先修复再前进,禁止跳步。

### 步骤 1:抽出 `HistoryController`(最独立,先做热身)

搬迁自 MainActivity:

- 字段:`btnRefreshHistory`、`etHistorySearch`、`chipHistoryAll/Interp/Video`、`tvHistoryEmpty`、`historyList`、`cardHistoryDetail`、`tvHistoryTitle`、`btnCopyHistory`、`tvHistoryDetail`、`tvHistoryDetailMeta`、`tvHistoryDetailContext`、`tvHistoryDetailEmpty`、`historyDetailSegments`、`allHistoryItems`、`historyModeFilter`;
- 方法:`setupHistoryPage`、`reloadHistory`、`renderHistoryList`、`showHistoryDetail`。

构造参数:`(activity: MainActivity, openDetailPage: () -> Unit)`,其中 `openDetailPage` 现阶段传 `{ openSettingsSub(R.id.pageHistoryDetail, "历史详情", R.id.nav_history) }` 的方法引用。`showPage` 里的 `if (itemId == R.id.nav_history) reloadHistory()` 改为调 `historyController.reload()`。toast 可以在 controller 里自带一个私有扩展,或由 activity 传入。

### 步骤 2:抽出 `SceneLibraryController`

搬迁:

- 字段:`toggleSceneLibraryMode`、`btnSceneLibraryInterp/Video`、`sceneLibraryList`、`btnResetSceneLibrary`、`fabNewScene`、`sceneLibraryMode`;
- 方法:`setupSceneLibraryPage`、`reloadSceneLibrary`、`buildSceneCard`、`showSceneEditor`;
- saved-state:`STATE_SCENE_LIBRARY_MODE` 的读写移进来(controller 提供 `saveState(outState)` / 构造时收恢复值)。

构造参数带 `onSceneChanged: (TranslationMode) -> Unit`(现阶段指向 MainActivity 的 `refreshSceneDependents`)。**保留 MainActivity 上的 `internal fun openSceneLibrary(mode, returnTabId)`**(测试 `videoSceneLibrarySurvivesRecreateAndReturnsToVideoHome` 直接调它),内部改为设 controller 的 mode 再开子页。

### 步骤 3:抽出 `SettingsController`

搬迁:

- 字段:`rowSet*` 六个入口行、`etApiKeys`、`etBaseUrl`、好友网关全部视图(`swFriendGateway`、`etFriendInviteCode`、`tvFriendGatewayStatus`、`btnBindFriendGateway`、`btnClearFriendGateway`)、`syncingFriendGatewayUi`、样式滑杆(`slFont/slOpacity/slLines` + 三个 label)、二号 AI(`etSecondAi*`、`btnSecondAiFormat`)、参数滑杆(`swEchoTarget`、`slRotate/slIdle/slMaxChars` + label、两个 reset 按钮)、`btnBattery`、`tvStatus`、`tvAboutVersion`、`btnAboutRepo`;
- 方法:`setupSettings`、`setupFriendGatewayUi`、`renderFriendGatewayUi`、`renderFriendBindingState`、`bindFriendGateway`、`refreshFriendGatewayStatus`、`secondAiFormat`、`updateSecondAiFormatLabel`、`toggleSecondAiFormat`、`saveSecondAiSettings`、`setupStyleSliders`、`updateStyleLabels`、`requestBatteryWhitelist`,以及 `setupParamControls` 中**除语言胶囊绑定以外**的部分(滑杆、echo 开关、两个重置按钮)。

注意:

- 语言胶囊绑定(`bindModeLanguageControls` / `renderModeLanguageControls` / `syncLanguageControlsFromStore`)**这一步先留在 MainActivity**,步骤 5 才移入 ModeHomePages;`btnResetTranslate` 点击后需要刷新语言胶囊,通过回调 `onTranslateParamsReset: () -> Unit` 通知(现阶段指向 activity 的 `renderParamValues` 等价逻辑)。
- `friendBindingViewModel` 的 `observe` 留在 MainActivity(生命周期拥有者),observer 体调 `settingsController.renderFriendBindingState(state)`。
- `onPause` / `closeSettingsSub` 里的 `saveSecondAiSettings()` 调用改为经 controller。
- 诊断页 `tvStatus` 的汇总渲染(`renderStatus` 尾部那段 `buildString`)先随本步移成 `settingsController.renderDiagnostics(...)`,由 activity 的刷新循环调用。
- 好友网关校验失败跳设置子页的逻辑仍在 `prepareSessionSettings`(此时还在 MainActivity),不动。

测试改造:`friendBindingDisablesAllMutableControls` 删除反射,改为 `activity.settingsController.renderFriendGatewayUi(null, bindingInProgress = true)`(方法设为 `internal`,controller 以 `internal lateinit var settingsController` 挂在 activity 上)。

### 步骤 4:抽出 `SessionCoordinator`(高风险步,对照边界 7 自查)

搬迁:

- 字段:`pendingSessionPrompt/Source/Target/Scene/SceneLabel/Title/Context`、`permRequested`、`pendingStartMode`、`pendingCredentialMode`;
- 方法:`onModeToggle`、`promptMode`、`prepareSessionSettings`、`captureStartIntent`、`startCapture`、`consumeStartedSession`、`currentSessionContext`、`composeSessionPrompt`;
- saved-state:`STATE_PENDING_*` 七个 key + `STATE_PERMISSION_REQUESTED` + `STATE_PENDING_START_MODE` + `STATE_PENDING_CREDENTIAL_MODE` 的读写全部移入(构造时恢复、`saveState(outState)` 写出)。

接线:

- 两个 launcher 留在 MainActivity:`permLauncher` 回调改为 `sessionCoordinator.onAudioPermissionResult()`(即原来的 `startCapture(pendingStartMode)`);`projLauncher` 回调改为 `sessionCoordinator.onProjectionResult(resultCode, data)`;`startCapture` 里发起投屏的那行通过构造参数 `launchProjection: (Intent) -> Unit` 回到 activity。
- `prepareSessionSettings` 需要读设置页输入框并保存(`etApiKeys`/`etBaseUrl`/`saveSecondAiSettings`),通过 `SettingsController` 暴露的 `internal fun persistDraftInputs()` 之类的方法提供;跳设置子页通过 lambda。
- 本场上下文输入框(`etInterpSessionContext`/`etVideoSessionContext`/`etVideoSessionUrl`)的读取与清空,先通过 lambda 取值(`sessionContextOf: (TranslationMode) -> String` 等),步骤 5/6 后自然落到 ModeHomeViews 上。

自查清单(边界 7):

- [ ] `reusePendingSnapshot` 判断原样;
- [ ] `prepareSessionSettings` 只在**非复用**路径调用;
- [ ] `consumeStartedSession` 的两个调用点时机不变;
- [ ] 进程重建后 `STATE_PENDING_*` 恢复完整,投屏授权回来仍用快照启动。

测试改造:`captureIntentCarriesFrozenCredentialMode` 删除反射,改为直接设 coordinator 的 `internal` 状态(如 `internal fun overrideCredentialModeForTest(...)` 或直接 `internal var pendingCredentialMode`)并调 `internal fun captureStartIntent(mode)`。

### 步骤 5:抽出 `ModeHomePages`(工作量最大的一步)

内容:

1. `ModeHomeViews`:按 §3.2 对照表绑定视图的类,构造时 `findViewById`;不对称项为可空。
2. `ModeHomeController`(每模式一实例,构造收 `mode: TranslationMode` 与对应 `ModeHomeViews`):
   - 吸收 `setupHomeSceneChips`、`bindModeLanguageControls`、`renderModeLanguageControls`、`updatePlanSummary`、`renderConfirmedTranslations`、`formatRunningElapsed`;
   - 吸收 `renderStatus` 中按模式重复的渲染分支:写成 `renderStatus(shared: SharedStatus)`,其中 `SharedStatus` 是一次算好的公共状态(running/mode/conn/level/activeStatusText/activeStatusColor/snapshot/overlayAllowed);
   - 同传空闲文案依赖"视频是否在跑"、视频副标题含悬浮窗状态——这些跨模式输入都放进 `SharedStatus`,不要让两个 controller 互相引用。
3. MainActivity 的刷新循环变为:算一次 `SharedStatus` → 两个 `modeHome.renderStatus(shared)` → `settingsController.renderDiagnostics(shared)`。`onResume` 里 `syncLanguageControlsFromStore` 改为对两个 controller 调 `syncLanguageFromStore()`。
4. 步骤 3 留下的 `onTranslateParamsReset` 回调、步骤 4 留下的本场上下文取值 lambda,改指到 controller/views。
5. `SceneLibraryController.onSceneChanged` 回调改为通知对应 mode 的 controller(`refreshSceneChips()` + `renderPlanSummary()`)。

**语言胶囊红线**:`bindModeLanguageControls` 中每个下拉的 `setOnClickListener { showDropDown() }` 必须原样搬入,并保留原方法注释;测试 `languageDropdownsOpenOnTap` 会验证。

测试改造:`renderStatus` / `setupHomeSceneChips` 的反射改为——MainActivity 保留 `internal fun renderStatus()`(即刷新循环的那次完整渲染,委托实现)供测试直调;`refreshHomeScenes` 辅助函数改调对应 controller 的 `internal fun refreshSceneChips()`。

### 步骤 6:抽出 `SessionContextController` 与 `MainNavigator`,收尾

1. `SessionContextController`:搬 `setupSessionContextUi`、`analyzeSessionContext`、`latestInterpAnalysisRequestId` / `latestVideoAnalysisRequestId`;视图经 `ModeHomeViews` 取;`STATE_INTERPRETATION_CONTEXT` / `STATE_VIDEO_CONTEXT` / `STATE_VIDEO_URL` 的存取移入。线程 + `runOnUiThread` + requestId 守卫的实现**原样搬**,不改成协程/ViewModel(那是后续独立改动,见 §7)。
2. `MainNavigator`:搬 `showPage`、`openSettingsSub`、`closeSettingsSub`、`setupBottomNav`、`applyWindowInsets`、`currentMainTabId` / `settingsSubId` / `settingsReturnTabId`、`STATE_MAIN_TAB` / `STATE_SETTINGS_SUB` / `STATE_SETTINGS_RETURN_TAB` 存取、onCreate 尾部恢复子页标题的 `when`。navigator 构造收三个 hook:`onMainTabShown(tabId)`(历史 tab → history.reload)、`onSubPageOpened(pageId)`(场景库 → scene.reload)、`onSubPageClosing(pageId)`(内容分析 AI → settings.saveSecondAiSettings)。`onBackPressed` 委托 `navigator.handleBack(): Boolean`。
3. 步骤 1–5 里所有暂时指向 MainActivity 方法的导航 lambda,改指 navigator。
4. 收尾核对 MainActivity:应只剩 §3 列的内容,约 250–300 行;所有 `// ---------- xxx ----------` 分节应已搬空。

### 最终验证(步骤 6 完成后)

```bash
cd android
./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
git diff --check
```

全绿后按 §5 交付。若有 Android 设备/模拟器可用,再人工过一遍冒烟:切四个 tab、进出场景库与历史详情、旋转屏幕后各页状态保留、(可选)启动同传看运行态渲染。

---

## 5. 交付要求

1. `docs/04-dev-log.md` 顶部追加一条(中文):改动(MainActivity 拆分为 ui/ 包七个协作类)、原因(为后续演进减债,行为等价)、版本(不变)、真实验证结果(测试/Lint/assembleDebug 输出摘要)。
2. 提交历史:每步一个 commit(共 6 个左右)+ 文档 commit,提交说明中文。
3. 推送:`git push -u origin claude/saas-architecture-review-bkde2s`(网络失败按 2s/4s/8s/16s 退避重试,最多 4 次)。
4. 不创建 Pull Request,除非用户明确要求。

---

## 6. 测试改造对照表(汇总)

| 测试用例 | 现状(反射) | 改为 |
|---|---|---|
| `renderStatus(activity)` 辅助函数 | 反射调私有 `renderStatus` | `activity.renderStatus()`(internal,委托实现) |
| `refreshHomeScenes(...)` 辅助函数 | 反射调私有 `setupHomeSceneChips` | 对应模式 controller 的 `internal fun refreshSceneChips()` |
| `captureIntentCarriesFrozenCredentialMode` | 反射设 `pendingCredentialMode` 字段 + 反射调 `captureStartIntent` | `activity.sessionCoordinator` 的 internal 成员直调 |
| `friendBindingDisablesAllMutableControls` | 反射调 `renderFriendGatewayUi(null, true)` | `activity.settingsController.renderFriendGatewayUi(null, bindingInProgress = true)` |
| `videoSceneLibrarySurvivesRecreateAndReturnsToVideoHome` | 调 `activity.openSceneLibrary`(已是 internal) | 不变(方法保留,内部委托) |
| 其余用例 | 只走 findViewById / performClick | 不变 |

---

## 7. 未来演变(这次重构在整体路线中的位置)

SaaS 化评估给出的路线,本次重构属于"阶段 0 的顺手投资":

- **阶段 0(现在)**:保持纯本地 App 形态,按 CLAUDE.md 边界继续开发。本次拆分的意义:未来加任何新页面(登录、订阅、同步设置)都是"新增一个 controller + 一个子页",不再膨胀 MainActivity。
- **阶段 1(决定做 SaaS 那天,后端先行)**:把 `server/friend_gateway` 的协议设计(WebSocket 代理 + ECDSA 设备绑定 + 请求签名)升级成正式网关:Postgres、无状态可水平扩展、账号 + 订阅 + 用量计量。客户端 `ApiCredentialMode.BEARER_TOKEN` 路径基本复用,改动集中在登录/绑定 UI。同时必须正式修订 CLAUDE.md 产品边界 10(账号体系是独立决策,不能顺着邀请码滑过去)。
- **阶段 2(客户端跟进)**:实时管线四件套(`CaptureService` / `PcmProcessor` / `GeminiLiveClient` / `SubtitleStabilizer`)原样保留;UI 层在本次拆分的基础上按需继续演进。
- **本次刻意不做、留给后续的独立小改动**:`analyzeSessionContext` 的线程实现改为 ViewModel(参照 `FriendGatewayBindingViewModel` 模式,解决旋转丢进行中状态);历史云同步;多 Key 池管理 UI。

其他前置提醒(做 SaaS 前必须先想清楚,与代码无关):Gemini Live 实时音频的成本模型与计量粒度;商店分发的录音 / MediaProjection / AudioPlaybackCapture 合规审查。
