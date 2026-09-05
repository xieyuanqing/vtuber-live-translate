/**
 * 从 <video> 元素旁路取音频，输出 100ms / 16kHz / PCM16 块。
 *
 * 两个必须记住的点：
 * 1. createMediaElementSource 之后，元素的声音就永久改走这个 AudioContext 了。
 *    所以 source 必须一直接着 ctx.destination，而且 context 绝不能 close，
 *    否则用户就再也听不见这个视频的声音。停止翻译时只断开旁路节点。
 * 2. 一个 <video> 元素只能建一次 MediaElementSource，重复建会抛 InvalidStateError。
 *    所以按元素缓存，切场景复用。
 */
globalThis.LT = globalThis.LT || {};

(() => {
  const LT = globalThis.LT;
  const hooked = new WeakMap(); // video 元素 → { ctx, source, sink }

  // 整页共用一个 AudioContext。Chrome 每个页面只允许约 6 个 AudioContext，
  // 而 YouTube 每次换视频都可能重建 <video>，一个元素一个 context 用不了几次就爆。
  let sharedCtx = null;
  let sharedSink = null;

  function ensureContext() {
    if (sharedCtx) return sharedCtx;
    sharedCtx = new AudioContext(); // 保持设备原生采样率，别设 16000，那会降低播放音质
    sharedSink = sharedCtx.createGain();
    sharedSink.gain.value = 0; // 旁路节点得有一条通往 destination 的路才会被调度，但不能出声
    sharedSink.connect(sharedCtx.destination);
    return sharedCtx;
  }

  /** 拿到（或复用）这个元素的音频图，并保证原声照常播放。 */
  function ensureGraph(video) {
    let g = hooked.get(video);
    if (g) return g;
    const ctx = ensureContext();
    const source = ctx.createMediaElementSource(video);
    source.connect(ctx.destination); // 原声路径，从此不能断
    g = { ctx, source, sink: sharedSink };
    hooked.set(video, g);
    return g;
  }

  class AudioTap {
    /**
     * @param {{onChunk:(u8:Uint8Array)=>void, onLevel:(pct:number)=>void,
     *          onError:(msg:string)=>void}} handlers
     */
    constructor(handlers) {
      this.handlers = handlers;
      this.video = null;
      this.node = null;
      this.graph = null;
      this.mode = '';
      this.gate = true; // false 时丢弃采集到的块（广告 / 暂停 / 静音）
    }

    get active() {
      return !!this.node;
    }

    setGate(open) {
      this.gate = !!open;
    }

    async attach(video) {
      if (this.node) this.detach();
      this.video = video;
      const graph = ensureGraph(video);
      this.graph = graph;
      if (graph.ctx.state === 'suspended') {
        await graph.ctx.resume().catch(() => {});
      }

      const onBuf = (buf) => {
        if (!this.gate) return;
        const u8 = new Uint8Array(buf);
        this.handlers.onChunk(u8);
        this.#reportLevel(buf);
      };

      // 优先 AudioWorklet（独立音频线程，YouTube 主线程再忙也不丢块）。
      // 扩展资源加载在个别页面 CSP 下可能失败，失败就退回 ScriptProcessorNode。
      try {
        await graph.ctx.audioWorklet.addModule(
          chrome.runtime.getURL('src/audio/pcm16k.js')
        );
        const node = new AudioWorkletNode(graph.ctx, 'lt-pcm16k', {
          numberOfInputs: 1,
          numberOfOutputs: 1,
          outputChannelCount: [1],
        });
        node.port.onmessage = (e) => {
          if (e.data && e.data.type === 'chunk') onBuf(e.data.buf);
        };
        graph.source.connect(node);
        node.connect(graph.sink);
        this.node = node;
        this.mode = 'worklet';
      } catch (err) {
        console.warn('[流译] AudioWorklet 不可用，回退 ScriptProcessor：', err);
        const bufferSize = 2048; // ≈43ms @48k，主线程处理，延迟可接受
        const node = graph.ctx.createScriptProcessor(bufferSize, 2, 1);
        const resampler = new globalThis.LtPcmResampler(graph.ctx.sampleRate, onBuf);
        node.onaudioprocess = (e) => {
          const inBuf = e.inputBuffer;
          const chans = [];
          for (let i = 0; i < inBuf.numberOfChannels; i++) chans.push(inBuf.getChannelData(i));
          resampler.feed(chans, inBuf.length);
        };
        graph.source.connect(node);
        node.connect(graph.sink);
        this.node = node;
        this.mode = 'script-processor';
      }
      return this.mode;
    }

    detach() {
      const node = this.node;
      this.node = null;
      if (!node) return;
      try {
        if (node.port) node.port.postMessage({ type: 'stop' });
        node.onaudioprocess = null;
        node.disconnect();
        if (this.graph) this.graph.source.disconnect(node);
      } catch (_) {
        /* 节点已经不在图里，忽略 */
      }
      // 注意：不断开 source→destination，也不 close ctx，否则页面声音会没了
      this.video = null;
      this.graph = null;
      this.mode = '';
    }

    #reportLevel(buf) {
      if (!this.handlers.onLevel) return;
      this.levelTick = (this.levelTick || 0) + 1;
      if (this.levelTick % 5 !== 0) return; // 每 500ms 报一次够用了
      const pcm = new Int16Array(buf);
      let sum = 0;
      for (let i = 0; i < pcm.length; i += 4) sum += (pcm[i] / 32768) ** 2;
      const rms = Math.sqrt(sum / Math.ceil(pcm.length / 4));
      this.handlers.onLevel(Math.max(0, Math.min(100, Math.round(rms * 180))));
    }
  }

  LT.AudioTap = AudioTap;
})();
