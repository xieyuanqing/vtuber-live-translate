# 流译 LiveTranslate

[![Android Debug Build](https://github.com/xieyuanqing/vtuber-live-translate/actions/workflows/android-debug.yml/badge.svg?branch=main)](https://github.com/xieyuanqing/vtuber-live-translate/actions/workflows/android-debug.yml)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)
![Version](https://img.shields.io/badge/version-2.1.0-0058BC)

面向 Android 的个人实时翻译工具：既可以通过麦克风进行现场同传，也可以捕获手机中正在播放的视频或直播音频，并在 App 内或系统悬浮窗显示翻译字幕。

> 当前是个人自用的实验项目，仅提供 Debug 构建，不承诺 API 稳定性或商店分发。产品目标是低延迟理解辅助，而不是发布级字幕制作。

## 功能概览

- **麦克风同传**：用于会议、课堂、采访、旅行交流等现场场景。
- **视频字幕**：通过 MediaProjection 与 AudioPlaybackCapture 捕获应用内播放音频。
- **模式隔离**：同传和视频分别维护语言方向、场景、方案与本场临时上下文，互不覆盖。
- **可编辑场景库**：内置模板只用于首次初始化；可按模式新建、编辑、删除、设为默认或恢复模板。
- **翻译方案**：保存长期使用的语言、场景引用和额外提示词；方案库支持创建、应用、编辑与删除。
- **AI 内容整理**：可在业务页整理现场背景，或根据视频链接生成本场上下文；支持 Gemini 原生与 OpenAI 兼容接口。
- **实时字幕**：提供 App 内字幕流，以及可拖动、独立暂停、可收进屏幕侧边且保持翻译运行的系统悬浮字幕。
- **结构化历史**：按会话保存语言、场景、时长、原文和译文，支持搜索、模式筛选、详情查看与 Markdown 复制。
- **本地安全存储**：API Key 使用 Android Keystore 加密，历史记录保存在 App 私有目录。

## 页面结构

底部导航固定为四个业务入口：

1. **同传**：麦克风实时翻译、本场背景和场景快捷配置。
2. **视频**：应用内音频捕获、视频链接分析和悬浮字幕。
3. **历史**：会话搜索、模式筛选与独立详情页。
4. **设置**：翻译服务、内容分析 AI、场景库、方案库、字幕、诊断和关于信息。

长期配置与临时信息有明确边界：

- **场景库**保存可复用的场景名称与场景提示词，同传和视频分别维护默认项。
- **方案**只保存语言方向、场景 ID 和方案级额外提示词；修改场景会影响下次启动的引用方案。
- **本场上下文**只存在于同传或视频主页，不写入方案库。
- 会话启动时冻结完整 Prompt 和场景名称；权限回调、重连和后台服务不会重新读取正在编辑的配置。

## 工作原理

```text
麦克风 AudioRecord ───────────────┐
                                  ├─→ PCM16 / 16 kHz / mono / 100 ms
应用音频 AudioPlaybackCapture ────┘
                                      ↓
                            Gemini Live Translate
                                      ↓
                              SubtitleStabilizer
                         ┌────────────┼────────────┐
                         ↓            ↓            ↓
                    App 字幕流    系统悬浮字幕   结构化历史
```

核心实时管线运行在前台服务 `CaptureService` 中：

- `PcmProcessor` 负责音频格式转换与分块。
- `GeminiLiveClient` 负责 WebSocket、音频队列、主动轮换和断线重连。
- `SubtitleStabilizer` 负责流式字幕切句、去重与确认行处理。
- `StatusBus` 向界面提供当前会话的只读状态快照。
- `SubtitleOverlay` 与 `TranscriptLogger` 分别负责悬浮显示和本地历史。

内容分析 AI 是独立链路，不参与实时音频连接。

## 技术栈

- Kotlin 2.0.21
- Android XML Views + Material 3
- Gradle 8.9 / Android Gradle Plugin 8.7.3
- OkHttp WebSocket
- JUnit 4 + Robolectric
- minSdk 29 / compileSdk 35 / targetSdk 35
- Java 17

## 环境要求

- Android 10 或更高版本
- JDK 17
- Android SDK 35
- 可访问已配置 Gemini Live Translate 服务的网络
- 至少一个有效 API Key

视频字幕还有两个系统限制：

- 目标应用必须允许 AudioPlaybackCapture；部分 DRM、通话或主动禁止捕获的应用无法内录。
- 视频模式需要系统悬浮窗权限，并且每次开始捕获都要确认 MediaProjection 系统授权。

## 本地构建

```bash
git clone https://github.com/xieyuanqing/vtuber-live-translate.git
cd vtuber-live-translate/android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

生成的 Debug APK：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

通过 ADB 覆盖安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

仓库内的 `android/app/debug.keystore` 仅用于个人 Debug 构建，使用 Android 标准公开调试口令，目的是让本地与 CI 产物可以互相覆盖安装；它不是正式发布签名。

## 初次使用

1. 打开 **设置 → 翻译服务**，填写一个或多个 API Key；多个 Key 使用英文逗号分隔。
2. 按需设置 WebSocket Base URL。默认连接 Google Gemini 服务，也可以使用兼容反代。
3. 在同传或视频主页选择源语言、目标语言和场景，必要时编辑长期方案。
4. 同传模式授予录音权限；视频模式额外授予悬浮窗和屏幕捕获权限。
5. 开始会话后，页面会切换到专注字幕的运行状态；停止后返回配置界面。

可选的内容分析 AI 在 **设置 → 内容分析 AI** 单独配置，不会复用实时翻译连接状态。

## 权限说明

- `INTERNET`：连接实时翻译与内容分析接口。
- `RECORD_AUDIO`：麦克风同传和 AudioPlaybackCapture 所需。
- `SYSTEM_ALERT_WINDOW`：显示系统悬浮字幕；视频模式必须启用。
- `FOREGROUND_SERVICE_*`：会话期间保持音频管线运行。
- `POST_NOTIFICATIONS`：Android 13 及以上显示前台服务通知；拒绝后不影响核心翻译管线启动。
- `WAKE_LOCK`：降低长会话休眠中断概率。
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：可选的后台存活设置入口。

## 数据与隐私

- API Key 经 Android Keystore AES-GCM 加密后存入本地 SharedPreferences。
- 会话历史以结构化 JSON 保存在 App 私有目录 `history_v2`，不会自动复制到公共 Downloads。
- 音频只在翻译会话运行期间发送到用户配置的实时翻译端点。
- 本场上下文会进入当前会话提示词；历史仅保存截断后的上下文摘要。
- 项目不包含账号系统、自建业务后端、广告 SDK 或分析 SDK。

使用自定义 Base URL 或内容分析服务时，数据处理规则取决于对应服务提供方，请自行评估可信度。

## 项目结构

```text
.
├── android/                         # Android Studio / Gradle 项目
│   └── app/src/
│       ├── main/java/.../           # Kotlin 业务代码
│       ├── main/res/                # XML 布局、主题与图形资源
│       └── test/java/.../           # JVM / Robolectric 回归测试
├── docs/                            # 路线图、技术记录和开发日志
├── .github/workflows/               # Android Debug CI
├── CLAUDE.md                        # AI 辅助开发速览
└── README.md
```

## 文档

从 [docs/README.md](docs/README.md) 查看完整文档索引。

常用入口：

- [开发路线图](docs/01-roadmap.md)
- [技术记录](docs/02-tech-notes.md)
- [Android 项目入门](docs/03-android-primer.md)
- [开发日志](docs/04-dev-log.md)
- [最终 UI 实施说明](docs/06-ui-polish-plan.md)

## 持续集成

GitHub Actions 工作流位于 [`.github/workflows/android-debug.yml`](.github/workflows/android-debug.yml)，会在涉及 `android/**` 或工作流文件的 `main` 推送 / Pull Request，以及手动触发时：

1. 校验 Gradle Wrapper；
2. 配置 JDK 17 与 Android SDK；
3. 执行 `:app:assembleDebug`；
4. 上传可下载的 Debug APK artifact。

本地交付前还应运行单元测试与 Lint，不能只以 CI 的 `assembleDebug` 代替全部验证。

## 当前状态

当前版本：**v2.1.0（versionCode 29）**。

v2.1.0 将原先不可编辑的内置场景改为按模式隔离的本地场景库，支持场景编辑、新建、删除、默认选择与模板恢复；方案只引用场景 ID，运行中的会话和历史名称继续使用启动快照。详细变更和真实验证记录见 [开发日志](docs/04-dev-log.md)。

## 已知限制

- Gemini Live Translate 使用预览模型，模型名称、可用区域和配额可能由上游调整。
- 实时输出适合快速理解，不保证完整、逐字或可直接发布的字幕质量。
- Android 厂商后台策略、悬浮窗策略和目标应用的内录策略可能影响体验。
- 当前没有正式 Release、自动更新、账号同步或跨设备历史同步。
- 项目未针对无障碍、平板、横屏和所有厂商 ROM 做完整测试。

## 参与开发

这是个人自用项目，但欢迎通过 Issue 提交可复现问题或明确建议。提交改动时请：

- 使用中文注释、文档和提交说明；
- 保持同传与视频配置严格隔离；
- 不把本场临时上下文写入长期方案；
- 更新 `docs/04-dev-log.md`；
- 至少通过单元测试、Lint 和 Debug APK 构建。

## 许可证

仓库目前未附带开源许可证。除非作者另行授权，代码仅供查看与个人参考，不代表授予复制、修改或再分发许可。
