/**
 * PCM 处理：立体声混单声道 → 线性插值重采样到 16kHz → 切成 100ms（1600 采样 / 3200 字节）块。
 * 重采样相位跨缓冲区保持连续。算法移植自安卓版 PcmProcessor.kt。
 *
 * 这个文件会被加载两次，两种作用域：
 * 1. 作为内容脚本 → 只定义 LtPcmResampler，给 ScriptProcessorNode 兜底路径用
 * 2. 作为 AudioWorklet 模块 → 额外注册 lt-pcm16k 处理器
 * 所以这里不能依赖 LT 命名空间，也不能在顶层碰 sampleRate / AudioWorkletProcessor。
 */
(() => {
  const SAMPLES_PER_CHUNK = 1600; // 100ms @ 16kHz
  const TARGET_RATE = 16000;

  class LtPcmResampler {
    /**
     * @param {number} srcRate 源采样率
     * @param {(buf: ArrayBuffer) => void} onChunk 每满 100ms 回调一次（PCM16 LE）
     */
    constructor(srcRate, onChunk) {
      this.step = srcRate / TARGET_RATE;
      this.onChunk = onChunk;
      this.pending = new Int16Array(SAMPLES_PER_CHUNK);
      this.pendingLen = 0;
      this.phase = 0;
      this.last = 0;
      this.haveLast = false;
      this.scratch = null;
    }

    /**
     * @param {Float32Array[]} channels 每声道一条
     * @param {number} frames 帧数
     */
    feed(channels, frames) {
      if (!frames || !channels || channels.length === 0) return;

      // 1) 混单声道
      let mono;
      if (channels.length >= 2) {
        if (!this.scratch || this.scratch.length < frames) {
          this.scratch = new Float32Array(frames);
        }
        mono = this.scratch;
        const a = channels[0];
        const b = channels[1];
        for (let i = 0; i < frames; i++) mono[i] = (a[i] + b[i]) * 0.5;
      } else {
        mono = channels[0];
      }

      // 2) 线性插值重采样。ext[0] 是上一批的最后一个样本，保证跨批连续。
      const n = frames;
      let pos = this.phase;
      const prev = this.haveLast ? this.last : mono[0];
      while (pos < n) {
        const i = Math.floor(pos);
        const frac = pos - i;
        const s0 = i === 0 ? prev : mono[i - 1];
        const s1 = mono[i];
        this.#emit(s0 + (s1 - s0) * frac);
        pos += this.step;
      }
      this.phase = pos - n;
      this.last = mono[n - 1];
      this.haveLast = true;
    }

    #emit(v) {
      const c = v < -1 ? -1 : v > 1 ? 1 : v;
      // Int16Array 用平台字节序，x86/ARM 都是小端，正好是接口要的 PCM16 LE。
      this.pending[this.pendingLen++] = c < 0 ? c * 0x8000 : c * 0x7fff;
      if (this.pendingLen === SAMPLES_PER_CHUNK) {
        this.pendingLen = 0;
        const copy = this.pending.slice();
        this.onChunk(copy.buffer);
      }
    }
  }

  globalThis.LtPcmResampler = LtPcmResampler;
  globalThis.LT_PCM_CHUNK_BYTES = SAMPLES_PER_CHUNK * 2;

  // ---------- 仅在 AudioWorklet 作用域生效 ----------
  if (
    typeof registerProcessor === 'function' &&
    typeof AudioWorkletProcessor === 'function'
  ) {
    class LtPcm16kProcessor extends AudioWorkletProcessor {
      constructor() {
        super();
        this.alive = true;
        this.resampler = new LtPcmResampler(sampleRate, (buf) => {
          this.port.postMessage({ type: 'chunk', buf }, [buf]);
        });
        this.port.onmessage = (e) => {
          if (e.data && e.data.type === 'stop') this.alive = false;
        };
      }

      process(inputs) {
        if (!this.alive) return false;
        const input = inputs[0];
        if (input && input.length > 0 && input[0] && input[0].length > 0) {
          this.resampler.feed(input, input[0].length);
        }
        return true;
      }
    }

    registerProcessor('lt-pcm16k', LtPcm16kProcessor);
  }
})();
