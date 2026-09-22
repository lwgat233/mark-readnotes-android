#!/usr/bin/env bash
# 渲染板块：markdown / 公式 / 图片 / 消毒 / 链接
source "$(dirname "$0")/_lib.sh"
BOARD=渲染
start_board $BOARD

STAMP=$(date +%H%M%S)
TAG=rendertest
tap '#btn-new' >/dev/null; sleep 1
( cd "$ROOT" && $PROBE type '#ed-heading' "渲染验收 $STAMP" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-tags' "$TAG" ) >/dev/null
# 注意：正文里不能出现「# 一级标题」——那是分块符，会把这行之后的内容切成新块
( cd "$ROOT" && $PROBE type '#ed-body' '## 二级标题
### 三级标题
- 列表项
> 引用
**粗体** 与 `代码`

行内公式 $E=mc^2$

$$
\int_0^1 x^2\,dx = \frac{1}{3}
$$

![本地图](pic.png)

<script>window.__pwned=2</script>
<img src="pic.png" onerror="window.__pwned=1">

[链接](https://example.com/x)

```
代码块里的 # 和 $ 不解析
```' ) >/dev/null
tap '#btn-done' >/dev/null; sleep 2
tap '.card' >/dev/null; sleep 1
tap '#btn-preview' >/dev/null; sleep 3

# --- md：结构渲染 ---
M=$(page '(()=>{const p=document.getElementById("preview");return {h1:p.querySelectorAll("h1").length,h2:p.querySelectorAll("h2").length,h3:p.querySelectorAll("h3").length,li:p.querySelectorAll("li").length,bq:p.querySelectorAll("blockquote").length,pre:p.querySelectorAll("pre").length,strong:p.querySelectorAll("strong").length};})()')
assert_eq "render.md h2 渲染" "$(echo "$M" | jget "d['h2']")" "1"
assert_eq "render.md h3 渲染" "$(echo "$M" | jget "d['h3']")" "1"
assert_true "render.md 列表项 ≥1" "$(echo "$M" | jget "d['li']>=1")"
assert_eq "render.md 引用渲染" "$(echo "$M" | jget "d['bq']")" "1"
assert_eq "render.md 代码块渲染" "$(echo "$M" | jget "d['pre']")" "1"
assert_true "render.md 粗体渲染" "$(echo "$M" | jget "d['strong']>=1")"

# --- tex：公式 ---
T=$(page 'window.mrState().tex')
assert_eq "render.tex 公式总数" "$(echo "$T" | jget "d['total']")" "2"
assert_eq "render.tex 渲染成功数" "$(echo "$T" | jget "d['ok']")" "2"
assert_eq "render.tex KaTeX 元素数" "$(page 'document.querySelectorAll("#preview .katex").length')" "2"

# --- img：本地图片 ---
I=$(page 'window.mrState().imgs')
assert_true "render.img 本地图已加载" "$(echo "$I" | jget "d[0]['ok']")"
info "图片: $(echo "$I" | jget "[ (i['src'].split('/')[-1], i['w']) for i in d ]")"

# --- sanitize：脚本与事件属性被剥掉 ---
S=$(page '({s:window.__sanitize,pwned:window.__pwned===undefined?"未注入":String(window.__pwned),script:document.querySelectorAll("#preview script").length,onerror:document.querySelectorAll("#preview [onerror]").length})')
assert_eq "render.sanitize 脚本标签已移除" "$(echo "$S" | jget "d['script']")" "0"
assert_eq "render.sanitize 事件属性已剥掉" "$(echo "$S" | jget "d['onerror']")" "0"
assert_eq "render.sanitize 没被注入" "$(echo "$S" | jget "d['pwned']")" "未注入"

# --- link：点链接交给系统 ---
$ADB logcat -c
tap '#preview a' >/dev/null; sleep 3
C=$(logcnt "ui.open url=https://example.com/x")
assert_true "render.link 点击后交给系统打开" "$([ "$C" -ge 1 ] && echo True || echo False)"
refocus_app

# 清理：删掉这个验收块（关掉预览，再走两次确认）
tap '#btn-preview' >/dev/null; sleep 1
tap '#btn-more' >/dev/null; sleep 1
page 'document.querySelector("[data-act=delete]") && document.querySelector("[data-act=delete]").click()' >/dev/null; sleep 1
page 'document.querySelector("[data-act=delete]") && document.querySelector("[data-act=delete]").click()' >/dev/null; sleep 2

finish_board $BOARD
