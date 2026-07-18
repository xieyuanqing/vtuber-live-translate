# 07 · MainActivity 拆分重构施工文档

> **锁定方式**：以本文件所在提交中的内容为施工真源；分支为 `claude/saas-architecture-review-bkde2s`，修订起点为 `90bf642`。不要按修订起点读取旧版文档。
>
> **状态**：待执行。执行者必须按步骤顺序施工；每一步都要独立编译、独立测试、独立提交。

## 1. 目标、现状与边界

### 1.1 本次目标

把大型 `MainActivity.kt` 拆为普通 Kotlin controller，使 `MainActivity` 最终只负责：

- Activity 生命周期与 `savedInstanceState` 汇总；
- 无条件、固定顺序注册 `ActivityResultLauncher`；
- 创建 `ViewModel`、视图持有对象和 controllers；
- 按本文规定的两阶段协议完成 controller、navigator 与回调接线；
- 启停 300 ms UI 刷新循环，并把一次生成的不可变状态快照分发给页面；
- 把 Android 系统能力包装成窄 host 回调。

重构只改变代码归属，不改变用户可见行为、Store 语义、Service 协议或实时翻译管线。

### 1.2 当前产品边界

本项目当前仍是**纯本地、个人 Key 优先**的 Android App：

- 用户自己的 Gemini API Key 和自定义 Base URL 是核心路径，不部署任何后端也必须完整可用；
- 好友邀请网关只是可选扩展，用邀请码和设备凭据分享持有者的 Key，不是账号系统；
- 主导航固定为“同传 / 视频 / 历史 / 设置”；
- 同传与视频的场景库、草稿、语言和本场上下文严格隔离；
- 启动前冻结 Prompt、语言、场景和本场上下文，权限回调不能重新读取当前草稿；
- 场景库是唯一可复用配置层，不恢复旧方案库、术语库或方案级额外提示词。

### 1.3 本次明确不做

本次不得借重构提前实现或伪造：

- 注册、登录、用户资料、账号切换；
- 订阅、套餐、账单、支付、权益页面；
- 云同步、历史上传、多端同步；
- 假 provider、假账号数据、未接后端的占位开关；
- Fragment、Compose、DI 框架、事件总线；
- `activity_main.xml` 或任何现有 View ID 的改造；
- Store、`CaptureService`、`GeminiLiveClient`、`SubtitleStabilizer` 的顺手重构。

未来正式 SaaS 只在第 8 节定义扩展缝，本次不创建这些未来实现类。

## 2. 必须保持的行为不变量

1. `activity_main.xml` 继续静态包含四个主页面和现有设置子页；不改 `res/layout/`。
2. 语言下拉仍显式执行 `setOnClickListener { showDropDown() }`，不能依赖不存在的下拉委托。
3. 空闲态与运行态显隐继续作用于完整根容器：
   - `interpIdleContent` / `interpRunningContent`；
   - `videoIdleContent` / `videoRunningContent`。
4. `showPage` 内不得设置 `bottomNav.selectedItemId`，避免选中回调无限重入。
5. `startCapture` 的 `reusePendingSnapshot` 判定语义不变；复用时不得再次执行 `prepareSessionSettings`。
6. `consumeStartedSession` 时机不变：
   - 同传：`startForegroundService` 成功发起后立即消费；
   - 视频：MediaProjection 返回成功并发起前台服务后消费；
   - 权限拒绝或投屏取消不得错误消费。
7. 普通 controller 不得持有完整 `MainActivity` 作为 service locator。只注入 `Context`、自己的 Views、窄能力接口或回调。
8. 每个 View 只有一个绑定真源。字段移入 controller 后，同一步删除 `MainActivity` 中对应别名及全部直接访问。
9. `FriendGatewayBindingViewModel` 仍由 Activity 创建和观察；`SettingsController` 只接收 `bind`、`clear`、`isBinding` 三个动作，不获取完整 ViewModel 或 LifecycleOwner。
10. 现有测试断言不得删除或弱化。反射可改为窄 `internal` 测试入口，但“改可见性”不能替代新增的生命周期与接线测试。

## 3. 目标文件与职责

