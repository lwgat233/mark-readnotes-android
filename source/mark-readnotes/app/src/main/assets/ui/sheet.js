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
