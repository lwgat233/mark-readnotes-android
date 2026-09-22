/* 随笔板块：主页（最近编辑的块） —— 由 app.js 按板块切分而来（见 docs/重构规格-第4轮.md） */
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
