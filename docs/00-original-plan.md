# Android VTuber 实时翻译悬浮字幕 App：需求、实验与开发备忘

生成时间：2026-07-07 19:54:31 CST

本文记录这次讨论中形成的产品想法、关键需求、已经完成的实测、技术判断、风险点和后续开发路线。目标是：以后想继续做这个项目时，可以直接从这份文档接上，不需要重新回忆上下文。

---

## 1. 项目一句话

做一个安卓端 App：用户粘贴正在直播的 YouTube 链接，App 自动获取主播与直播信息，生成适合该场直播的提示词，把手机系统内播放的 YouTube 音频实时送入 Gemini Live Translate，并用悬浮窗在 YouTube 上层显示中文实时字幕。

核心链路：

```text
YouTube 直播链接
→ 获取直播/主播信息
→ 生成并确认提示词
→ 捕获系统内 YouTube 音频
→ Gemini Live Translate 实时翻译
→ 悬浮窗显示中文字幕
→ 保存实时 transcript，后续可接录播精修流程
```

产品定位：

```text
直播中：上下文增强的低延迟粗字幕，让观众能看懂大意
直播后：导出音频/transcript，进入现有录播翻译流程做发布级字幕
```

不要把它定位成“一步生成发布级字幕”的工具。实测结果表明：Live Translate 的日文听写底子不错，但中文实时翻译仍有碎片、重复、误译和风格不稳定，适合直播辅助，不适合直接发布。

---

## 2. 用户原始需求整理

用户希望的完整体验：

1. 打开安卓 App。
2. 粘贴一个正在直播的 YouTube 链接。
3. App 自动获取链接中的主播和直播信息。
4. App 基于这些信息，用另一个大模型生成本场直播提示词。
5. 用户确认提示词没问题。
6. 点“开始翻译”。
7. App 开启悬浮窗。
8. 用户把 App 切后台，打开 YouTube。
9. App 根据系统内录音实时生成中文字幕。
10. 字幕显示在悬浮窗里，覆盖在 YouTube 之上。

这个设计的关键不是“翻译”本身，而是把 VTuber 直播的专名、口癖、梗、频道信息和本场标题/简介注入到实时翻译里，提升专名识别和上下文理解。

---

## 3. 已有项目背景

当前相关项目路径：

```text
/home/claude/workspace/gemini-asr-correction-github
```

现有录播翻译流程大致能力：

- 输入音频/视频与参考 ASR。
- 分段处理。
- 日文增强。
- 中文润色。
- 术语和主播 profile。
- 审计修复。
- 输出可发布的日文/中文字幕。

本次用于对比的 run：

```text
/home/claude/workspace/gemini-asr-correction-github/runs/kazamairoha_nerumae_radio_15_NbsF6hpHPC0_20260628
```

主播资料库：

```text
/home/claude/workspace/gemini-asr-correction-github/streamers/kazamairoha.json
```

示例主播 profile 内容包括：

- 主播：風真いろは / 风真伊吕波。
- 所属：hololive / 秘密结社 holoX。
- 角色：用心棒 / 侍。
- 口癖：ござる。
- 系列：#ねるまえらじお / 睡前广播。
- 常见错听修正：風間、岡山いろは、金丸ハ、ネルマイラジオ、チャームする、妖人坊等。

这说明：安卓 App 不应该每次完全靠大模型“猜主播”，而应该复用或维护类似 streamer profile 的结构化资料库。

---

## 4. 前期相关探索结论

### 4.1 AI Studio 免费 key

结论：Google AI Studio 的 Gemini API key 可以直接申请，免费层可用，能调用普通 Gemini API，也能用于 Gemini Live Translate 的测试。

申请入口：

```text
https://aistudio.google.com/apikey
```

普通文本接口验证结果：

```text
model_reply=pong
```

Live Translate WebSocket 真实音频流验证结果：

