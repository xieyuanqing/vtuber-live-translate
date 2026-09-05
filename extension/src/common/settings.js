/** chrome.storage.local 读写封装，所有上下文（SW / 内容脚本 / 设置页）共用。 */
globalThis.LT = globalThis.LT || {};

(() => {
  const LT = globalThis.LT;
  const KEY = 'settings';

  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

  /** 只做范围收敛，不做结构迁移——个人自用工具，字段变了直接恢复默认。 */
  function normalize(raw) {
    const s = Object.assign({}, LT.DEFAULTS, raw || {});
    if (!Array.isArray(s.scenes) || s.scenes.length === 0) s.scenes = LT.DEFAULT_SCENES;
    if (!s.scenes.some((x) => x.id === s.sceneId)) s.sceneId = s.scenes[0].id;
    if (!s.baseUrl) s.baseUrl = LT.DEFAULT_BASE_URL;
    s.rotateSeconds = clamp(Number(s.rotateSeconds) || 505, 120, 580);
    s.stabIdleMs = clamp(Number(s.stabIdleMs) || 2500, 1000, 6000);
    s.stabMaxChars = clamp(Number(s.stabMaxChars) || 42, 20, 80);
    s.metadataLimit = clamp(Number(s.metadataLimit) || 1200, 0, 6000);
    s.captionLines = clamp(Number(s.captionLines) || 2, 1, 5);
    s.captionScale = clamp(Number(s.captionScale) || 1, 0.6, 2.5);
    s.captionBottom = clamp(Number(s.captionBottom) || 11, 2, 60);
    s.captionOpacity = clamp(Number(s.captionOpacity), 0, 100);
    return s;
  }

  LT.Settings = {
    normalize,

    async load() {
      const box = await chrome.storage.local.get(KEY);
      return normalize(box[KEY]);
    },

    /** 局部更新，返回合并后的完整设置。 */
    async save(patch) {
      const current = await this.load();
      const next = normalize(Object.assign({}, current, patch));
      await chrome.storage.local.set({ [KEY]: next });
      return next;
    },

    /** 逗号分隔的多 key，会话开始时随机取一个（沿用安卓版做法）。 */
    keyList(settings) {
      return String(settings.apiKeys || '')
        .split(',')
        .map((k) => k.trim())
        .filter(Boolean);
    },

    pickKey(settings) {
      const list = this.keyList(settings);
      if (list.length === 0) return '';
      return list[Math.floor(Math.random() * list.length)];
    },

    scene(settings) {
      return (
        settings.scenes.find((s) => s.id === settings.sceneId) || settings.scenes[0]
      );
    },
  };
})();
