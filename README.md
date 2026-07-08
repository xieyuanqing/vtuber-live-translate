# VTuber 直播实时翻译（安卓悬浮字幕）

自用安卓 App：捕获手机上正在播放的 YouTube 直播音频，送 Gemini Live Translate 实时翻译，用悬浮窗把中文字幕盖在 YouTube 上层。

> 定位：直播中看懂大意的辅助字幕，不追求发布级质量。录播精修另有离线流程（gemini-asr-correction-github，在 VPS 上）。

## 核心链路

```text
YouTube App 播放直播
→ MediaProjection + AudioPlaybackCapture 内录
→ PCM16 / 16kHz / mono / 100ms chunk
→ Gemini Live Translate WebSocket（gemini-3.5-live-translate-preview）
→ 字幕稳定器
→ 悬浮窗中文字幕
→ transcript 落盘（可接录播精修）
```

## 当前状态（2026-07-09）

- [x] 协议与翻译质量已实测：20 分钟真实节奏推流，日文听写约 8/10，中文实时约 6/10（够看大意），详见原计划第 5、6 节
- [x] 手机内录能力已验证：腾讯会议共享屏幕可带系统声音（第三方 App 走 AudioPlaybackCapture 的直接证据）、系统录屏支持内录
- [x] 构建链路已验证：骨架 App 在真机运行确认（2026-07-08）
- [x] **v0.2 全链路真机实测通过**（2026-07-08）：内录 → 翻译 → 悬浮字幕 + transcript 落盘，最小闭环达成
- [x] v0.3 字幕稳定器完成：切句去重、确认行/当前行两级显示
- [x] **v1.0 完整版完成**（2026-07-08）：key 加密 + 多 key 轮换、prompt 预设管理、悬浮窗样式控制 + 点击收起、transcript 存公共下载目录、电池白名单引导。阶段 1 全部落地
- [x] v1.1 UI 打磨（2026-07-08）：自适应 App 图标、Material 3 卡片式界面、悬浮窗两级字幕视觉分层
- [x] **v1.1 真机实测基本通过**（2026-07-09）：用户确认基本运行功能正常；只要悬浮窗存在，后台存活无需再作为主要风险反复追踪
- [x] **v1.2 P0 页面整体打磨第一版**（2026-07-09）：运行控制台前置、输入音量电平、当前会话卡片、最近稳定中文字幕记录流
- [x] **v1.3 App 壳子 + P1 后端第一版**（2026-07-09）：侧边栏多页面结构；主播资料库、YouTube oEmbed 获取、prompt 生成与应用链路落地
- [x] **v1.4 状态栏 + 提示词分层 + 历史页 + 文案重做**（2026-07-09）：修状态栏遮挡（edge-to-edge inset）；固定提示词（主播资料）/ 临时提示词（仅本场，不保存）拆分；历史记录页可查看/复制；统一页面文案与风格样式
- [ ] 下一步：继续 P1，预置常用主播资料、增强 YouTube/Holodex 场次信息；临时提示词后续接小模型自动总结；界面细节继续打磨

## 文档

| 文件 | 内容 |
|---|---|
| [docs/00-original-plan.md](docs/00-original-plan.md) | 原始调研与计划全文（2026-07-07，含全部实测数据），存档不改动 |
| [docs/01-roadmap.md](docs/01-roadmap.md) | 开发路线图：阶段划分、每步验收标准——**跟着这个做** |
| [docs/02-tech-notes.md](docs/02-tech-notes.md) | 技术速查：WS 协议、音频格式、重连策略、安卓 API、本机构建环境、参考项目对照 |
| [docs/03-android-primer.md](docs/03-android-primer.md) | 安卓开发入门速成（只讲本项目用到的概念）+ App 架构图 + 名词表 |
| [docs/04-dev-log.md](docs/04-dev-log.md) | 开发日志：每步做了什么、验收结果、遇到的问题（每次开发后更新） |
| [docs/05-felo-benchmark.md](docs/05-felo-benchmark.md) | Felo Translator（`base.apk`）静态分析与产品体验对标清单 |

## 参考项目（灵感来源，Linux.do 论坛分享）

- [FaQxD233/gemini-live-translate](https://github.com/FaQxD233/gemini-live-translate) — Windows 桌面版（Python/PySide6，WASAPI loopback 内录）。`gemini_client.py` / `pcm_processor.py` 是 WS 协议和音频处理的直接移植参考。
- [letr1n1ty/Gemive](https://github.com/letr1n1ty/Gemive) — Chrome MV3 插件（tabCapture 抓标签页音频）。悬浮窗交互、transcript Markdown 导出、多 key 轮换的产品设计参考。

## 原则

- 自用优先：不做后端、账号系统、应用商店分发
- 一步一验收：先跑通最小闭环，再加舒适性功能
- 发布级字幕不在本项目范围内，走既有录播精修流程
