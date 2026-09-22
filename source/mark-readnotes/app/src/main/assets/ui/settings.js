/* 设置板块 —— 由 app.js 按板块切分而来（见 docs/重构规格-第4轮.md） */
function openSettings() {
  const s = ST.src || {};
  const rows = [
    rowKV('笔记根', s.has ? s.rootLabel : '未选择'),
    rowKV('文件 / 块', (s.files || 0) + ' / ' + (s.blocks || 0)),
    rowKV('最近扫描', s.lastScanAt ? new Date(s.lastScanAt).toLocaleString() : '还没扫过')
  ];
  sheetShow('设置', rows, [
    { label: '刷新索引', fn: doRefresh },
    { label: '重新选择目录', fn: pickDir },
    { label: '关闭', fn: closeSheet }
  ]);
}
async function pickDir() {
  try { await call('src.pick'); toast('在系统窗口里选一个目录', 2600); }
  catch (e) { toast('打不开系统选择器：' + e.message, 3000); }
}
async function doRefresh() {
  toast('正在刷新索引…', 2000);
  try {
    const r = await call('idx.refresh');
    toast('已刷新：' + r.files + ' 个文件、' + r.blocks + ' 个块', 2600);
    await reload();
  } catch (e) { toast('刷新失败：' + e.message, 3000); }
}
