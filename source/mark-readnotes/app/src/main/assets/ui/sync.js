/* 同步板块：WebDAV 的配置 / 连接测试 / 计划 / 执行 / 冲突三选 —— 一个板块一个文件。
   判据见 docs/同步规格.md（S1–S8）；做法参考 waikr/KardLeaf 的基线 + 冲突三选（见 THIRD-PARTY-NOTICES）。 */
let SYNC_CFG = null;
let SYNC_PLAN = null;

async function openSync() {
  try { SYNC_CFG = await call('sync.get'); } catch (e) { SYNC_CFG = { url: '', user: '', hasPass: false }; }
  renderSync();
}
function renderSync() {
  const c = SYNC_CFG || {};
  const p = SYNC_PLAN;
  const rows = [
    {
      k: '服务器', items: [
        { id: 'dav-url', ph: 'https://…/dav', value: c.url || '' },
        { btn: { label: '测试', act: 'davtest', fn: doDavTest } }
      ]
    },
    {
      k: '账号', items: [
        { id: 'dav-user', ph: '用户名', value: c.user || '' },
        { id: 'dav-pass', type: 'password', ph: c.hasPass ? '密码（已存，不改就别填）' : '密码' },
        { btn: { label: '保存', act: 'davsave', fn: doDavSave } }
      ]
    },
    rowKV('上次同步', c.syncedFiles ? (c.syncedFiles + ' 个文件有基线') : '还没同步过')
  ];
  if (p) {
    const parts = [];
    if (p.upload) parts.push('上传 ' + p.upload);
    if (p.download) parts.push('下载 ' + p.download);
    if (p.keep) parts.push('不用动 ' + p.keep);
    if (p.conflicts) parts.push('冲突 ' + p.conflicts);
    rows.push(rowKV('这次要做的', parts.length ? parts.join('　') : '没有要动的'));
  }
  rows.push({
    k: '同步', items: [
      { text: p ? '按上面这份计划动手' : '先点「立即同步」算一份计划' },
      { btn: { label: '立即同步', act: 'davsync', fn: doDavSync } }
    ]
  });
  if (p && p.conflicts) {
    rows.push({
      k: '冲突', items: [
        { text: p.conflicts + ' 个（两边都改过，谁也没动）' },
        { btn: { label: '逐个处理', act: 'davconf', fn: openConflicts } }
      ]
    });
  }
  sheetShow('WebDAV 同步', rows, [{ label: '关闭', fn: closeSheet }]);
}

function davInputs() {
  return {
    url: (el('dav-url') && el('dav-url').value || '').trim(),
    user: (el('dav-user') && el('dav-user').value || '').trim(),
    pass: (el('dav-pass') && el('dav-pass').value || '')
  };
}
async function doDavSave() {
  const v = davInputs();
  if (!v.url) { toast('先填服务器地址', 2200); return; }
  try {
    SYNC_CFG = await call('sync.set', v);
    toast('已保存', 1800);
    renderSync();
  } catch (e) { toast('存不下：' + e.message, 3200); }
}
async function doDavTest() {
  const v = davInputs();
  if (!v.url) { toast('先填服务器地址', 2200); return; }
  try {
    SYNC_CFG = await call('sync.set', v);           // 测试前先存下这份配置，免得“测的是旧的”
    const r = await call('sync.test');
    toast(r.ok ? ('能连上：' + r.message) : ('连不上：' + r.message), 4000);
  } catch (e) { toast('测试失败：' + e.message, 3600); }
}
async function doDavSync() {
  toast('正在同步…', 2000);
  try {
    const r = await call('sync.run');
    SYNC_PLAN = await call('sync.plan');
    const bad = (r.failed || [])[0];
    let msg = '上传 ' + r.uploaded + '，下载 ' + r.downloaded;
    if (r.conflicts) msg += '，' + r.conflicts + ' 个冲突没动';
    if (bad) msg += '；有失败：' + bad.relPath + '（' + bad.error + '）';
    toast(msg, 4200);
    await reload();
    renderSync();
  } catch (e) { toast('同步失败：' + e.message, 4200); }
}
function openConflicts() {
  const list = (SYNC_PLAN && SYNC_PLAN.conflictList) || [];
  if (!list.length) { toast('现在没有冲突', 2000); return; }
  const rows = list.map(function (c) {
    return {
      k: c.relPath.replace('随笔/', ''),
      items: [
        { text: c.reason },
        { btn: { label: '保留本地', act: 'keepLocal', fn: function () { resolveConflict(c.relPath, 'local'); } } },
        { btn: { label: '保留远端', act: 'keepRemote', fn: function () { resolveConflict(c.relPath, 'remote'); } } },
        { btn: { label: '跳过', act: 'skip', fn: function () { resolveConflict(c.relPath, 'skip'); } } }
      ]
    };
  });
  sheetShow('冲突（' + list.length + '）', rows, [{ label: '返回同步', fn: openSync }, { label: '关闭', fn: closeSheet }]);
}
async function resolveConflict(relPath, choice) {
  try {
    await call('sync.resolve', { relPath: relPath, choice: choice });
    SYNC_PLAN = await call('sync.plan');
    await reload();
    toast(choice === 'skip' ? '先放着，下次还会提醒' : ('已按「' + (choice === 'local' ? '保留本地' : '保留远端') + '」处理'), 3200);
    openConflicts();
  } catch (e) { toast('处理不了：' + e.message, 3600); }
}
