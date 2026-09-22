/* 核心层：桥 + 全局状态 —— 由 app.js 按板块切分而来（见 docs/重构规格-第4轮.md） */
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