```text
模型：gemini-3.5-live-translate-preview
输入音频：3.01 秒 PCM16 / 16kHz / mono
输入转写：This is a live translation test.
中文输出：这是一个实时翻译测试。
返回音频块：66 个
errors=0
```

注意：测试 key 已经在聊天里出现过，正式开发/长期使用前应在 AI Studio 重新生成新 key，并撤销旧 key。

### 4.2 aistudio-to-api 不能直接替代 Gemini Live Translate

我们检查过当前 VPS 上的 `aistudio-to-api` Docker：

```text
容器：aistudio-to-api
镜像：ghcr.io/ibuhub/aistudio-to-api:latest
版本：1.3.3
本地端口：127.0.0.1:8081 -> 7860
```

它的普通 Gemini HTTP 接口正常：

```text
/v1/models: 27 个模型
/v1beta/models: 27 个模型
```

但源码和接口探测显示没有 Gemini Live/Bidi WebSocket 能力：

```text
BidiGenerate=0
ws/google=0
translationConfig=0
gemini-3.5-live=0
generateContent=1
streamGenerateContent=1
```

直接探测：

```text
ws://127.0.0.1:8081/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent
→ failed / did not receive valid WebSocket response
```

结论：

```text
aistudio-to-api 适合普通 Gemini HTTP / CCH Gemini-native
不适合直接作为 Gemive / Gemini Live Translate 的实时音频入口
```

后续 App 应直接连 Google Gemini Live WebSocket，或使用我们自己写的专用 Live WebSocket proxy。

---

## 5. Gemini Live Translate 接口实测

### 5.1 WebSocket 入口

Gemini Live Translate 使用 WebSocket：

```text
wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$GEMINI_API_KEY
```

模型：

```text
gemini-3.5-live-translate-preview
```

### 5.2 正确 setup 结构

可用结构如下：

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
    "outputAudioTranscription": {}
  }
}
```

字段位置坑：

- `translationConfig` 必须放在 `generationConfig` 里。
- `inputAudioTranscription` / `outputAudioTranscription` 必须放在 `setup` 顶层。
- 如果把 `inputAudioTranscription` 放进 `generationConfig`，服务端会关闭连接：

```text
WebSocket close 1007
Unknown name "inputAudioTranscription" at setup.generation_config
```

- 如果把 `translationConfig` 放在 `setup` 顶层，也会关闭连接：

```text
WebSocket close 1007
Unknown name "translationConfig" at setup
```

### 5.3 音频格式

发送给 Live Translate 的音频格式：

```text
Raw PCM16
Little-endian
Mono
16kHz
100ms chunk
```

发送消息格式：

```json
{
  "realtimeInput": {
    "audio": {
      "data": "<base64 pcm chunk>",
      "mimeType": "audio/pcm;rate=16000"
    }
  }
}
```

有限音频测试结束时发送：

```json
{"realtimeInput":{"audioStreamEnd":true}}
```

### 5.4 systemInstruction 是否能注入

实测结果：`systemInstruction` 在 raw WebSocket `setup` 中可以被接受，服务端返回 `setupComplete`。

示例：

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
      "parts": [
        {"text": "你正在翻译 hololive 風真いろは 的日语直播。保留ござる、holo+、holoX等专有名词。"}
      ]
    }
  }
}
```

注意：虽然握手通过，并且实测专名有改善，但 Live Translate 的定位仍然是实时翻译管线，不是完整聊天模型。提示词更适合做术语/上下文 bias，不能当作强约束翻译规范。

---

## 6. 20 分钟真实直播模拟实验

### 6.1 实验目的

验证：

- 将录播音频按真实直播节奏实时推送给 Gemini Live Translate。
- 注入主播/术语提示词。
- 对比实时字幕和现有录播翻译结果。
- 判断它是否适合 Android App 的直播字幕场景。

### 6.2 样本

使用样本：

