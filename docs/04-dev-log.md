# 开发日志

倒序排列，最新在上。每完成一步（或踩一个值得记的坑）加一条。

---

## 2026-07-16 · v2.0.5 术语库与临时上下文彻底移除

**目标**：只保留「方案」心智。

**变更**：
- 设置里删除术语库入口；移除 `page_settings_scenes`
- 方案编辑 BottomSheet 只保留：方案名称 / 场景预设 / 自定义场景 / 方案提示词
- 去掉临时本场上下文、视频链接分析、术语库绑定 UI
- 提示词组装只读方案字段（场景 + 方案提示词）
- 方案库卡片不再展示术语库绑定
- 启动链路仍兼容旧 Intent 字段，但 glossary/context 固定为空

**验证**：compileDebugKotlin / testDebugUnitTest / lintDebug / assembleDebug 全过。

---

## 2026-07-16 · v2.0.4 修复启动闪退

**现象**：安装 2.0.3 后打开 App 直接闪退。

**根因**：同传/视频主页语言胶囊使用
`TextInputLayout.OutlinedBox.Dense.ExposedDropdownMenu` + `app:boxBackgroundMode="none"`。
Material 的 ExposedDropdownMenu 会强制 `endIconMode=dropdown`，而 dropdown end icon 不支持 `boxBackgroundMode=none`，
inflate 时抛 `IllegalStateException: The current box background mode 0 is not supported by the end icon mode 3`，
在 `MainActivity.setContentView` 阶段崩溃。

**修复**：语言胶囊改为普通 Dense 样式 + `app:endIconMode="none"`，保留透明胶囊外观与 AutoComplete 绑定。

**回归**：新增 Robolectric `MainActivityStartupTest` 覆盖启动 inflate；本地复现失败后修复通过。

**版本**：versionCode 25 / versionName 2.0.4。

---

## 2026-07-16 · v2.0.3 第二期：方案库列表页

按 `docs/06-ui-polish-plan.md` 第二期落地独立「方案库」页。

**页面**

- 新增 `page_plan_library.xml`：同传/视频 segmented 切换、方案卡片列表、FAB 新建
- 新增 `item_saved_plan.xml`：图标字 + 名称 + 场景/语言/术语标签 + 应用/编辑/删除
- 设置首页新增「方案库」入口；同传/视频方案卡增加「方案库」快捷入口

**交互**

- 应用：写入对应模式 draft，刷新主页摘要与语言控件
- 编辑：先 apply 再打开现有 BottomSheet 编辑器
- 新建：打开当前分段对应模式的 BottomSheet
- 删除：`TranslationPlanStore.deleteSavedPlan`
- 从主页进入方案库后返回原 Tab（同传/视频），从设置进入返回设置

**版本**：versionCode 24 / versionName 2.0.3。

**真实验证**：`:app:compileDebugKotlin` + `:app:testDebugUnitTest` + `:app:lintDebug` + `:app:assembleDebug` 通过。

---

## 2026-07-16 · v2.0.2 主页 UI 整理：方案合并呈现 + iOS 风格

用户确认：场景与术语库在 UI 上合并成「方案」心智；保留功能，主页先向 `ui.zip` 的 iOS 工具感靠拢。

**信息架构**

- 新增 `docs/06-ui-polish-plan.md`：方案 = 场景 + 语言 + 可选术语库；本场背景仍临时
- 主页「当前方案」摘要展示：`场景 · 语言 · 术语库（如有）`
- 术语库仍在设置维护，日常入口是方案卡 / 弹窗

**同传 / 视频主页**

- 语言选择改为胶囊条（保留原下拉绑定与草稿读写）
- 同传：大圆主按钮置中，状态改为胶囊标签，音量条收在下方
- 视频：状态胶囊 + 全宽圆角主按钮
- 方案卡文案改为「当前方案 / 编辑」，去掉重复的页面说明感

**版本**：versionCode 23 / versionName 2.0.2。

**真实验证**：`:app:testDebugUnitTest` + `:app:lintDebug` + `:app:assembleDebug` 通过。

---

## 2026-07-16 · v2.0.1 UI 细节打磨：网格对齐与文案清理

纯界面打磨，不动功能代码、协议和数据链路，Luminous Blue 设计系统风格不变。

**间距网格对齐**

- 把 11 个 layout 和 `styles.xml` 里 margin/padding 的硬编码 dp 全部对齐到 4dp 网格并换成 `@dimen` token
- 脱格值回格：`14→space_16`、`10→space_12`、`6→space_8`、`18→space_16`、`3→grid_4`
- 卡片内边距统一 `space_16`（原来 14/16 混用）
- 同传/视频两个主页 4 张卡片段间距统一 `space_16`（原来末张字幕卡少 4dp）
- 底部弹窗刻意的 `20dp` 横向内缩保留，字号/圆角/控件宽高一律未动

**文案清理**

