(() => {
  const LT = globalThis.LT;
  const $ = (id) => document.getElementById(id);

  let settings = LT.DEFAULTS;
  let saveTimer = null;

  const TEXT_FIELDS = ['apiKeys', 'baseUrl', 'manualContext'];
  const SELECT_FIELDS = ['sourceLang', 'targetLang'];
  const CHECK_FIELDS = [
    'autoStartLive',
    'pauseOnAd',
    'useMetadata',
    'showSource',
    'echoTargetLanguage',
  ];
  const RANGE_FIELDS = [
    'metadataLimit',
    'captionLines',
    'captionScale',
    'captionBottom',
    'captionOpacity',
    'rotateSeconds',
    'stabIdleMs',
    'stabMaxChars',
  ];

  // ---------- 保存 ----------

  function flashSaved() {
    const el = $('saved');
    el.textContent = '已保存';
    el.classList.add('flash');
    clearTimeout(flashSaved.t);
    flashSaved.t = setTimeout(() => {
      el.textContent = '改动即时保存';
      el.classList.remove('flash');
    }, 1200);
  }

  async function notifyTabs() {
    try {
      const tabs = await chrome.tabs.query({ url: 'https://www.youtube.com/*' });
      for (const tab of tabs) {
        if (tab.id != null) {
          chrome.tabs
            .sendMessage(tab.id, { type: LT.MSG.SETTINGS_CHANGED })
            .catch(() => {});
        }
      }
    } catch (_) {
      /* 没有打开的 YouTube 页面 */
    }
  }

  function queueSave(patch) {
    Object.assign(settings, patch);
    renderPreview();
    clearTimeout(saveTimer);
    saveTimer = setTimeout(async () => {
      // 不要用返回值覆盖 settings：场景卡片的事件闭包持有当前这些对象引用，
      // 换成存储里反序列化出来的新对象后，下一次输入就会写丢。
      await LT.Settings.save(settings);
      flashSaved();
      notifyTabs();
    }, 250);
  }

  // ---------- 场景库 ----------

  function renderScenes() {
    const box = $('scenes');
    box.replaceChildren();

    settings.scenes.forEach((scene) => {
      const isDefault = scene.id === settings.sceneId;
      const card = document.createElement('div');
      card.className = isDefault ? 'scene default' : 'scene';

      const head = document.createElement('div');
      head.className = 'head';

      const name = document.createElement('input');
      name.type = 'text';
      name.value = scene.label;
      name.placeholder = '场景名称';
      // 按 ID 原位更新，不重排、不新建条目
      name.addEventListener('input', () => {
        scene.label = name.value;
        queueSave({});
      });

      head.appendChild(name);

      if (isDefault) {
        const badge = document.createElement('span');
        badge.className = 'badge';
        badge.textContent = '默认';
        head.appendChild(badge);
      } else {
        const use = document.createElement('button');
        use.textContent = '设为默认';
        use.addEventListener('click', () => {
          queueSave({ sceneId: scene.id });
          renderScenes();
        });
        head.appendChild(use);
      }

      const del = document.createElement('button');
      del.className = 'danger';
      del.textContent = '删除';
      del.disabled = settings.scenes.length <= 1;
      del.addEventListener('click', () => {
        if (!confirm(`确定删除场景「${scene.label}」？`)) return;
        settings.scenes = settings.scenes.filter((s) => s.id !== scene.id);
        if (settings.sceneId === scene.id) settings.sceneId = settings.scenes[0].id;
        queueSave({});
        renderScenes();
      });
      head.appendChild(del);

      const instr = document.createElement('textarea');
      instr.rows = 3;
      instr.value = scene.instruction;
      instr.placeholder = '这个场景要告诉模型什么（口吻、术语、专名处理…）';
      instr.addEventListener('input', () => {
        scene.instruction = instr.value;
        queueSave({});
      });

      card.append(head, instr);
      box.appendChild(card);
    });
  }

  // ---------- 提示词预览 ----------

  function renderPreview() {
    const scene = LT.Settings.scene(settings);
    const sampleMeta = {
      title: '（当前视频标题）',
      author: '（频道名）',
      isLive: true,
      category: '（分类）',
      keywords: ['（标签1）', '（标签2）'],
      description: '（视频简介，最多 ' + settings.metadataLimit + ' 字）',
    };
    const metadataText = settings.useMetadata
      ? LT.Prompt.formatMetadata(sampleMeta, settings.metadataLimit)
      : '';
    $('preview').textContent = LT.Prompt.build({
      scene,
      sourceLang: settings.sourceLang,
      targetLang: settings.targetLang,
      metadataText,
      manualContext: settings.manualContext,
    });
  }

  // ---------- 绑定 ----------

  function syncRangeLabel(id) {
    const label = $(`${id}Val`);
    if (label) label.textContent = String(settings[id]);
  }

  function bind() {
    for (const id of TEXT_FIELDS) {
      const el = $(id);
      el.value = settings[id];
      el.addEventListener('input', () => queueSave({ [id]: el.value }));
    }

    for (const id of SELECT_FIELDS) {
      const el = $(id);
      const list = id === 'sourceLang' ? LT.SOURCE_LANGS : LT.TARGET_LANGS;
      el.replaceChildren();
      for (const lang of list) {
        const opt = document.createElement('option');
        opt.value = lang.code;
        opt.textContent = lang.label;
        el.appendChild(opt);
      }
      el.value = settings[id];
      el.addEventListener('change', () => queueSave({ [id]: el.value }));
    }

    for (const id of CHECK_FIELDS) {
      const el = $(id);
      el.checked = !!settings[id];
      el.addEventListener('change', () => queueSave({ [id]: el.checked }));
    }

    for (const id of RANGE_FIELDS) {
      const el = $(id);
      el.value = settings[id];
      syncRangeLabel(id);
      el.addEventListener('input', () => {
        const v = id === 'captionScale' ? parseFloat(el.value) : parseInt(el.value, 10);
        settings[id] = v;
        syncRangeLabel(id);
        queueSave({ [id]: v });
      });
    }

    $('addScene').addEventListener('click', () => {
      settings.scenes.push({
        id: `custom-${Date.now()}`,
        label: '新场景',
        instruction: '',
      });
      queueSave({});
      renderScenes();
    });

    $('resetScenes').addEventListener('click', () => {
      if (!confirm('恢复默认场景模板？你自己加的场景会被清掉。')) return;
      settings.scenes = JSON.parse(JSON.stringify(LT.DEFAULT_SCENES));
      settings.sceneId = settings.scenes[0].id;
      queueSave({});
      renderScenes();
    });
  }

  (async () => {
    settings = await LT.Settings.load();
    bind();
    renderScenes();
    renderPreview();
  })();
})();
