'use strict';
/* 随笔 前端：主页（最近编辑的块）+ 全屏白板 + 小窗。
   所有数据都走 op 层（window.mrbridge），页面自己不碰文件系统。 */

const DEFAULT_TAG = 'unsorted';
const B = { ready: false, attached: false, seq: 0, waiters: new Map() };
const ST = { info: null, src: { has: false }, blocks: [] };
const board = { open: false, id: null, dirty: false };
let previewOn = false;

function el(id) { return document.getElementById(id); }
function toast(msg, ms) {
  const t = el('toast'); t.textContent = msg; t.classList.remove('hidden');
  clearTimeout(t._t); t._t = setTimeout(function () { t.classList.add('hidden'); }, ms || 1800);
}

/* ---------- 桥（原生 → 页面必须先挂 onmessage，否则回包静默丢失） ---------- */
function attachBridge() {
  if (window.mrbridge && !B.attached) {
    B.attached = true;
    B.ready = true;
    window.mrbridge.onmessage = function (e) { handleRaw(e.data); };
  }
}
function handleRaw(data) {
  let msg; try { msg = JSON.parse(typeof data === 'string' ? data : String(data)); } catch (err) { return; }
  if (msg.id && B.waiters.has(msg.id)) {
    const w = B.waiters.get(msg.id); B.waiters.delete(msg.id);
    if (msg.ok) w.res(msg.data); else w.rej(new Error(msg.error || 'op 失败'));
    return;
  }
  if (msg.type) onPush(msg.type, msg.data || {});
}
function call(op, args) {
  return new Promise(function (res, rej) {
    attachBridge();
    if (!B.ready) { rej(new Error('桥未就绪')); return; }
    const id = 'r' + (++B.seq);
    B.waiters.set(id, { res: res, rej: rej });
    setTimeout(function () {
      if (B.waiters.has(id)) { B.waiters.delete(id); rej(new Error('op 超时：' + op)); }
    }, 20000);
    try { window.mrbridge.postMessage(JSON.stringify({ id: id, op: op, args: args || {} })); }
    catch (err) { B.waiters.delete(id); rej(err); }
  });
}
window.mrpush = function (payload) { try { handleRaw(payload); } catch (e) { } };

/* ---------- 主页 ---------- */
function renderList() {
  const list = el('list');
  list.textContent = '';
  ST.blocks.forEach(function (b) {
    const card = document.createElement('div');
    card.className = 'card';
    card.dataset.id = String(b.id);

    const arch = document.createElement('div');
    arch.className = 'b-arch';
    const head = document.createElement('div');
    head.className = 'b-head';
    head.textContent = b.heading || '（无标题）';
    const tags = document.createElement('div');
    tags.className = 'b-tags';
    (b.tags || []).forEach(function (x) {
      const s = document.createElement('span'); s.textContent = '#' + x; tags.appendChild(s);
    });
    arch.appendChild(head); arch.appendChild(tags);

    const body = document.createElement('div');
    body.className = 'b-body' + (b.preview ? '' : ' empty');
    body.textContent = b.preview || '（空）';

    card.appendChild(arch); card.appendChild(body);
    card.addEventListener('click', function () { openBoard(b.id); });
    list.appendChild(card);
  });

  const emptyBox = el('empty');
  const emptyText = el('empty-text');
  const pickBtn = el('btn-pick-empty');
  if (!ST.src.has) {
    emptyBox.classList.remove('hidden');
    emptyText.textContent = '先选一个笔记目录，再点「＋ 新随笔」';
    pickBtn.classList.remove('hidden');
  } else if (ST.blocks.length === 0) {
    emptyBox.classList.remove('hidden');
    emptyText.textContent = '这里还没有随笔：点下面的「＋ 新随笔」开始';
    pickBtn.classList.add('hidden');
  } else {
    emptyBox.classList.add('hidden');
  }
}

async function reload() {
  try {
    ST.src = await call('src.get');
    ST.blocks = ST.src.has ? await call('idx.blocks', { limit: 60 }) : [];
    renderList();
  } catch (e) { toast('读取失败：' + e.message, 3000); }
}