- 删掉四个页面标题下的引导性说明句（PageDesc）：实时同传、视频字幕、历史记录、术语库
- 历史空状态由「还没有历史记录。翻译一场之后再回来看看。」精简为「暂无历史记录」
- 其余功能性文案（按钮、字段、状态、参数说明、设置项副标题）审阅后保留

**版本**：versionCode 22 / versionName 2.0.1。

**真实验证**：改前基线 `assembleDebug` 通过；改后 `lintDebug` + `assembleDebug` 均通过，`@dimen` 引用全部解析，APK 重新生成。

---

## 2026-07-16 · v2.0.0 通用实时翻译与 Luminous Blue 全面重构

本次不保留旧测试数据兼容层，直接将产品从 VTuber 视频字幕工具重构为通用的「同传 + 视频」实时翻译 App。

**产品与界面**

- 统一底部「同传 / 视频 / 历史 / 设置」四页结构，使用 Luminous Blue 蓝白设计系统
- 同传和视频各自拥有独立语言、场景、术语库、高级要求和本场上下文
- 配置集中到 `TranslationPlanBottomSheet`；AI 内容分析只在对应业务页出现
- 设置页只维护 AI 服务、字幕、长期翻译参数、诊断、关于和通用术语库

**数据与运行链路**

- 新增 `TranslationPlan` / `TranslationPlanStore`，草稿、命名方案及模式校验完整隔离
- 新增通用 `GlossaryProfile`，替代旧 `StreamerProfile`
- 启动时创建不可变会话快照，权限回调、重连和后台服务不再读取变化中的 UI 设置
- `StatusBus` 提供结构化字幕快照；历史升级为包含模式、语言、时长、原文和译文的会话 JSON
- 悬浮字幕支持普通/紧凑态和真实暂停；本场上下文随 Activity 重建保存，并在会话启动快照成功交给服务后立即消费
- 历史只自动写入 App 私有结构化存储，不在公共 Downloads 留下会议或同传副本

**清理**

- 删除旧 prompt 预设、`composedPrompt` 回退、旧场景同步、旧迁移代码
- 删除 `StreamerProfile`、`StreamerProfileStore`、`ProfileGenerator` 和隐藏旧表单
- 将 `YouTubeVideoInfo` 拆成独立有效模型，视频 URL 仅用于抓取，不注入翻译提示词

**追加代码审查修复**

- Android 11–13 麦克风前台服务显式启用 `FOREGROUND_SERVICE_TYPE_MICROPHONE`；视频模式继续使用 `MEDIA_PROJECTION`
- Android 13+ 通知权限改为可选，拒绝通知不再阻止录音启动
- 同传/视频本场资料跨 Activity 重建保存，并在启动 Intent 成功发出后立即消费，避免旧上下文串入下一场
- 暂停状态与真实连接状态分离；原文片段即时进入结构化会话快照
- 取消自动写入公共 Downloads 的 Markdown 副本，历史删除语义只对应一份 App 私有数据
- 方案库和术语库逐项容错；外层或单条数据损坏时，在修复写入前保存原始 JSON 备份
- AI 生成术语先保留为内存候选，只有点击“应用”才持久化；View 销毁后旧请求自动失效
- 连续两句译文相同但原文不同的情况不再被误判为重复；同传边界会过滤残留视频元数据

**版本**：versionCode 21 / versionName 2.0.0。

**真实验证**：

- `:app:testDebugUnitTest`：12 项测试通过
- `:app:lintDebug`：通过
- `:app:assembleDebug`：通过
- APK ZIP 完整性：通过
- APK v2 签名：通过，固定 debug 证书
- 包信息：`com.xyq.livetranslate`，minSdk 29，targetSdk 35
- 最终复验：12 项单测、0 failures；GitHub Actions `Android Debug Build #29482790597` 通过并上传 `LiveTranslate-debug-apk`
- 最终 Debug APK SHA-256：`d6373f9fe17f7cb211dd75c0d302123b2de0c6213d0ca36ab85504cbbe12de0a`

---

## 2026-07-15 · 同传 / 视频四层提示词拆分

**问题**：同传和视频虽然输入场景不同，却共用同一个 `composedPrompt`；默认资料还会让通用同传带上 VTuber 偏置。

**实现**：