```text
/home/claude/workspace/gemini-asr-correction-github/runs/kazamairoha_nerumae_radio_15_NbsF6hpHPC0_20260628/input/segments/seg_01_0000_0019.ogg
```

实际时长：

```text
1199.993 秒，约 20 分钟
```

直播标题：

```text
風真いろはの #ねるまえらじお 📻❣ #15
```

主播：

```text
Iroha ch. 風真いろは - holoX -
```

对照产物：

```text
离线增强日文：output/gemini/kazamairoha_nerumae_radio_15_NbsF6hpHPC0_20260628.enhance.seg_01_0000_0019.ja.srt
离线润色中文：output/gemini/kazamairoha_nerumae_radio_15_NbsF6hpHPC0_20260628.polish.seg_01_0000_0019.zh.srt
```

### 6.3 注入提示词

提示词文件：

```text
/home/claude/workspace/gemini-asr-correction-github/runs/kazamairoha_nerumae_radio_15_NbsF6hpHPC0_20260628/live_translate_eval/prompt.kazamairoha.zh.txt
```

提示词覆盖内容：

- 主播身份：風真いろは / 风真伊吕波 / Kazama Iroha。
- 所属：hololive 6 期生，秘密结社 holoX 的用心棒，侍角色。
- 系列：#ねるまえらじお，可译“睡前广播”。
- 口癖：ござる。
- 固定术语：holoX、用心棒、SC、メンバーシップ、holo+、holoQ、マシュマロ、マネちゃん、はじめちゃん、フブキ先輩。
- 本场上下文：首次用 holo+ / holoQ 收信、梅雨季、健康料理、豆腐沙拉、电饭煲鸡胸肉、防暑、生日周边、午睡/碎片睡眠等。
- 常见错听修正：風間、岡山いろは、金丸ハ、ネルマイラジオ、チャームする、妖人坊等。
- 中文风格：自然中文，适合 B 站 VTuber 粉丝字幕；不要每个ござる都硬译成“是也”。

### 6.4 运行方式

不是离线上传整段音频，而是模拟真实直播：

```text
OGG 音频
→ ffmpeg 解码成 PCM16 / 16kHz / mono
→ 按 100ms chunk 切分
→ 以 1.0x 实时速度推送到 Gemini Live Translate WebSocket
→ 收集 inputTranscription / outputTranscription / translated audio chunks
```

输出目录：

```text
/home/claude/workspace/gemini-asr-correction-github/runs/kazamairoha_nerumae_radio_15_NbsF6hpHPC0_20260628/live_translate_eval/combined_prompted_realtime_seg01
```

报告文件：

```text
/home/claude/workspace/gemini-asr-correction-github/runs/kazamairoha_nerumae_radio_15_NbsF6hpHPC0_20260628/live_translate_eval/combined_prompted_realtime_seg01/comparison_report.md
```

### 6.5 重要发现：单连接约 590 秒会被关闭

第一次连续推完整 20 分钟时，连接在约 590 秒被 Google 主动关闭：

```text
close_code=1008
reason=Connection aborted because the client failed to close the connection after receiving a GoAway signal once the session duration...
```

因此真实 App 必须做自动重连。

推荐策略：

```text
每 8 分 30 秒主动重连
重连后重新发送同一提示词
音频缓冲重叠 1–2 秒
字幕时间轴客户端拼接
```

实验中为了覆盖完整 20 分钟，改成三段：

```text
0–590s
590–895s
895–1200s
```

然后按 offset 合并结果。

### 6.6 实验指标

合并后的完整结果：

```text
音频覆盖：1199.993 秒
发送音频块：12002 个
接收消息：10625 条
Live 日文输入转写片段：1028 条
Live 中文输出片段：999 条
返回翻译音频块：5034 个
input_text_chars：6249
output_text_chars：4362
```

粗量化对比：

```text
日文输入转写 vs 离线增强日文
全局相似度：0.881
覆盖率：0.885

中文实时输出 vs 离线润色中文
全局相似度：0.420
覆盖率：0.418
```

