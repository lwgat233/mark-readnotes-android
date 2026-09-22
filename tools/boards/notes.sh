#!/usr/bin/env bash
# 随笔板块：主页 / 新建 / 白板 / 归档隔离 / 搬家 / 删除
source "$(dirname "$0")/_lib.sh"
BOARD=随笔
start_board $BOARD

# --- home：主页卡片数 == 索引里的块数 ---
ST=$(page 'window.mrState()')
CARDS=$(echo "$ST" | jget "d['cards']")
BLOCKS=$(echo "$ST" | jget "d['src']['blocks']")
assert_eq "notes.home 卡片数=索引块数" "$CARDS" "$BLOCKS"
BUILD=$(echo "$ST" | jget "d['build']")
info "页面版本标记 build=$BUILD"

# --- board：白板是全屏（等于可视区，不是小窗） ---
R=$(page '(()=>{const r=document.getElementById("board").getBoundingClientRect();return {w:Math.round(r.width),h:Math.round(r.height),vw:innerWidth,vh:innerHeight};})()')
W=$(echo "$R" | jget "d['w']"); VW=$(echo "$R" | jget "d['vw']"); H=$(echo "$R" | jget "d['h']"); VH=$(echo "$R" | jget "d['vh']")
# 未打开时白板收在屏幕下方，先打开再看
tap '#btn-new' >/dev/null; sleep 1
R=$(page '(()=>{const r=document.getElementById("board").getBoundingClientRect();return {x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height),vw:innerWidth,vh:innerHeight};})()')
W=$(echo "$R" | jget "d['w']"); VW=$(echo "$R" | jget "d['vw']"); H=$(echo "$R" | jget "d['h']"); VH=$(echo "$R" | jget "d['vh']"); Y=$(echo "$R" | jget "d['y']")
assert_eq "notes.board 白板宽=可视宽" "$W" "$VW"
assert_eq "notes.board 白板高=可视高" "$H" "$VH"
assert_eq "notes.board 白板贴顶(y=0)" "$Y" "0"

# --- arch：归档区（标题+标签）与正文隔离 ---
A=$(page '(()=>{const h=document.getElementById("ed-heading"),t=document.getElementById("ed-tags"),b=document.getElementById("ed-body");return {arch:!!document.querySelector(".arch"),headInArch:document.querySelector(".arch").contains(h),bodySeparate:!document.querySelector(".arch").contains(b)};})()')
assert_true "notes.arch 标题在归档区里" "$(echo "$A" | jget "d['headInArch']")"
assert_true "notes.arch 正文不在归档区里" "$(echo "$A" | jget "d['bodySeparate']")"

# --- new：新建块落盘（正文写进标签对应的 md） ---
STAMP=$(date +%H%M%S)
TAG1=boardtest
TAG2=boardtest2
tap '#ed-heading' >/dev/null
page 'document.getElementById("ed-heading").value=""' >/dev/null
( cd "$ROOT" && $PROBE type '#ed-heading' "验收块 $STAMP" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-tags' "$TAG1" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-body' "分板块验收用的一行 $STAMP" ) >/dev/null
tap '#btn-done' >/dev/null; sleep 2
devcat "/sdcard/Documents/mark-readnotes/$TAG1.md"
grep -q "验收块 $STAMP" /tmp/board_check.txt && ok "notes.new 块已写进标签对应的 md" "$TAG1.md 含标题" || bad "notes.new 块未落盘" "$TAG1.md 里没有 $STAMP"

# --- move：改第一个标签 = 整块搬家（boardtest → boardtest2） ---
tap '.card' >/dev/null; sleep 1
( cd "$ROOT" && $PROBE type '#ed-tags' "$TAG2" ) >/dev/null
tap '#btn-done' >/dev/null; sleep 2
devcat "/sdcard/Documents/mark-readnotes/$TAG2.md"
grep -q "验收块 $STAMP" /tmp/board_check.txt && ok "notes.move 整块搬到了新文件" "$TAG2.md 含该块" || bad "notes.move 搬家失败" "$TAG2.md 里没有"

# --- delete：两次确认后删除 ---
# 用 DOM 点击做确认序列（真手指点击的覆盖在第 2/3 轮证据里；这里只做板块级回归，
# 避免“第一次点击被浮层位置变动吃掉”造成假红）
tap '.card' >/dev/null; sleep 1
tap '#btn-more' >/dev/null; sleep 1
page 'document.querySelector("[data-act=delete]").click()' >/dev/null; sleep 1
TXT=$(page 'document.querySelector("[data-act=delete]").textContent' | unq)
assert_eq "notes.delete 第一次点击进入确认态" "$TXT" "再点一次确认删除"
page 'document.querySelector("[data-act=delete]").click()' >/dev/null; sleep 2
info "删除后提示：$(page 'document.getElementById("toast").textContent')"
devcat "/sdcard/Documents/mark-readnotes/$TAG2.md"
grep -q "验收块 $STAMP" /tmp/board_check.txt && bad "notes.delete 块还在文件里" "$TAG2.md 仍含该块" || ok "notes.delete 块已从 md 里删掉" "$TAG2.md 不再含该块"
CARDS2=$(page 'window.mrState().cards')
# 本轮先建了一个块（基线 +1），删除后应回到开场的基线
assert_eq "notes.delete 块数回到基线" "${CARDS2:-0}" "$CARDS"

finish_board $BOARD
