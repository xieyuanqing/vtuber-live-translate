# 流译

自用 Android 实时翻译工具。支持现场麦克风同传，以及捕获手机中视频/直播音频后生成悬浮字幕。

> 产品定位是低延迟理解辅助，不追求发布级字幕质量；录播精修仍使用离线流程。

## 功能

- **同传**：麦克风采集，适合会议、课堂、采访、旅行交流等现场场景
- **视频**：通过 MediaProjection + AudioPlaybackCapture 捕获应用内播放音频
- **独立翻译方案**：同传和视频分别保存语言方向、场景、术语库、高级要求与本场上下文
- **通用术语库**：维护名称、分类、别名、固定译法、误识别修正和翻译风格
- **AI 内容分析**：在对应业务页整理现场资料或视频元数据，可直接形成本场上下文和术语库
- **实时字幕**：App 内字幕流，以及可暂停、可切换普通/紧凑形态的系统悬浮字幕
- **结构化历史**：保存模式、语言方向、时长、原文和译文，支持详情查看与 Markdown 复制
- **安全设置**：API Key 通过 Android Keystore 加密；实时翻译与内容分析可使用独立接口

## 页面结构

底部导航固定为：

1. 同传
2. 视频
3. 历史
4. 设置

AI 资料整理位于同传/视频方案面板；设置页只维护 AI 服务、字幕样式、长期翻译参数、诊断和术语库。

## 核心链路

```text
麦克风 / 应用内播放音频
→ PCM16 / 16kHz / mono / 100ms chunk
→ Gemini Live Translate WebSocket
→ 字幕稳定器与结构化会话状态
→ App 字幕流 + 系统悬浮字幕
→ 结构化历史记录
```

开始翻译时会生成不可变会话快照。权限回调、重连和后台服务不会重新读取正在编辑的方案，因此同传与视频的语言、场景、提示词和本场上下文不会互相覆盖。

## Android 要求

- minSdk 29
- targetSdk 35
- Java 17
- 视频内录要求目标应用允许 AudioPlaybackCapture
- 悬浮字幕需要系统悬浮窗权限
- Android 13 及以上建议允许通知，以便前台服务稳定运行

## 本地构建

```bash
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug APK：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## 2.0.0

- 使用 Luminous Blue 蓝白视觉系统重写四个主页面和设置子页面
- 将原有全局配置拆分为同传/视频独立 `TranslationPlan`
- 用通用 `GlossaryProfile` 替代主播专用资料模型
- 新增业务页 AI 内容分析、方案 Bottom Sheet 和命名方案
- 历史记录升级为结构化会话 JSON
- 悬浮字幕升级为普通/紧凑双形态，并接入真实暂停控制
- 删除旧 prompt 预设、旧场景兼容层、旧主播模型和迁移代码

## 文档

- [docs/00-original-plan.md](docs/00-original-plan.md)：原始调研与计划存档
- [docs/01-roadmap.md](docs/01-roadmap.md)：开发路线与验收标准
- [docs/02-tech-notes.md](docs/02-tech-notes.md)：协议、音频、重连和 Android 技术记录
- [docs/03-android-primer.md](docs/03-android-primer.md)：项目架构和 Android 概念
- [docs/04-dev-log.md](docs/04-dev-log.md)：开发日志与真实验证记录
- [docs/05-felo-benchmark.md](docs/05-felo-benchmark.md)：竞品静态分析与体验对标

## 原则

- 自用优先，不建设账号系统或服务端
- 实时链路优先低延迟和可用性
- 同传与视频的临时配置严格隔离
- 发布级字幕交给离线精修流程
