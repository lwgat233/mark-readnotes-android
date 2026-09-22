/* 渲染层：一个渲染器一个文件 —— markdown-it 管 markdown、KaTeX 管公式、放行的 HTML 走白名单消毒。
   内置引擎（都在 assets/ui/vendor，离线，不联网）：
     markdown-it 14.1.0 (MIT) · markdown-it-texmath 1.0.0 (MIT) · markdown-it-task-lists 2.1.1 (ISC) · KaTeX 0.18.7 (MIT)
   架构参考 waikr/KardLeaf（Apache-2.0）：同样是 markdown-it + texmath + 白名单消毒，
   同样把相对图片路径交给壳子的虚拟源去解析。出处见 THIRD-PARTY-NOTICES.md，判据见 docs/渲染规格.md（R1–R9）。
   与参照实现的两点不同（有意为之）：
     1) 不用 texmath 自带的 <eq>/<section> 模板（浏览器不认这些标记，KaTeX 的 CSS 也管不到），
        直接把 KaTeX 的输出当公式的 HTML，四种写法都走同一条路；
     2) 消毒做成「token 级白名单 + 插进 DOM 后再兜一遍」两层，且都放过 KaTeX 生成的部分
        （它自带大量内联样式，按白名单收拾会把公式拆坏）。 */

const FILE_ORIGIN = 'https://appassets.androidplatform.net/file/';

