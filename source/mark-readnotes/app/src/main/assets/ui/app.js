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
  if (previewOn) el('preview').innerHTML = renderMd(el('ed-body').value);
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
}

/* ---------- markdown 渲染（自己写，不引外部库） ---------- */
function esc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
function inline(s) {
  let t = esc(s);
  t = t.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, function (m, a, u) { return '<img alt="' + a + '" src="' + u + '">'; });
  t = t.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, function (m, a, u) { return '<a href="' + u + '">' + a + '</a>'; });
  t = t.replace(/`([^`]+)`/g, '<code>$1</code>');
  t = t.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  t = t.replace(/(^|\s)\*([^*\s][^*]*)\*/g, '$1<em>$2</em>');
  t = t.replace(/\$([^$\n]+)\$/g, '<span class="tex">$1</span>');
  return t;
}
function renderMd(src) {
  const lines = String(src || '').replace(/\r\n/g, '\n').split('\n');
  const out = [];
  let inCode = false, listType = null;
  function closeList() { if (listType) { out.push('</' + listType + '>'); listType = null; } }
  for (let i = 0; i < lines.length; i++) {
    const ln = lines[i];
    let m;
    if (/^```/.test(ln)) {
      closeList();
      if (!inCode) { out.push('<pre><code>'); inCode = true; } else { out.push('</code></pre>'); inCode = false; }
      continue;
    }
    if (inCode) { out.push(esc(ln)); continue; }
    if (/^\s*$/.test(ln)) { closeList(); continue; }
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
    return { build: document.body.dataset.build, bridge: B.ready, boardOpen: board.open, boardId: board.id,
      boardRect: { x: r.x, y: r.y, w: r.width, h: r.height },
      cards: ST.blocks.length, src: ST.src, previewOn: previewOn };
  };

  el('btn-new').addEventListener('click', function () { openBoard(null); });
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