去掉开头静音和尾部空桶后，按分钟桶：

```text
日文实时转写 median similarity ≈ 0.782
中文实时输出 median similarity ≈ 0.419
```

解释：

- 日文实时转写 0.88 全局相似度说明听写底子不错。
- 中文实时输出 0.42 不等于“只有四成能懂”，因为实时翻译与离线润色稿表达差异很大；但它确实反映了实时中文质量明显更粗。

### 6.7 专名提示词效果

Live 输出中的关键词命中：

```text
风真：12
伊吕波：10
holo / holoPlus：15
holoQ：2
holoX：1
睡前：8
广播：12
YouTube：3
棉花糖：2
经纪：1
保镖：1
```

开场自我介绍对比：

离线中文：

```text
hololive 6期生，秘密结社 holoX 的保镖，武士风真伊吕波！
```

Live 中文：

```text
我是hololive 6期生holoX 的保镖武士风真伊吕波。
```

这说明提示词对专名和身份识别有帮助。

### 6.8 质量判断

日文实时转写：约 8/10。

优点：

- 主体内容基本能听出。
- 专名能被提示词拉住。
- 对持续讲话跟随能力不错。

问题：

- 仍有错听，例如 `植木鉢` 被听成其他词。
- 听众名不稳定。
- 长句会出现碎片和插入式错词。

中文实时翻译：约 6/10，适合直播看大意。

优点：

- 大部分主旨能看懂。
- 专名比无提示词状态更稳定。
- 延迟低，能跟直播节奏。

问题：

- 字幕碎片化。
- 偶发重复。
- 长句语义粘连。
- 有物品/动作误译。
- 风格不如离线润色自然。

发布级字幕：约 3–4/10，不可直接替代现有录播流程。

典型问题：

```text
要注意饮食。要注意饮食。要注意饮食。要注意饮食...
```

```text
お豆腐アレンジ
→ 豆腐啊微波炉是最轻松的吧
```

```text
植木鉢
→ 游泳圈
```

```text
ハッシュタグ
→ 明天的话题
```

结论：

```text
直播中可用，录播发布不可直接用。
```

---

## 7. Android App 可行性判断

### 7.1 系统内录音

Android 10 / API 29 起支持 `AudioPlaybackCapture`，可以捕获其他 App 正在播放的音频。需要：

```text
MediaProjection 用户授权
RECORD_AUDIO 权限
AudioPlaybackCaptureConfiguration
AudioRecord
```

核心限制：

- 播放 App 的音频 usage 必须是可捕获类型，例如 `USAGE_MEDIA`、`USAGE_GAME`、`USAGE_UNKNOWN`。
- 播放 App 或播放器不能禁止 capture policy。
- 播放 App 的 manifest 必须允许 audio playback capture，或者 targetSdkVersion >= Android Q 且未显式禁止。
- 捕获 App 和播放 App 必须在同一个 user profile。
- 用户可以随时撤销 MediaProjection 授权。

YouTube 通常属于媒体播放，理论上适合这个方案，但必须真机验证。

### 7.2 悬浮窗

悬浮字幕需要系统悬浮窗权限：

```text
SYSTEM_ALERT_WINDOW
Draw over other apps
```

体验路径：

```text
用户授权悬浮窗
→ App 启动前台服务
→ 用户切到 YouTube
→ 悬浮窗继续显示字幕
```

### 7.3 前台服务

因为 App 需要后台持续录音、网络传输和悬浮窗显示，应使用 Foreground Service。Android 14 / API 34 起需要声明对应 foreground service type。涉及：

```text
mediaProjection
microphone / mediaProjection 相关权限和前台服务声明
```

第一版应优先在真机上验证：

- MediaProjection 授权能否在切后台后持续。
- AudioRecord 是否持续有数据。
- 悬浮窗是否被系统杀掉。
- 不同 ROM 的后台策略差异。

