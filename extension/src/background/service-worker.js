/**
 * Service Worker：只做三件轻活——首次写入默认设置、维护标签页角标、转发快捷键。
 * MV3 的 SW 随时会被杀掉，所以这里不保存任何会话状态，会话完全活在内容脚本里。
 */
importScripts('/src/common/constants.js', '/src/common/settings.js');

const LT = globalThis.LT;

const BADGE = {
  ready: { text: 'ON', color: '#2e7d32' },
  connecting: { text: '···', color: '#ef6c00' },
  reconnecting: { text: '···', color: '#ef6c00' },
  rotating: { text: '···', color: '#ef6c00' },
  error: { text: '!', color: '#c62828' },
  idle: { text: '', color: '#000000' },
};

function paintBadge(tabId, status) {
  let key = 'idle';
  if (status.error) key = 'error';
  else if (status.phase === 'running') key = status.conn === 'ready' ? 'ready' : 'connecting';
  else if (status.phase === 'starting') key = 'connecting';

  const badge = BADGE[key] || BADGE.idle;
  chrome.action.setBadgeText({ tabId, text: badge.text }).catch(() => {});
  chrome.action.setBadgeBackgroundColor({ tabId, color: badge.color }).catch(() => {});
}

chrome.runtime.onInstalled.addListener(async () => {
  // 只补齐缺失字段，不覆盖用户已有设置
  const current = await LT.Settings.load();
  await LT.Settings.save(current);
});

chrome.runtime.onMessage.addListener((msg, sender) => {
  if (!msg || msg.type !== LT.MSG.STATUS) return;
  if (sender.tab && typeof sender.tab.id === 'number') {
    paintBadge(sender.tab.id, msg.payload || {});
  }
});

chrome.commands.onCommand.addListener(async (command) => {
  if (command !== 'toggle-session') return;
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab || !tab.id) return;
  chrome.tabs.sendMessage(tab.id, { type: LT.MSG.TOGGLE }).catch(() => {});
});
