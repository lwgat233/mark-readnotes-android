/* 随笔板块：白板（全屏编辑层） —— 由 app.js 按板块切分而来（见 docs/重构规格-第4轮.md） */
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
      board.folder = b.folder || '';      // 这个块所在文件夹：预览里的相对图片按它解析
      fillBoard(b.heading, (b.tags || []).join(' '), b.body);
      showBoard();
    }).catch(function (e) { toast('打不开这个块：' + e.message, 3000); });
  } else {
    // 新块：标签预填“上次用过的标签”，没有就留空（留空即归 unsorted.md，由提示行说明）
    const lastTag = (ST.blocks[0] && (ST.blocks[0].tags || [])[0]) || '';
    board.folder = '';
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
  const folder = folderOfTag(first);
  el('tag-hint').textContent = '归属文件：' + (folder ? folder + '/' : '') + name + (tags.length > 1 ? '　其余标签仅备注' : '');
}
/* 标签 → 文件夹（设置里指过去的那份映射；空 = 就在随笔目录下） */
function folderOfTag(tag) {
  const list = (ST.src && ST.src.tagFolders) || [];
  const hit = list.filter(function (x) { return x.tag === tag; })[0];
  return hit ? (hit.folder || '') : '';
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
    renderInto(el('preview'), el('ed-body').value, board.folder);
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
