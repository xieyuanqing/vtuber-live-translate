/**
 * 全局常量与默认设置。
 *
 * 本扩展不使用打包工具：所有脚本都是普通脚本，统一往 globalThis.LT 上挂东西。
 * - 内容脚本：manifest 按顺序注入，共享同一个隔离世界的全局作用域
 * - Service Worker：importScripts 依次加载
 * - popup / options：<script> 标签依次加载
 */
globalThis.LT = globalThis.LT || {};

(() => {
  const LT = globalThis.LT;

  // ---------- Gemini Live Translate 协议常量 ----------
  // 字段层级是安卓版实测出来的，改动前先看 docs/02-tech-notes.md。
  LT.MODEL = 'models/gemini-3.5-live-translate-preview';
  LT.WS_PATH =
    '/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent';
  LT.DEFAULT_BASE_URL = 'wss://generativelanguage.googleapis.com';

  // ---------- 语言 ----------
  LT.SOURCE_LANGS = [
    { code: 'ja', label: '日语' },
    { code: 'auto', label: '自动检测' },
    { code: 'en', label: '英语' },
    { code: 'zh', label: '中文' },
    { code: 'ko', label: '韩语' },
    { code: 'es', label: '西班牙语' },
    { code: 'fr', label: '法语' },
    { code: 'de', label: '德语' },
    { code: 'ru', label: '俄语' },
  ];

  LT.TARGET_LANGS = [
    { code: 'zh', label: '中文' },
    { code: 'zh-Hans', label: '简体中文' },
    { code: 'zh-Hant', label: '繁体中文' },
    { code: 'en', label: '英语' },
    { code: 'ja', label: '日语' },
    { code: 'ko', label: '韩语' },
    { code: 'es', label: '西班牙语' },
    { code: 'fr', label: '法语' },
    { code: 'de', label: '德语' },
    { code: 'ru', label: '俄语' },
  ];

  LT.sourceLabel = (code) =>
    (LT.SOURCE_LANGS.find((l) => l.code === code) || { label: code }).label;
  LT.targetLabel = (code) =>
    (LT.TARGET_LANGS.find((l) => l.code === code) || { label: code }).label;

  // ---------- 场景库初始模板 ----------
  // 只用于首次初始化和「恢复默认」，运行时真源是 chrome.storage 里的 scenes。
  LT.DEFAULT_SCENES = [
    {
      id: 'vtuber',
      label: 'VTuber',
      instruction:
        '这是 VTuber 直播。优先使用圈内常见的人名、组合名和直播术语译法；' +
        '保留主播的口癖和语气，观众称呼按上下文自然翻译；不确定的专名保留原文。',
    },
    {
      id: 'livestream',
      label: '通用直播',
      instruction:
        '这是实时直播。适应口语、省略、互动和话题跳转，弹幕或观众称呼按上下文自然翻译。',
    },
    {
      id: 'game',
      label: '游戏实况',
      instruction:
        '这是游戏直播。准确处理游戏名、角色、技能、道具、地图和机制术语，保留玩家口语节奏。',
    },
    {
      id: 'talk',
      label: '杂谈 / 电台',
      instruction:
        '这是杂谈或聊天直播。以自然口语为主，保留玩笑、吐槽和语气词的味道，不要书面化。',
    },
    {
      id: 'music',
      label: '歌回 / 音乐',
      instruction:
        '这是歌回或音乐直播。歌曲演唱部分可以直译歌词大意，间隙的说话部分按日常口语翻译；' +
        '曲名和歌手名优先使用通行译名，不确定就保留原文。',
    },
    {
      id: 'news',
      label: '新闻 / 发布会',
      instruction:
        '这是新闻或发布会内容。保持客观和信息密度，准确翻译人名、地名、机构、数字、日期与引语。',
    },
    {
      id: 'general',
      label: '通用',
      instruction: '适用于一般视频内容。保持前后字幕连贯，准确处理标题、人物、组织和主题词。',
    },
  ];

  // ---------- 默认设置 ----------
  // 轮换 / 断句这几个数值直接沿用安卓版 SettingsStore 的实测默认值。
  LT.DEFAULTS = {
    apiKeys: '', // 英文逗号分隔多个，会话开始时随机选一个
    baseUrl: LT.DEFAULT_BASE_URL,

    sourceLang: 'ja',
    targetLang: 'zh',
    sceneId: 'vtuber',
    scenes: LT.DEFAULT_SCENES,

    autoStartLive: true, // 打开直播页自动开始
    useMetadata: true, // 把标题/简介塞进 systemInstruction
    metadataLimit: 1200, // 简介截断长度
    manualContext: '', // 用户手填的长期背景（人名表等）

    echoTargetLanguage: true,
    rotateSeconds: 505,
    stabIdleMs: 2500,
    stabMaxChars: 42,

    captionLines: 2, // 保留几行已确认字幕
    captionScale: 1.0,
    captionBottom: 11, // 距播放器底部百分比
    captionOpacity: 60, // 背景不透明度 %
    showSource: false, // 同时显示原文
    pauseOnAd: true,
  };

  // ---------- 消息类型 ----------
  LT.MSG = {
    QUERY_STATUS: 'lt:query-status',
    STATUS: 'lt:status',
    START: 'lt:start',
    STOP: 'lt:stop',
    TOGGLE: 'lt:toggle',
    SETTINGS_CHANGED: 'lt:settings-changed',
  };

  // 页面桥（MAIN world）与内容脚本之间的 window.postMessage 协议
  LT.BRIDGE = {
    REQ: 'lt-bridge:request',
    META: 'lt-bridge:meta',
  };
})();
