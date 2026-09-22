/* 随笔板块：主页（最近编辑的块 / 搜索结果） —— 由 app.js 按板块切分而来（见 docs/重构规格-第4轮.md） */
/* ---------- 主页 ---------- */

/* 把命中处套上 <mark>：按 DOM 节点拼，不拼字符串 —— 笔记里的内容永远不当 HTML 处理 */
function markHits(text, q) {
  const frag = document.createDocumentFragment();
  const s = String(text == null ? '' : text);
  if (!q) { frag.appendChild(document.createTextNode(s)); return frag; }
  const low = s.toLowerCase(), ql = q.toLowerCase();
  let i = 0;
  for (;;) {
    const at = low.indexOf(ql, i);
    if (at < 0) { frag.appendChild(document.createTextNode(s.slice(i))); break; }
    if (at > i) frag.appendChild(document.createTextNode(s.slice(i, at)));
    const m = document.createElement('mark');
    m.textContent = s.slice(at, at + q.length);
    frag.appendChild(m);
    i = at + q.length;
  }
  return frag;
}

function renderList() {
  const list = el('list');
  list.textContent = '';
  const q = ST.q || '';
  ST.blocks.forEach(function (b) {
    const card = document.createElement('div');
    card.className = 'card';
    card.dataset.id = String(b.id);

    const arch = document.createElement('div');
    arch.className = 'b-arch';
    const head = document.createElement('div');
    head.className = 'b-head';
    const headText = b.heading || '（无标题）';
    if (q) head.replaceChildren(markHits(headText, q)); else head.textContent = headText;
    const tags = document.createElement('div');
    tags.className = 'b-tags';
    (b.tags || []).forEach(function (x) {
      const s = document.createElement('span'); s.textContent = '#' + x; tags.appendChild(s);
    });
    arch.appendChild(head); arch.appendChild(tags);

    const body = document.createElement('div');
    const bodyText = q ? (b.snippet || '') : (b.preview || '');
    body.className = 'b-body' + (bodyText ? '' : ' empty');
    if (q) body.replaceChildren(markHits(bodyText || '（空）', q));
    else body.textContent = bodyText || '（空）';

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
    emptyText.textContent = q ? '没有匹配的随笔' : '这里还没有随笔：点下面的「＋ 新随笔」开始';
    pickBtn.classList.add('hidden');
  } else {
    emptyBox.classList.add('hidden');
  }
}

/* 搜索：q 非空就是搜索结果，清空就回到最近编辑（判据 boards/notes.sh#search） */
async function runSearch(q) {
  const query = String(q == null ? '' : q).trim();
  ST.q = query;
  try {
    if (query) {
      const r = await call('idx.search', { q: query, limit: 60 });
      ST.blocks = r.items || [];
      clearTimeout(ST.searchTimer);
      ST.searchTimer = setTimeout(function () {   // 结果有延迟时别把手指下那份闪掉
        if (ST.q === query) renderList();
      }, 0);
    } else {
      ST.blocks = ST.src.has ? await call('idx.blocks', { limit: 60 }) : [];
    }
    renderList();
  } catch (e) { toast('搜索失败：' + e.message, 3000); }
}

async function reload() {
  try {
    ST.src = await call('src.get');
    if (ST.q) { await runSearch(ST.q); return; }
    ST.blocks = ST.src.has ? await call('idx.blocks', { limit: 60 }) : [];
    if (ST.src.has) { try { ST.tags = (await call('tag.list')).tags; } catch (e) { ST.tags = []; } }
    renderList();
  } catch (e) { toast('读取失败：' + e.message, 3000); }
}