新代码位于 `android/app/src/main/java/com/xyq/livetranslate/ui/`：

| 文件 | 唯一职责 |
|---|---|
| `MainNavigator.kt` | 四个主页面、设置子页、toolbar、bottom navigation、window insets、导航 saved state |
| `ModeHomePages.kt` | `ModeHomeViews`、两个模式主页的 chips、语言、方案摘要、运行态渲染和主页动作 |
| `SessionCoordinator.kt` | 启停、权限与投屏结果、Activity 内容/模式快照、Service Intent 组装 |
| `SessionContextController.kt` | 两个模式的本场上下文、视频 URL、AI 解析、requestId 守卫、上下文 saved state |
| `HistoryController.kt` | 历史列表、筛选、搜索、详情与复制 |
| `SceneLibraryController.kt` | 场景库模式、列表、编辑、新建、恢复模板与 saved state |
| `SettingsController.kt` | 设置入口、个人 Key/Base URL、好友网关 UI、字幕与参数、二号 AI、诊断、关于、电池白名单 |

允许在同一文件中定义只服务于该页面的 `Views`、不可变状态和窄接口；不要为每个三行接口再拆文件。

## 4. 依赖、所有权与构造协议

### 4.1 页面 controller 的依赖规则

页面 controller 的构造参数仅可来自以下集合：

- `Context` 或 `applicationContext`：调用现有 Store、读取资源；
- 该页面唯一的 `Views` 持有对象；
- 窄 host，例如 `toast(String)`、`launchIntent(Intent)`、`postToUi(Runnable)`；
- 明确的动作接口或 lambda，例如 `openSceneLibrary(mode, returnTabId)`；
- 不可变渲染状态。

禁止以下依赖：

- `(activity: MainActivity)`；
- controller 自行经 Activity 访问其他 controller 或 private 字段；
- controller 之间互相持有；
- 重新从 `StatusBus` 读取未包含在渲染快照里的字段。

### 4.2 `SettingsController` 的好友绑定依赖

使用窄动作对象，语义等价于：

```kotlin
internal data class FriendGatewayBindingActions(
    val bind: (code: String, version: String, enableOnSuccess: Boolean) -> Unit,
    val clear: () -> Unit,
    val isBinding: () -> Boolean,
)
```

Activity 用 `friendBindingViewModel.bind(...)`、`clearBinding()`、`isBinding()` 构造该对象。状态观察仍写成 Activity 的 lifecycle observer，并只调用 `settingsController.renderFriendBindingState(state)`。

### 4.3 `SessionCoordinator` 的窄 host

`SessionCoordinator` 不接收 Activity。至少注入以下能力，名称可按 Kotlin 风格微调，但不得退化为一个万能 `activity`：

```kotlin
internal interface SessionHost {
    fun requestPermissions(permissions: Array<String>)
    fun launchProjection(intent: Intent)
    fun startForegroundService(intent: Intent)
    fun startService(intent: Intent)
    fun checkPermission(permission: String): Boolean
    fun canDrawOverlays(): Boolean
    fun createProjectionIntent(): Intent
    fun openSystemSettings(intent: Intent)
    fun openTranslationSettings()
    fun toast(message: String)
}
```

`openTranslationSettings()` 是窄导航动作，完成“切到设置 tab 并打开翻译服务子页”。不得让 coordinator 操作 `bottomNav`、launcher 或 navigator 字段。

权限流必须同时覆盖两端：

- 发起端：`requestPermissions(...)` 和 `launchProjection(...)`；
- 结果端：Activity launcher 回调转发给 `onAudioPermissionResult(...)` / `onProjectionResult(...)`。

只迁移结果回调、漏掉 `permLauncher.launch(...)`，该步骤不算闭合。

### 4.4 两阶段 wiring 与最终构造顺序

为避免 controller ↔ navigator 构造环，最终 `onCreate` 严格按以下顺序：