---

## 8. 产品架构建议

### 8.1 MVP 架构

```text
Android App
├─ URL 输入与直播信息获取
├─ 主播资料库匹配
├─ 提示词生成与编辑
├─ MediaProjection 音频捕获
├─ PCM 重采样 / chunk 切分
├─ Gemini Live WebSocket 客户端
├─ 自动重连管理
├─ 字幕稳定器
├─ 悬浮窗显示
└─ 实时 transcript 保存
```

### 8.2 后续可选后端

自用版可以 App 直接连 Google Gemini Live。

公开分发版建议加后端：

```text
Android App
→ 你的后端 / WebSocket Relay
→ Google Gemini Live API
```

后端职责：

- Gemini key 不暴露给 APK。
- 签发短期 token。
- 限流。
- 统计用量。
- 防止滥用。
- 可选地做 prompt 生成。
- 可选地做字幕后处理。

---

## 9. 用户流程设计

### 9.1 首页

```text
输入 / 粘贴 YouTube 直播链接
按钮：获取直播信息
```

### 9.2 直播信息页

显示：

```text
频道：Iroha ch. 風真いろは
标题：風真いろはの #ねるまえらじお #15
直播状态：直播中 / 已预约 / 已结束
识别主播：風真いろは
资料库匹配：已匹配
可能话题：睡前广播 / holo+ 来信 / 梅雨 / 健康料理
```

按钮：

```text
生成提示词
```

### 9.3 提示词确认页

显示：

- 生成的完整 prompt。
- 术语列表。
- 主播 profile 命中信息。
- 本场直播信息。

允许：

- 手动编辑 prompt。
- 保存为本场配置。
- 点“开始翻译”。

### 9.4 权限引导页

需要引导：

```text
1. 开启悬浮窗权限
2. 开启系统内录音 / 屏幕录制授权 MediaProjection
3. 允许通知 / 前台服务
4. 可选：电池优化白名单
```

### 9.5 翻译运行页

显示：

- WebSocket 连接状态。
- 当前提示词摘要。
- 音频输入电平。
- 当前字幕。
- 错误/重连状态。
- 停止翻译按钮。

### 9.6 悬浮字幕窗

功能：

- 拖动位置。
- 调整字体大小。
- 调整背景透明度。
- 显示 1–3 行。
- 暂停 / 继续。
- 关闭。
- 小圆点显示连接状态。
- 可选显示延迟和重连提示。

---

## 10. 提示词生成系统设计

### 10.1 目标

不要让大模型自由发挥一大段泛泛的“你是专业翻译”。要用固定模板，把信息填进去。

提示词作用定位：

```text
增强专名、口癖、直播语境和翻译风格
不是强制控制 Live Translate 的完整输出格式
```

### 10.2 输入来源

优先级：

1. 用户粘贴的 YouTube URL。
2. YouTube oEmbed：标题、作者、缩略图。
3. YouTube Data API：title、channelId、channelTitle、description、liveBroadcastContent、scheduled/actual start time。
4. Holodex API：VTuber 场景信息，尤其 hololive / Nijisanji / VTuber 直播。
5. 本地/远程 streamer profile：主播名、别名、术语、口癖、常见错听、字幕风格。
6. 用户手动补充：本场关键词、目标语言、是否保留口癖。

### 10.3 主播资料库格式建议

示例：

```json
{
  "channel_id": "...",
  "name_jp": "風真いろは",
  "name_zh": "风真伊吕波",
  "aliases": ["kazamairoha", "いろは", "ござる"],
  "affiliation": "hololive / holoX",
  "catchphrases": ["ござる"],
  "terms": [
    {"jp": "holoX", "zh": "holoX"},
    {"jp": "用心棒", "zh": "保镖"},
    {"jp": "#ねるまえらじお", "zh": "睡前广播"}
  ],
  "misheard": [
    {"wrong": "風間", "right": "風真"},
    {"wrong": "ネルマイラジオ", "right": "ねるまえらじお"}
  ],
  "style": "自然中文，适合 B 站 VTuber 粉丝字幕；保留必要粉丝梗。"
}
```

