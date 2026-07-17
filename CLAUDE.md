# CLAUDE.md

给 AI 辅助开发工具使用的项目速览。只记录进入仓库后必须知道的当前约束；产品说明看 `README.md`，详细技术背景看 `docs/`。

## 项目定位

流译是个人自用的原生 Android 实时翻译 App：

- **同传**：麦克风 `AudioRecord` → Gemini Live Translate → App 内字幕。
- **视频**：MediaProjection + AudioPlaybackCapture → Gemini Live Translate → App 内字幕与系统悬浮字幕。
- 定位是低延迟理解辅助，不追求发布级字幕，不建设后端、账号、商店分发或跨设备同步。

当前版本：`2.0.7` / versionCode `28`。

## 不可破坏的产品边界

1. 主导航固定为 **同传 / 视频 / 历史 / 设置**。
2. 同传和视频分别维护 `TranslationPlan` 草稿、命名方案与本场上下文，禁止跨模式复用或覆盖。
3. **方案是唯一长期配置**：只保存语言、场景、自定义场景说明和方案提示词。
4. **本场临时上下文只在同传/视频主页**，不写入方案库；视频页额外保留视频链接与 AI 解析。
5. 术语库已经从当前 UI 和提示词链路移除，不要恢复入口、词条或演示数据。
6. 启动翻译前创建不可变会话快照。权限回调、重连和前台服务只能继续该快照，不能重新读取当前 UI 草稿。
7. 方案库的“编辑”与“应用”是两个动作：编辑已有方案必须按 ID 原位更新；取消编辑不能污染当前草稿。
8. 历史只写 App 私有目录 `history_v2`，不自动写公共 Downloads。

## 代码结构

Android 子项目位于 `android/`，单 App 模块，源码包：

```text
android/app/src/main/java/com/xyq/livetranslate/
```

实时管线：

```text
CaptureService
  ├─ mic   → AudioRecord
  └─ video → MediaProjection + AudioPlaybackCapture
        ↓
  PcmProcessor（PCM16 / 16 kHz / mono / 100 ms）
        ↓
  GeminiLiveClient（WebSocket、队列、轮换、重连）
        ↓
  SubtitleStabilizer（确认行 / 当前行、切句、去重）
        ├─ StatusBus → MainActivity
        ├─ SubtitleOverlay
        └─ TranscriptLogger → HistoryStore
```

关键文件：

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | 四个主页面、设置子页、权限流程、会话快照与 UI 状态 |
| `CaptureService.kt` | 前台服务和完整实时音频管线 |
| `GeminiLiveClient.kt` | Gemini Live setup、WebSocket、主动轮换与断线重连 |
| `PcmProcessor.kt` | 音频重采样和分块 |
| `SubtitleStabilizer.kt` | 流式字幕稳定、确认与去重 |
| `SubtitleOverlay.kt` | WindowManager 悬浮字幕、拖动、普通/紧凑样式 |
| `TranslationPlan.kt` | 当前长期方案模型与语言/场景边界 |
| `TranslationPlanStore.kt` | 模式隔离的草稿和命名方案持久化 |
| `PromptBuilder.kt` | 翻译 systemInstruction 组合 |
| `StatusBus.kt` | 服务到界面的会话状态与字幕快照 |
| `HistorySession.kt` / `HistoryStore.kt` | 结构化历史模型与私有目录存储 |
| `AiTextClient.kt` / `ProfileGenerator.kt` | 独立内容分析 AI，不参与实时连接 |
| `SettingsStore.kt` / `KeystoreCrypto.kt` | 设置持久化与 API Key 加密 |

布局位于 `android/app/src/main/res/layout/`。四个主页面始终由 `activity_main.xml` 引入，Robolectric 启动测试会覆盖关键 ID 绑定。

## 构建与验证

要求 JDK 17、Android SDK 35。仓库包含 Gradle Wrapper：

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

需要排除缓存影响时：

```bash
./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions 工作流：`.github/workflows/android-debug.yml`。

- 涉及 `android/**` 或工作流文件的 `main` push / Pull Request 自动运行 `assembleDebug`。
- `workflow_dispatch` 可对任意分支手动触发。
- 远端交付必须确认 Run 的 `headSha` 等于目标提交，并实际核验下载的 APK artifact。

## Debug 签名

`android/app/debug.keystore` 是仓库内固定的公开 Debug 签名，`build.gradle.kts` 已显式引用。它只用于个人内测，让本地与 CI 产物可以覆盖安装。

不要删除、替换或当作正式发布密钥。口令是 Android 标准公开 Debug 口令，不是项目机密。

## 开发约定

- 注释、文档和提交说明使用中文。
- 功能或重要修复后，在 `docs/04-dev-log.md` 顶部追加：改动、原因、版本和真实验证结果。
- 修改可见版本时同步更新 `android/app/build.gradle.kts` 与 README 当前状态。
- 不为用户已确认无需保留的测试数据增加兼容层或迁移层。
- 不添加演示假数据、假提供商、账号、订阅或 Web-only 视觉效果。
- UI 改造优先保留既有 View ID 和业务绑定；新增页面要加入现有返回栈与 `settingsSubViews`。
- 交付前至少执行 `git diff --check`、单元测试、Lint 和 `assembleDebug`。

## 容易踩的坑

- `GeminiLiveClient.buildSetupJson()` 的字段层级严格依赖 Gemini Live 协议，修改前先读 `docs/02-tech-notes.md`。
- 单连接会被上游 GoAway；连接生命周期操作必须继续串行在 scheduler 单线程，WebSocket 回调不能直接竞争修改 `generation` 或 `ws`。
- 静音时长时间没有模型输出是正常情况，不能仅凭无字幕判定断线。
- MediaProjection 授权会导致 Activity 生命周期切换；必须保留发起授权前的操作快照。
- `ExposedDropdownMenu + boxBackgroundMode=none` 曾导致启动时 `InflateException`，语言胶囊不要恢复该组合。
- 同传/视频空闲态与运行态是 `FrameLayout` 中的兄弟层；显隐必须作用于完整根容器，不能只隐藏内部内容，否则透明层会拦截触摸。
- `SubtitleOverlay.show()` 需要处理悬浮窗权限竞态；视频模式创建失败时必须安全停止会话，不能让主线程崩溃。
- 不要从旧开发日志恢复术语库或把本场上下文重新塞回方案编辑器；当前边界以 README、`docs/README.md` 和本文件为准。
