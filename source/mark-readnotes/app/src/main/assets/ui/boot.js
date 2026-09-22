/* 启动装配 —— 由 app.js 按板块切分而来（见 docs/重构规格-第4轮.md） */
function onPush(type, data) {
  if (type === 'src.changed') {
    if (data.cancelled) { toast('没有选择目录'); return; }
    if (data.error) { toast('目录不可用：' + data.error, 3200); return; }
    closeSheet();
    toast('笔记根：' + data.pickedName + '/mark-readnotes', 2600);
    reload();
  }
  if (type === 'exp.changed') {
    if (data.cancelled) { exPendingWrite = false; toast('没有选择导出目录'); return; }
    if (data.error) { exPendingWrite = false; toast('导出目录不可用：' + data.error, 3200); return; }
    toast('导出目录：' + data.rootLabel, 2600);
    if (exPendingWrite) { exPendingWrite = false; doWriteDir(); }
    else if (sheetOpen()) renderExport();
  }
}

/* ---------- 启动 ---------- */
(function boot() {
  attachBridge();
  const timer = setInterval(function () { attachBridge(); if (B.ready) clearInterval(timer); }, 60);

  window.mrBack = function () {
    if (sheetOpen()) { closeSheet(); return true; }
    if (board.open) { doneBoard(); return true; }   // 返回键先保存再收白板
    return false;
  };
  window.mrState = function () {   // 验收用：页面自报真实状态
    const r = el('board').getBoundingClientRect();
    const imgs = Array.from(document.querySelectorAll('#preview img'));
    return { build: document.body.dataset.build, bridge: B.ready, boardOpen: board.open, boardId: board.id,
      boardRect: { x: r.x, y: r.y, w: r.width, h: r.height },
      cards: ST.blocks.length, src: ST.src, previewOn: previewOn,
      tex: window.__tex || null,
      imgs: imgs.map(function (i) { return { src: i.getAttribute('src'), ok: i.complete && i.naturalWidth > 0, w: i.naturalWidth, h: i.naturalHeight }; }) };
  };

  el('btn-new').addEventListener('click', function () { openBoard(null); });
  el('btn-export').addEventListener('click', openExport);
  el('btn-settings').addEventListener('click', openSettings);
  el('btn-pick-empty').addEventListener('click', pickDir);
  el('btn-done').addEventListener('click', doneBoard);
  el('btn-preview').addEventListener('click', function () { setPreview(!previewOn); });
  el('btn-more').addEventListener('click', openBlockMore);
  el('sheet-close').addEventListener('click', closeSheet);
  ['ed-heading', 'ed-tags', 'ed-body'].forEach(function (id) {
    el(id).addEventListener('input', function () {
      board.dirty = true;
      if (id === 'ed-tags') updateTagHint();
    });
  });
  el('ed-tags').addEventListener('focus', function () { this.select(); });  // 短字段：点进去即全选，打字就是替换
  // 搜索（有一个字就打一次 op；清空回到最近编辑）
  el('q').addEventListener('input', function () {
    const v = this.value;
    el('q-clear').classList.toggle('hidden', !v);
    clearTimeout(ST.searchTimer);
    ST.searchTimer = setTimeout(function () { runSearch(v); }, 200);
  });
  el('q-clear').addEventListener('click', function () {
    el('q').value = '';
    el('q-clear').classList.add('hidden');
    runSearch('');
  });
  setInterval(autosave, 10000);
  call('app.info').then(function (i) { ST.info = i; }).catch(function () { });
  reload();
})();
