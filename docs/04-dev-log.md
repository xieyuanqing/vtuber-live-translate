# 开发日志

倒序排列，最新在上。每完成一步（或踩一个值得记的坑）加一条。

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