### 10.4 提示词模板

建议模板：

```text
你正在进行真实直播场景下的低延迟字幕翻译。

任务：把日语 VTuber 直播音频实时翻译成自然中文。优先准确理解日语原意，其次保持低延迟和可读性。不要添加解释、总结或不存在的信息。

主播信息：
- 主播：{name_jp} / {name_zh} / {aliases}
- 所属：{affiliation}
- 直播系列：{series}
- 口癖：{catchphrases}
- 常见自我介绍：{self_intro_pattern}

术语与专名：
{term_pairs}

本场上下文：
- 标题：{video_title}
- 频道：{channel_title}
- 简介摘要：{description_summary}
- 可能话题：{topic_hints}

常见错听修正：
{misheard_pairs}

中文风格：
- 输出自然中文，适合 VTuber 粉丝实时字幕。
- 专名按上面的固定译法。
- 保留主播语气；吐槽处可以稍微口语化。
- 不要把每个口癖都机械翻译。
- 不确定的听众 ID 不要硬编。
```

### 10.5 生成提示词的大模型职责

另一个大模型只负责：

```text
直播 metadata + streamer profile + 用户偏好
→ 填充模板
→ 压缩成适合 Live Translate 的提示词
→ 避免过长、空泛、互相矛盾
```

它不负责实时翻译。实时翻译仍由 Gemini Live Translate 完成。

---

## 11. 字幕稳定器设计

必须做。Live Translate 输出天然碎：

```text
风真
伊吕波的
睡前
广播
好的
所以各位
晚上好
```

如果原样显示，体验会很差。

### 11.1 第一版规则层

输入：Live `outputTranscription` 碎片。

处理：

- 3–6 秒滚动缓冲。
- 连续重复词/句去重。
- 过短片段暂存。
- 遇到句末标点、停顿、超过 N 字时提交。
- 合并短行。
- 限制每行字数。
- 当前行显示“临时字幕”，上一行显示“已确认字幕”。

输出：悬浮窗显示 1–3 行。

### 11.2 后续可选大模型后处理

如果第一版延迟和成本允许，可做：

```text
Live 输出 5–10 秒窗口
→ 快模型文本清理
→ 去重复、修断句、修专名
→ 替换悬浮窗字幕
```

但 MVP 不建议加入，先把基础链路跑通。

---

## 12. 自动重连设计

实测单连接约 590 秒被关闭，因此必须实现重连。

推荐策略：

```text
主动重连间隔：8 分 30 秒
重叠音频缓冲：1–2 秒
重连后重新 setup + systemInstruction
字幕时间轴继续累加
```

流程：

```text
连接 A 正在运行
→ 到 8:30，创建连接 B
→ B 发送 setup 和同一提示词
→ 从 ring buffer 重放最近 1–2 秒音频给 B
→ B 开始输出后切换到 B
→ 关闭 A
```

兜底：

```text
如果连接异常断开
→ 立即重连
→ 重新发送 setup 和提示词
→ 继续推当前音频
→ 悬浮窗提示“正在重连”
```

---

## 13. 密钥和分发策略

### 13.1 自用 / MVP

可以让用户手动填 Gemini API key：

```text
App 设置页输入 GEMINI_API_KEY
Android Keystore 保存
App 直接连 Google Live WebSocket
```

优点：

- 开发快。
- 无需后端。
- 适合个人使用和验证体验。

缺点：

- 不适合公开分发。
- 用户需要自己申请 key。
- 仍要注意 key 泄漏。

### 13.2 公开分发

不要把 key 写进 APK。应使用后端：

