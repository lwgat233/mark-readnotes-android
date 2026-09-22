/* 设置板块 —— 由 app.js 按板块切分而来（见 docs/重构规格-第4轮.md）
   本轮的规矩：底部常驻键只留 3 个（刷新索引 / 重新选择目录 / 关闭）；
   新建文件夹、标签→文件夹、整理 这类动作**挨着它们自己的输入框放在同一行**，不往底部堆。 */
function openSettings() {
  const s = ST.src || {};
  const rows = [
    rowKV('随笔目录', s.has ? s.nodeLabel : '未选择'),
    rowKV('文件 / 块', (s.files || 0) + ' / ' + (s.blocks || 0)),
    rowKV('最近扫描', s.lastScanAt ? new Date(s.lastScanAt).toLocaleString() : '还没扫过')
  ];
  if (s.has) {
    rows.push({
      k: '新建文件夹',
      items: [
        { id: 'set-folder-name', ph: '文件夹名' },
        { btn: { label: '建', act: 'mkdir', fn: doMkdir } }
      ]
    });
    rows.push({
      k: '标签 → 文件夹',
      items: [
        { id: 'set-tag', ph: '标签' },
        { id: 'set-tag-folder', ph: '文件夹（空=随笔根）' },
        { btn: { label: '指过去', act: 'tagfolder', fn: doSetTagFolder } }
      ]
    });
    if (s.legacy) {
      rows.push({
        k: '待整理',
        items: [
          { text: s.legacy + ' 个文件还在根下' },
          { btn: { label: '整理到随笔目录', act: 'migrate', fn: doMigrate } }
        ]
      });
    } else {
      rows.push(rowKV('待整理', '没有（文件都在随笔目录里）'));
    }
    const tf = s.tagFolders || [];
    if (tf.length) rows.push(rowKV('已指过的标签', tf.map(function (x) { return x.tag + '→' + (x.folder || '随笔'); }).join('　')));
    rows.push({
      k: 'WebDAV 同步', items: [
        { text: (ST.sync && ST.sync.url) ? ST.sync.url : '还没设置' },
        { btn: { label: '打开', act: 'davopen', fn: openSync } }
      ]
    });
  }
  // 设置小窗打开时顺手把同步配置取回来（那一行要显示服务器地址）
  call('sync.get').then(function (c) { ST.sync = c; }).catch(function () { });
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
    openSettings();
  } catch (e) { toast('刷新失败：' + e.message, 3000); }
}
async function doMkdir() {
  const inp = el('set-folder-name');
  const name = (inp && inp.value || '').trim();
  if (!name) { toast('先填文件夹名', 2200); return; }
  try {
    const r = await call('src.mkdir', { folder: name });
    toast('已建：' + r.rel, 2600);
    if (inp) inp.value = '';
    openSettings();
  } catch (e) { toast('建不了：' + e.message, 3200); }
}
async function doSetTagFolder() {
  const tag = (el('set-tag') && el('set-tag').value || '').trim();
  const folder = (el('set-tag-folder') && el('set-tag-folder').value || '').trim();
  if (!tag) { toast('先填标签', 2200); return; }
  try {
    const r = await call('src.setTagFolder', { tag: tag, folder: folder });
    const mv = r.move || {};
    let msg = '「' + tag.replace(/^#/, '') + '」的块以后落到 ' + (folder || '随笔目录下');
    if (mv.moved) msg += '，已把 ' + mv.blocks + ' 个块搬过去' + (mv.merged ? '（并进已有文件）' : '');
    else if (r.moveError) msg += '，但搬家没成功：' + r.moveError;
    toast(msg, 3600);
    await reload();
    openSettings();
  } catch (e) { toast('指不过去：' + e.message, 3400); }
}
async function doMigrate() {
  toast('正在整理…', 2000);
  try {
    const r = await call('src.migrate');
    const moved = (r.moved || []).length;
    const left = r.left || 0;
    const bad = (r.failed || [])[0];
    toast(bad ? ('整理停下：' + bad) : ('已整理 ' + moved + ' 个文件' + (left ? '，还剩 ' + left : '')), 3800);
    await reload();
    openSettings();
  } catch (e) { toast('整理失败：' + e.message, 3400); }
}
