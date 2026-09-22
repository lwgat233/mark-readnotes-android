/* 导出板块 —— 由 app.js 按板块切分而来（见 docs/重构规格-第4轮.md） */
/* ---------- 导出（按标签） ---------- */
let exExclude = new Set(), exPlan = null, exPreviewed = false, exPendingWrite = false;

function div(cls, text) { const d = document.createElement('div'); if (cls) d.className = cls; if (text != null) d.textContent = text; return d; }
function exBtn(id, label, primary, fn) {
  const w = div('row'); const b = document.createElement('button');
  b.id = id; b.className = 'wide' + (primary ? ' primary' : ''); b.textContent = label;
  b.addEventListener('click', fn); w.appendChild(b); return w;
}
function exKv(k, v) { const r = div('row'); r.appendChild(div('k', k)); r.appendChild(div('v', v)); return r; }

async function openExport() {
  exExclude = new Set(); exPlan = null; exPreviewed = false;
  await renderExport();
}
async function renderExport() {
  el('sheet-title').textContent = '导出';
  const body = el('sheet-body');
  body.textContent = '';
  const tags = await call('exp.tags').catch(function () { return []; });
  exPlan = await call('exp.plan', { exclude: Array.from(exExclude) });

  body.appendChild(exKv('规则', '第 1 个标签＝一级目录，第 2 个＝二级目录；第 3 个及以后只留在文件里'));

  const tagRow = div('row');
  tagRow.appendChild(div('k', '排除标签（点一下排除，再点取消）'));
  const chips = div('chips');
  (tags || []).forEach(function (t) {
    const c = document.createElement('button');
    c.className = 'ex-tag' + (exExclude.has(t.tag) ? ' on' : '');
    c.textContent = '#' + t.tag + ' ' + t.count;
    c.addEventListener('click', function () {
      if (exExclude.has(t.tag)) exExclude.delete(t.tag); else exExclude.add(t.tag);
      exPreviewed = false;
      renderExport();
    });
    chips.appendChild(c);
  });
  tagRow.appendChild(chips);
  body.appendChild(tagRow);

  const sum = div('row'); sum.id = 'ex-summary';
  sum.appendChild(div('k', '汇总'));
  sum.appendChild(div('v', '块 ' + exPlan.blocks + ' · 文件 ' + exPlan.files + ' · 排除 ' + exPlan.excluded + ' · 目录 ' + exPlan.dirs));
  sum.appendChild(div('k', '导出目录'));
  sum.appendChild(div('v', exPlan.exportRoot || '未选择（第一次导出时选一个）'));
  body.appendChild(sum);

  if (exPreviewed) {
    const tree = div('row'); tree.id = 'ex-tree';
    tree.appendChild(div('k', '将生成这些文件（还没有写盘）'));
    (exPlan.tree || []).forEach(function (g) {
      tree.appendChild(div('tree-dir', g.dir + '/　(' + g.count + ')'));
      (g.files || []).forEach(function (f) { tree.appendChild(div('tree-file', '　' + f.name)); });
    });
    body.appendChild(tree);
    body.appendChild(exBtn('ex-to-dir', '写入目录', true, doWriteDir));
    body.appendChild(exBtn('ex-share', '系统分享', false, doShare));
  } else {
    body.appendChild(exBtn('ex-preview', '导出并预览', true, function () { exPreviewed = true; renderExport(); }));
    body.appendChild(exBtn('ex-run', '直接导出', false, doWriteDir));
  }
  const foot = div('foot', ST.info ? ('版本 ' + ST.info.version + ' · build ' + ST.info.build) : '');
  body.appendChild(foot);
  el('sheet').classList.add('open');
}

async function doWriteDir() {
  try {
    const r = await call('exp.writeDir', { exclude: Array.from(exExclude) });
    toast('已导出 ' + r.files + ' 个文件（新建 ' + r.new + '，更新 ' + r.overwritten + '）到 ' + r.root, 3600);
    await reload();
  } catch (e) {
    if (/还没选导出目录/.test(e.message || '')) {
      exPendingWrite = true;
      toast('先选一个导出目录', 2600);
      call('src.pickExport').catch(function () { });
    } else {
      toast('导出失败：' + e.message, 3200);
    }
  }
}
async function doShare() {
  try {
    const r = await call('exp.share', { exclude: Array.from(exExclude) });
    if (r.targets && r.targets.length) toast('已交给系统分享：' + r.uris + ' 个文件', 3200);
    else toast('系统里没有能接收 markdown 的应用', 3200);
  } catch (e) { toast('分享失败：' + e.message, 3200); }
}
