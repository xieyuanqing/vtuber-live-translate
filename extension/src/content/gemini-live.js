/**
 * Gemini Live Translate WebSocket 客户端，移植自安卓版 GeminiLiveClient.kt。
 *
 * - setup 结构照 docs/02-tech-notes.md，字段位置不能动
 * - 单连接约 590s 被服务端 GoAway 断开：505s 主动轮换 + goAway 消息即时轮换
 * - 异常断线：指数退避重连，并把最近 1 秒已发送音频塞回队首弥补断点
 * - 音频块进有界队列，断线期间自动积压、恢复后追发
 *
 * 安卓版为了串行化连接生命周期用了单线程 scheduler；浏览器只有一条事件循环，
 * 那套线程约定天然成立，但 generation 计数仍然必须保留：轮换/重连之后，
 * 旧 socket 的 onclose / onmessage 还会继续到达，得靠代际号作废掉。
 */
globalThis.LT = globalThis.LT || {};

(() => {
  const LT = globalThis.LT;

  const MAX_QUEUE = 200; // 约 20 秒积压上限，超出丢最旧
  const OVERLAP_CHUNKS = 10; // 异常断线重发最近 1 秒
  const HANDSHAKE_TIMEOUT_MS = 12000;
  const MAX_BUFFERED = 512 * 1024;

  function toBase64(u8) {
    let s = '';
    for (let i = 0; i < u8.length; i += 0x8000) {
      s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
    }
    return btoa(s);
  }

  /** WebSocket 拿不到 HTTP 状态码，只能按关闭码猜原因，否则用户完全看不出为什么连不上。 */
  function handshakeHint(code) {
    if (code === 1006) return '连不上服务器（检查网络代理是否放行 generativelanguage.googleapis.com）';
    if (code === 1008) return '被服务端拒绝（多半是 API Key 无效或没有 Live API 权限）';
    if (code === 1007 || code === 1002) return '服务端拒绝了 setup 配置';
    return `握手失败（close ${code}）`;
  }

  class GeminiLiveClient {
    /**
     * @param {{keyProvider:()=>string, baseUrl:string, prompt:string, targetLang:string,
     *          echoTargetLanguage:boolean, rotateAfterMs:number,
     *          listener:{onState:(s:string)=>void, onInputText:(t:string)=>void,
     *                    onOutputText:(t:string)=>void}}} opts
     */
    constructor(opts) {
      this.opts = opts;
      this.listener = opts.listener;

      this.queue = [];
      this.sentRing = [];
      this.running = false;
      this.ws = null;
      this.ready = false;
      this.generation = 0;
      this.reconnectDelayMs = 1000;
      this.failedHandshakes = 0;
      this.chunksSent = 0;

      this.rotateTimer = null;
      this.watchdogTimer = null;
      this.reconnectTimer = null;
      this.drainTimer = null;
    }

    start() {
      if (this.running) return;
      this.running = true;
      this.drainTimer = setInterval(() => this.#drain(), 100);
      this.#connect();
    }

    stop() {
      if (!this.running) return;
      this.running = false;
      this.generation++;
      clearTimeout(this.rotateTimer);
      clearTimeout(this.watchdogTimer);
      clearTimeout(this.reconnectTimer);
      clearInterval(this.drainTimer);
      const ws = this.ws;
      this.ws = null;
      this.ready = false;
      try {
        if (ws) ws.close(1000, 'bye');
      } catch (_) {
        /* 已经关了 */
      }
      this.queue.length = 0;
      this.sentRing.length = 0;
      this.#state('stopped');
    }

    /** 采集侧调用：塞入一块 100ms/16k/mono 的 PCM。 */
    feedChunk(u8) {
      if (!this.running) return;
      if (this.queue.length >= MAX_QUEUE) this.queue.shift();
      this.queue.push(u8);
      this.#drain();
    }

    // ---------- 连接管理 ----------

    #connect() {
      if (!this.running) return;
      this.ready = false;
      clearTimeout(this.rotateTimer);
      const gen = ++this.generation;
      this.#state(gen === 1 ? 'connecting' : 'reconnecting');

      const key = this.opts.keyProvider();
      if (!key) {
        this.#state('error:未配置 API Key');
        return;
      }
      const url =
        this.opts.baseUrl.replace(/\/+$/, '') +
        LT.WS_PATH +
        '?key=' +
        encodeURIComponent(key);

      let ws;
      try {
        ws = new WebSocket(url);
      } catch (err) {
        this.#state('error:' + (err && err.message ? err.message : '无法创建连接'));
        this.#scheduleReconnect(true);
        return;
      }
      ws.binaryType = 'arraybuffer';
      this.ws = ws;

      let sawError = false;
      ws.onopen = () => {
        if (gen !== this.generation) return;
        ws.send(this.#buildSetupJson());
      };
      ws.onerror = () => {
        sawError = true;
      };
      ws.onmessage = (e) => {
        if (gen !== this.generation || !this.running) return;
        const text =
          typeof e.data === 'string'
            ? e.data
            : new TextDecoder().decode(new Uint8Array(e.data));
        this.#handle(gen, text);
      };
      ws.onclose = (e) => {
        if (gen !== this.generation || !this.running) return;
        if (!this.ready) {
          this.failedHandshakes++;
          if (this.failedHandshakes >= 2) {
            this.#state('error:' + handshakeHint(e.code));
          }
        }
        this.#scheduleReconnect(sawError || !this.ready);
      };

      this.#armWatchdog(gen);
    }

    /** 连上却拿不到 setupComplete（卡在握手）时强制重连自愈。 */
    #armWatchdog(gen) {
      clearTimeout(this.watchdogTimer);
      this.watchdogTimer = setTimeout(() => {
        if (!this.running || this.generation !== gen || this.ready) return;
        console.warn('[流译] 握手超时，强制重连 gen=' + gen);
        const old = this.ws;
        this.generation++; // 作废旧连接回调，避免它的 onclose 再触发一次重连
        try {
          if (old) old.close(4000, 'handshake timeout');
        } catch (_) {
          /* ignore */
        }
        this.#scheduleReconnect(true);
      }, HANDSHAKE_TIMEOUT_MS);
    }

    /**
     * 轮换。fromGen 是触发时的代际：定时任务与 goAway 可能同时排队，
     * 后到的看到代际已推进就跳过，避免重复轮换。
     */
    #rotate(fromGen) {
      if (!this.running || this.generation !== fromGen) return;
      this.#state('rotating');
      const old = this.ws;
      this.#connect(); // generation++ 之后旧连接回调全部作废
      try {
        if (old) old.close(1000, 'rotate');
      } catch (_) {
        /* ignore */
      }
    }

    #scheduleReconnect(abrupt) {
      if (!this.running) return;
      this.ready = false;
      clearTimeout(this.watchdogTimer);
      clearTimeout(this.reconnectTimer);
      if (abrupt) this.#prependOverlap();
      const delay = this.reconnectDelayMs;
      this.reconnectDelayMs = Math.min(this.reconnectDelayMs * 2, 15000);
      this.reconnectTimer = setTimeout(() => {
        if (this.running) this.#connect();
      }, delay);
    }

    #prependOverlap() {
      if (this.sentRing.length === 0) return;
      this.queue = this.sentRing.concat(this.queue);
      this.sentRing.length = 0;
      if (this.queue.length > MAX_QUEUE) {
        this.queue.splice(0, this.queue.length - MAX_QUEUE);
      }
    }

    // ---------- 收发 ----------

    #handle(gen, text) {
      let o;
      try {
        o = JSON.parse(text);
      } catch (_) {
        console.warn('[流译] 无法解析的消息：', text.slice(0, 120));
        return;
      }

      if (o.setupComplete) {
        this.ready = true;
        this.reconnectDelayMs = 1000;
        this.failedHandshakes = 0;
        clearTimeout(this.watchdogTimer);
        clearTimeout(this.rotateTimer);
        this.rotateTimer = setTimeout(() => this.#rotate(gen), this.opts.rotateAfterMs);
        this.#state('ready');
        this.#drain();
        return;
      }
      if (o.goAway) {
        this.#rotate(gen);
        return;
      }

      const sc = o.serverContent;
      if (!sc) return;
      const input = sc.inputTranscription && sc.inputTranscription.text;
      if (input) this.listener.onInputText(input);
      const output = sc.outputTranscription && sc.outputTranscription.text;
      if (output) this.listener.onOutputText(output);
      // modelTurn 里的翻译语音块直接忽略，不播放
    }

    #drain() {
      const ws = this.ws;
      if (!this.ready || !ws || ws.readyState !== WebSocket.OPEN) return;
      while (this.queue.length > 0) {
        if (ws.bufferedAmount > MAX_BUFFERED) break;
        const chunk = this.queue.shift();
        try {
          ws.send(
            '{"realtimeInput":{"audio":{"data":"' +
              toBase64(chunk) +
              '","mimeType":"audio/pcm;rate=16000"}}}'
          );
        } catch (err) {
          this.queue.unshift(chunk);
          break;
        }
        this.sentRing.push(chunk);
        if (this.sentRing.length > OVERLAP_CHUNKS) this.sentRing.shift();
        this.chunksSent++;
      }
    }

    #buildSetupJson() {
      const setup = {
        model: LT.MODEL,
        generationConfig: {
          responseModalities: ['AUDIO'],
          translationConfig: {
            targetLanguageCode: this.opts.targetLang,
            echoTargetLanguage: !!this.opts.echoTargetLanguage,
          },
        },
        // 这两个必须在 setup 顶层，放进 generationConfig 会 close 1007
        inputAudioTranscription: {},
        outputAudioTranscription: {},
      };
      if (this.opts.prompt && this.opts.prompt.trim()) {
        setup.systemInstruction = { parts: [{ text: this.opts.prompt }] };
      }
      return JSON.stringify({ setup });
    }

    #state(s) {
      this.listener.onState(s);
    }
  }

  LT.GeminiLiveClient = GeminiLiveClient;
})();