- `PromptBuilder` 改为四层组合：隐藏基础规则 → 自动模式规则 → 模式独立场景预设 → 仅本场上下文 / 专名资料
- 同传预设：通用、会议、课堂、采访、旅行交流、自定义
- 视频预设：通用视频、直播、VTuber、动漫、游戏、新闻、课程、自定义
- 两个 Tab 各自提供预设、本模式自定义要求和本场上下文；预设与自定义要求分模式保存，本场上下文不单独持久化，并在该模式停止后自动清空
- 设置 → 场景 / 术语库新增同传和视频场景入口，与两个首页使用同一状态并双向即时同步；本场上下文仍只保留在对应首页
- 同传和视频首页顶部新增显眼的“源语言 → 目标语言”选择，和设置页双向同步；默认日语 → 中文，运行期间锁定避免界面与当前连接不一致
- 源语言方向写入系统提示词，目标语言同时写入提示词和 Gemini Live `targetLanguageCode`；字幕标签与 AI 资料生成也跟随当前语言方向
- 通用模式不再默认注入 `通用VTuber` 资料；只有 VTuber 场景或明确选中的自建资料才加入专名块
- `prepareSessionSettings(captureMode)` 只校验即将启动的模式；本场提示词延后到麦克风服务真正启动或投屏授权成功时才写入，`CaptureService` 停止时删除 `composedPrompt`
- 权限请求中的待启动模式写入 `savedInstanceState`，Activity 重建后不会把同传串成视频
- 设置页预览改为“本场资料预览”，不再显示隐藏的基础 / 模式提示词；YouTube AI 分析结果只回填视频上下文，注入模型时不携带视频 URL
- 自建资料即使只填写“资料名”，该名称也会进入专名资料块

**验证**：

- `:app:testDebugUnitTest`：6 组单元测试通过，覆盖预设隔离、同传无视频偏置、VTuber 视频资料、仅资料名、语言方向和隐藏预览
- `:app:lintDebug`：通过；剩余为项目既有硬编码文案、依赖版本和大布局警告
- `:app:assembleDebug`：本地 Android 35 SDK 实际构建成功

---

## 2026-07-13 · v1.9.1 修复：切换底部 Tab 闪退

**现象**：App 默认打开「同传」正常，点击「视频 / 历史 / 设置」立即闪退。

**根本原因**：`BottomNavigationView.setOnItemSelectedListener` 回调调用 `showPage(itemId)`；`showPage()` 又在 `selectedItemId` 尚未由 Material 更新时写回同一个 itemId，导致选中回调无限重入，最终 `StackOverflowError`。

**修复**：`showPage()` 只负责页面显示，不再修改 `bottomNav.selectedItemId`。程序化切换只在回调外的明确入口进行。版本升到 `1.9.1`（versionCode 20）。

**回归检查**：静态探针确认 `showPage()` 函数体内没有 `selectedItemId =`；完整构建走 GitHub Actions。

---

## 2026-07-13 · v1.9 通用实时翻译：底部 4 Tab + 麦克风同传

目标：从「VTuber 专用视频字幕」扩成「同传 + 视频」双入口，共用同一翻译引擎。

### A. 导航壳

- 去掉侧边栏 `DrawerLayout` + `NavigationView`
- 底部 4 Tab：
  1. **同传**：麦克风实时同传
  2. **视频**：系统内录 + 悬浮字幕（原实时翻译）
  3. **历史**
  4. **设置**
- 原「主播资料」→ 设置内 **场景 / 术语库** 二级页（复用 `pageStreamer`）
- 设置二级页打开时隐藏底部导航，工具栏返回箭头

### B. 麦克风同传后端

- `CaptureService` 支持 `EXTRA_MODE=mic|video`
  - video：MediaProjection 内录（原逻辑）
  - mic：`AudioRecord`（优先 `VOICE_RECOGNITION` → `VOICE_COMMUNICATION` → `MIC`），16k/48k mono 优先
- 共用：`PcmProcessor` → `GeminiLiveClient` → `SubtitleStabilizer` → transcript 落盘
- 悬浮窗：视频模式强制；同传模式有悬浮窗权限才挂，否则只在 App 内看字幕
- 前台服务类型：`mediaProjection|microphone`；Manifest 增加 `FOREGROUND_SERVICE_MICROPHONE`
- `StatusBus.captureMode` 标记当前模式；两模式互斥（先停再开）
- 同传页：音量条、中/原文字幕、开停按钮、场景标签

### 涉及文件

- `CaptureService.kt` / `StatusBus.kt` / `MainActivity.kt` / `AndroidManifest.xml`
- `activity_main.xml` / `menu/bottom_nav.xml` + tab 图标
- 去掉 `drawerlayout` 依赖；版本 `1.8` → `1.9`（versionCode 19）

### 未做（刻意）

- 视觉重设计（暗色霓虹等）
- 文案全面去 VTuber 化
- 同传专用 TTS 播报 / 双向对话
- 历史列表标注来源模式

### 验证

- 本地无完整 Android SDK；Kotlin 括号平衡 / XML 解析 / `R.id` 引用自检通过
- 完整 `assembleDebug` 待确认后走 GitHub Actions
- 真机待测：同传开停、音量电平、日→中字幕、与视频模式互斥

---

## 2026-07-12 · 修复：CI 构建每次签名不同、无法覆盖安装

用户反馈：不同次构建出来的安装包签名好像都不一样。

**根因**：`app/build.gradle.kts` 没配 `signingConfig`，`assembleDebug` 走默认 debug 签名，用的是 `~/.android/debug.keystore`——该文件每台机器首次构建时随机生成。GitHub Actions runner 用完即弃，每次都是全新机器 → 每次现生成不同的 debug.keystore → 每个 APK 签名证书都不同 → 新包无法覆盖旧包（签名不一致），只能卸载重装、丢失设置。

