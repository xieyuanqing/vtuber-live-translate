# CLAUDE.md

给 AI 辅助开发看的项目速览。**只放"一进来就该知道、否则会踩坑"的东西**；细节在 `docs/`，不在这里重复。

## 这是什么

自用安卓 App：内录手机正在播放的 YouTube 直播音频 → 送 Gemini Live Translate 实时翻译 → 悬浮窗把中文字幕盖在 YouTube 上层。定位是「看懂大意的辅助字幕」，不追求发布级质量，不做后端/账号/商店分发。

## 代码结构

单模块 Android 项目，源码全在 `android/app/src/main/java/com/xyq/livetranslate/`。整条管线的宿主是前台服务：

```
CaptureService（前台服务，管线宿主）
  AudioPlaybackCapture 内录 → PcmProcessor（重采样成 PCM16/16k/mono/100ms 块）
  → GeminiLiveClient（WebSocket 到 gemini-3.5-live-translate-preview）
  → SubtitleStabilizer（切句/去重/两级显示）
  → SubtitleOverlay（悬浮窗） + TranscriptLogger（落盘）
```

关键文件：

| 文件 | 职责 |
|------|------|
| `CaptureService.kt` | 管线宿主：内录、串起所有组件、前台通知、WakeLock |
| `GeminiLiveClient.kt` | Live 翻译 WebSocket：setup、连接轮换、断线重连、音频队列 |
| `PcmProcessor.kt` | 内录 PCM 重采样成模型要求的格式 |
| `SubtitleStabilizer.kt` | 字幕稳定：确认行 / 当前行两级，静默转正 |
| `SubtitleOverlay.kt` | 悬浮窗渲染与样式 |
| `SettingsStore.kt` | 所有设置项的读写（含高级参数默认值） |
| `PromptBuilder.kt` | 拼给翻译模型的 systemInstruction |
| `AiTextClient.kt` / `ProfileGenerator.kt` | 第二路 AI（资料生成，Gemini/OpenAI 双格式），与翻译无关 |
| `StatusBus.kt` | 服务 → 界面的状态单例，MainActivity 每秒轮询 |

## 构建与验证（重要，不显然）

- **本地没有 Android SDK / AGP 依赖，跑不了完整构建**。改完代码验证要走 **GitHub Actions**：`.github/workflows/android-debug.yml`（`workflow_dispatch` 可对任意分支手动触发 `assembleDebug`），产物在 run 的 artifact。
- 只想快速验证 Kotlin 语法/编译，本机有系统 Gradle（`/opt/gradle/bin/gradle`）和 JDK，但仍会因缺 SDK 在 AGP 阶段失败——别指望本地出包。
- **签名固定**：`android/app/debug.keystore` 是提交进仓库的固定 debug 签名（公开口令 `android`/`androiddebugkey`），`build.gradle.kts` 的 `signingConfigs.debug` 指向它。**别删、别改成随机签名**，否则又会回到「每次 CI 构建签名不同、装不上旧版」的老问题。
- 改功能记得同步 `versionCode` / `versionName`（`android/app/build.gradle.kts`）。

## 约定

- **注释、文档、commit 一律中文**，跟现有风格保持一致。
- **每次改动后追加 `docs/04-dev-log.md`**（倒序，最新在上）：做了什么、为什么、怎么验证的。这是项目的强约定。
- 开发跟着 `docs/01-roadmap.md` 的阶段和验收标准走；协议/音频/API 细节查 `docs/02-tech-notes.md`。

## 容易踩的坑

- **setup JSON 的字段位置不能动**：Live 接口对 setup 结构挑剔，`GeminiLiveClient.buildSetupJson()` 的层级照 `docs/02-tech-notes.md`，动了就连不上。
- **连接轮换有并发约定**：单连接约 590s 被服务端 GoAway 断开，靠 505s 主动轮换 + goAway 即时轮换。所有连接生命周期操作（connect/rotate/重连/看门狗）**串行在 scheduler 单线程**，WebSocket 读线程只投递任务、不直接改 `generation`/`ws`。改这块前先读懂 `GeminiLiveClient` 顶部的并发注释，否则很容易引入「轮换后 ready 卡死、字幕冻结」的竞态。
- **静音时长时间无返回是正常的**，不要当断线处理（Windows 版也是无声就不发数据）。
- 翻译只有云端 `translationConfig` 的两个参数可调（目标语言 / 同语言回显），别指望更多服务端旋钮。
