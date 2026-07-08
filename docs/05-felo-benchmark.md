# Felo Translator 对标备忘

分析对象：根目录 `base.apk`。

> 说明：这是第三方商业 App 的 APK 静态观察记录，只用于产品体验对标和需求拆解，不复制其代码、不绕过授权、不做破解。

## 1. APK 基本信息

| 项 | 值 |
|---|---|
| App 名称 | Felo Translator |
| 包名 | `com.honso.ai.felotranslator` |
| 版本 | `V1.6.12(build 69)` / versionCode 69 |
| minSdk / targetSdk | 26 / 35 |
| 分发来源痕迹 | Google Play split APK（manifest 中有 `com.android.vending.splits.required=true`） |
| 主 Activity | `com.honso.ai.felotranslator.MainActivity` |

主要权限：

- `RECORD_AUDIO`：麦克风实时识别
- `INTERNET` / `ACCESS_NETWORK_STATE`：云端识别/翻译/账号
- `WAKE_LOCK`：运行时保活
- `CAMERA` / `FLASHLIGHT`：可能用于拍照/OCR/扫码/支付卡扫描等功能
- `BLUETOOTH` / `BLUETOOTH_ADMIN`：耳机/蓝牙音频场景
- `BILLING` / `CHECK_LICENSE`：订阅和授权
- 广告 ID / install referrer / Firebase 相关权限：商业化、统计、归因

明显没有看到：

- `SYSTEM_ALERT_WINDOW`
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`
- `MediaProjection` 相关前台服务声明

所以它大概率是“麦克风/会议同传/对话翻译”产品，不是我们这种“捕获其他 App 内部播放声音 + 悬浮在 YouTube 上”的专用方案。它仍然很适合做产品体验标杆。

## 2. 技术栈与服务线索

从 manifest、资源和字符串可见：

- Microsoft Speech SDK / Cognitive Services 相关：
  - `com.microsoft.cognitiveservices.speech.util.InternalContentProvider`
  - `https://cognitiveservices.azure.com/.default`
  - `https://edge.microsoft.com/translate/auth`
  - `https://api-edge.cognitive.microsofttranslator.com/`
- Google ML Kit 离线翻译：
  - `res/raw/translate_models_metadata.json`
  - 离线模型下载路径：`https://dl.google.com/translate/offline/v5/high/r29/`
  - 字符串里有“Local Translation Package Management”“Offline Language Package”
- Firebase：Auth、Crashlytics、Analytics、Remote Config、Sessions
- Stripe + Google Play Billing：订阅/支付
- Jetpack Navigation / DrawerLayout / Fragment 架构
- Material Components + 部分 Compose 依赖

这说明 Felo 的产品形态是“成熟商业 App”：账号、云同步、订阅、崩溃统计、离线包、支付等都齐全。我们自用版不需要照搬这些重资产部分。

## 3. 主要产品功能形态

### 首页 / 实时翻译页

从 `fragment_home.xml` 可见：

- 主体是一个 `RecyclerView`：实时显示多条翻译记录/气泡
- 底部有 `FeloWaveView`：实时音频波形反馈
- 中央底部一个小号 FAB：开始/停止识别入口
- 右下还有一个隐藏 FAB：可能用于分享、更多操作或展开控制
- 左下有状态按钮：可能显示试用时长/翻译时长状态
- 顶部有试用/VIP 提示条：“使用时长不足3分钟，点击购买VIP”

可借鉴点：

- 首页不要只是设置面板，运行时应该更像“会话面板”
- 实时波形比单纯文字状态更直观
- 翻译记录以消息气泡/卡片流展示，比尾巴文本更容易回看

### 语言选择

从 `language_selection_layout.xml` 可见：

```text
[源语言]  ↔  [目标语言]
```

中间有交换按钮，两侧是可点击语言选择区。

可借鉴点：

- 虽然我们当前目标固定中文，但 UI 上可以做成：
  - 源语言：日语/自动
  - 目标语言：中文
  - 交换按钮可以隐藏或禁用
- 对自用 VTuber 场景，最实用的是“源语言 preset”：日语直播、英语直播、自动识别。

### transcript / 历史

资源里有：

- `fragment_cloud_transcript.xml`
- `fragment_segment_transcripts.xml`
- `item_transcript.xml`
- `item_segment.xml`
- `menu_transcript.xml`
- `menu_segment_transcripts.xml`