**修复**：仓库内放固定 `app/debug.keystore`（标准公开口令 `android` / `androiddebugkey`，非机密），`signingConfigs.getByName("debug")` 指向它。此后所有机器、所有 CI 构建签名一致，可直接覆盖安装保留数据。一次性代价：换固定签名后首次安装仍与手机上现有随机签名旧版不匹配，需再卸载重装一次，之后永久稳定。

---

## 2026-07-12 · 修复：连接轮换后偶发不识别、字幕卡死

用户实测反馈：断线轮换之后有时不再识别，字幕就卡在那里，后台一直卡着。

**根因（`GeminiLiveClient`）**：轮换有两条触发路径——定时 `rotateTask`（scheduler 线程）和 `goAway` 即时轮换（WebSocket 读线程）。两者会**跨线程同时进 `connect()`**，而 `generation++` 是 `@Volatile Int` 的非原子读-改-写，`ws` 赋值也没串行化。竞态下会建出两条连接、两个 WsListener 的 `gen` 都等于当前 `generation`，`ws` 只指向其一；另一条拿不到音频被服务端闲置关闭，其 `onClosed` 又因 `gen==generation` 触发重连，级联下去 `ws` 很容易指向一条永远走不到 `setupComplete` 的连接。`ready` 永久卡 false → `senderLoop` 把音频块无限退回队列 → 服务端收不到输入 → 没有转写 → 字幕永久冻结。且原本**没有握手看门狗**，一旦卡死无法自愈。

**修复**：

- 连接生命周期操作（connect / rotate / scheduleReconnect / 看门狗）全部**串行到 scheduler 单线程**；WebSocket 读线程的 `goAway` / `setupComplete` 只用 `runOnScheduler` 投递任务，不再直接改 `generation` / `ws` / `rotateTask`
- `generation` 改 `AtomicInteger`；`rotate(fromGen)` 加代际守卫，定时任务与 goAway 谁先跑谁生效，后到的看到代际推进就跳过，杜绝重复轮换
- `connect()` 开头始终取消残留 `rotateTask`，避免过期轮换任务乱触发
- 新增**握手看门狗** `HANDSHAKE_TIMEOUT_MS`（12s）：连上后到点仍没 `setupComplete` 就作废旧连接并强制重连，管线不再可能永久冻结

**验证**：改动集中在 `GeminiLiveClient.kt` 单文件（本地无 Android SDK / AGP 缓存，完整构建走 GitHub Actions assembleDebug）。

---

## 2026-07-10 · v1.8 提示词优化：注入提示词瘦身 + 资料 AI 输出对齐用途

用户反馈：预览提示词里居然带着 YouTube 链接——翻译模型根本用不上。

**1. `PromptBuilder` 重写（注入给 Gemini Live 的 systemInstruction）**

- **去掉 URL**：本场直播只保留标题 + 频道
- 去掉 App 内部概念表头（"固定提示词（主播资料）""临时提示词（仅本次会话使用…）"）——模型不需要知道我们的数据分层，改为直白分节：主播 / 本场直播 / 术语固定译法 / 常见错听修正 / 中文风格
- 去掉模型做不到的要求（"保持低延迟"）和重复句；开头一句话说清任务
- 删除未使用的 `buildFixedProfileBlock` / `buildTemporaryContextBlock`

**2. `ProfileGenerator` system prompt 对齐用途**

- 开头声明 JSON 的用途：注入实时翻译模型当背景知识，所有字段往"帮它听对、译对专名"方向写
- `temporaryContext` 从"200–400 字内容猜测"改为"100–250 字给翻译模型的临时提示"：点出主题/环节 + 可能反复出现的专名及译法（游戏名、歌名、连动成员），明确禁止复述链接和"本场应该很有趣"式废话
- `terms` 引导优先直播高频词、5–15 条为宜

**版本**：versionCode 18 / versionName 1.8。

**验证**：GitHub Actions assembleDebug。

---

## 2026-07-10 · v1.7 设置界面重构：分类二级菜单

用户需求：设置不要全堆一页，改成分类二级菜单，方便以后加新设置（如兼容 OpenAI 的 live 模型）；诊断、关于并入设置。

**结构变化**

- 侧边栏从 5 项减为 4 项：实时翻译 / 主播资料 / 历史记录 / 设置（诊断挪进设置）
- 设置首页变成分类入口列表（单卡片 + 分隔线 + 右箭头），点击进二级页；工具栏自动切换返回箭头，物理返回键同样先退回设置首页
- 五个二级页：
  - **翻译服务**：翻译 API（Key / Base URL）+ 翻译参数（目标语言 / 同语言回显 / 轮换间隔，独立恢复默认）——以后接其他翻译模型（OpenAI Realtime 等）就在这页加"服务商"选择
  - **字幕与悬浮窗**：悬浮窗样式三滑条 + 断句节奏两滑条（独立恢复默认，会连样式一起重置）
  - **资料 AI**：原资料 AI 卡片原样搬入
  - **诊断**：电池白名单 + 运行状态原文
  - **关于**：App 定位说明、版本号（运行时从 PackageInfo 读）、翻译模型名、GitHub 入口