1. ActivityResult launchers 作为 Activity 字段无条件注册，注册顺序不随页面状态变化；回调只转发给已创建的 coordinator。
2. 恢复原始 saved-state 值，执行既有迁移，`setContentView`。
3. 创建 `FriendGatewayBindingViewModel`。
4. 绑定 shell Views 和每页 Views；每个 ID 只绑定一次。
5. **第一阶段**：创建页面 controllers 和 `SessionCoordinator`；构造函数只保存依赖或绑定本地 listener，不执行跨组件导航。
6. 创建 `MainNavigator`，其 hooks 指向已经初始化的 controllers。
7. **第二阶段**：统一调用 `bindCallbacks(...)` / `attachNavigator(...)` / `bindSessionContextAccess(...)`，安装跨组件回调。
8. 注册 `friendBindingViewModel.state.observe(...)`。LiveData 可能同步回放，所以必须晚于 `settingsController` 创建。
9. 启动首次状态渲染。
10. 最后恢复 `bottomNav.selectedItemId` 和设置子页。任何可能触发导航 hook 的恢复动作都不得早于第 7 步。

不得靠捕获未初始化的 `lateinit navigator` 并假设 listener “暂时不会触发”。

### 4.5 `ModeHomeViews` 单一绑定规则

步骤 5 创建后：

- 每个模式各有且仅有一个 `ModeHomeViews` 实例；
- `ModeHomeController` 和 `SessionContextController` 共享这两个实例；
- `SessionContextController` 不得再次 `findViewById`；
- `MainActivity` 删除对应主页 View 字段，不能保留第二套别名；
- `setupFinalPlanUi`、开始/停止按钮、overlay 权限入口和上下文按钮都必须在步骤 5 闭合，不能留到步骤 6。

## 5. 两类启动快照必须明确区分

### 5.1 Activity：内容/模式快照

在权限请求前由 `prepareSessionSettings` 一次性创建不可变 `PendingSessionSnapshot`，至少包含：

- capture mode；
- 完整 Prompt；
- source / target language code；
- scene ID / scene label；
- session title / context；
- 用户当时选择的凭据模式（个人或好友）。

这是**内容快照 + 模式选择快照**，不是完整访问凭据。它必须能写入/恢复自 `savedInstanceState`；权限回调和 MediaProjection 回调只消费该对象。

### 5.2 Service：访问/运行参数快照

`CaptureService.start(intent)` 在真正启动时，根据 Activity 传来的模式选择，再从现有 Store 解析一次并冻结：

- API Key 列表或好友设备 token；
- Base URL；
- `ApiCredentialMode`；
- device ID / 请求签名能力；
- echo、rotate、stabilizer idle、max chars 等运行参数。

这是**访问快照 + 运行参数快照**。现状不应把 API Key/token 塞进 Activity saved state 或普通 Intent。Service 启动后的重连继续使用该次解析结果，不在每次重连重新读取设置。

因此测试和文档不得把 `pendingCredentialMode` 或 `EXTRA_CREDENTIAL_MODE` 单独称为“完整启动快照”。未来若正式 SaaS 采用短期 access token，还必须单独设计 refresh、撤销和重连语义，不能假设本次 UI 重构已经解决。

## 6. 不可变 UI 状态模型

步骤 5 必须用一次采集、不可变分发替换 controller 对 `StatusBus` 的零散读取。

### 6.1 `UiRuntimeStatus`

`UiRuntimeStatus` 至少完整复制以下页面可见字段：

- `serviceRunning`、`captureMode`、`connState`、`paused`；
- `audioLevelPct`、`overlayAllowed`；
- `currentKeyLabel`、`transcriptPath`；
- session 的 `startedAtMs`、source/target language、scene ID/label；
- `confirmedTranslations.toList()`、`currentTranslation`、`sourceTail`；
- 由同一次采集算出的 active status text/color。

不得把可变 `StatusBus`、`Atomic*` 或可变列表直接放进对象。

### 6.2 `DiagnosticsState`

`DiagnosticsState` 至少包含：

- `serviceRunning`、`captureMode`、`connState`；
- `currentKeyLabel`、`audioLevelPct`；
- `chunksSent` 的当次数值；
- `transcriptPath`、`jaTail`、`zhTail`。

每个 300 ms tick 只读取 `StatusBus` 一次，产出同一时刻的 `UiRuntimeStatus` 与 `DiagnosticsState`，再分别传给两个 `ModeHomeController` 和 `SettingsController`。诊断页不得重新读取全局状态。

