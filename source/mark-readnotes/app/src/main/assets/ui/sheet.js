/* 小窗基础设施 —— 由 app.js 按板块切分而来（见 docs/重构规格-第4轮.md） */
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
    if (r.items) {   // 行里带输入框/行内按键（输入与它自己的动作挨着，用户不用猜哪个键管哪个框）
      const box = document.createElement('div'); box.className = 'v controls';
      r.items.forEach(function (it) {
        if (it.id) {
          const inp = document.createElement('input');
          inp.id = it.id; inp.type = it.type || 'text'; inp.placeholder = it.ph || '';
          if (it.value) inp.value = it.value;
          box.appendChild(inp);
        }
        if (it.text) { const t = document.createElement('span'); t.className = 'hintline'; t.textContent = it.text; box.appendChild(t); }
        if (it.btn) {
          const b = document.createElement('button');
          if (it.btn.act) b.dataset.act = it.btn.act;
          b.textContent = it.btn.label;
          b.addEventListener('click', it.btn.fn);
          box.appendChild(b);
        }
      });
      d.appendChild(k); d.appendChild(box); body.appendChild(d);
      return;
    }
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