- v1.6 的"高级设置"折叠卡片取消——分类之后每项都有了自然归属，不再需要"藏起来"

**实现**

- 布局仍是单 Activity + 页面可见性切换，无 Fragment；新增 SettingsRow* 公共样式，新分类行复制三行 XML 即可
- 新增 `ic_arrow_back_24` 返回图标；`SettingsStore` 悬浮窗样式默认值提为常量；删除一次性的 resetAdvanced
- 填 Key 的引导现在直接跳到"设置 → 翻译服务"二级页

**版本**：versionCode 17 / versionName 1.7。

**验证**：GitHub Actions assembleDebug。

---

## 2026-07-10 · v1.6 高级设置（云端参数摸底 + 本地断句可调）

用户需求：把 `gemini-3.5-live-translate-preview` 能设置的选项查清楚，做成设置页里默认隐藏、点开可见的高级设置。

**1. 云端参数摸底（官方文档核对，结论记入 tech-notes）**

- 这个模型的云端配置是刻意做减法的：`translationConfig` 只有 `targetLanguageCode` / `echoTargetLanguage` 两个字段 + 两个转写开关，没有 VAD/voice/temperature
- 官方声称翻译模式不支持 systemInstruction，但实测可用——记为"依赖未文档化行为"风险
- 中文官方语言代码是 `zh-Hans` / `zh-Hant`，我们用的 `zh` 实测可用，保持默认

**2. 高级设置卡片（设置页，默认收起，点标题展开）**

- **目标语言**：可编辑下拉（zh / zh-Hans / zh-Hant / en / ja / ko + 自由输入），默认 zh
- **同语言回显**（echoTargetLanguage）开关，默认开（维持旧行为）
- **连接主动轮换间隔**：120–580 秒滑条，默认 505（服务端约 590s GoAway）
- **断句·静默转正**：1–6 秒滑条（步进 0.25s），默认 2.5s
- **断句·当前行最长**：20–80 字滑条，默认 42
- "恢复默认"按钮；所有改动下次开始翻译时生效

**3. 实现方式**

- `SettingsStore` 新增 adv* 字段（带范围钳制 + resetAdvanced）
- `GeminiLiveClient` 的 targetLang / echoTargetLanguage / rotateAfterMs 从硬编码常量改为构造参数
- `SubtitleStabilizer` 的 idleCommitMs / maxCurrentChars 同样参数化；`CaptureService` 开播时从设置读取传入
- 老用户无感：所有默认值 = 原硬编码值，不填就是原行为

**版本**：versionCode 16 / versionName 1.6。

**验证**：GitHub Actions assembleDebug（本次开发环境无法直连 dl.google.com，无本地 SDK）。

---

## 2026-07-10 · v1.5 资料 AI + UI 精简（编译通过）

用户需求：把主播资料和提示词改成傻瓜式自动化——贴 YouTube 链接 → AI 自动生成资料和临时提示词。

**1. 第二 AI 接口 (`AiTextClient`)**

- 独立于翻译 WebSocket 的文本 AI 通道，走 REST `generateContent` / OpenAI `chat/completions`
- 支持两种 API 格式，设置页可一键切换（Gemini 原生 / OpenAI 兼容）
- Gemini 格式自动开启 `google_search` grounding（模型实时搜索 web 获取 VTuber 最新信息）
- 输出强制 JSON Mode，带 markdown code fence 剥离和容错解析
- Key、URL、模型名均独立配置，可与翻译 API 共用或分开

**2. AI 资料生成器 (`ProfileGenerator`)**

- system prompt 详细描述 `StreamerProfile` 各字段含义，要求模型基于 VTuber 圈子知识 + Google Search 联网搜索
- 一次 API 调用产出：主播资料 JSON + 本场临时提示词文本 + 备注
- 不认识的主播会诚实标注，不编造

**3. 设置页新增"资料 AI"卡片**

- AI Key / Base URL / 模型名 + Gemini/OpenAI 格式切换按钮
- 默认模型 `gemini-3-flash-001`（够用且快）

**4. 主播资料页 UI 重构**

- "获取视频信息" + "AI 自动分析生成" 改为并排 TextButton，更紧凑
- AI 完成自动回填主播资料表单 + 临时上下文 + 触发预览
- 提示词预览卡片改为标题行+小刷新按钮，默认空白

**5. 全界面文案精简**

- 删除各页 PageDesc 长说明、FieldLabel 小提示、hint 括号注释、helperText 冗文、诊断页流程说明
- 卡片标题缩短；表单 hint 只留字段名
- 整体从"自言自语说明"调整为"大厂成品"风格

**版本**：versionCode 15 / versionName 1.5。

**新增文件**：`AiTextClient.kt`（~230 行）、`ProfileGenerator.kt`（~90 行）。

**验证**：`./gradlew assembleDebug` → BUILD SUCCESSFUL。