function esc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
function attr(s) { return esc(s).replace(/"/g, '&quot;').replace(/'/g, '&#39;'); }

/* 图片：带协议的（http/https/data）当外部地址，其余（含 ./ 与子目录）交给原生按笔记根解析。
   base = 这个块所在文件夹（页面这一侧只知道相对随笔目录的路径），拼进去才能找到同目录的图。 */
function imgSrc(u, base) {
  const s = String(u == null ? '' : u).trim();
  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(s)) return s;
  const rel = s.replace(/^\.?\//, '');
  const b = String(base == null ? '' : base).trim().replace(/^\/+|\/+$/g, '');
  return FILE_ORIGIN + encodeURIComponent(b ? b + '/' + rel : rel);
}

/* ---------- 放行的“基本 HTML”：白名单（属性名一律小写比对） ---------- */
const KEEP_TAGS = {
  a: 1, b: 1, blockquote: 1, br: 1, code: 1, dd: 1, del: 1, details: 1, div: 1, dl: 1, dt: 1,
  em: 1, figcaption: 1, figure: 1, h1: 1, h2: 1, h3: 1, h4: 1, h5: 1, h6: 1, hr: 1, i: 1,
  img: 1, input: 1, kbd: 1, li: 1, mark: 1, ol: 1, p: 1, pre: 1, s: 1, small: 1, span: 1,
  strong: 1, sub: 1, summary: 1, sup: 1, table: 1, tbody: 1, td: 1, tfoot: 1, th: 1, thead: 1,
  tr: 1, u: 1, ul: 1
};
const KEEP_ATTRS = {
  '*': { title: 1, class: 1, style: 1 },
  a: { href: 1 },
  img: { src: 1, alt: 1, width: 1, height: 1 },
  input: { type: 1, checked: 1, disabled: 1 },
  ol: { start: 1 },
  details: { open: 1 },
  td: { colspan: 1, rowspan: 1, align: 1 },
  th: { colspan: 1, rowspan: 1, align: 1 }
};
/* 这些标签连内容一起去掉（不是「去标签留文字」） */
const DROP_WHOLE = { script: 1, style: 1, iframe: 1, object: 1, embed: 1, link: 1, meta: 1, base: 1, form: 1, frame: 1, frameset: 1, applet: 1, svg: 1, math: 1, template: 1, noscript: 1 };
const SAFE_URL = /^\s*(https?:|mailto:|tel:|#|\/file\/)/i;

function isKatexSpan(el) { return el.tagName === 'SPAN' && /(^|\s)katex(-display|-error)?(\s|$)/.test(el.getAttribute('class') || ''); }

/* 只拿掉“能动脚本”的东西：事件属性、危险协议 */
function stripHandlers(el, st) {
  const attrs = Array.prototype.slice.call(el.attributes);
  for (let i = 0; i < attrs.length; i++) {
    const n = attrs[i].name.toLowerCase(), v = attrs[i].value;
    if (n.indexOf('on') === 0) { el.removeAttribute(attrs[i].name); st.removedAttrs++; continue; }
    if ((n === 'href' || n === 'src') && /^\s*(javascript|vbscript|data:text\/html)/i.test(v)) { el.removeAttribute(attrs[i].name); st.removedAttrs++; }
  }
}

function filterNode(parent, insideKatex, st) {
  const kids = Array.prototype.slice.call(parent.childNodes);
  for (let i = 0; i < kids.length; i++) {
    const el = kids[i];
    if (el.nodeType === 3) continue;
    if (el.nodeType === 8) { el.remove(); continue; }          // 注释一律去掉
    if (el.nodeType !== 1) continue;
    const tag = el.tagName.toLowerCase();
    if (insideKatex || isKatexSpan(el)) {                      // KaTeX 生成的部分：结构全留，只剥事件属性
      stripHandlers(el, st);
      filterNode(el, true, st);
      continue;
    }
    if (DROP_WHOLE[tag]) { el.remove(); st.removedTags++; continue; }
    if (!KEEP_TAGS[tag]) {                                     // 不在白名单：去标签、留文字
      const text = document.createTextNode(el.textContent || '');
      el.replaceWith(text);
      st.removedTags++;
      continue;
    }
    const allow = KEEP_ATTRS[tag] || {}, common = KEEP_ATTRS['*'];
    const attrs = Array.prototype.slice.call(el.attributes);
    for (let j = 0; j < attrs.length; j++) {
      const n = attrs[j].name.toLowerCase(), v = attrs[j].value;
      if (n.indexOf('on') === 0 || !(common[n] || allow[n])) { el.removeAttribute(attrs[j].name); st.removedAttrs++; continue; }
      if (n === 'href' && !SAFE_URL.test(v)) { el.removeAttribute(attrs[j].name); st.removedAttrs++; }
    }
    if (tag === 'img') {                                       // 原始 HTML 里的相对图也交给 /file/（P13）
      const src = el.getAttribute('src');
      if (src) el.setAttribute('src', imgSrc(src));
    }
    filterNode(el, false, st);
  }
}

/* 片段级消毒（作用在 markdown-it 的原始 HTML token 上；<template> 里的内容是惰性的，script 不会执行） */
function sanitizeFragment(html, st) {
  const tpl = document.createElement('template');
  tpl.innerHTML = String(html || '');
  filterNode(tpl.content, false, st);
  return tpl.innerHTML;
}

/* ---------- 公式：直接产出 KaTeX 的 HTML（四种分隔符都汇到这条路上） ---------- */
function katexHtml(tex, display) {
  if (!window.katex) return '<code>' + esc(tex) + '</code>';
  try {
    return katex.renderToString(String(tex), { displayMode: !!display, throwOnError: false, errorColor: '#d9534f', strict: false });
  } catch (e) {
    return '<span class="katex-error">' + esc(tex) + '</span>';
  }
}

/* ---------- markdown-it 装配 ---------- */
let MD = null;
function buildMd() {
  const md = window.markdownit({ html: true, linkify: true, breaks: false, typographer: false });
  if (window.texmath && window.katex) {
    // dollars 管 $…$ 与 $$…$$，brackets 管 \(…\) 与 \[…\]
    md.use(window.texmath, { engine: window.katex, delimiters: ['dollars', 'brackets'], katexOptions: { throwOnError: false, errorColor: '#d9534f', strict: false } });
    md.renderer.rules.math_inline = function (tokens, idx) { return katexHtml(tokens[idx].content, false); };
    md.renderer.rules.math_block = function (tokens, idx) { return katexHtml(tokens[idx].content, true); };
    md.renderer.rules.math_block_eqno = function (tokens, idx) {
      return katexHtml(tokens[idx].content, true) + '<div style="text-align:right;opacity:.6">(' + esc(tokens[idx].info) + ')</div>';
    };
  }
  if (window.markdownitTaskLists) md.use(window.markdownitTaskLists, { enabled: true, label: true, labelAfter: true });

  const htmlRule = function (tokens, idx, options, env) { return sanitizeFragment(tokens[idx].content, env.st); };
  md.renderer.rules.html_block = htmlRule;
  /* 行内 HTML 不在这里动：markdown-it 会把 `<span …>` 与 `</span>` 拆成两个 token，
     拿模板逐个解析会把成对标签拆散（实测：span 变空、文字被挤到外面）。
     行内 HTML 交给下面的“整段解析 + 整体消毒”那一层。 */

  const baseImage = md.renderer.rules.image || function (tokens, idx, options, env, self) { return self.renderToken(tokens, idx, options); };
  md.renderer.rules.image = function (tokens, idx, options, env, self) {
    const k = tokens[idx].attrIndex('src');
    if (k >= 0) tokens[idx].attrs[k][1] = imgSrc(tokens[idx].attrs[k][1], env.base);
    return baseImage(tokens, idx, options, env, self);
  };
  return md;
}

function renderMd(src, base) {
  if (!MD) MD = buildMd();
  const st = { removedTags: 0, removedAttrs: 0 };
  return { html: MD.render(String(src || ''), { st: st, base: base || '' }), st: st };
}

/* 渲染进节点：渲染 → 在游离容器里整段解析并消毒 → 挂进页面 → 统计（验收读的就是这套读数）
   为什么要游离容器：整段一起解析才能正确配对标签；先消毒再挂载，危险的标签/属性
   在接触真实文档之前就已经没了。 */
function renderInto(node, src, base) {
  const t0 = performance.now();
  const raw = renderMd(src, base);
  const box = document.createElement('div');
  box.innerHTML = raw.html;
  const st = { removedTags: raw.st.removedTags, removedAttrs: raw.st.removedAttrs };
  filterNode(box, false, st);
  // 表格外面套一层横向滚动容器（宽表不会把页面撑破）—— 参照 waikr/KardLeaf 的 preview.html 做法
  Array.prototype.slice.call(box.querySelectorAll('table')).forEach(function (t) {
    const wrap = document.createElement('div');
    wrap.className = 'table-scroll';
    t.parentNode.insertBefore(wrap, t);
    wrap.appendChild(t);
  });
  node.replaceChildren.apply(node, Array.prototype.slice.call(box.childNodes));
  const ms = Math.round(performance.now() - t0);
  const q = function (s) { return node.querySelectorAll(s).length; };
  const katex = q('.katex'), katexErr = q('.katex-error');
  window.__render = {
    ms: ms, bytes: raw.html.length, tables: q('table'), tasks: q('input[type=checkbox]'),
    nested: q('ul ul, ul ol, ol ul, ol ol'), li: q('li'), katex: katex, katexErr: katexErr,
    removedTags: st.removedTags, removedAttrs: st.removedAttrs
  };
  window.__sanitize = { removedTags: st.removedTags, removedAttrs: st.removedAttrs };
  window.__tex = { total: katex, ok: katex - katexErr, failed: katexErr };
  return window.__render;
}
