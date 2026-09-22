#!/usr/bin/env bash
# 渲染板块：markdown 结构 / 表格 / 任务清单 / 嵌套 / 公式四写法 / HTML 消毒 / 代码块 / 图片 / 链接
# 判据编号见 docs/渲染规格.md（R1–R9）；文本固定为 tools/fixtures/render-fixture.md，
# 改造前后各跑一遍同一份脚本 —— 改造前红的就是旧渲染器的缺口。
source "$(dirname "$0")/_lib.sh"
BOARD=渲染
start_board $BOARD

FIX="$(cat "$ROOT/tools/fixtures/render-fixture.md")"
STAMP=$(date +%H%M%S)
TAG=rendertest
tap '#btn-new' >/dev/null; sleep 1
( cd "$ROOT" && $PROBE type '#ed-heading' "渲染验收 $STAMP" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-tags' "$TAG" ) >/dev/null
# 注意：正文里不能出现行首的「# 一级标题」——那是分块符，会把这行之后的内容切成新块
( cd "$ROOT" && $PROBE type '#ed-body' "$FIX" ) >/dev/null
tap '#btn-done' >/dev/null; sleep 2
tap '.card' >/dev/null; sleep 1
tap '#btn-preview' >/dev/null; sleep 3

# --- R1 结构 + 表格 ---
M=$(page '(()=>{const p=document.getElementById("preview");return {table:p.querySelectorAll("table").length,th:p.querySelectorAll("table th").length,td:p.querySelectorAll("table td").length,h2:p.querySelectorAll("h2").length,h3:p.querySelectorAll("h3").length,h4:p.querySelectorAll("h4").length,bq:p.querySelectorAll("blockquote").length,strong:p.querySelectorAll("strong").length};})()')
assert_eq "R1 标题 h2/h3/h4 各一" "$(echo "$M" | jget "[d['h2'],d['h3'],d['h4']]")" "[1, 1, 1]"
assert_eq "R1 引用渲染" "$(echo "$M" | jget "d['bq']")" "1"
assert_true "R1 粗体渲染" "$(echo "$M" | jget "d['strong']>=1")"
assert_eq "R1 表格渲染" "$(echo "$M" | jget "d['table']")" "1"
assert_eq "R1 表头单元格数" "$(echo "$M" | jget "d['th']")" "3"
assert_eq "R1 表体单元格数" "$(echo "$M" | jget "d['td']")" "6"

# --- R2 任务清单 ---
T=$(page '(()=>{const p=document.getElementById("preview");return {box:p.querySelectorAll("input[type=checkbox]").length,checked:p.querySelectorAll("input[type=checkbox]:checked").length};})()')
assert_eq "R2 任务清单 checkbox 数" "$(echo "$T" | jget "d['box']")" "2"
assert_eq "R2 已勾选数" "$(echo "$T" | jget "d['checked']")" "1"

# --- R3 嵌套列表 ---
N=$(page '(()=>{const p=document.getElementById("preview");let deep=0,e=p.querySelector("li li");while(e){deep++;e=e.querySelector("li");}return {nested:p.querySelectorAll("ul ul, ul ol, ol ul, ol ol").length,ol:p.querySelectorAll("ol").length,deep:deep};})()')
assert_true "R3 有嵌套列表" "$(echo "$N" | jget "d['nested']>=1")"
assert_true "R3 有序列表渲染" "$(echo "$N" | jget "d['ol']>=1")"
assert_true "R3 嵌套层级 ≥2" "$(echo "$N" | jget "d['deep']>=2")"

# --- R4 公式四种写法（$…$ / $$…$$ / \(…\) / \[…\]） ---
assert_eq "R4 公式总数（四写法）" "$(page '(()=>{const p=document.getElementById("preview");return p.querySelectorAll(".katex").length+p.querySelectorAll(".katex-error").length;})()')" "4"
assert_true "R4 KaTeX 渲染成功数 ≥4" "$([ "$(page 'document.querySelectorAll("#preview .katex").length>=4')" = "true" ] && echo True || echo False)"
assert_eq "R4 公式报错数" "$(page 'document.querySelectorAll("#preview .katex-error").length')" "0"
info "公式读数：$(page 'window.__tex')"

# --- R5 HTML 白名单消毒 ---
S=$(page '(()=>{const p=document.getElementById("preview");const s=Array.prototype.slice.call(p.querySelectorAll("span")).filter(e=>e.getAttribute("style")==="color:red")[0];return {script:p.querySelectorAll("script").length,onerr:p.querySelectorAll("[onerror]").length,div:p.querySelectorAll("div.note").length,det:p.querySelectorAll("details").length,color:s?getComputedStyle(s).color:"（没有这个 span）",spanText:s?s.textContent:"",pwned:(typeof window.__pwned==="undefined")?"未注入":String(window.__pwned),san:window.__sanitize};})()')
assert_eq "R5 脚本标签已移除" "$(echo "$S" | jget "d['script']")" "0"
assert_eq "R5 事件属性已剥掉" "$(echo "$S" | jget "d['onerr']")" "0"
assert_eq "R5 放行的 div 保留" "$(echo "$S" | jget "d['div']")" "1"
assert_eq "R5 details 保留" "$(echo "$S" | jget "d['det']")" "1"
assert_eq "R5 保留的内联样式仍生效" "$(echo "$S" | jget "d['color']")" "rgb(255, 0, 0)"
assert_eq "R5 行内 HTML 里的文字没被挤到标签外" "$(echo "$S" | jget "d['spanText']")" "带颜色的 span"
assert_eq "R5 没被注入" "$(echo "$S" | jget "d['pwned']")" "未注入"
info "消毒读数：$(echo "$S" | jget "d['san']")"

# --- R6 代码块里的语法不解析 ---
C=$(page '(()=>{const p=document.getElementById("preview");const c=p.querySelector("pre code");return {pre:p.querySelectorAll("pre").length,inside:c?c.querySelectorAll("strong,em").length:-1,text:c?c.textContent:""};})()')
assert_eq "R6 代码块渲染" "$(echo "$C" | jget "d['pre']")" "1"
assert_eq "R6 代码块内不解析语法" "$(echo "$C" | jget "d['inside']")" "0"
info "代码块原文：$(echo "$C" | jget "d['text'].split(chr(10))[1][:24]")"

# --- R7 图片：markdown 写法与原始 HTML 写法都要出来（P13） ---
I=$(page 'window.mrState().imgs')
assert_true "R7 markdown 图片已加载" "$(echo "$I" | jget "d[0]['ok']")"
assert_true "R7 原始 HTML 图片已加载" "$(echo "$I" | jget "d[1]['ok']")"
info "图片：$(echo "$I" | jget "[ (i['src'].split('/')[-1], i['w']) for i in d ]")"

# --- R8 渲染耗时 ---
R=$(page 'window.__render||null')
info "渲染读数：$R"
if [ "$R" != "null" ] && [ -n "$R" ]; then
  assert_true "R8 渲染耗时 < 400ms" "$(echo "$R" | jget "d['ms']<400")"
else
  info "（这一版还没有耗时读数：R8 属于新渲染器）"
fi

# --- R9 出处可查 ---
assert_true "R9 第三方许可声明在" "$([ -f "$ROOT/THIRD-PARTY-NOTICES.md" ] && echo True || echo False)"
assert_true "R9 内置库来源与校验留档" "$([ -f "$ROOT/evidence/vendor-provenance.txt" ] && echo True || echo False)"

# --- 链接：点一下交给系统 ---
$ADB logcat -c
tap '#preview a' >/dev/null; sleep 3
LC=$(logcnt "ui.open url=https://example.com/x")
assert_true "render.link 点击后交给系统打开" "$([ "$LC" -ge 1 ] && echo True || echo False)"
refocus_app

# 清理：删掉这个验收块（关掉预览，再走两次确认）
tap '#btn-preview' >/dev/null; sleep 1
tap '#btn-more' >/dev/null; sleep 1
page 'document.querySelector("[data-act=delete]") && document.querySelector("[data-act=delete]").click()' >/dev/null; sleep 1
page 'document.querySelector("[data-act=delete]") && document.querySelector("[data-act=delete]").click()' >/dev/null; sleep 2

finish_board $BOARD
