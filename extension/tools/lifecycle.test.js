/** 会话竞态回归：模拟等待与时间推进，不访问浏览器、不连接真实 API。 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.join(__dirname, '..');
const quiet = { info() {}, warn() {}, error() {} };
const load = (ctx, file) => vm.runInContext(fs.readFileSync(path.join(ROOT, file), 'utf8'), ctx);
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((a, b) => { resolve = a; reject = b; });
  return { promise, resolve, reject };
};
const flush = async () => { for (let i = 0; i < 15; i++) await Promise.resolve(); };

async function sessionHarness() {
  const video = { paused: false, muted: false, volume: 1 };
  const player = {};
  const clients = [], taps = [], statuses = [];
  let messageHandler, navigate;
  let videoId = 'video-A';
  const ctx = vm.createContext({ console: quiet, Date, setInterval() {},
    window: { addEventListener() {} },
    chrome: { runtime: {
      sendMessage: async (msg) => statuses.push(msg.payload),
      onMessage: { addListener(fn) { messageHandler = fn; } },
    } },
  });
  load(ctx, 'src/common/constants.js');
  load(ctx, 'src/common/prompt.js');
  const LT = ctx.LT;
  let settings = { ...LT.DEFAULTS, autoStartLive: false, useMetadata: false, apiKeys: 'test-only' };
  LT.Settings = {
    load: async () => settings,
    pickKey: s => s.apiKeys,
    keyList: s => s.apiKeys ? [s.apiKeys] : [],
    scene: s => s.scenes.find(x => x.id === s.sceneId),
  };
  LT.CaptionLayer = class {
    mount(p) { this.player = p; this.mounted = true; } unmount() { this.mounted = false; }
    applySettings() {} clear() {} setStatus() {} setVisible() {}
    pushCommitted() {} setCurrent() {} setSource() {} render() {}
  };
  LT.SubtitleStabilizer = class { reset() {} onFragment() {} };
  LT.GeminiLiveClient = class {
    constructor(opts) { this.opts = opts; this.starts = 0; this.running = false; clients.push(this); }
    start() { this.starts++; this.running = true; } stop() { this.running = false; }
    feedChunk() {}
  };
  LT.AudioTap = class {
    constructor() { this.detached = false; taps.push(this); }
    async attach() { return 'worklet'; } detach() { this.detached = true; } setGate() {}
  };
  LT.YouTube = {
    isWatchPage: () => true, videoIdFromUrl: () => videoId, player: () => player, video: () => video,
    waitForVideo: async () => video, waitForMeta: async () => ({ videoId, isLive: false }),
    onMetaPush() {}, onNavigate(fn) { navigate = fn; }, adShowing: () => false,
  };
  load(ctx, 'src/content/main.js');
  await flush();
  return { LT, video, clients, taps, statuses,
    setSettings: patch => { settings = { ...settings, ...patch }; },
    message: (type, payload) => messageHandler({ type, payload }, {}, () => {}),
    navigate: id => { videoId = id; navigate(); },
  };
}

test('等待播放器时停止，旧启动不会复活', async () => {
  const h = await sessionHarness();
  const wait = deferred();
  h.LT.YouTube.waitForVideo = () => wait.promise;
  const pending = h.LT.debug.start('test');
  await flush();
  assert.equal(h.LT.debug.session.phase, 'starting');
  await h.LT.debug.stop();
  wait.resolve(h.video);
  await pending;
  assert.equal(h.LT.debug.session.phase, 'idle');
  assert.equal(h.clients.length, 0);
});

test('取消后立即重新开始，旧任务失败不会清掉新会话', async () => {
  const h = await sessionHarness();
  const old = deferred();
  h.LT.YouTube.waitForVideo = () => old.promise;
  const pending = h.LT.debug.start('old');
  await flush();
  await h.LT.debug.stop();
  h.LT.YouTube.waitForVideo = async () => h.video;
  await h.LT.debug.start('new');
  old.reject(new Error('旧播放器已移除'));
  await pending;
  assert.equal(h.LT.debug.session.phase, 'running');
  assert.equal(h.clients.length, 1);
  assert.equal(h.clients[0].running, true);
});

test('切视频会作废仍在等待的启动', async () => {
  const h = await sessionHarness();
  const wait = deferred();
  h.LT.YouTube.waitForVideo = () => wait.promise;
  const pending = h.LT.debug.start('old-video');
  await flush();
  h.navigate('video-B');
  wait.resolve(h.video);
  await pending;
  await flush();
  assert.equal(h.LT.debug.session.phase, 'idle');
  assert.equal(h.clients.length, 0);
});

test('等待音频挂载时停止，客户端不启动并清理旁路', async () => {
  const h = await sessionHarness();
  const wait = deferred();
  h.LT.AudioTap.prototype.attach = () => wait.promise;
  const pending = h.LT.debug.start('test');
  await flush();
  await h.LT.debug.stop();
  wait.resolve('worklet');
  await pending;
  assert.equal(h.LT.debug.session.phase, 'idle');
  assert.equal(h.clients[0].starts, 0);
  assert.equal(h.taps[0].detached, true);
});

test('运行中修改配置不改变本场连接凭据和方向', async () => {
  const h = await sessionHarness();
  await h.LT.debug.start('test');
  h.setSettings({ apiKeys: 'new-test-key', targetLang: 'en' });
  h.message(h.LT.MSG.SETTINGS_CHANGED);
  await flush();
  assert.equal(h.clients[0].opts.keyProvider(), 'test-only');
  assert.equal(h.clients[0].opts.targetLang, 'zh');
});

test('没有 API Key 时角标收到最终空闲状态', async () => {
  const h = await sessionHarness();
  h.setSettings({ apiKeys: '' });
  await h.LT.debug.start('test');
  assert.equal(h.statuses.at(-1).phase, 'idle');
  assert.match(h.statuses.at(-1).error, /API Key/);
});

test('取消 AudioWorklet 加载后不再创建音频节点', async () => {
  const wait = deferred();
  let created = 0;
  const source = { connect() {}, disconnect() {} };
  const ctx = vm.createContext({ console: quiet,
    chrome: { runtime: { getURL: x => x } },
    AudioContext: class {
      state = 'running'; destination = {}; audioWorklet = { addModule: () => wait.promise };
      createGain() { return { gain: {}, connect() {} }; }
      createMediaElementSource() { return source; }
    },
    AudioWorkletNode: class { constructor() { created++; } },
  });
  load(ctx, 'src/content/audio-tap.js');
  const tap = new ctx.LT.AudioTap({ onChunk() {}, onLevel() {} });
  const pending = tap.attach({});
  tap.detach();
  wait.resolve();
  await pending;
  assert.equal(created, 0);
  assert.equal(tap.node, null);
  assert.equal(tap.graph, null);
});

function socketHarness() {
  let now = 0, seq = 0;
  const timers = new Map(), sockets = [], states = [];
  const setTimer = (fn, ms) => { const id = ++seq; timers.set(id, { at: now + ms, fn }); return id; };
  const advance = ms => {
    const end = now + ms;
    while (true) {
      const due = [...timers].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      timers.delete(due[0]); now = due[1].at; due[1].fn();
    }
    now = end;
  };
  class Socket {
    static OPEN = 1;
    constructor() { this.readyState = 0; this.bufferedAmount = 0; this.sent = []; sockets.push(this); }
    send(x) { this.sent.push(x); }
    open() { this.readyState = 1; this.onopen(); this.onmessage({ data: '{"setupComplete":{}}' }); }
    close(code) { this.readyState = 3; this.onclose?.({ code }); }
  }
  const ctx = vm.createContext({ console: quiet, WebSocket: Socket, setTimeout: setTimer,
    clearTimeout: id => timers.delete(id), setInterval() {}, clearInterval() {},
    btoa: x => Buffer.from(x, 'binary').toString('base64'), LT: { MODEL: 'test', WS_PATH: '/test' },
  });
  load(ctx, 'src/content/gemini-live.js');
  const client = new ctx.LT.GeminiLiveClient({ keyProvider: () => 'test', baseUrl: 'wss://example.invalid',
    prompt: 'test', targetLang: 'zh', rotateAfterMs: 505000,
    listener: { onState: x => states.push(x), onInputText() {}, onOutputText() {} },
  });
  client.start(); sockets[0].open();
  return { client, sockets, states, advance };
}

test('断线撞上轮换只重连一次，停止后没有遗留连接', () => {
  const h = socketHarness();
  h.advance(504500);
  h.sockets[0].close(1006);
  assert.equal(h.states.at(-1), 'reconnecting');
  h.advance(500);
  assert.equal(h.sockets.length, 1);
  h.advance(500);
  h.sockets[1].open();
  assert.equal(h.sockets.length, 2);
  h.client.stop();
  assert.equal(h.sockets.filter(s => s.readyState !== 3).length, 0);
  h.advance(600000);
  assert.equal(h.sockets.length, 2);
});

test('正常轮换关闭旧连接并保留轮换提示', () => {
  const h = socketHarness();
  h.advance(505000);
  assert.equal(h.sockets.length, 2);
  assert.equal(h.sockets[0].readyState, 3);
  assert.equal(h.states.at(-1), 'rotating');
  h.sockets[0].onmessage({ data: '{"goAway":{}}' });
  assert.equal(h.sockets.length, 2);
  h.client.stop();
});

test('异常断线仍保留最近音频回填', () => {
  const h = socketHarness();
  h.client.feedChunk(new Uint8Array([1, 2]));
  h.sockets[0].onerror();
  h.sockets[0].close(1006);
  assert.equal(h.client.queue.length, 1);
  h.advance(1000);
  h.sockets[1].open();
  assert.equal(h.client.queue.length, 0);
  assert.equal(h.sockets[1].sent.length, 2);
  h.client.stop();
});