```text
App
→ 你的后端鉴权
→ 后端签发短期 token 或代理 WebSocket
→ Google Gemini Live
```

后端能力：

- Gemini key 保存在服务器。
- 限流。
- 用量统计。
- 设备/用户授权。
- 可选敏感词和滥用防护。
- 可选日志脱敏。

---

## 14. 技术栈建议

Android：

```text
Kotlin
Jetpack Compose
ForegroundService
MediaProjection
AudioPlaybackCaptureConfiguration
AudioRecord
OkHttp WebSocket
DataStore / Room
WindowManager Overlay
```

音频：

```text
AudioRecord 读取系统播放音频
统一转 PCM16 / 16kHz / mono
100ms chunk
Base64 编码
WebSocket 发送
```

字幕：

```text
Live outputTranscription
→ SubtitleStabilizer
→ Overlay Renderer
→ Transcript Logger
```

后端可选：

```text
FastAPI / Node.js / Go
YouTube metadata resolver
Prompt generation service
Gemini Live WebSocket relay
Usage accounting
```

---

## 15. MVP 开发阶段

### 阶段 0：验证件

目标：证明核心 Android 能力可行。

任务：

- Android 系统内录音 demo：播放 YouTube 时能抓到 PCM。
- 悬浮窗 demo：切到 YouTube 后字幕框仍显示。
- Gemini Live WebSocket demo：把本机 PCM 推到 Gemini，收到中文转写。
- 8–9 分钟重连 demo：模拟长时间连接并自动重连。

验收：

```text
打开 YouTube 播日语视频
App 能持续捕获音频
悬浮窗能显示 Gemini 返回的中文
10 分钟后能自动重连不断字幕
```

### 阶段 1：自用 MVP

功能：

- 手动输入 Gemini API key。
- 粘贴 YouTube URL。
- 使用 oEmbed / YouTube Data API 获取标题和频道。
- 手动或简单规则选择 streamer profile。
- 生成提示词。
- 用户可编辑提示词。
- 开始实时翻译。
- 悬浮窗显示字幕。
- 保存 transcript。

不做：

- 账号系统。
- 云同步。
- 复杂后处理。
- App 商店分发。

### 阶段 2：VTuber 场景增强

功能：

- Holodex 接入。
- 主播资料库维护。
- 多主播匹配。
- 常见术语自动补全。
- 直播标题/简介话题抽取。
- 字幕稳定器优化。
- 历史提示词复用。

### 阶段 3：可分发版本

功能：

- 后端托管 Gemini key。
- 设备授权 / 用户登录。
- WebSocket relay。
- 用量限制。
- 订阅/额度系统可选。
- 崩溃和质量反馈收集。

---

## 16. 风险清单

### 16.1 YouTube 音频捕获兼容性

Android API 支持不等于所有设备都稳定。需要真机测试：

- Pixel / 原生 Android。
- Samsung One UI。
- Xiaomi / HyperOS。
- OPPO / ColorOS。
- Vivo / OriginOS。

风险：

- 系统限制后台服务。
- 悬浮窗被杀。
- MediaProjection 被撤销。
- YouTube 或 ROM 改策略导致音频捕获失败。

### 16.2 字幕碎片化

如果没有字幕稳定器，体验会明显下降。

### 16.3 单连接时长限制

必须自动重连。不能假设 WebSocket 可以从直播开始一直跑到结束。

### 16.4 提示词不是强控制

systemInstruction 可以帮助专名，但不能保证 Live Translate 完全按字幕规范输出。

### 16.5 成本和配额

Live Translate 免费层可测试，但长期直播会消耗配额。公开分发时必须有成本控制。

### 16.6 Key 泄漏

公开版不能把 Gemini key 写进 APK。

### 16.7 法务/平台政策

系统内录音和悬浮窗字幕用于个人辅助观看通常更合理；公开分发时应注意：