---

## 2026-07-09 · v1.4 状态栏修复 + 提示词分层 + 历史页 + 前端文案重做（编译通过）

按用户三点反馈一次做完，核心翻译链路不动。

**1. 状态栏遮挡**

- targetSdk 35 默认 edge-to-edge，顶部内容会压到状态栏。用 `ViewCompat.setOnApplyWindowInsetsListener` 给 toolbar 顶部、页面容器底部、侧边栏上下分别让出 system bars 高度。toolbar 改 `wrap_content + minHeight`，状态栏区域用品牌色填充。

**2. 提示词拆成固定 / 临时两层**

- `PromptBuilder` 重构：`build(profile, session: SessionPromptContext)`，输出「固定提示词（主播资料）+ 临时提示词（仅本次会话）+ 中文风格」。
- 新增 `SessionPromptContext`（YouTube 信息 + 手动补充），**不保存**，只组装进当前会话。
- `StreamerProfile` 增加 `category` 字段（可选，带常用分类下拉）。
- 开播时 `MainActivity` 组装 `固定资料 + 临时上下文` → `SettingsStore.saveComposedPrompt`；`CaptureService` 读 `composedPrompt`（为空退回旧预设，保证兜底）。
- 主播资料页去掉旧“提示词预设”编辑器；改为固定资料卡 + 临时提示词卡 + 完整提示词预览。
- 备注：临时提示词后续计划接小模型自动总结，先留手动入口。

**3. 历史记录页**

- 新增 `HistoryStore`：transcript 同时写一份到 App 内部 `files/history/`（`TranscriptLogger` 双写），历史页稳定读取，不受公共目录权限影响。
- 历史页：列表（时间/大小）→ 点开查看全文 → 复制全文；公共 `下载/LiveTranslate/` 仍保留供文件管理器分享。

**4. 前端文案与风格**

- 每页顶部加一句“这页是干嘛的”说明；输入框提示词重写得更口语、更准确。
- 新增 `styles.xml` 统一 PageTitle / PageDesc / CardTitle / FieldLabel / Field，全 App 风格一致。

**版本**：versionCode 14 / versionName 1.4。APK：`LiveTranslate-v1.4.apk`。

**验证**：`../tools/gradle-8.9/bin/gradle assembleDebug` → BUILD SUCCESSFUL。

---

## 2026-07-09 · v1.3 App 壳子 + P1 后端第一版（编译通过）

响应用户反馈：一页平铺太简陋，先做正常 App 风格；同时 P1 优先“后端逻辑固定”，前端只做够用入口。

**P0 收尾：App 壳子**

- 主界面改为 `DrawerLayout + NavigationView + MaterialToolbar`：左侧侧边栏包含“实时翻译 / 主播资料 / 设置 / 诊断”
- 实时翻译页只保留运行控制台和当前会话，不再堆设置项
- 设置页承载 API key、Base URL、prompt 预设、悬浮窗样式
- 诊断页承载电池白名单和原始状态文本

**P1 后端第一版**

- 新增 `StreamerProfile` / `StreamerProfileStore`：本地主播资料库，SharedPreferences JSON 保存；字段包含日文名、中文名、所属、别名/口癖、术语、错听、中文风格
- 新增 `PromptBuilder`：根据主播资料 + YouTube 信息 + 本场补充上下文生成 Gemini Live systemInstruction 文本
- 新增 `YouTubeOEmbedClient`：解析 `youtu.be` / `youtube.com/watch` / `/live/` / `/shorts/` URL，并通过 YouTube oEmbed 获取标题和频道
- 主播资料页可新建/保存资料、获取 YouTube 标题频道、生成 prompt、应用到现有提示词预设

**版本**：versionCode 13 / versionName 1.3。APK：`LiveTranslate-v1.3.apk`。

**验证**：`../tools/gradle-8.9/bin/gradle assembleDebug` → BUILD SUCCESSFUL（12s）。

---

## 2026-07-09 · v1.2 P0 页面整体打磨第一版（编译通过）

按 Felo Translator 对标方向先做 P0 的“页面整体搞好”，不改核心翻译链路。

**新增/调整**：

- 主页面从“设置页优先”改成“运行控制台优先”：顶部直接显示运行状态、连接状态、已发送块数和当前 key 标签
- 新增输入音量电平条：`CaptureService` 在内录线程计算 PCM RMS，写入 `StatusBus.audioLevelPct`，主界面 300ms 刷新显示；用于快速判断是否捕到系统音频
- 新增“当前会话”卡片：展示稳定后的中文字幕、最近日文听写、transcript 保存路径
- 中文字幕面板保留最近 4 条确认行，并显示当前临时行；悬浮窗仍保持原来的确认行/当前行两级字幕
- Key、Base URL、prompt 预设、悬浮窗样式等设置保留，但下移到运行控制台之后
- 停止服务时音量电平归零；`onResume` 避免重复挂多个刷新循环

**版本**：versionCode 12 / versionName 1.2。