## 7. 分步施工

总规则：每一步完成代码、迁移/新增测试、运行验证、提交后，才进入下一步。每一步结束时工作树都必须是可编译、可测试状态，不允许用“下一步会补上”解释当前编译错误。

每步最低验证命令：

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
cd ..
git diff --check
```

### 步骤 1：抽出 `HistoryController`

**修改文件**：

- 新建 `ui/HistoryController.kt`；
- 修改 `MainActivity.kt`；
- 按需修改 `MainActivityStartupTest.kt`，不得删现有断言。

**同一步完成的所有权闭包**：

- 迁移历史搜索、筛选、列表、空态、详情、复制相关 Views 和状态；
- 迁移 `setupHistoryPage`、`reloadHistory`、`renderHistoryList`、`showHistoryDetail`；
- controller 只接收 `Context`、`HistoryViews`、`openDetailPage` 和 toast/copy 等窄动作；
- `MainActivity.showPage` 暂时改调 `historyController.reload()`；
- 删除 Activity 中已迁移字段、方法和全部直接访问。

**测试**：

- 保留启动时历史页关键 ID 绑定断言；
- 增加历史 tab 切入会触发 reload、详情会请求正确 return tab 的行为测试；
- 运行本步最低验证命令。

**提交**：`refactor: 抽出历史页面控制器`

### 步骤 2：抽出 `SceneLibraryController`

**修改文件**：

- 新建 `ui/SceneLibraryController.kt`；
- 修改 `MainActivity.kt`；
- 修改/补充 `MainActivityStartupTest.kt`。

**同一步完成的所有权闭包**：

- 迁移场景库 mode toggle、列表、恢复、新建按钮和 `sceneLibraryMode`；
- 迁移 `setupSceneLibraryPage`、`reloadSceneLibrary`、`buildSceneCard`、`showSceneEditor`；
- controller 负责 `STATE_SCENE_LIBRARY_MODE` 的读写；
- 保留 `MainActivity.internal fun openSceneLibrary(...)` 作为窄测试/兼容入口，但内部只委托 controller 设 mode 并请求打开页面；
- `openSettingsSub(pageSceneLibrary)` 的临时分支改调 `sceneLibraryController.reload()`；设置首页的场景库入口改调 `openSceneLibrary(TranslationMode.INTERPRETATION)`，不得继续直接读写 `sceneLibraryMode`；
- `onSceneChanged(mode)` 暂时回调 Activity 仍存在的 `refreshSceneDependents`；
- 删除 Activity 中已迁移字段、方法和直接 View 访问。

**测试**：

- `sceneLibrarySettingsEntryOpensSeededLibrary` 保持等价；
- `homeCardOpensUnifiedSceneLibrary` 保持等价；
- `videoSceneLibrarySurvivesRecreateAndReturnsToVideoHome` 保持等价，验证 mode、子页和 return tab；
- 运行本步最低验证命令。

**提交**：`refactor: 抽出场景库控制器`

### 步骤 3：抽出 `SettingsController`，立即闭合启动设置读取

**修改文件**：

- 新建 `ui/SettingsController.kt`；
- 修改 `MainActivity.kt`；
- 修改/补充 `MainActivityStartupTest.kt`。

**同一步完成的所有权闭包**：

- 迁移六个设置入口、`etApiKeys`、`etBaseUrl`、好友网关 Views、字幕样式、二号 AI、参数滑杆、reset、诊断、关于、电池白名单相关 Views；
- 迁移 `setupSettings`、好友网关 UI、样式、参数（二个模式语言控件除外）、二号 AI 保存、关于和电池设置逻辑；
- 注入第 4.2 节的 `bind/clear/isBinding`，不得直接获取 `FriendGatewayBindingViewModel`；
- Activity 在 controller 构造完成后再注册 observer；
- `onPause` 和关闭“内容分析 AI”子页时改调 `settingsController.persistSecondAiInputs()`；
- 提供 `persistDraftInputs()`，一次保存个人 Key、Base URL 和二号 AI 输入；
- **本步骤内立即把仍在 Activity 的 `prepareSessionSettings` 改为调用 `settingsController.persistDraftInputs()`**。迁走 `etApiKeys` / `etBaseUrl` / `saveSecondAiSettings` 后不得留下旧引用；
- `btnResetTranslate` 通过临时 `onTranslateParamsReset` 回调刷新仍在 Activity 的语言控件；步骤 5 再换成两个 mode controller；
- 诊断暂时接收显式参数对象，不直接读 `StatusBus`，步骤 5 换为完整 `DiagnosticsState`。

**测试**：

- `friendBindingDisablesAllMutableControls` 改为 controller 的窄 `internal` 渲染入口，保留全部控件断言；
- 增加 LiveData 已有状态同步回放时不访问未初始化 controller 的 recreate 测试；
- 增加 `persistDraftInputs` 后 `prepareSessionSettings` 仍能保存个人 Key/Base URL 的测试；
- 运行本步最低验证命令。

**提交**：`refactor: 抽出设置页面控制器`

### 步骤 4：抽出 `SessionCoordinator`，闭合权限发起与结果

**修改文件**：

- 新建 `ui/SessionCoordinator.kt`；
- 修改 `MainActivity.kt`；
- 修改 `MainActivityStartupTest.kt`；
- 新建或补充 `SessionCoordinatorTest.kt`。

**同一步完成的所有权闭包**：

- 用不可变 `PendingSessionSnapshot?` 替代分散的 `pendingSession*` 字段；
- 迁移 `pendingStartMode`、`permRequested`、`onModeToggle`、`prepareSessionSettings`、`captureStartIntent`、`startCapture`、`consumeStartedSession`、`promptMode`、Prompt 组装；
- coordinator 负责所有 pending snapshot saved-state key；
- 注入第 4.3 节全部 host 能力，特别是 `requestPermissions`、`launchProjection`、`startForegroundService`、`startService`、`checkPermission`、`openSystemSettings` 和导航；
- 两个 launchers 仍由 Activity 无条件注册，结果回调只转发；
- 四个开始/停止按钮在本步骤先改为委托 `sessionCoordinator.onModeToggle(mode)`，不得继续引用已经迁走的 Activity 方法；步骤 5 再把四个 listener 的所有权整体迁入两个 mode controller；
- 本步骤仍可通过 Activity 的临时 `SessionContextAccess` lambda 读取/清空主页上下文；步骤 5 替换成 `SessionContextController`，但本步骤自身必须编译；
- `SettingsController.persistDraftInputs()` 是启动设置保存的唯一入口；
- 个人 Key 缺失或好友凭据失效时经 `openTranslationSettings()` 导航，不直接访问 bottomNav；
- 保留 `reusePendingSnapshot`、拒绝路径和两个消费时机。

**测试**：

- 音频权限缺失时断言实际调用 `requestPermissions`，并按 API 等级验证通知权限集合；
- 权限回调继续使用冻结快照，不重新读取已修改的 Prompt、语言、场景或凭据模式；
- Activity recreate 后恢复 pending snapshot，权限同意只启动/消费一次；
- MediaProjection recreate 后成功只启动/消费一次；
- 录音拒绝和投屏取消不启动、不消费、不拿当前草稿覆盖快照；
- `captureIntentCarriesFrozenCredentialMode` 改走 coordinator 的窄测试入口，但扩展为断言全部内容/模式字段，而不只断言 credential mode；
- 运行本步最低验证命令。

**提交**：`refactor: 抽出会话启动协调器`

### 步骤 5：抽出主页与本场上下文，建立单一 View 真源

**修改文件**：

- 新建 `ui/ModeHomePages.kt`；
- 新建 `ui/SessionContextController.kt`；
- 修改 `MainActivity.kt`、`SessionCoordinator.kt`、`SettingsController.kt`、`SceneLibraryController.kt`；
- 修改/补充 `MainActivityStartupTest.kt` 和 coordinator 测试。

**同一步完成的所有权闭包**：

1. 每个模式创建唯一 `ModeHomeViews`，完整覆盖第 7.1 节 ID；不对称 View 用可空字段表达。
2. 两个 `ModeHomeController` 迁移：
   - `setupHomeSceneChips`；
   - `bindModeLanguageControls` / `renderModeLanguageControls` / store 同步；
   - `setupFinalPlanUi` 的卡片、打开场景库按钮、初次 chips 和摘要；
   - `updatePlanSummary`、确认行渲染和 elapsed 格式化；
   - 空闲/运行根容器与运行态状态渲染。
3. 开始/停止四个按钮全部在 mode controller 中绑定到 coordinator 的窄 toggle 动作。
4. overlay 权限行和设置按钮全部在 video mode controller 中绑定到 `openSystemSettings`；状态也由该 controller 渲染。
5. 同一步创建 `SessionContextController`，共享两个 `ModeHomeViews`：
   - 迁移 `setupSessionContextUi`、`analyzeSessionContext`、两个 requestId；
   - 迁移 `STATE_INTERPRETATION_CONTEXT`、`STATE_VIDEO_CONTEXT`、`STATE_VIDEO_URL`；
   - 保持现有后台线程、UI 回投时点和 requestId 防陈旧回写语义；UI 回投使用注入的窄 `postToUi`，不为此持有 Activity，也不改成协程/ViewModel；
   - 实现 coordinator 所需的上下文读取和成功后清空接口。
6. 调用 `sessionCoordinator.bindSessionContextAccess(sessionContextController)` 完成第二阶段接线；Activity 中不再保留上下文 View 别名。
7. `SceneLibraryController.onSceneChanged(mode)` 精确刷新对应 mode 的 chips 和摘要。
8. `SettingsController` 的 translate reset 回调精确刷新两个 mode controller 的语言、摘要和参数状态。
9. 引入第 6 节完整不可变 `UiRuntimeStatus` / `DiagnosticsState`；一次采集后分发，所有字段齐全。

**测试**：

- 保留 `languageDropdownsOpenOnTap`；
- 保留场景 chips 始终单选、删除回退和恢复测试；
- 保留 idle/mic/video 三种根容器显隐测试；
- 新增四个开始/停止按钮都调用正确 mode 的接线测试；
- 新增 plan 卡片和两个“打开场景库”按钮 return tab 正确的测试；
- 新增 overlay 行与按钮都打开正确系统设置的测试；
- 新增同一模式 Views 只绑定一次、context controller 与 mode controller 使用同一实例的测试或构造断言；
- 新增状态快照保留 `currentKeyLabel`、`transcriptPath`、`chunksSent`、`jaTail`、`zhTail` 的测试；
- 新增上下文 AI 旧 requestId 不能覆盖新请求状态的既有语义测试；
- 运行本步最低验证命令。

**提交**：`refactor: 抽出模式主页与本场上下文`

### 步骤 6：抽出 `MainNavigator`，完成两阶段接线和恢复顺序

**修改文件**：

- 新建 `ui/MainNavigator.kt`；
- 修改 `MainActivity.kt` 和各 controller 的临时导航回调；
- 修改/补充 `MainActivityStartupTest.kt`。

**同一步完成的所有权闭包**：

- 迁移 shell Views、`showPage`、`openSettingsSub`、`closeSettingsSub`、bottom nav、toolbar、window insets；
- 迁移 `currentMainTabId`、`settingsSubId`、`settingsReturnTabId` 和对应 saved state；
- hooks 明确为：
  - 主历史 tab 显示时 `historyController.reload()`；
  - 场景库子页打开时 `sceneLibraryController.reload()`；
  - 内容分析 AI 子页关闭前 `settingsController.persistSecondAiInputs()`；
- 所有步骤 1–5 的临时 Activity 导航 lambda 改为 navigator 窄方法；
- 严格执行第 4.4 节构造顺序，导航恢复放最后；
- `onBackPressed` 先交给 `navigator.handleBack()`，未消费再调用 Activity 默认行为；
- Activity 只保留生命周期、launchers、ViewModel、controllers、wiring、状态采集/分发和窄 `internal` 测试入口。

**测试**：

- 启动 smoke 不只断言“不崩溃”，还要断言四个主页面、所有现有设置子页和关键 View 已绑定；
- 默认启动、指定主 tab 恢复、设置子页恢复、历史详情 return tab、视频场景库 return tab 全覆盖；
- 构造期间的 LiveData 同步回放和首次导航恢复都不得访问未初始化 controller/navigator；
- bottom nav 恢复不无限重入；
- 返回键先关闭子页，再遵循 Activity 默认行为；
- 全量运行本步最低验证命令。

**提交**：`refactor: 抽出主导航并完成页面接线`

### 7.1 `ModeHomeViews` ID 清单

| 语义 | 同传 | 视频 |
|---|---|---|
| 空闲/运行根 | `interpIdleContent` / `interpRunningContent` | `videoIdleContent` / `videoRunningContent` |
| 主页场景 chips | `chipGroupInterpHomeScenes` | `chipGroupVideoHomeScenes` |
| 运行状态/圆点 | `tvInterpRunningStatus` / `viewInterpRunningStatusDot` | `tvHeroStatus` / `viewVideoRunningStatusDot` |
| 运行副标题 | 无 | `tvHeroSubStatus` |
| elapsed/meta | `tvInterpElapsed` / `tvInterpRunningMeta` | `tvVideoElapsed` / `tvVideoRunningMeta` |
| 确认行容器 | `interpConfirmedList` | `videoConfirmedList` |
| 音量文字/进度 | `tvInterpAudioLevel` / `pbInterpAudio` | `tvAudioLevel` / `pbAudio` |
| 开始/停止 | `btnInterpToggle` / `btnInterpStop` | `btnToggle` / `btnVideoStop` |
| 目标语言标签 | `tvInterpTargetLanguageLabel` | `tvVideoTargetLanguageLabel` |
| 当前译文/原文 | `tvInterpZh` / `tvInterpJa` | `tvLiveZh` / `tvLiveJa` |
| 记录路径 | `tvInterpTranscriptPath` | `tvTranscriptPath` |
| 语言下拉 | `acInterpSourceLang` / `acInterpTargetLang` | `acVideoSourceLang` / `acVideoTargetLang` |
| 方案卡/摘要/profile | `cardInterpPlan` / `tvInterpPlanSummary` / `tvInterpProfile` | `cardVideoPlan` / `tvVideoPlanSummary` / `tvCurrentProfile` |
| 打开场景库 | `btnInterpOpenPlanLibrary` | `btnVideoOpenPlanLibrary` |
| 本场上下文 | `etInterpSessionContext` | `etVideoSessionContext` |
| AI 按钮/状态 | `btnInterpAnalyzeContext` / `tvInterpAnalyzeStatus` | `btnVideoAnalyzeContext` / `tvVideoAnalyzeStatus` |
| 模式独有 | `tvInterpStatus` / `tvInterpSubStatus` | `rowOverlayPermission` / `viewOverlayPermissionDot` / `tvOverlayPermissionStatus` / `btnOverlayPermissionSettings` / `etVideoSessionUrl` |

## 8. 未来正式 SaaS 的扩展缝（仅定义边界，本次不实现）

本节不改变当前“个人 Key + 可选好友网关”的产品定位，只记录未来若另行决策做正式 SaaS 时必须存在的窄接口。

### 8.1 账号、凭据与权益访问层

未来应在实时与文本分析两条付费调用链之前，共用一个访问决策边界，输入用途、当前产品后端选择和会话上下文，输出：

- 是否允许访问及可展示的失败原因；
- endpoint / wire protocol 选择；
- 凭据提供与生命周期策略；
- 必需的签名或设备证明；
- reconnect 时刷新还是终止的明确语义。

`ApiCredentialMode` 当前只决定凭据如何传输（query API key 或 Bearer），**不是后端抽象**，也不描述账号、权益、协议、token refresh 或撤销。只有正式网关继续兼容当前 Gemini wire protocol 且保留兼容凭据生命周期时，实时管线才可能大比例复用。

本次不新增 `ACCOUNT` 枚举、不创建空实现、不让 UI 假装支持登录。

### 8.2 页面注册与导航 hook

未来新增账号或权益页面时，应通过显式 page registration / navigation hook 接入 `MainNavigator`，并定义：

- 页面 ID、标题、返回 tab；
- 打开、关闭和恢复 hooks；
- saved-state 与深链语义；
- 对应静态 layout/View ID 的真实实现。

controller 拆分只是可维护性基础，不代表“未来只加一个 controller 就完成 SaaS”。在当前静态 View/XML 架构下，正式页面仍需布局、导航注册、状态和后端能力同时落地。

### 8.3 多实例网关的共享租约与限流

当前好友网关的 `active_live` 与 `BindLimiter` 是进程内状态。把 SQLite 换成 Postgres **不等于**无状态水平扩展。未来多实例至少需要：

- 按 account/tenant/device 维度的共享实时会话租约；
- acquire / renew / release、TTL、故障回收和 fencing token；
- 跨实例共享绑定/IP/账号限流；
- 撤销和额度决策的一致可见性。

WebSocket 连接本身可留在单实例内，但影响全局资格、并发与额度的状态必须外置。负载均衡粘性不能替代正确租约。

### 8.4 可审计计量，不把 usage 当账单真源

现有 `usage_daily` 适合好友测试防滥用，不能直接作为订阅账单真源。正式计量边界至少要定义：

- account / tenant / subscription 归属；
- 幂等 event ID 与不可变 usage ledger；
- 请求前预留、成功、失败、取消、退款和重放语义；
- 音频时长/字节、上下游 token、供应商成本等真实计量维度；
- 与供应商账单的对账和审计。

限额计数、活跃会话租约和可计费账本是三个不同概念，不得复用一个 `usage` 表混为一谈。

## 9. 测试与最终验收矩阵

除每步测试外，步骤 6 后必须确认以下矩阵全部有自动化覆盖：

| 风险 | 必须证明 |
|---|---|
| 启动绑定 | 四个主页面、现有子页、关键 View 全部绑定；初次 render 不崩溃 |
| 构造顺序 | controller 创建后才 observe，全部 wiring 后才恢复导航 |
| 权限 recreate | 冻结 Prompt/语言/场景/上下文/凭据模式，不读取新草稿，只消费一次 |
| Projection recreate | 成功使用原快照启动一次；取消不启动、不消费 |
| 设置闭包 | Key/Base URL/二号 AI 经 `SettingsController` 保存，Activity 无旧 View 引用 |
| 主页接线 | 四个开始/停止按钮、四个场景库入口、两个 overlay 入口全部可达 |
| View 所有权 | `ModeHomeViews` 每模式单实例，context 不重复绑定 |
| 状态完整性 | Key 标签、记录路径、chunks、ja/zh tail 均保留，列表已复制 |
| 导航恢复 | 四个 tab、所有可恢复子页、return tab、返回键语义不变 |
| Activity 内容/模式快照 | Service Intent 携带冻结内容和模式，不含明文 Key/token |
| Service 访问/运行快照 | Service 启动时解析一次 access/runtime 参数，重连沿用；与 Activity 快照测试分开 |

反射迁移约定：

- `renderStatus(activity)` 可改为 `activity.renderStatusForTest()`，内部只触发一次真实采集与分发；
- `refreshHomeScenes(...)` 改为对应 mode controller 的窄 `internal` 入口；
- pending snapshot 不公开可变字段，优先使用构造 fixture 或只读测试入口；
- `friendBindingDisablesAllMutableControls` 直调 settings controller 渲染入口；
- `openSceneLibrary(...)` 可保留 Activity 的 `internal` 委托入口；
- 不得用 `internal var pending...` 暴露生产状态来代替行为测试。

## 10. 最终交付

步骤 6 提交后运行：

```bash
cd android
./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
cd ..
git diff --check
git status --short
```

然后：

1. 在 `docs/04-dev-log.md` 顶部写入真实改动、原因、版本不变和实际验证结果；
2. 确认实现阶段每一步都有独立 commit，且任一 commit checkout 后都能通过该步最低验证；
3. 确认没有布局、版本号、账号/订阅假功能或实时管线越界改动；
4. 有设备/模拟器时补做人工冒烟：四 tab、场景库、历史详情、设置子页、旋转恢复、权限拒绝、同传启动、视频投屏取消和 overlay 设置入口。

除非用户明确要求，不创建 Pull Request，不把未来 SaaS 扩展缝实现进本次 UI 重构。
