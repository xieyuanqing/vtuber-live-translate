(() => {
  const LT = globalThis.LT;
  const $ = (id) => document.getElementById(id);

  let tabId = null;
  let settings = LT.DEFAULTS;
  let status = null;
  let timer = null;

  const CONN_LABEL = {
    '': '未开始',
    connecting: '连接中…',
    reconnecting: '重连中…',
    rotating: '切换连接…',
    ready: '翻译中',
    stopped: '已停止',
  };

  function fillSelect(el, items, value) {
    el.replaceChildren();
    for (const it of items) {
      const opt = document.createElement('option');
      opt.value = it.id || it.code;
      opt.textContent = it.label;
      el.appendChild(opt);
    }
    el.value = value;
  }

  async function send(type) {
    if (tabId == null) return null;
    try {
      return await chrome.tabs.sendMessage(tabId, { type });
    } catch (_) {
      return null; // 内容脚本还没注入（比如刚装完扩展没刷新页面）
    }
  }

  function fmtTime(ms) {
    if (!ms) return '—';
    const s = Math.floor(ms / 1000);
    const m = Math.floor(s / 60);
    return `${m}:${String(s % 60).padStart(2, '0')}`;
  }

  function banner(text, kind) {
    const el = $('banner');
    el.classList.toggle('hidden', !text);
    el.classList.toggle('info', kind === 'info');
    el.textContent = text || '';
  }

  function renderStatus() {
    const running = !!status && status.phase !== 'idle';
    const dot = $('dot');
    // classList.add('') 会抛异常，所以先算出类名再决定加不加
    const dotKind = !status
      ? ''
      : status.error
        ? 'err'
        : status.conn === 'ready'
          ? 'ok'
          : running
            ? 'warn'
            : '';
    dot.className = dotKind ? `dot ${dotKind}` : 'dot';

    $('toggle').textContent = running ? '停止翻译' : '开始翻译';
    $('toggle').classList.toggle('on', running);
    $('toggle').disabled = !status || !status.onWatchPage;

    if (!status) {
      $('videoTitle').textContent = '请在 YouTube 视频页打开';
      $('videoMeta').textContent = '装好扩展后需要刷新一次已打开的页面';
      return;
    }
    if (!status.onWatchPage) {
      $('videoTitle').textContent = '当前不是 YouTube 视频页';
      $('videoMeta').textContent = '打开一个直播或视频页面再试';
    } else {
      $('videoTitle').textContent = status.title || '（正在读取视频信息…）';
      $('videoMeta').textContent = [
        status.isLive ? '直播中' : '录播/点播',
        status.usedMetadata ? '已注入标题简介' : '',
        status.mode === 'script-processor' ? '兼容音频模式' : '',
      ]
        .filter(Boolean)
        .join(' · ');
    }

    $('stConn').textContent = status.error
      ? status.error
      : CONN_LABEL[status.conn] !== undefined
        ? CONN_LABEL[status.conn]
        : status.conn;
    $('stConn').style.color = status.error ? 'var(--err)' : '';
    $('stDir').textContent = status.direction || '—';
    $('stScene').textContent = status.sceneLabel || '—';
    $('stTime').textContent = fmtTime(status.elapsedMs);
    $('level').style.width = `${running ? status.level : 0}%`;

    if (LT.Settings.keyList(settings).length === 0) {
      banner('还没有填 API Key，先去设置里填一个再开始。', '');
    } else if (running && status.phase === 'running') {
      banner('', '');
    } else if (settings.autoStartLive && status.onWatchPage && status.isLive) {
      banner('直播页会自动开始翻译。', 'info');
    } else {
      banner('', '');
    }

    $('hint').textContent = running
      ? '改语言或场景要重开一场才生效（停止再开始）。'
      : '快捷键 Alt+T 可以直接开始 / 停止。';
  }

  async function refresh() {
    status = await send(LT.MSG.QUERY_STATUS);
    renderStatus();
  }

  async function init() {
    settings = await LT.Settings.load();
    fillSelect($('sourceLang'), LT.SOURCE_LANGS, settings.sourceLang);
    fillSelect($('targetLang'), LT.TARGET_LANGS, settings.targetLang);
    fillSelect($('scene'), settings.scenes, settings.sceneId);

    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tab && tab.id && /^https:\/\/www\.youtube\.com\//.test(tab.url || '')) {
      tabId = tab.id;
    }
    await refresh();
    timer = setInterval(refresh, 1000);
  }

  $('toggle').addEventListener('click', async () => {
    const running = !!status && status.phase !== 'idle';
    await send(running ? LT.MSG.STOP : LT.MSG.START);
    setTimeout(refresh, 150);
  });

  $('openOptions').addEventListener('click', () => chrome.runtime.openOptionsPage());

  for (const [id, key] of [
    ['sourceLang', 'sourceLang'],
    ['targetLang', 'targetLang'],
    ['scene', 'sceneId'],
  ]) {
    $(id).addEventListener('change', async (e) => {
      settings = await LT.Settings.save({ [key]: e.target.value });
      if (tabId != null) {
        chrome.tabs.sendMessage(tabId, { type: LT.MSG.SETTINGS_CHANGED }).catch(() => {});
      }
      renderStatus();
    });
  }

  window.addEventListener('unload', () => clearInterval(timer));
  init();
})();
