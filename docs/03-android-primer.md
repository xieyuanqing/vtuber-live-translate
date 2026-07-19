# 安卓开发入门速成（只讲这个项目用到的）

面向"理工背景但没写过安卓"的读者。每个概念只讲到"够理解我们项目在干什么"的深度。

## 1. 一个安卓项目长什么样

```text
android/                          ← 用 Android Studio 打开的目录
├── settings.gradle.kts           项目叫什么、包含哪些模块
├── build.gradle.kts              全项目共用的构建配置（用什么版本的工具）
├── gradle.properties             构建参数（内存大小之类）
├── local.properties              本机 SDK 路径（每台电脑不同，不进版本库）
└── app/                          ← App 主模块，代码都在这里面
    ├── build.gradle.kts          这个 App 的配置：包名、版本、依赖了哪些库
    └── src/main/
        ├── AndroidManifest.xml   ← App 的"身份证+权限清单"，见第 4 节
        ├── java/com/xyq/livetranslate/   Kotlin 源代码
        └── res/                  资源：界面布局 XML、图标、文字、颜色
```

**Gradle** 是构建工具：读上面那些 `.gradle.kts` 配置，从网上下载依赖库，把 Kotlin 代码编译、打包成 APK。类比：`make` 之于 C 项目。第一次构建要下载很多东西所以慢，之后有缓存就快了。

**AGP**（Android Gradle Plugin）是 Gradle 的安卓插件，版本要和 Gradle 版本配套——所以路线图里"版本基线定死"很重要，乱升级会出现一堆看不懂的构建错误。

## 2. 从代码到手机

- **APK** = 安卓的安装包（本质是个 zip）。我们编译产物在 `app/build/outputs/apk/debug/app-debug.apk`
- **签名**：每个 APK 必须有签名。开发时用自动生成的 **debug 签名**，装自己手机没问题；上应用商店才需要正式签名（我们不上）
- **debug 版特点**：可以被 adb 调试、日志全开。自用完全够
- 安装方式：
  1. Android Studio 点绿色 ▶（插 USB 时）
  2. 命令行 `adb install -r xxx.apk`（Claude 用这种）
  3. 把 APK 文件传到手机上点开装（会提示"允许安装未知来源"，同意即可）

## 3. adb 是什么

Android Debug Bridge，电脑和手机之间的调试通道，在 SDK 的 `platform-tools` 里。前提：手机开"开发者选项 → USB 调试"，USB 连电脑。

常用命令（Claude 会用到，你了解即可）：

```text
adb devices              列出连接的手机（确认连上了）
adb install -r x.apk     安装/覆盖安装
adb logcat               实时看手机日志 ← 调试的命根子
adb shell ...            在手机上执行命令
```

**为什么建议开发时插着 USB**：App 崩溃或行为异常时，真相都在 logcat 日志里。插着线 Claude 能直接看日志定位问题；不插线就只能靠你描述现象来猜。

## 4. Manifest：身份证 + 权限清单

`AndroidManifest.xml` 声明：App 叫什么、有哪些界面（Activity）和后台服务（Service）、需要哪些权限。**没在这里声明的组件和权限，运行时直接不可用**——所以加新功能常常要同时改 Manifest。

## 5. 本项目用到的系统组件

| 组件 | 是什么 | 我们用它做什么 |
|---|---|---|
| **Activity** | 一个界面（一屏） | 主控制台：开始/停止、设置、状态显示 |
| **Service** | 没有界面的后台工作单元 | 承载"录音→翻译→字幕"整条管线 |
| **前台服务** (Foreground Service) | 挂着一条通知栏常驻通知的 Service，系统承诺不轻易杀它 | 我们的 Service 必须是前台服务，否则切到 YouTube 后几分钟内就会被系统回收。Android 14 起还要在 Manifest 里声明服务类型（我们用 `mediaProjection`） |
| **悬浮窗** (Overlay) | 通过 WindowManager 直接往屏幕最上层挂 View，可以盖在任何 App 上面 | 字幕窗。类型用 `TYPE_APPLICATION_OVERLAY` |
| **通知** (Notification) | 通知栏消息 | 前台服务必须配一条常驻通知（"翻译运行中"），这是系统规定 |

## 6. 权限模型：三档 + 一个特例

