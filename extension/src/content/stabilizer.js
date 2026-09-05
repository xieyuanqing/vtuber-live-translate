/**
 * 字幕稳定器，移植自安卓版 SubtitleStabilizer.kt。
 * 把 Live Translate 的碎片输出整理成「确认行 + 当前行」：
 * - 碎片边界重叠合并（服务端偶尔把结尾几个字重发一遍）
 * - 句末标点切句；与上一句相同/被包含的句子直接丢弃（治复读）
 * - idleCommitMs 无新碎片、或当前行超 maxCurrentChars 时强制转正
 *
 * 与安卓版的唯一差别：onRender 直接把本次新转正的句子数组交出去。
 * 安卓那边是在 CaptureService 里靠对比字符串变化反推的，这里没必要绕。
 */
globalThis.LT = globalThis.LT || {};

(() => {
  const LT = globalThis.LT;
  const TERMINATORS = new Set(['。', '！', '？', '…', '～', '!', '?']);

  class SubtitleStabilizer {
    /**
     * @param {{idleCommitMs:number, maxCurrentChars:number,
     *          onRender:(current:string, committed:string[])=>void}} opts
     */
    constructor(opts) {
      this.idleCommitMs = opts.idleCommitMs;
      this.maxCurrentChars = opts.maxCurrentChars;
      this.onRender = opts.onRender;
      this.current = '';
      this.lastCommitted = '';
      this.idleTimer = null;
    }

    onFragment(t) {
      if (!t) return;
      this.#appendWithOverlap(t);
      const committed = this.#commit(this.current.length >= this.maxCurrentChars);
      clearTimeout(this.idleTimer);
      if (this.current.length > 0) {
        this.idleTimer = setTimeout(() => {
          const late = this.#commit(true);
          this.onRender(this.current, late);
        }, this.idleCommitMs);
      }
      this.onRender(this.current, committed);
    }

    /** 会话结束时把残留的当前行转正。 */
    flush() {
      clearTimeout(this.idleTimer);
      const committed = this.#commit(true);
      if (committed.length) this.onRender(this.current, committed);
      return committed;
    }

    reset() {
      clearTimeout(this.idleTimer);
      this.current = '';
      this.lastCommitted = '';
    }

    /** 若新碎片的开头和缓冲区结尾重叠（>=2 字），只追加不重叠的部分。 */
    #appendWithOverlap(frag) {
      const tail = this.current;
      let k = Math.min(tail.length, frag.length);
      while (k > 0) {
        if (tail.slice(tail.length - k) === frag.slice(0, k)) break;
        k--;
      }
      if (k < 2) k = 0; // 单字重叠多半是巧合（"…的" + "的…"），不按重叠处理
      this.current = tail + frag.slice(k);
    }

    /** 把 current 中已到句末的部分切出去转正。force = 无句末标点也全部转正。 */
    #commit(force) {
      const text = this.current;
      let cut = -1;
      for (let i = text.length - 1; i >= 0; i--) {
        if (TERMINATORS.has(text[i])) {
          cut = i;
          break;
        }
      }
      let done;
      let rest;
      if (cut >= 0) {
        done = text.slice(0, cut + 1);
        rest = text.slice(cut + 1);
      } else if (force && text.trim()) {
        done = text;
        rest = '';
      } else {
        return [];
      }
      this.current = rest;

      const committed = [];
      for (const raw of this.#splitSentences(done)) {
        const sentence = raw.trim();
        if (!sentence) continue;
        // 连续重复：和上一句相同，或整句包含在上一句里 → 丢弃
        if (sentence === this.lastCommitted || this.lastCommitted.includes(sentence)) continue;
        this.lastCommitted = sentence;
        committed.push(sentence);
      }
      // 一个服务端碎片可能同时包含多句；去重基线取合并后的整段
      if (committed.length > 0) this.lastCommitted = committed.join('');
      return committed;
    }

    #splitSentences(s) {
      const out = [];
      let cur = '';
      for (const ch of s) {
        cur += ch;
        if (TERMINATORS.has(ch)) {
          out.push(cur);
          cur = '';
        }
      }
      if (cur.trim()) out.push(cur);
      return out;
    }
  }

  LT.SubtitleStabilizer = SubtitleStabilizer;
})();
