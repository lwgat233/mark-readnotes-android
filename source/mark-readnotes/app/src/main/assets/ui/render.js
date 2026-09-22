/* 渲染层：一个渲染器一个文件（markdown / KaTeX / 消毒 / 图片地址） —— 由 app.js 按板块切分而来（见 docs/重构规格-第4轮.md） */
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