/* ---------- 白板 ---------- */
function fillBoard(heading, tags, body) {
  el('ed-heading').value = heading || '';
  el('ed-tags').value = tags || '';
  el('ed-body').value = body || '';
  board.dirty = false;
  updateTagHint();
}
function showBoard() { el('board').classList.add('open'); board.open = true; setPreview(false); }
function hideBoard() { el('board').classList.remove('open'); board.open = false; }

function openBoard(id) {
  board.id = id || null;
  if (id) {
    call('blk.get', { id: id }).then(function (b) {
      fillBoard(b.heading, (b.tags || []).join(' '), b.body);
      showBoard();
    }).catch(function (e) { toast('打不开这个块：' + e.message, 3000); });
  } else {
    // 新块：标签预填“上次用过的标签”，没有就留空（留空即归 unsorted.md，由提示行说明）
    const lastTag = (ST.blocks[0] && (ST.blocks[0].tags || [])[0]) || '';
    fillBoard('', lastTag, '');
    showBoard();
    setTimeout(function () { el('ed-heading').focus(); }, 260);
  }
}

function tagList() {
  return el('ed-tags').value.trim().split(/\s+/).filter(function (x) { return x; })
    .map(function (x) { return x.replace(/^#/, ''); });
}
function updateTagHint() {
  const tags = tagList();
  const first = tags[0] || DEFAULT_TAG;
  const name = /^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(first) ? first + '.md' : '（自动命名）';
  el('tag-hint').textContent = '归属文件：' + name + (tags.length > 1 ? '　其余标签仅备注' : '');
}

async function saveBoard(force) {
  const heading = el('ed-heading').value.trim();
  const tags = tagList();
  const body = el('ed-body').value;
  if (board.id) {
    const r = await call('blk.save', { id: board.id, heading: heading, tags: tags, body: body });
    if (r.moved) toast('已搬到 ' + r.file);
    board.dirty = false;
    return r.id;
  }
  if (!force && !heading && !body.trim()) return null;
  const r = await call('blk.new', { tag: tags[0] || DEFAULT_TAG, heading: heading, body: body });
  board.id = r.id;
  if (tags.length > 1) await call('blk.save', { id: r.id, heading: heading, tags: tags, body: body });
  board.dirty = false;
  toast('已新建：' + r.file);
  return r.id;
}

async function doneBoard() {
  try {
    await saveBoard(false);
  } catch (e) { toast('保存失败：' + e.message, 3000); return; }
  hideBoard();
  board.id = null;
  await reload();
}
function autosave() {
  if (!board.open || !board.id || !board.dirty) return;
  saveBoard(true).then(function () { loadSilently(); }).catch(function () { });
}
async function loadSilently() {
  try { ST.blocks = await call('idx.blocks', { limit: 60 }); renderList(); } catch (e) { }
}

function setPreview(on) {
  previewOn = !!on;
  el('preview').classList.toggle('hidden', !previewOn);
  el('ed-body').classList.toggle('hidden', previewOn);
  el('btn-preview').textContent = previewOn ? '编辑' : '预览';
  if (previewOn) {
    el('preview').innerHTML = renderMd(el('ed-body').value);
    sanitizePreview(el('preview'));
    renderTex();
  }
}

function openBlockMore() {
  if (!board.id) { sheetShow('本块', [rowKV('状态', '还没保存')], [{ label: '关闭', fn: closeSheet }]); return; }
  const rows = [rowKV('笔记', el('ed-heading').value || '（无标题）'), rowKV('归属', el('tag-hint').textContent)];
  sheetShow('本块', rows, [
    { label: '删除本块', fn: deleteCurrent, danger: true },
    { label: '关闭', fn: closeSheet }
  ]);
}
async function deleteCurrent() {
  const btn = el('sheet-body').querySelector('[data-act="delete"]');
  if (btn && btn.dataset.armed !== '1') {
    btn.dataset.armed = '1';
    btn.textContent = '再点一次确认删除';
    return;
  }
  try {
    await call('blk.delete', { id: board.id });
    closeSheet(); hideBoard(); board.id = null;
    toast('已删除');
    await reload();
  } catch (e) { toast('删除失败：' + e.message, 3000); }
}

/* ---------- 小窗（设置 / 本块） ---------- */
let sheetActions = [];
function rowKV(k, v) { return { k: k, v: v }; }
function sheetShow(title, rows, actions) {
  el('sheet-title').textContent = title;
  const body = el('sheet-body');
  body.textContent = '';
  rows.forEach(function (r) {
    const d = document.createElement('div'); d.className = 'row';
    const k = document.createElement('div'); k.className = 'k'; k.textContent = r.k;
    const v = document.createElement('div'); v.className = 'v'; v.textContent = r.v;
    d.appendChild(k); d.appendChild(v); body.appendChild(d);
  });
  sheetActions = actions || [];
  sheetActions.forEach(function (a, i) {
    const wrap = document.createElement('div');
    wrap.className = 'row';
    const btn = document.createElement('button');
    btn.className = 'wide' + (a.danger ? '' : (i === 0 ? ' primary' : ''));
    if (a.danger) btn.dataset.act = 'delete';
    btn.textContent = a.label;
    btn.addEventListener('click', a.fn);
    wrap.appendChild(btn);
    body.appendChild(wrap);
  });
  const foot = document.createElement('div');
  foot.className = 'foot';
  foot.textContent = ST.info ? ('版本 ' + ST.info.version + ' · build ' + ST.info.build) : '';
  body.appendChild(foot);
  el('sheet').classList.add('open');
}
function closeSheet() { el('sheet').classList.remove('open'); }
function sheetOpen() { return el('sheet').classList.contains('open'); }

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

/* ---------- markdown 渲染（自己写，不引外部库；数学公式用本机内置的 KaTeX，不联网） ---------- */
const FILE_ORIGIN = 'https://appassets.androidplatform.net/file/';
function esc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
function attr(s) { return esc(s).replace(/"/g, '&quot;').replace(/'/g, '&#39;'); }
/* 图片：只有 http/https/data 这种带协议的当外部地址，其余（含 ./ 与子目录）都交给原生按笔记根解析 */
function imgSrc(u) {
  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(u)) return u;
  return FILE_ORIGIN + encodeURIComponent(u.replace(/^\.?\//, ''));
}
function texSpan(tex, display) {
  return '<span class="tex' + (display ? ' tex-block' : '') + '" data-tex="' + attr(tex) + '"></span>';
}
function inline(s) {
  let t = esc(s);
  t = t.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, function (m, a, u) { return '<img alt="' + a + '" src="' + attr(imgSrc(u)) + '">'; });
  t = t.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, function (m, a, u) { return '<a href="' + attr(u) + '">' + a + '</a>'; });
  t = t.replace(/`([^`]+)`/g, '<code>$1</code>');
  t = t.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  t = t.replace(/(^|\s)\*([^*\s][^*]*)\*/g, '$1<em>$2</em>');
  t = t.replace(/\$\$([^$]+)\$\$/g, function (m, x) { return texSpan(x, false); });
  t = t.replace(/(^|[^\\$])\$([^$\n]+)\$/g, function (m, p, x) { return p + texSpan(x, false); });
  t = t.replace(/\\\((.+?)\\\)/g, function (m, x) { return texSpan(x, false); });
  return t;
}
function renderMd(src) {
  const lines = String(src || '').replace(/\r\n/g, '\n').split('\n');
  const out = [];
  let inCode = false, listType = null, inMath = false, mathBuf = [];
  function closeList() { if (listType) { out.push('</' + listType + '>'); listType = null; } }
  for (let i = 0; i < lines.length; i++) {
    const ln = lines[i];
    let m;
    // $$ 独占一行时按“多行公式块”收集，直到下一个 $$
    if (/^\s*\$\$\s*$/.test(ln)) {
      if (!inMath) { closeList(); inMath = true; mathBuf = []; }
      else { closeList(); out.push(texSpan(mathBuf.join('\n'), true)); inMath = false; }
      continue;
    }
    if (inMath) { mathBuf.push(ln); continue; }
    if (/^```/.test(ln)) {
      closeList();
      if (!inCode) { out.push('<pre><code>'); inCode = true; } else { out.push('</code></pre>'); inCode = false; }
      continue;
    }
    if (inCode) { out.push(esc(ln)); continue; }
    if (/^\s*$/.test(ln)) { closeList(); continue; }
    // 单行数学公式：$$…$$ 或 \[…\]
    if ((m = ln.match(/^\s*\$\$(.+?)\$\$\s*$/)) || (m = ln.match(/^\s*\\\[(.+?)\\\]\s*$/))) {
      closeList(); out.push(texSpan(m[1], true)); continue;
    }
    if ((m = ln.match(/^(#{1,6})\s+(.*)$/))) { closeList(); out.push('<h' + m[1].length + '>' + inline(m[2]) + '</h' + m[1].length + '>'); continue; }
    if (/^(---|\*\*\*|___)\s*$/.test(ln)) { closeList(); out.push('<hr>'); continue; }
    if ((m = ln.match(/^\s*>\s?(.*)$/))) { closeList(); out.push('<blockquote>' + inline(m[1]) + '</blockquote>'); continue; }
    if ((m = ln.match(/^\s*[-*+]\s+(.*)$/))) {
      if (listType !== 'ul') { closeList(); out.push('<ul>'); listType = 'ul'; }
      out.push('<li>' + inline(m[1]) + '</li>'); continue;
    }
    if ((m = ln.match(/^\s*\d+\.\s+(.*)$/))) {
      if (listType !== 'ol') { closeList(); out.push('<ol>'); listType = 'ol'; }
      out.push('<li>' + inline(m[1]) + '</li>'); continue;
    }
    if (/^\s*<[a-zA-Z!/]/.test(ln)) { closeList(); out.push(ln); continue; }  // 基本 HTML 原样放行
    closeList(); out.push('<p>' + inline(ln) + '</p>');
  }
  if (inCode) out.push('</code></pre>');
  closeList();
  return out.join('\n');
}
/* 渲染结果里放行“基本 HTML”，但先过一遍消毒：脚本类标签与事件属性一律拿掉
   （笔记里粘来的 HTML 不能借桥去动文件），顺序：innerHTML → 消毒 → 公式渲染 */
const BLOCKED_TAGS = { SCRIPT: 1, IFRAME: 1, OBJECT: 1, EMBED: 1, LINK: 1, META: 1, BASE: 1, FORM: 1, FRAME: 1, FRAMESET: 1, APPLET: 1 };
function sanitizePreview(root) {
  const bad = [];
  let attrs = 0;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT, null);
  while (walker.nextNode()) {
    const el = walker.currentNode;
    if (BLOCKED_TAGS[el.tagName]) { bad.push(el); continue; }
    Array.from(el.attributes).forEach(function (a) {
      const n = a.name.toLowerCase();
      if (n.indexOf('on') === 0) { el.removeAttribute(a.name); attrs++; return; }
      if ((n === 'href' || n === 'src' || n === 'xlink:href') && /^\s*(javascript|vbscript|data:text\/html)/i.test(a.value)) {
        el.removeAttribute(a.name); attrs++;
      }
    });
  }
  bad.forEach(function (el) { el.remove(); });
  window.__sanitize = { removedTags: bad.length, removedAttrs: attrs };
  return window.__sanitize;
}

/* 公式真渲染（本机 KaTeX）：逐个替换占位元素；坏公式不炸页面，按原文显示 */
function renderTex() {
  const nodes = document.querySelectorAll('#preview .tex');
  let ok = 0, failed = 0;
  for (let i = 0; i < nodes.length; i++) {
    const el = nodes[i], tex = el.getAttribute('data-tex') || '';
    if (!window.katex) { el.textContent = tex; failed++; continue; }
    try {
      katex.render(tex, el, { displayMode: el.classList.contains('tex-block'), throwOnError: false, errorColor: '#ff6b6b', strict: false });
      if (el.querySelector('.katex')) ok++; else failed++;
    } catch (e) { el.textContent = tex; failed++; }
  }
  window.__tex = { total: nodes.length, ok: ok, failed: failed };
  return window.__tex;
}

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
  setInterval(autosave, 10000);
  call('app.info').then(function (i) { ST.info = i; }).catch(function () { });
  reload();
})();