- 不保存或上传用户观看内容，除非明确同意。
- 明确提示音频会发给 Gemini API 处理。
- 提供本地删除 transcript 功能。
- 避免绕过 DRM/受保护内容。

---

## 17. 与现有录播翻译流程的关系

推荐组合：

```text
直播中：Android App + Gemini Live Translate + 悬浮字幕
直播后：导出 transcript/audio → gemini-asr-correction-github → 发布级字幕
```

App 可以保存：

```text
live_input_transcript.txt
live_output_zh.txt
session_metadata.json
prompt.txt
audio_capture_info.json
```

后续录播流程可以利用这些产物：

- Live 日文转写作为参考之一。
- Live 中文输出作为粗翻参考。
- prompt 和主播资料作为上下文。
- 原音频仍作为最终依据。

这样两套系统互补，而不是互相替代。

---

## 18. 当前已产出的文件

本次实验相关：

```text
提示词：
/home/claude/workspace/gemini-asr-correction-github/runs/kazamairoha_nerumae_radio_15_NbsF6hpHPC0_20260628/live_translate_eval/prompt.kazamairoha.zh.txt

Live 合并输出目录：
/home/claude/workspace/gemini-asr-correction-github/runs/kazamairoha_nerumae_radio_15_NbsF6hpHPC0_20260628/live_translate_eval/combined_prompted_realtime_seg01

对比报告：
/home/claude/workspace/gemini-asr-correction-github/runs/kazamairoha_nerumae_radio_15_NbsF6hpHPC0_20260628/live_translate_eval/combined_prompted_realtime_seg01/comparison_report.md

Live 输入转写：
/home/claude/workspace/gemini-asr-correction-github/runs/kazamairoha_nerumae_radio_15_NbsF6hpHPC0_20260628/live_translate_eval/combined_prompted_realtime_seg01/live_input_transcript.txt

Live 中文输出：
/home/claude/workspace/gemini-asr-correction-github/runs/kazamairoha_nerumae_radio_15_NbsF6hpHPC0_20260628/live_translate_eval/combined_prompted_realtime_seg01/live_output_zh.txt
```

本文档路径：

```text
/home/claude/workspace/gemini-asr-correction-github/docs/android-vtuber-live-translate-app-plan.md
```

---

## 19. 下一步建议

如果继续开发，建议按这个顺序：

1. 新建 Android 项目，先不接 Gemini，只做系统内录音验证。
2. 录 YouTube 播放音频，确认能拿到 PCM 数据和音量波形。
3. 接 Gemini Live Translate，硬编码一个 prompt，显示普通 TextView 字幕。
4. 改成悬浮窗显示。
5. 加入 8 分 30 秒自动重连。
6. 加入 URL 解析和提示词生成页。
7. 接主播资料库。
8. 加字幕稳定器。
9. 保存 transcript。
10. 再考虑后端和公开分发。

最低可交付 MVP 标准：

```text
用户粘贴 YouTube 链接
→ App 生成并展示提示词
→ 用户点开始
→ 切到 YouTube
→ 悬浮窗持续显示中文实时字幕
→ 至少连续 20 分钟不断线或自动恢复
```

---

## 20. 最终判断

这个方案值得做。

原因：

- Android 系统内录音能力存在，技术链路可行。
- Gemini Live Translate 已实测能处理真实直播音频。
- systemInstruction 已实测能注入，并且对专名有帮助。
- VTuber 直播强依赖专名、口癖和场景上下文，提示词模板和主播资料库有明确价值。
- 悬浮窗形态符合用户真实观看习惯。

但边界要清楚：

```text
它是直播实时辅助字幕器
不是录播发布级字幕生成器
```

最合理的产品闭环是：

```text
直播时看懂大意
直播后用现有离线流程打磨成品字幕
```

如果后面要继续，我会优先从“Android 系统内录音 + 悬浮窗 + Gemini Live 最小闭环”开始，而不是先做完整后端和复杂 UI。