| 档位 | 授权方式 | 本项目涉及 |
|---|---|---|
| 普通权限 | 装上自动有 | `INTERNET`（联网）、`FOREGROUND_SERVICE` 系列（起前台服务） |
| 运行时权限 | 用到时弹窗，用户点同意 | `RECORD_AUDIO`（录音——内录也算录音）、`POST_NOTIFICATIONS`（发通知，Android 13+） |
| 特殊权限 | 要跳到系统设置页手动开开关 | `SYSTEM_ALERT_WINDOW`（悬浮窗，设置里叫"显示在其他应用上层"） |
| **MediaProjection**（特例） | **每次开始捕获都弹一次**系统确认框（"开始录制或投放？"），不是永久授权 | 内录的入口。这个弹窗躲不掉，是系统级隐私设计，习惯就好 |

## 7. 内录的技术链路（Step 1 的原理）

```text
MediaProjection 授权（用户点确认框）
→ AudioPlaybackCaptureConfiguration（声明"我要捕获其他 App 播放的声音"，
   过滤条件：usage 为 MEDIA / GAME / UNKNOWN 的音频流，YouTube 属于 MEDIA）
→ AudioRecord（安卓的录音 API，从上面的配置里读 PCM 数据流）
→ 我们的代码拿到一串串 PCM 字节
```

关键点：这是**旁路复制**——YouTube 的声音照常从扬声器出，我们只是拿到一份拷贝。

## 8. 音频名词表

| 名词 | 意思 |
|---|---|
| **PCM** | 未压缩的原始音频数据：每个时刻的声波振幅值直接存成数字。PCM16 = 每个采样点用 16 位整数 |
| **采样率** | 每秒采多少个点。48kHz = 每秒 4.8 万个点（安卓内录常见）；Gemini 要 16kHz，所以要**重采样**（降采样） |
| **单声道 / mono** | 只有一条声道。内录抓到的常是立体声（左右两条），要**混音**成单声道 |
| **chunk** | 把连续音频流切成的小块。我们按 100ms 一块发（16kHz × 0.1s × 2 字节 = 3200 字节/块） |
| **WAV** | PCM 加个 44 字节文件头，让播放器认识它。Step 1 的样本存 WAV 就是为了能直接点开听 |
| **发送队列 / overlap** | `GeminiLiveClient` 用 FIFO 队列缓存最多约 20 秒音频块；异常断线重连时把最近约 1 秒已发送块前置回去。主动轮换目前不做双连接无缝交接 |
| **WebSocket** | 一种"拨通后保持在线、双方随时互发消息"的网络连接（对比普通 HTTP 的一问一答）。实时翻译必须用这种 |
| **base64** | 把二进制数据编码成纯文本的方法。PCM 块要 base64 后塞进 JSON 消息里发 |

## 9. 本 App 的架构（谁管什么）

```text
MainActivity（界面）
  操作入口：开始/停止、设置、状态展示
      │ 启动/停止
      ▼
CaptureService（前台服务 = 管线宿主，App 切后台后活着的就是它）
  ├─ AudioRecord 循环        不停读麦克风或内录 PCM
  ├─ PcmProcessor            混音成单声道 → 重采样 16kHz → 切 100ms 块
  ├─ GeminiLiveClient        WebSocket：发 setup+prompt、推音频块、收转写文本；
  │                          发送队列约 20 秒；默认约 505s 主动轮换；异常重连补约 1 秒 overlap
  ├─ SubtitleStabilizer      碎片合并、多句确认、去重、临时/确认两级
  ├─ SubtitleOverlay         悬浮窗渲染字幕 + 连接状态小圆点
  └─ TranscriptLogger        确认段写入 App 私有 history_v2 JSON（后台线程）
SettingsStore                API key（加密）、Base URL、高级参数、语言等
```

数据流一句话：**PCM 进，中文字幕出，全程在前台服务里循环。**

## 10. 出问题时你会被要求做什么

- 描述现象 + 截图（界面状态、通知栏、报错弹窗）
- 插上 USB 让 Claude 看 logcat（最高效）
- 偶尔去系统设置里改开关（电池优化白名单、悬浮窗权限、后台弹出界面权限——国产 ROM 的这些开关名字五花八门，遇到再说）