**验证**：`../tools/gradle-8.9/bin/gradle assembleDebug` → BUILD SUCCESSFUL（27s）。

---

## 2026-07-09 · v1.1 真机实测基本通过 + Felo Translator 对标启动

**用户实测结论**：v1.1 基本运行功能正常。后台/8 分钟/荣耀杀后台不再作为主要风险反复追踪；用户判断只要悬浮窗存在，后台存活基本可靠。多 key 轮换功能已实现，不再单独作为待办追验。

**待处理方向变化**：从“继续验证基础链路”转为“对标成熟产品体验”。根目录 `base.apk` 确认为第三方 App **Felo Translator**（包名 `com.honso.ai.felotranslator`，版本 `V1.6.12(build 69)`），后续以其作为产品形态标杆，重点参考会话历史、语言选择、运行态反馈、transcript 浏览/复制/分享、自动播放等体验。

**静态分析摘要**：

- Felo 是通用同传/语音翻译产品，不是 VTuber/YouTube 内录专用产品
- 使用 Microsoft Speech / Translator 相关服务、ML Kit 离线翻译包、Firebase、Stripe/Google Billing 等商业化组件
- 有首页实时转写列表、底部波形、语言选择、历史记录、云同步、离线翻译包管理、VIP 时长限制等完整产品层
- 详情见 [05-felo-benchmark.md](05-felo-benchmark.md)

---

## 2026-07-08 · v1.1 UI 打磨（用户要求，编译通过）

- **App 图标**：自适应图标（纯矢量）——靛蓝渐变底 + 白色字幕条 ×2 + 绿色状态点（呼应悬浮窗设计语言），含 Android 13 单色主题图标
- **通知栏小图标**：同款字幕条图形（白色剪影），替换之前借用的系统图标
- **主界面**：Material 3 重做——卡片分区（连接 / 提示词预设 / 悬浮窗样式 / 状态）、浮动标签输入框（TextInputLayout）、预设改用 ExposedDropdownMenu、SeekBar 换 Material Slider、按钮分级（主按钮实心 / 白名单 tonal / 预设操作文字按钮）、品牌色 #4F46E5（深色模式 #818CF8），自动适配深浅色
- **悬浮窗**：确认行（小一号、75% 白）与当前行（原字号、白 + 阴影）视觉分层；圆角加大到 14dp + 1dp 淡描边
- git 提交 930bd07；APK：LiveTranslate-v1.1.apk（旧 v1.0 拷贝已移除）

---

## 2026-07-08 深夜 · v1.0 完整版完成（用户睡前授权自主开发，待验收）

用户指示：不再分步实测，一步到位做完整成品。阶段 1 剩余项全部实现，编译一次通过。

**v1.0 新增（相对 v0.3）**：

- **key 安全**：新增 `KeystoreCrypto`（Android Keystore AES/GCM），API key 加密落盘；旧版明文 key 首次运行自动迁移成加密存储
- **多 key 轮换**：key 输入框支持英文逗号分隔多个；每次建立连接随机选一个（含 8 分半轮换时，等于每段直播换 key 分摊配额）；状态区显示当前用的 key#序号
- **prompt 预设**：按主播存多套提示词（Spinner 选择 + 新建/保存/删除），旧版单条 prompt 自动迁移为"通用VTuber"预设
- **悬浮窗控制**：字号（12–26sp）/ 背景不透明度（20–95%）/ 最多行数（1–3）三个滑条，改完立即生效（styleVersion 机制，无需重启会话）；**点一下悬浮窗收起成小圆点，再点展开**；窗宽改用屏幕短边计算，横竖屏都不越界
- **transcript 挪到公共下载目录**：`下载(Download)/LiveTranslate/日期_时间.md`，文件管理器直接可见（MediaStore 写入，失败退回私有目录）
- **防杀后台**：PARTIAL WakeLock（4 小时上限兜底）+ "加入电池白名单"按钮（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，不支持的 ROM 退到电池优化列表页）
- 内录初始化失败等错误现在会显示在状态区（error:内录初始化失败）

**版本**：versionCode 10 / versionName 1.0。覆盖安装即可，旧设置自动迁移。

**待用户验收/确认**（v1.1 实测后收口）：

- [ ] 覆盖安装后旧 key 还在（迁移验证；如用户已重新填 key，可不再追）
- [x] 基本运行功能正常（2026-07-09 用户反馈）
- [x] 后台/8 分半/荣耀杀后台不再作为主要风险追踪：只要悬浮窗存在，后台存活基本可靠
- [x] 多 key 轮换功能已实现，不再单独作为待办追验
- [ ] transcript 出现在 下载/LiveTranslate/（下次顺手确认即可）

---

## 2026-07-08 · v0.2 真机实测通过 ✅ + v0.3 字幕稳定器完成

**v0.2 实测结果**（用户，约 2 分钟短测）：

