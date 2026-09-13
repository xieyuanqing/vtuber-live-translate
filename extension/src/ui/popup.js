(() => {
  const LT = globalThis.LT;
  const $ = (id) => document.getElementById(id);

  let tabId = null;
  let settings = LT.DEFAULTS;
  let status = null;
  let timer = null;
  let busy = false;
  let settingsSave = Promise.resolve();

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

  async function send(type, payload) {
    if (tabId == null) return null;
    try {
      return await chrome.tabs.sendMessage(tabId, { type, payload });
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

    $('toggle').textContent = status?.phase === 'starting' ? '取消启动' : running ? '停止翻译' : '开始翻译';
    $('toggle').classList.toggle('on', running);
    $('toggle').disabled = busy || !status || !status.onWatchPage;
    $('reloadPage').classList.toggle('hidden', !!status || tabId == null);
    $('restart').classList.toggle('hidden', !running);
    $('restart').disabled = busy;

    $('stConn').textContent = status?.error || CONN_LABEL[status?.conn || ''] || status.conn;
    $('stConn').style.color = status?.error ? '#ffb4ab' : '';
    $('stDir').textContent = status?.direction || `${LT.sourceLabel(settings.sourceLang)} → ${LT.targetLabel(settings.targetLang)}`;
    $('stScene').textContent = status?.sceneLabel || LT.Settings.scene(settings).label;
    $('stTime').textContent = fmtTime(status?.elapsedMs);
    $('level').style.width = `${running ? status.level : 0}%`;

    if (!status) {
      $('videoTitle').textContent = tabId == null ? '打开一场 YouTube 直播' : '当前页面尚未连接';
      $('videoMeta').textContent = tabId == null ? '字幕会直接显示在播放器里' : '安装或更新插件后，刷新页面即可恢复';
      $('tempContext').disabled = true;
      $('tempHint').textContent = '';
      $('hint').textContent = 'Alt+T · 开始 / 停止翻译';
      banner('', '');
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
        status.usedTemp ? '已注入临时补充' : '',
        status.mode === 'script-processor' ? '兼容音频模式' : '',
      ]
        .filter(Boolean)
        .join(' · ');
    }

    // 临时补充输入框：状态里存的是内容脚本的当前值，没在打字时才回填，避免打断输入
    const ta = $('tempContext');
    const temp = status.tempContext || '';
    if (document.activeElement !== ta && ta.value !== temp) ta.value = temp;
    ta.disabled = !status.onWatchPage;
    $('tempHint').textContent = running
      ? '修改后点下方「应用当前设置」即可生效。'
      : '开始翻译时生效；换视频或刷新后清空。';

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
      ? ''
      : 'Alt+T · 开始 / 停止翻译';
  }

  async function refresh() {
    status = await send(LT.MSG.QUERY_STATUS);
    renderStatus();
  }

  async function init() {
    $('version').textContent = chrome.runtime.getManifest().version;
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

  async function changeSession(restart = false) {
    if (busy) return;
    const context = $('tempContext').value;
    busy = true;
    renderStatus();
    try {
      await settingsSave;
      const running = !!status && status.phase !== 'idle';
      if (running) await send(LT.MSG.STOP);
      if (!running || restart) {
        await send(LT.MSG.SET_TEMP_CONTEXT, context);
        await send(LT.MSG.START);
      }
      await refresh();
    } finally {
      busy = false;
      renderStatus();
    }
  }
  $('toggle').addEventListener('click', () => changeSession());
  $('restart').addEventListener('click', () => changeSession(true));
  $('reloadPage').addEventListener('click', async () => {
    if (tabId == null) return;
    await chrome.tabs.reload(tabId);
    window.close();
  });

  $('openOptions').addEventListener('click', () => chrome.runtime.openOptionsPage());

  for (const [id, key] of [
    ['sourceLang', 'sourceLang'],
    ['targetLang', 'targetLang'],
    ['scene', 'sceneId'],
  ]) {
    $(id).addEventListener('change', (e) => {
      const value = e.target.value;
      settingsSave = settingsSave.then(async () => {
        settings = await LT.Settings.save({ [key]: value });
        await send(LT.MSG.SETTINGS_CHANGED);
        renderStatus();
      }).catch(() => banner('设置保存失败，请重新打开弹窗后重试。', ''));
    });
  }

  // 输入直接送到页面内存，避免立即关掉弹窗时防抖任务来不及执行。
  $('tempContext').addEventListener('input', (e) => {
    send(LT.MSG.SET_TEMP_CONTEXT, e.target.value);
  });

  window.addEventListener('unload', () => clearInterval(timer));
  init();
})();
