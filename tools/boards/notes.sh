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
# 落位按“这个块自己”的真实相对路径读（按文件名查会撞上遗留的同名旧行；标签可能被指到文件夹）
BID=$(page '(async()=>{const r=await call("idx.blocks",{limit:1});return r[0].id;})()' | unq)
REL1=$(page '(async()=>{const b=await call("blk.get",{id:'"$BID"'});return b.relPath||"";})()' | unq)
info "验收块 id=$BID 落位：$REL1"
devcat "/sdcard/Documents/mark-readnotes/$REL1"
grep -q "验收块 $STAMP" /tmp/board_check.txt && ok "notes.new 块已写进标签对应的 md" "$REL1 含标题" || bad "notes.new 块未落盘" "$REL1 里没有 $STAMP"

# --- move：改第一个标签 = 整块搬家（boardtest → boardtest2） ---
tap '.card' >/dev/null; sleep 1
( cd "$ROOT" && $PROBE type '#ed-tags' "$TAG2" ) >/dev/null
tap '#btn-done' >/dev/null; sleep 2
REL2=$(page '(async()=>{const b=await call("blk.get",{id:'"$BID"'});return b.relPath||"";})()' | unq)
info "搬家后该块落位：$REL2"
devcat "/sdcard/Documents/mark-readnotes/$REL2"
grep -q "验收块 $STAMP" /tmp/board_check.txt && ok "notes.move 整块搬到了新文件" "$REL2 含该块" || bad "notes.move 搬家失败" "$REL2 里没有"

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
devcat "/sdcard/Documents/mark-readnotes/$REL2"
grep -q "验收块 $STAMP" /tmp/board_check.txt && bad "notes.delete 块还在文件里" "$REL2 仍含该块" || ok "notes.delete 块已从 md 里删掉" "$REL2 不再含该块"
CARDS2=$(page 'window.mrState().cards')
# 本轮先建了一个块（基线 +1），删除后应回到开场的基线
assert_eq "notes.delete 块数回到基线" "${CARDS2:-0}" "$CARDS"

# --- search：搜索（正文/标题命中 → 界面只列命中项 + 高亮 → 搜不到有空态 → 清空回基线 → 收尾自清） ---
WORD="searchword$STAMP"
tap '#btn-new' >/dev/null; sleep 1
page 'document.getElementById("ed-heading").value=""' >/dev/null
( cd "$ROOT" && $PROBE type '#ed-heading' "搜索验收 $WORD" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-tags' "$TAG1" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-body' "正文里也有 $WORD 这个词" ) >/dev/null
tap '#btn-done' >/dev/null; sleep 2
SID=$(page '(async()=>{const r=await call("idx.blocks",{limit:1});return r[0].id;})()' | unq)
HIT=$(page '(async()=>{const r=await call("idx.search",{q:"'"$WORD"'"});return {n:r.count,id:r.items[0]?r.items[0].id:-1,snip:r.items[0]?r.items[0].snippet:""};})()')
assert_eq "notes.search 命中 1 个块" "$(echo "$HIT" | jget "d['n']")" "1"
assert_eq "notes.search 命中的就是这个块" "$(echo "$HIT" | jget "d['id']")" "$SID"
info "命中片段：$(echo "$HIT" | jget "d['snip']")"
( cd "$ROOT" && $PROBE type '#q' "$WORD" ) >/dev/null; sleep 1
UI=$(page '(()=>({cards:document.querySelectorAll("#list .card").length,mark:document.querySelectorAll("#list mark").length}))()')
assert_eq "notes.search 界面只列命中项" "$(echo "$UI" | jget "d['cards']")" "1"
assert_true "notes.search 命中处有高亮" "$(echo "$UI" | jget "d['mark']>=1")"
page 'document.getElementById("q").value=""' >/dev/null
( cd "$ROOT" && $PROBE type '#q' "zzz$STAMP" ) >/dev/null; sleep 1
NONE=$(page '(()=>({cards:document.querySelectorAll("#list .card").length,empty:document.getElementById("empty-text").textContent}))()')
assert_eq "notes.search 搜不到时 0 张卡片" "$(echo "$NONE" | jget "d['cards']")" "0"
assert_eq "notes.search 搜不到时有空态" "$(echo "$NONE" | jget "d['empty']")" "没有匹配的随笔"
page 'document.getElementById("q").value=""' >/dev/null
tap '#q-clear' >/dev/null; sleep 1
CARDS3=$(page 'window.mrState().cards')
# 此时搜索验收用的块还在（下面一步才删），所以清空后应该是 基线 + 1
assert_eq "notes.search 清空后回到最近编辑（基线+搜索块）" "${CARDS3:-0}" "$((CARDS + 1))"
# 收尾：搜索验收用的块删掉，不在随笔目录里留残件（见 P23）
page '(async()=>{await call("blk.delete",{id:'"$SID"'});await reload();return 1;})()' >/dev/null; sleep 1
CARDS4=$(page 'window.mrState().cards')
assert_eq "notes.search 收尾把验收块清掉了" "${CARDS4:-0}" "$CARDS"

finish_board $BOARD
