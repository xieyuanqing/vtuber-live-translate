/**
 * 页面桥，跑在 MAIN world（和 YouTube 自己的脚本同一个 JS 环境）。
 *
 * 唯一职责：把播放器的结构化元数据递给内容脚本。
 * 走 movie_player.getPlayerResponse() 而不是解析 DOM，也不用 ytInitialPlayerResponse：
 * - 比 DOM 稳，不依赖会变的 class 名，简介不用展开就是全文
 * - SPA 切视频后 ytInitialPlayerResponse 是旧的，getPlayerResponse() 永远是当前视频
 *
 * 这里不能用 chrome.* API（MAIN world 没有扩展 API），只能 window.postMessage。
 * 消息名要和 src/common/constants.js 里的 LT.BRIDGE 对上。
 */
(() => {
  const TAG = 'lt-bridge';

  function read() {
    let response = null;
    try {
      const player = document.getElementById('movie_player');
      if (player && typeof player.getPlayerResponse === 'function') {
        response = player.getPlayerResponse();
      }
    } catch (_) {
      /* 播放器还没初始化 */
    }
    if (!response || !response.videoDetails) {
      response = window.ytInitialPlayerResponse || null;
    }
    const d = response && response.videoDetails;
    if (!d) return null;

    const micro =
      response.microformat && response.microformat.playerMicroformatRenderer;
    return {
      videoId: d.videoId || '',
      title: d.title || '',
      author: d.author || '',
      description: d.shortDescription || '',
      keywords: Array.isArray(d.keywords) ? d.keywords : [],
      isLive: !!(d.isLive || d.isLiveNow),
      isLiveContent: !!d.isLiveContent,
      lengthSeconds: Number(d.lengthSeconds || 0),
      category: (micro && micro.category) || '',
    };
  }

  function post(id, payload) {
    window.postMessage({ __lt: TAG, dir: 'meta', id, payload }, '*');
  }

  window.addEventListener('message', (e) => {
    if (e.source !== window) return;
    const msg = e.data;
    if (!msg || msg.__lt !== TAG || msg.dir !== 'req') return;
    post(msg.id, read());
  });

  // SPA 切视频后主动推一次，内容脚本不用轮询
  document.addEventListener('yt-navigate-finish', () => {
    setTimeout(() => post(0, read()), 300);
  });
})();
