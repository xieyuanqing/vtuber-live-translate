# 流译 · YouTube 直播实时翻译（Chrome 扩展）

抓 YouTube 直播的音频，经 Gemini Live Translate 实时翻译，字幕直接叠在播放器里，像原生 CC 一样看。

从安卓版[流译](../README.md)移植而来：Live API 协议处理、PCM 重采样、字幕稳定、提示词组合都是同一套逻辑，采集和显示换成了浏览器的做法。

---

## 特点

- **字幕在播放器里**，不是另开窗口。因为字幕层是 `#movie_player` 的子元素，全屏、剧场模式、改窗口大小都自动跟随。
- **自动开始**：打开直播页就开始翻译，不用点。
- **自动带上下文**：把视频标题、频道名、标签、简介塞进提示词，人名和专有名词的翻译准确率会明显好一截。
- **原声不受影响**：音频是旁路复制，视频照常出声。
- **纯本地**：填自己的 Gemini API Key，没有任何后端。

## 安装

1. Chrome 打开 `chrome://extensions/`，右上角开「开发者模式」
2. 点「加载已解压的扩展程序」，选中这个 `extension/` 目录
3. **刷新已经打开的 YouTube 页面**（内容脚本不会注入到装扩展之前就打开的页面）

## 首次配置

点扩展图标 → 「设置」：

1. 填 **Gemini API Key**（在 [Google AI Studio](https://aistudio.google.com/apikey) 拿）。可以填多个，英文逗号分隔，每场会话随机用一个。
2. 确认浏览器能访问 `generativelanguage.googleapis.com`。国内需要给浏览器配代理，规则模式记得放行这个域名。
3. 语言方向默认 **日语 → 中文**，按需要改。

## 用法

| 操作 | 怎么做 |
|---|---|
| 自动开始 | 打开 YouTube **直播页**即可（可在设置里关掉） |
| 手动开始 / 停止 | 点扩展图标 → 「开始翻译」，或按 `Alt+T` |
| 临时补背景 | 弹窗 → 「本场临时补充」输入框，打完字直接开始翻译即可 |
| 看运行状态 | 播放器左上角的小胶囊，或点扩展图标 |
| 调字幕大小位置 | 设置 → 字幕外观 |

录播和点播不会自动开始，需要手动点或按快捷键。

播放器左上角胶囊的含义：

| 显示 | 含义 |
|---|---|
| 连接中 / 重连中 | 正在建立 WebSocket |
| 翻译中 | 一切正常（几秒后自动淡出） |
| 切换连接 | 正常的连接轮换，每 8 分半一次，不用管 |
| 没有检测到声音 | 视频静音了，或者音频没挂上 |
| 红色报错 | 见下面「排查」 |

## 设置说明

- **背景资料**：标题简介注入。这些是任何人都能写的文本，会被当作不可信数据用围栏包起来发给模型，模型不会执行里面的指令。
- **长期背景**：每场都带上的固定资料，适合放人名对照表、称呼习惯。
- **本场临时补充**：弹窗里的输入框。标题和简介没覆盖到的信息（今天玩的游戏、出场人物、临时活动名）当场补一句，只存在当前页面的内存里，**换视频或刷新页面就没了**，不进 storage。和长期背景、标题简介一起放进同一个不可信围栏。
- **场景库**：场景 = 名称 + 提示词，是唯一的可复用配置层。语言方向不进场景，随时单独调。所有条目都能改，「恢复默认模板」会清掉自定义的。
- **高级**：轮换秒数、断句参数直接沿用安卓版实测默认值，没有明确理由不用动。

改语言、场景、提示词相关的设置**要重开一场才生效**（开始翻译时会冻结完整快照，之后重连和轮换都继续用它），弹窗里的「本场临时补充」同理——运行中改了它，停止再开始就带上。字幕外观是即时生效的。

## 排查

| 现象 | 原因 |
|---|---|
| 点图标显示「请在 YouTube 视频页打开」 | 装扩展后没刷新页面 |
| 红色「连不上服务器」 | 代理没放行 `generativelanguage.googleapis.com` |
| 红色「被服务端拒绝」 | API Key 无效，或这个 Key 没有 Live API 权限 |
| 有连接但一直没字幕 | 先看播放器音量是不是 0；再看扩展图标里的「输入音量」条有没有跳 |
| 字幕和原生 CC 打架 | 关掉 YouTube 自己的字幕，或在设置里调「距播放器底部」 |
| 弹窗里显示「兼容音频模式」 | AudioWorklet 没加载成功，已自动退回 ScriptProcessor。能用，但主线程忙的时候可能丢一点音频 |

控制台里所有日志都带 `[流译]` 前缀。想手动调试可以在 YouTube 页面的控制台里用 `LT.debug.start()` / `LT.debug.stop()` / `LT.debug.status()`。

## 代码结构

```text
manifest.json
src/
  common/        constants.js（语言/场景/默认值）· settings.js（存储）· prompt.js（提示词组合）
  audio/         pcm16k.js      重采样 + 分块，同时被当作内容脚本和 AudioWorklet 模块加载
  main-world/    page-bridge.js 跑在页面 JS 环境里，读 movie_player.getPlayerResponse()
  content/       gemini-live.js Live WebSocket 客户端（setup / 轮换 / 重连 / 队列）
                 stabilizer.js  流式碎片 → 确认行 + 当前行
                 audio-tap.js   video 元素 → AudioWorklet 旁路
                 caption-layer.js / .css  播放器内字幕层
                 youtube.js     页面适配（播放器、SPA 导航、广告检测）
                 main.js        会话总控，冻结快照
  background/    service-worker.js  角标、默认设置、快捷键
  ui/            popup · options
tools/
  selftest.js    纯逻辑自检：node tools/selftest.js
  make-icons.js  生成 icons/ 下的 PNG
```

数据流：

```text
<video> ──createMediaElementSource──┬─→ destination（原声照常播）
                                    └─→ AudioWorklet（16k/mono/PCM16/100ms）
                                              ↓
                                    GeminiLiveClient（WebSocket）
                                              ↓
                                    SubtitleStabilizer（确认行 / 当前行）
                                              ↓
                                    CaptionLayer（注入 #movie_player）
```

## 已知限制

- **画中画（PiP）里看不到字幕**——PiP 显示不了 DOM，这是浏览器限制。
- **只做 YouTube**。B站、Twitch 需要各自的页面适配。
- API Key 明文存在 `chrome.storage.local`，只适合自己用的电脑，不要分发。
- 用的是 preview 模型 `gemini-3.5-live-translate-preview`，且 `systemInstruction` 在翻译模式下属于未文档化行为，模型更新后可能失效。
- 长时间直播是持续计费的，开着就一直在用额度。

## 开发

改完代码后在 `chrome://extensions/` 点扩展卡片上的刷新按钮，再刷新 YouTube 页面。

提交前跑一遍：

```bash
node tools/selftest.js
```

自检覆盖重采样（不漂移、跨批相位连续、立体声混音）、字幕稳定器（重叠合并、切句、去复读）、提示词围栏和 manifest 引用完整性。浏览器里的部分（音频挂载、WebSocket、字幕注入）只能真机验。
