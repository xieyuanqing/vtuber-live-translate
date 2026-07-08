# 技术速查

写代码时直接抄这页。实测细节的完整出处：[00-original-plan.md](00-original-plan.md) 第 5 节。App 架构图（谁管什么）见 [03-android-primer.md](03-android-primer.md) 第 9 节。

## 本机构建环境（2026-07-08 探测）

| 项 | 值 |
|---|---|
| SDK 路径 | `C:\Users\XYQ\AppData\Local\Android\Sdk`（platforms: android-35；build-tools: 34.0.0 / 35.0.0） |
| adb | `C:\Users\XYQ\AppData\Local\Android\Sdk\platform-tools\adb.exe`（不在 PATH，用全路径） |
| JDK | 系统 PATH 有 Microsoft JDK 17；Android Studio 自带 JBR 21 |
| Gradle | 无系统安装，项目自带发行版在 `D:\vtuber-live-translate\tools\gradle-8.9\` |
| 版本组合 | Gradle 8.9 + AGP 8.7.3 + Kotlin 2.0.21，compileSdk 35 / minSdk 29 |
| 命令行构建 | 在 `android/` 目录下运行 `../tools/gradle-8.9/bin/gradle assembleDebug`（生成 wrapper 后用 `./gradlew assembleDebug`） |
| APK 产物 | `android/app/build/outputs/apk/debug/app-debug.apk` |

## Gemini Live Translate 接口

WebSocket 入口：

```text
wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<API_KEY>
```

模型：`gemini-3.5-live-translate-preview`

### setup 消息（字段位置是坑，照抄这个结构）

```json
{
  "setup": {
    "model": "models/gemini-3.5-live-translate-preview",
    "generationConfig": {
      "responseModalities": ["AUDIO"],
      "translationConfig": {
        "targetLanguageCode": "zh",
        "echoTargetLanguage": true
      }
    },
    "inputAudioTranscription": {},
    "outputAudioTranscription": {},
    "systemInstruction": {
      "parts": [{"text": "<主播 prompt>"}]
    }
  }
}
```

已实测的报错规律：

- `translationConfig` 必须在 `generationConfig` 里；放到 setup 顶层 → close 1007
- `inputAudioTranscription` / `outputAudioTranscription` 必须在 setup 顶层；放进 generationConfig → close 1007
- `systemInstruction` 放 setup 顶层，可用，对专名识别有实测改善

### 音频推送

- 格式：PCM16 little-endian / mono / 16kHz，每 100ms 一块（= 3200 字节）
- 消息格式：

```json
{"realtimeInput": {"audio": {"data": "<base64 pcm>", "mimeType": "audio/pcm;rate=16000"}}}
```

- 有限音频测试结束时发：`{"realtimeInput":{"audioStreamEnd":true}}`（直播场景用不到）

### 连接寿命与重连

- 实测单连接约 590 秒后服务端发 GoAway 并关闭（close 1008）
- 主动重连策略：跑到 8 分 30 秒 → 开新连接 B → 发同样 setup + prompt → 从 ring buffer 回放最近 1–2 秒音频 → B 开始出字幕后切换、关掉 A
- 异常断线：立即重连，悬浮窗提示"重连中"
- **静音时长时间无返回是正常行为**（Windows 版 README 也确认：无声就不发数据），不要误判成断线

## 安卓关键 API

| 能力 | API | 备注 |
|---|---|---|
| 内录 | MediaProjection + AudioPlaybackCaptureConfiguration + AudioRecord | API 29+；每次会话需用户授权；**旁路复制，YouTube 原声照常播**，无需回放原声 |
| 悬浮窗 | SYSTEM_ALERT_WINDOW + WindowManager | 窗口类型 TYPE_APPLICATION_OVERLAY |
| 后台常驻 | ForegroundService | Android 14（API 34）起需声明 `foregroundServiceType="mediaProjection"` |
| 网络 | OkHttp WebSocket | Base URL 做成可配置 |
| 配置存储 | SharedPreferences + 加密存储（Keystore） | API key 不落明文；自用版足够简单可靠 |

可被捕获的音频 usage：`USAGE_MEDIA` / `USAGE_GAME` / `USAGE_UNKNOWN`。YouTube 属于媒体播放。

内录能力已在本机验证（2026-07-08）：腾讯会议共享屏幕可带系统声音（第三方 App 走同一套 API），系统录屏支持内录。

## 网络

- App 需能访问 `generativelanguage.googleapis.com`：手机代理软件开分应用代理，把本 App 勾上
- 备选方案：VPS 上用 nginx 做 wss 纯转发（只是网络管道，不是"后端"），App 的 Base URL 指向 VPS
- 注意：VPS 上现有的 aistudio-to-api **不支持** Live WebSocket（已实测探测过），别绕这条路

## 参考项目对照（要做什么，抄哪里）

| 要做的事 | 抄哪里 |
|---|---|
| WS 客户端（setup / 收发循环 / 重连） | gemini-live-translate 的 `gemini_client.py`，Python→Kotlin 对照移植 |
| PCM 重采样 + 分块 | 同项目 `pcm_processor.py` |
| 自定义 API Base URL 的设置设计 | 同项目 `settings.py` / `settings_window.py` |
| 悬浮窗交互（拖动/缩放/收起小圆点） | Gemive 的 content/overlay 部分 |
| transcript 导出（Downloads/日期/文件名格式） | Gemive 的 Markdown 导出 |
| 多 key 逗号分隔、会话开始随机选 | Gemive 的设置页 |

仓库地址：

- https://github.com/FaQxD233/gemini-live-translate （Windows，Python/PySide6）
- https://github.com/letr1n1ty/Gemive （Chrome MV3 插件）

## prompt

- 模板与主播资料库的完整设计：原计划第 10 节
- 实测有效的完整示例（風真いろは场次）：原计划 6.3 节
- 阶段 0/1 先硬编码或本地预设文件，自动生成放阶段 2
