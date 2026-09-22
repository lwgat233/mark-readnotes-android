#!/usr/bin/env node
/* WebView 探针：通过 devtools socket 读写应用里那个真实 WebView。
 * 用法：
 *   node tools/webview-cdp.mjs eval '<js 表达式>'      # 读真实状态（返回 JSON）
 *   node tools/webview-cdp.mjs rect '<选择器>'         # 元素几何（判断“全屏”“高度上限”）
 *   node tools/webview-cdp.mjs tap '<选择器>'          # 真手指点击（synthesizeTapGesture）
 *   node tools/webview-cdp.mjs type '<选择器>' '<文本>' # 聚焦并输入（触发 input 事件）
 *   node tools/webview-cdp.mjs listeners '<选择器>'    # 事件监听器数量（0 = 死键）
 *   node tools/webview-cdp.mjs targets                 # 列出可连接的页面
 * 前置：adb forward tcp:9222 localabstract:webview_devtools_remote_$PID
 */
import { execSync } from 'node:child_process';

const PORT = process.env.CDP_PORT || 9222;
const argv = process.argv.slice(2);
const cmd = argv[0];

class Cdp {
  constructor(ws) { this.ws = ws; this.seq = 0; this.pending = new Map(); }
  static async connect(url) {
    const ws = new WebSocket(url);
    const c = new Cdp(ws);
    ws.addEventListener('message', (ev) => {
      let msg; try { msg = JSON.parse(ev.data); } catch { return; }
      if (msg.id && c.pending.has(msg.id)) {
        const p = c.pending.get(msg.id); c.pending.delete(msg.id);
        msg.error ? p.rej(new Error(msg.error.message)) : p.res(msg.result);
      }
    });
    await new Promise((res, rej) => {
      ws.addEventListener('open', res, { once: true });
      ws.addEventListener('error', () => rej(new Error('WebSocket 连不上：' + url)), { once: true });
    });
    return c;
  }
  send(method, params = {}) {
    return new Promise((res, rej) => {
      const id = ++this.seq;
      this.pending.set(id, { res, rej });
      this.ws.send(JSON.stringify({ id, method, params }));
      setTimeout(() => { if (this.pending.has(id)) { this.pending.delete(id); rej(new Error('CDP 超时：' + method)); } }, 30000);
    });
  }
}

async function listTargets() {
  const r = await fetch(`http://127.0.0.1:${PORT}/json`);
  return r.json();
}

async function pickPage() {
  const ts = await listTargets();
  const pages = ts.filter((t) => t.type === 'page' && t.webSocketDebuggerUrl);
  return pages.find((t) => (t.url || '').includes('appassets')) || pages[0];
}

function out(v) { console.log(JSON.stringify(v)); }
function die(msg, code = 1) { console.error(msg); process.exit(code); }

const page = await pickPage();
if (!page) die('没有可连接的 WebView 页面（App 起了吗？forward 加了吗？）', 3);
if (cmd === 'targets') { out(await listTargets()); process.exit(0); }

const cdp = await Cdp.connect(page.webSocketDebuggerUrl);
await cdp.send('Runtime.enable');

async function evaluate(expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) die('页面抛异常：' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text), 4);
  return r.result?.value;
}
const SEL = (s) => JSON.stringify(s);
const rectExpr = (sel) => `(()=>{const e=document.querySelector(${SEL(sel)}); if(!e) return null; const r=e.getBoundingClientRect();
  return {x:r.x,y:r.y,w:r.width,h:r.height,cx:Math.round(r.x+r.width/2),cy:Math.round(r.y+r.height/2),
  vw:window.innerWidth,vh:window.innerHeight,visible:r.width>0&&r.height>0};})()`;

let code = 0;
switch (cmd) {
  case 'eval':
    out(await evaluate(argv[1]));
    break;
  case 'rect':
    out(await evaluate(rectExpr(argv[1])));
    break;
  case 'tap': {
    const r = await evaluate(rectExpr(argv[1]));
    if (!r || !r.visible) die('点不到：元素不存在或不可见 ' + argv[1], 2);
    await cdp.send('Input.synthesizeTapGesture', { x: r.cx, y: r.cy, duration: 60, tapCount: 1, gestureSourceType: 'touch' });
    out({ tapped: argv[1], at: [r.cx, r.cy] });
    break;
  }
  case 'type': {
    const [sel, text] = [argv[1], argv[2]];
    const ok = await evaluate(`(()=>{const e=document.querySelector(${SEL(sel)}); if(!e) return false; e.focus(); return true;})()`);
    if (!ok) die('找不到输入框 ' + sel, 2);
    await cdp.send('Input.insertText', { text });
    out({ typed: sel, len: (text || '').length });
    break;
  }
  case 'listeners': {
    const r = await evaluate(`(()=>{const e=document.querySelector(${SEL(argv[1])}); if(!e) return null; return true;})()`);
    if (!r) die('找不到元素 ' + argv[1], 2);
    const h = await cdp.send('Runtime.evaluate', { expression: `document.querySelector(${SEL(argv[1])})` });
    const l = await cdp.send('DOMDebugger.getEventListeners', { objectId: h.result.objectId });
    out((l.listeners || []).map((x) => x.type));
    break;
  }
  default:
    die('未知子命令：' + cmd, 2);
}
cdp.ws.close();
process.exit(code);
