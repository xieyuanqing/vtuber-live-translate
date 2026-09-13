/**
 * 会话总控：串起「视频元素 → 音频旁路 → Live 翻译 → 字幕层」。
 *
 * 沿用安卓版最重要的一条约定：**开始翻译前冻结快照**。
 * Prompt、语言、场景、元数据在 start() 时算好并固定，之后重连、轮换、
 * 用户改设置都不会影响本场会话——要生效就重开一场。
 */
globalThis.LT = globalThis.LT || {};

(() => {
  const LT = globalThis.LT;

  const caption = new LT.CaptionLayer();
  let settings = LT.DEFAULTS;

  const session = {
    phase: 'idle', // idle | starting | running
    conn: '',
    error: '',
    mode: '',
    level: 0,
    startedAt: 0,
    lastOutputAt: 0,
    snapshot: null, // 本场冻结的 { prompt, sceneLabel, sourceLang, targetLang, meta }
    client: null,
    tap: null,
    stabilizer: null,
  };

  let currentVideoId = '';
  let currentMeta = null;
  let autoStartedFor = '';
  let userStoppedFor = '';
  let gateHint = ''; // applyGate 自己挂上去的提示，条件消失后要由它负责收掉
  let sessionGeneration = 0; // 停止后作废仍在等待播放器 / 音频挂载的启动操作
  // 本场临时补充（弹窗输入）：只活在内容脚本内存里，不进 storage，
  // 换视频 / 刷新页面即消失。开始翻译时冻结进快照，运行中改动要重开一场才生效。
  let tempContext = '';

  // ---------- 与扩展其他部分通信 ----------

  function statusSnapshot() {
    return {
      onWatchPage: LT.YouTube.isWatchPage(),
      phase: session.phase,
      conn: session.conn,
      error: session.error,
      mode: session.mode,
      level: session.level,
      elapsedMs: session.startedAt ? Date.now() - session.startedAt : 0,
      videoId: currentVideoId,
      title: currentMeta ? currentMeta.title : '',
      isLive: currentMeta ? currentMeta.isLive : false,
      sceneLabel: session.snapshot ? session.snapshot.sceneLabel : '',
      direction: session.snapshot
        ? `${LT.sourceLabel(session.snapshot.sourceLang)} → ${LT.targetLabel(
            session.snapshot.targetLang
          )}`
        : '',
      usedMetadata: session.snapshot ? !!session.snapshot.metaUsed : false,
      usedTemp: session.snapshot ? !!session.snapshot.tempUsed : false,
      tempContext,
    };
  }

  function pushStatus() {
    try {
      chrome.runtime
        .sendMessage({ type: LT.MSG.STATUS, payload: statusSnapshot() })
        .catch(() => {});
    } catch (_) {
      // 扩展被重新加载后旧内容脚本会失效，忽略
    }
  }

  // ---------- 连接状态 → 播放器角标 ----------

  function onConnState(raw) {
    session.conn = raw;
    if (raw.startsWith('error:')) {
      session.error = raw.slice(6);
      caption.setStatus(`流译：${session.error}`, 'err', false);
    } else {
      session.error = '';
      switch (raw) {
        case 'connecting':
          caption.setStatus('流译：连接中…', 'warn', false);
          break;
        case 'reconnecting':
          caption.setStatus('流译：重连中…', 'warn', false);
          break;
        case 'rotating':
          caption.setStatus('流译：切换连接…', 'warn', true);
          break;
        case 'ready':
          caption.setStatus('流译：翻译中', 'ok', true);
          break;
        case 'stopped':
          caption.setStatus('', 'ok', false);
          break;
        default:
          break;
      }
    }
    gateHint = ''; // 连接状态换了，让 applyGate 下一拍重新判断要不要挂提示
    pushStatus();
  }

  // ---------- 启停 ----------

  async function start(reason) {
    if (session.phase !== 'idle') return;
    const generation = ++sessionGeneration;
    const videoId = LT.YouTube.videoIdFromUrl();
    const runTempContext = tempContext;
    const isCurrent = () => generation === sessionGeneration && videoId === LT.YouTube.videoIdFromUrl();
    session.phase = 'starting';
    session.error = '';
    pushStatus();

    try {
      const runSettings = await LT.Settings.load();
      if (!isCurrent()) return;
      settings = runSettings;

      const key = LT.Settings.pickKey(settings);
      if (!key) {
        onConnState('error:未配置 API Key，请在扩展设置里填写');
        session.phase = 'idle';
        return;
      }

      const video = await LT.YouTube.waitForVideo();
      if (!isCurrent()) return;
      const player = LT.YouTube.player();
      if (!player || !video) {
        onConnState('error:没有找到播放器');
        session.phase = 'idle';
        return;
      }
      caption.mount(player);
      caption.applySettings(settings);
      caption.clear();

      // ---- 冻结本场快照 ----
      let meta = currentMeta;
      if (runSettings.useMetadata && (!meta || meta.videoId !== videoId)) {
        meta = await LT.YouTube.waitForMeta({ videoId: LT.YouTube.videoIdFromUrl(), tries: 4 });
        if (!isCurrent()) return;
        if (meta) currentMeta = meta;
      }
      // 等待期间设置页可能发来新配置，本场继续使用启动时读取的那一份。
      const scene = LT.Settings.scene(runSettings);
      const metadataText = runSettings.useMetadata
        ? LT.Prompt.formatMetadata(meta, runSettings.metadataLimit)
        : '';
      const prompt = LT.Prompt.build({
        scene,
        sourceLang: runSettings.sourceLang,
        targetLang: runSettings.targetLang,
        metadataText,
        manualContext: runSettings.manualContext,
        tempContext: runTempContext,
      });
      session.snapshot = {
        prompt,
        sceneLabel: scene.label,
        sourceLang: runSettings.sourceLang,
        targetLang: runSettings.targetLang,
        metaUsed: !!metadataText,
        tempUsed: !!runTempContext,
        videoId: LT.YouTube.videoIdFromUrl(),
      };
      console.info(
        `[流译] 开始（${reason}）｜场景 ${scene.label}｜${LT.sourceLabel(
          runSettings.sourceLang
        )} → ${LT.targetLabel(runSettings.targetLang)}｜元数据 ${
          metadataText ? '已注入' : '未使用'
        }｜临时补充 ${runTempContext ? '已注入' : '未使用'}`
      );

      // ---- 字幕稳定器 ----
      session.stabilizer = new LT.SubtitleStabilizer({
        idleCommitMs: runSettings.stabIdleMs,
        maxCurrentChars: runSettings.stabMaxChars,
        onRender: (current, committed) => {
          if (!isCurrent()) return;
          caption.pushCommitted(committed);
          caption.setCurrent(current);
          caption.render();
        },
      });

      // ---- Live 客户端 ----
      session.client = new LT.GeminiLiveClient({
        keyProvider: () => LT.Settings.pickKey(runSettings),
        baseUrl: runSettings.baseUrl,
        prompt,
        targetLang: runSettings.targetLang,
        echoTargetLanguage: runSettings.echoTargetLanguage,
        rotateAfterMs: runSettings.rotateSeconds * 1000,
        listener: {
          onState: (state) => { if (isCurrent()) onConnState(state); },
          onInputText: (t) => {
            if (!isCurrent() || !settings.showSource) return;
            caption.setSource(t);
            caption.render();
          },
          onOutputText: (t) => {
            if (!isCurrent()) return;
            session.lastOutputAt = Date.now();
            session.stabilizer.onFragment(t);
          },
        },
      });

      // ---- 音频旁路 ----
      const tap = new LT.AudioTap({
        onChunk: (u8) => isCurrent() && session.client && session.client.feedChunk(u8),
        onLevel: (pct) => {
          if (!isCurrent()) return;
          session.level = pct;
        },
      });
      session.tap = tap;
      const mode = await tap.attach(video);
      if (!isCurrent()) { tap.detach(); return; }
      session.mode = mode;
      session.startedAt = Date.now();
      session.lastOutputAt = 0;
      session.phase = 'running';
      session.client.start();
      applyGate();
      pushStatus();
    } catch (err) {
      if (!isCurrent()) return;
      console.error('[流译] 启动失败', err);
      // 先把半成品拆干净，再报错——client.stop() 会发 'stopped'，
      // 顺序反了的话报错提示会立刻被它清掉，用户什么都看不到。
      teardown();
      session.phase = 'idle';
      onConnState(`error:${err && err.message ? err.message : '启动失败'}`);
      pushStatus();
    } finally {
      if (generation === sessionGeneration) {
        if (session.phase === 'starting') session.phase = 'idle';
        pushStatus();
      }
    }
  }

  function teardown() {
    if (session.tap) session.tap.detach();
    if (session.client) session.client.stop();
    if (session.stabilizer) session.stabilizer.reset();
    session.tap = null;
    session.client = null;
    session.stabilizer = null;
    session.mode = '';
    session.level = 0;
    session.startedAt = 0;
    session.snapshot = null;
    gateHint = '';
  }

  async function stop() {
    sessionGeneration++;
    if (session.phase === 'idle') return;
    teardown();
    session.phase = 'idle';
    session.conn = 'stopped';
    session.error = '';
    caption.clear();
    caption.setStatus('', 'ok', false);
    pushStatus();
  }

  // ---------- 广告 / 暂停 / 静音时不发音频 ----------

  function applyGate() {
    if (session.phase !== 'running' || !session.tap) return;
    const video = LT.YouTube.video();
    const ad = settings.pauseOnAd && LT.YouTube.adShowing();
    const paused = !video || video.paused;
    const silent = !!video && (video.muted || video.volume === 0);
    session.tap.setGate(!ad && !paused && !silent);
    if (ad || paused || silent) session.level = 0;
    caption.setVisible(!ad);

    // 连接本身有问题时以连接状态为准，不抢它的提示位
    if (session.conn !== 'ready' && session.conn !== 'rotating') {
      gateHint = '';
      return;
    }

    let hint = '';
    if (ad) hint = '流译：广告中，已暂停';
    else if (silent) hint = '流译：视频已静音，收不到声音';
    else if (paused) hint = '流译：视频暂停中';
    else if (session.startedAt && !session.lastOutputAt && Date.now() - session.startedAt > 25000) {
      // 长时间静音本来就没有输出，是正常的；只在音量条也没动静时才提示
      hint = session.level > 2 ? '流译：等待可翻译的语音…' : '流译：没有检测到声音';
    }

    if (hint === gateHint) return;
    gateHint = hint;
    caption.setStatus(hint, 'warn', false);
  }

  // ---------- 页面生命周期 ----------

  function ensureMounted() {
    if (!LT.YouTube.isWatchPage()) {
      if (caption.mounted) caption.unmount();
      return;
    }
    const player = LT.YouTube.player();
    if (player && (!caption.mounted || caption.player !== player)) {
      caption.mount(player);
      caption.applySettings(settings);
    }
  }

  async function handleVideoChanged() {
    const id = LT.YouTube.videoIdFromUrl();
    if (id === currentVideoId) return;
    currentVideoId = id;
    currentMeta = null;
    tempContext = ''; // 临时补充跟着视频走，换视频即作废
    if (session.phase !== 'idle') await stop();
    ensureMounted();
    if (!id) {
      pushStatus();
      return;
    }
    const meta = await LT.YouTube.waitForMeta({ videoId: id, tries: 8 });
    if (id !== currentVideoId || id !== LT.YouTube.videoIdFromUrl()) return;
    currentMeta = meta;
    pushStatus();
    maybeAutoStart();
  }

  function maybeAutoStart() {
    if (session.phase !== 'idle') return;
    if (!settings.autoStartLive) return;
    if (!currentVideoId || currentVideoId === userStoppedFor) return;
    if (currentVideoId === autoStartedFor) return;
    if (!currentMeta || !currentMeta.isLive) return;
    if (LT.Settings.keyList(settings).length === 0) return;
    autoStartedFor = currentVideoId;
    start('自动·直播');
  }

  chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
    if (!msg || !msg.type) return;
    switch (msg.type) {
      case LT.MSG.QUERY_STATUS:
        sendResponse(statusSnapshot());
        return true;
      case LT.MSG.START:
        userStoppedFor = '';
        start('手动');
        break;
      case LT.MSG.STOP:
        userStoppedFor = currentVideoId;
        stop();
        break;
      case LT.MSG.TOGGLE:
        if (session.phase === 'idle') {
          userStoppedFor = '';
          start('快捷键');
        } else {
          userStoppedFor = currentVideoId;
          stop();
        }
        break;
      case LT.MSG.SETTINGS_CHANGED:
        LT.Settings.load().then((s) => {
          settings = s;
          caption.applySettings(s);
        });
        break;
      case LT.MSG.SET_TEMP_CONTEXT:
        tempContext = String(msg.payload || '').trim();
        sendResponse({ ok: true });
        break;
      default:
        break;
    }
    return undefined;
  });

  window.addEventListener('pagehide', () => {
    if (session.phase !== 'idle') stop();
  });

  LT.YouTube.onMetaPush((meta) => {
    if (meta && meta.videoId === currentVideoId && meta.videoId === LT.YouTube.videoIdFromUrl()) {
      currentMeta = meta;
      if (meta.videoId === LT.YouTube.videoIdFromUrl()) maybeAutoStart();
    }
  });

  LT.YouTube.onNavigate(() => {
    handleVideoChanged();
  });

  setInterval(() => {
    ensureMounted();
    applyGate();
  }, 500);

  (async () => {
    settings = await LT.Settings.load();
    ensureMounted();
    await handleVideoChanged();
  })();

  // 方便在控制台手动调试：LT.debug.start() / LT.debug.stop()
  LT.debug = { start, stop, session, status: statusSnapshot };
})();