- 全链路通：悬浮窗出字、YouTube 原声正常、transcript 正确落盘
- transcript 质量好于预期：ja/zh 对齐整齐，专名（FF14、7-11 无水咖喱、メカブ）接得住
- 无播放时字幕静止 —— 符合预期（静音不发数据）
- 短测未覆盖：8 分半主动轮换、荣耀后台长时间存活 → 留给下次长测

**v0.3 新增 `SubtitleStabilizer`**（一次编译通过）：

- 碎片边界重叠合并（≥2 字重叠才算，防止误合）
- 句末标点（。！？…～!?）切句；与上一句相同或被包含的句子丢弃（治复读）
- 2.5s 无新碎片或当前行超 42 字 → 强制转正
- 悬浮窗改为两级显示：上一句确认行 + 正在生成的当前行；主界面 zh 状态同步稳定后的文本（▏分隔两级）
- transcript 落盘仍是原始碎片流（方便对照排查），稳定器只影响显示

**遗留**：

- [ ] v0.3 长测（>10 分钟）：字幕连贯性、8 分半轮换的感知、荣耀后台存活
- [ ] 换新 API key（如果还没换）

---

## 2026-07-08 · v0.2 完整版编译通过（待真机实测）

**决策**：用户确认跳过 Step 1–3 独立 demo，直接做整合版。

**实现内容**（9 个源文件，一次编译通过）：

- `CaptureService`：前台服务（mediaProjection 类型），MediaProjection + AudioPlaybackCapture 内录（48k/44.1k 立体声/单声道自动降级尝试），投影被撤销时自动停止
- `PcmProcessor`：混单声道 → 线性插值重采样 16kHz（跨缓冲区相位连续）→ 100ms/3200 字节分块
- `GeminiLiveClient`：OkHttp WebSocket；setup 按 tech-notes 结构；505s 主动轮换 + goAway 即时轮换 + 异常断线指数退避重连（1s→15s 封顶）；断线期间音频进有界队列（20s 上限）恢复后追发；异常断线额外重发最近 1s
- `SubtitleOverlay`：可拖动悬浮窗，尾部 120 字滚动显示，状态圆点（绿/黄/红）
- `TranscriptLogger`：ja/zh 按会话落盘 Markdown（`Android/data/com.xyq.livetranslate/files/transcripts/`）
- `MainActivity`：API key / Base URL / prompt 设置 + 权限引导链（运行时权限 → 悬浮窗 → MediaProjection）+ 每秒刷新的状态区（连接状态、已发送块数、ja/zh 尾巴）
- `SettingsStore` 明文 SharedPreferences（加密留到阶段 1）；`StatusBus` 服务→界面状态单例

**已知取舍（v0.2 故意不做）**：字幕稳定器（碎片/重复会直接显示）、翻译语音丢弃不播放、转 landscape 悬浮窗宽度不自适应、key 明文存储。

**遗留**：

- [ ] 真机实测（用户）：连接可达性（代理）、字幕出字、8 分半轮换的感知、长时间运行稳定性
- [ ] 换新 API key（旧 key 泄露在聊天记录中）

---

## 2026-07-08 · Step 0.5 构建链路验证 ✅（编译成功，待手机安装确认）

**做了什么**：

- 建立项目文件夹 `D:\vtuber-live-translate\`，文档体系成型（README + docs 00–04）
- 分析两个灵感项目（gemini-live-translate Windows 版 / Gemive Chrome 插件），可抄的点记入 02-tech-notes
- 内录能力确认：腾讯会议共享屏幕可带系统声音（第三方 App 走 AudioPlaybackCapture 的直接证据）→ 内录路线可行
- 环境探测：SDK android-35 就位、系统 JDK 17、无 gradle → 项目自带 Gradle 8.9 于 `tools/`
- **技术基线变更**：放弃 Jetpack Compose，全部用传统 View（UI 简单、悬浮窗必须用 View、少一层版本矩阵）
- 骨架项目 `android/` 建立：Gradle 8.9 + AGP 8.7.3 + Kotlin 2.0.21，compileSdk 35 / minSdk 29，包名 `com.xyq.livetranslate`
- 首次命令行编译 **BUILD SUCCESSFUL**（4m23s，含依赖下载），生成 gradle wrapper
- APK 产物：`android/app/build/outputs/apk/debug/app-debug.apk`（11.6 MB）

**遗留**：

- [ ] 用户把骨架 APK 装到手机，打开看到"构建链路验证 OK"→ Step 0.5 完全通过
- [ ] 换新 API key（Step 3 前）

**下一步**：Step 1 内录 demo（电平条 + 10 秒 WAV 样本导出）

---

## 2026-07-07 · 前期调研与协议实测（详见 00-original-plan.md）

- Gemini Live Translate WebSocket 20 分钟真实节奏推流实验完成：日文听写约 8/10，中文实时约 6/10，结论"直播看大意可用，发布级不可用"
- 实测确认：setup 字段位置的坑、单连接约 590 秒被 GoAway 断开、systemInstruction 可注入且对专名有效
- 细节全部在原计划文档第 4–6 节，此处不重复