功能线索：

- 会话历史列表
- 分段 transcript 列表
- 复制原文 / 复制译文
- 分享 transcript
- 清空所有 segments
- 云同步 translation history

可借鉴点：

- 我们现在 transcript 已经落盘，但 App 内查看能力还弱
- 后续可以补一个“历史记录”页：
  - 按日期/时间列出直播会话
  - 显示 prompt 名称、持续时长、行数
  - 点开查看 ja/zh 双列或气泡列表
  - 一键复制中文 / 复制全文 / 分享 Markdown

### 设置项

从 `root_preferences.xml` 和字符串看，Felo 有这些设置：

- 自动同步到云端
- 语言设置
- 自动播放翻译音频
- 扬声器/耳机场景下是否播放译文
- 自动开始识别
- 历史记录开关
- 离线翻译包管理
- 兑换码 / 反馈 / 关于

对我们有价值的不是云同步/订阅，而是：

- 自动开始：打开 App 后是否直接进入上次配置
- 历史记录：是否保存 transcript
- 音频反馈：我们暂时不播放译音，可以明确“不播放，只显示字幕”
- 反馈入口：自用版可换成“导出诊断日志/复制错误信息”

## 4. 与本项目的核心差异

| 维度 | Felo Translator | 我们的 App |
|---|---|---|
| 主要场景 | 通用同传、会议、对话翻译 | VTuber / YouTube 直播字幕 |
| 音频来源 | 麦克风为主 | 系统内录其他 App 播放音频 |
| 显示形态 | App 内 transcript / 气泡 | YouTube 上层悬浮字幕 + App 状态页 |
| 翻译引擎 | Microsoft Speech/Translator + ML Kit 离线翻译线索 | Gemini Live Translate |
| 账号/商业化 | 登录、云同步、VIP、支付 | 自用，无账号、无后端 |
| 上下文增强 | 通用语言翻译 | 主播 prompt、术语、口癖、VTuber 场景 |
| 离线能力 | 有离线翻译包管理 | 无，依赖 Gemini Live |

结论：Felo 可以作为“同传产品体验标杆”，但不是技术路线标杆。我们的差异化在：

1. 系统内录 + 悬浮在视频上层；
2. Gemini Live 的低延迟音频到字幕；
3. VTuber 专名、口癖、场景 prompt；
4. transcript 可接录播精修流程。

## 5. 建议按 Felo 对标的下一批功能

### P0：不改变核心链路，只补体验

1. **运行态会话面板**
   - 主界面增加“当前会话记录流”
   - ja / zh 气泡或卡片逐条追加
   - 保留现有状态区，但降低视觉优先级

2. **音频波形/电平反馈**
   - 在捕获线程计算 RMS 音量
   - 主界面显示简单波形或电平条
   - 用来快速判断“是不是没捕到音”

3. **transcript 历史页**
   - 读取 `Download/LiveTranslate/` 或内部索引
   - 列出历史 Markdown
   - 支持打开、复制中文、分享文件

4. **语言选择 UI 简化版**
   - 源语言：日语 / 英语 / 自动（先只影响 prompt 文案或 setup）
   - 目标语言：中文（默认固定）
   - 后续如果 Gemini 支持更多目标语言，再开放

### P1：VTuber 场景增强

1. **主播 preset 资料库**
   - 不只是 prompt 文本框，而是结构化资料：主播名、别名、术语、口癖、常见错听
   - prompt 由模板生成

2. **直播场次信息**
   - 粘贴 YouTube URL 获取标题/频道
   - 自动填入 prompt 上下文

3. **错误/诊断导出**
   - 复制当前状态、连接错误、最近日志尾巴
   - 方便不用插 USB 时反馈问题

### 暂不建议照搬

- 登录系统
- 云同步
- VIP/订阅
- 支付
- 广告/归因
- 离线翻译包
- 译文语音自动播放

这些对自用 VTuber 字幕器不是当前核心价值，容易过度工程化。

## 6. 对当前文档和路线图的影响

- v1.1 已经从“待基础验收”进入“可用后打磨”阶段
- 后台/8 分钟/杀后台不再作为主要风险反复追踪
- 多 key 轮换已实现，不再单独作为 TODO
- 下一阶段重点：产品体验，而不是继续堆底层链路
