#!/usr/bin/env bash
# 存储板块：授权 / 懒扫描 / 写回（文件是权威源）/ 随笔独立目录 / 文件夹 / 旧布局整理
# 判据 D1–D4 见 docs/新需求规格-随笔独立目录.md
source "$(dirname "$0")/_lib.sh"
BOARD=存储
start_board $BOARD

NODE_DIR="/sdcard/Documents/mark-readnotes/随笔"
STAMP=$(date +%H%M%S)        # 本轮唯一后缀（验收文件用它命名，避免与上一轮的残留撞上）
FOLDER=boardfolder          # 验收用的文件夹（ASCII，避免中文经 adb 传参走样）
TAGF=foldertest             # 指到上面那个文件夹的标签
LEGACY=legacyboard-$STAMP     # 模拟“旧布局：md 还平铺在根下”（每轮唯一名，避免与上一轮已迁走的同名文件撞上）

# 归位：上一轮若被打断，根下或随笔目录里可能留着这个验收文件（否则“根下不堆 md”会假红）
$ADB shell "rm -f /sdcard/Documents/mark-readnotes/legacyboard*.md /sdcard/Documents/mark-readnotes/随笔/legacyboard*.md" >/dev/null 2>&1

# 把某个标签指到某个文件夹：走设置小窗里的真输入框与真按键，回提示文本
map_tag() {
  tap '#btn-settings' >/dev/null; sleep 1
  ( cd "$ROOT" && $PROBE type '#set-tag' "$1" ) >/dev/null
  ( cd "$ROOT" && $PROBE type '#set-tag-folder' "$2" ) >/dev/null
  tap '[data-act=tagfolder]' >/dev/null; sleep 3
  page 'document.getElementById("toast").textContent' | unq
}

# --- auth：SAF 持久授权在（重启后仍在 = 真的持久化了） ---
S=$(page 'window.mrState().src')
assert_true "store.auth 已授权笔记根" "$(echo "$S" | jget "d['has']")"
ROOT_LABEL=$(echo "$S" | jget "d['rootLabel']")
info "笔记根：$ROOT_LABEL"
assert_true "store.auth 树 URI 非空" "$(echo "$S" | jget "bool(d['treeUri'])")"
info "随笔目录：$(echo "$S" | jget "d['nodeLabel']")"
assert_eq "store.node 随笔目录名" "$(echo "$S" | jget "d['node']")" "随笔"

# --- scan：冷启动不枚举目录，只读索引 ---
$ADB logcat -c
$ADB shell am force-stop $PKG >/dev/null; sleep 1
$ADB shell am start -n $PKG/.MainActivity >/dev/null; sleep 5
PID=$($ADB shell pidof $PKG | tr -d '\r')
$ADB forward --remove-all >/dev/null
$ADB forward tcp:9222 localabstract:webview_devtools_remote_$PID >/dev/null
SKIP=$(logcnt "scan=skipped\(reason=startup\)")
WALK=$(logcnt "scan=walk")
assert_true "store.scan 冷启动打的是 scan=skipped" "$([ "$SKIP" -ge 1 ] && echo True || echo False)"
assert_eq "store.scan 冷启动没有全目录 walk" "$WALK" "0"
CARDS=$(page 'window.mrState().cards')
assert_true "store.scan 只读索引也有数据" "$([ "$CARDS" -ge 1 ] && echo True || echo False)"
info "冷启动后卡片数：$CARDS"

# --- write：界面存的块与磁盘上的 md 一致（md 是权威源），且落在随笔目录里 ---
TAG=storetest
tap '#btn-new' >/dev/null; sleep 1
( cd "$ROOT" && $PROBE type '#ed-heading' "存储验收 $STAMP" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-tags' "$TAG" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-body' "写在磁盘上的正文 $STAMP" ) >/dev/null
tap '#btn-done' >/dev/null; sleep 2
devcat "$NODE_DIR/$TAG.md"
grep -q "写在磁盘上的正文 $STAMP" /tmp/board_check.txt && ok "store.write 正文在 md 文件里" "$TAG.md" || bad "store.write 文件里没有正文" "$STAMP"
grep -q "#$TAG" /tmp/board_check.txt && ok "store.write 标签行在文件里" "#$TAG" || bad "store.write 没有标签行" "缺"
# 收拾掉这个块（免得每轮往盘上堆一份验收文件）
tap '.card' >/dev/null; sleep 1
tap '#btn-more' >/dev/null; sleep 1
page 'document.querySelector("[data-act=delete]") && document.querySelector("[data-act=delete]").click()' >/dev/null; sleep 1
page 'document.querySelector("[data-act=delete]") && document.querySelector("[data-act=delete]").click()' >/dev/null; sleep 2

# --- D1：随笔独立目录（文件不再堆在授权根下） ---
$ADB shell "ls $NODE_DIR" 2>/dev/null | tr -d '\r' | grep -q '\.md' && ok "store.node 随笔目录里有 md（D1）" "$NODE_DIR" || bad "store.node 随笔目录里没有 md" "空"
ROOTMD=$($ADB shell "ls /sdcard/Documents/mark-readnotes/*.md 2>/dev/null | wc -l" | tr -d '\r')
assert_eq "store.node 授权根下不再堆 md" "${ROOTMD:-0}" "0"

# --- D4：新建文件夹（真点设置里那个键）+ 标签→文件夹 ---
tap '#btn-settings' >/dev/null; sleep 1
( cd "$ROOT" && $PROBE type '#set-folder-name' "$FOLDER" ) >/dev/null
tap '[data-act=mkdir]' >/dev/null; sleep 2
$ADB shell "ls $NODE_DIR" 2>/dev/null | tr -d '\r' | grep -q "$FOLDER" && ok "store.folder 文件夹建在盘上" "$FOLDER" || bad "store.folder 盘上没有这个文件夹" "$FOLDER"
( cd "$ROOT" && $PROBE type '#set-tag' "$TAGF" ) >/dev/null
( cd "$ROOT" && $PROBE type '#set-tag-folder' "$FOLDER" ) >/dev/null
tap '[data-act=tagfolder]' >/dev/null; sleep 2
TF=$(page '(ST.src.tagFolders||[]).filter(x=>x.tag==="'"$TAGF"'")[0]||null')
assert_eq "store.folder 映射写进了索引" "$(echo "$TF" | jget "d['folder']")" "$FOLDER"
tap '#sheet-close' >/dev/null; sleep 1

# 用这个标签新建一个块：应当落在文件夹里（界面 → 磁盘全链路）
tap '#btn-new' >/dev/null; sleep 1
( cd "$ROOT" && $PROBE type '#ed-heading' "文件夹验收 $STAMP" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-tags' "$TAGF" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-body' "这个块应该在文件夹里" ) >/dev/null
HINT=$(page 'document.getElementById("tag-hint").textContent')
info "归属提示：$HINT"
echo "$HINT" | grep -q "$FOLDER/" && ok "store.folder 归属提示带上文件夹" "$HINT" || bad "store.folder 归属提示没带文件夹" "$HINT"
tap '#btn-done' >/dev/null; sleep 2
$ADB shell "ls $NODE_DIR/$FOLDER" 2>/dev/null | tr -d '\r' | grep -q "$TAGF.md" && ok "store.folder 该标签的块落在文件夹里" "$FOLDER/$TAGF.md" || bad "store.folder 没落到文件夹" "$FOLDER"
$ADB shell "cat $NODE_DIR/$FOLDER/$TAGF.md" 2>/dev/null | tr -d '\r' | grep -q "这个块应该在文件夹里" && ok "store.folder 内容确实写进了那个文件" "ok" || bad "store.folder 文件里没有正文" "缺"

# 清理这个块
tap '.card' >/dev/null; sleep 1
tap '#btn-more' >/dev/null; sleep 1
page 'document.querySelector("[data-act=delete]") && document.querySelector("[data-act=delete]").click()' >/dev/null; sleep 1
page 'document.querySelector("[data-act=delete]") && document.querySelector("[data-act=delete]").click()' >/dev/null; sleep 2

# --- D5：已有的文件要跟着走（整文件搬家；目标已有同名则按块合并） ---
MOVETAG=movetest$STAMP
tap '#btn-new' >/dev/null; sleep 1
( cd "$ROOT" && $PROBE type '#ed-heading' "整文件搬家 $STAMP" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-tags' "$MOVETAG" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-body' "搬之前在这里" ) >/dev/null
tap '#btn-done' >/dev/null; sleep 2
CNT_A=$(page 'window.mrState().cards')
$ADB shell "ls $NODE_DIR" 2>/dev/null | tr -d '\r' | grep -q "$MOVETAG.md" && ok "store.move 搬家前文件在随笔目录" "$MOVETAG.md" || bad "store.move 搬家前没这个文件" "$MOVETAG.md"
TOAST=$(map_tag "$MOVETAG" "$FOLDER")
info "指过去提示：$TOAST"
echo "$TOAST" | grep -q "已把 1 个块搬过去" && ok "store.move 提示里报了搬几个块" "$TOAST" || bad "store.move 提示没报搬家" "$TOAST"
$ADB shell "ls $NODE_DIR/$FOLDER" 2>/dev/null | tr -d '\r' | grep -q "$MOVETAG.md" && ok "store.move 文件到了文件夹里" "$FOLDER/$MOVETAG.md" || bad "store.move 文件夹里没有它" "$MOVETAG.md"
$ADB shell "ls $NODE_DIR" 2>/dev/null | tr -d '\r' | grep -q "^$MOVETAG.md$" && bad "store.move 旧位置还留着文件" "$MOVETAG.md" || ok "store.move 旧位置的文件没了" "已搬走"
devcat "$NODE_DIR/$FOLDER/$MOVETAG.md"
grep -q "搬之前在这里" /tmp/board_check.txt && ok "store.move 内容跟着走了" "ok" || bad "store.move 内容丢了" "缺"
CNT_B=$(page 'window.mrState().cards')
assert_eq "store.move 搬完块数没变" "$CNT_B" "$CNT_A"
REL=$(page '(async()=>{const b=(await call("idx.blocks",{limit:60})).filter(x=>x.tag==="'"$MOVETAG"'")[0]||{};return b.relPath||"";})()' | unq)
assert_eq "store.move 索引里的落位也改了" "$REL" "随笔/$FOLDER/$MOVETAG.md"
tap '#sheet-close' >/dev/null; sleep 1

# 合并：原地一个文件 + 文件夹里已有同名文件 → 合成一个（块数相加、两边都不丢）
MERG=mergetest$STAMP
$ADB shell "printf '# 原地的第一块\n#$MERG\n甲一号\n\n# 原地的第二块\n#$MERG\n甲二号\n' > $NODE_DIR/$MERG.md"
$ADB shell "printf '# 文件夹里已有的块\n#$MERG\n乙一号\n' > $NODE_DIR/$FOLDER/$MERG.md"
page '(async()=>{await call("idx.refresh");await reload();return true;})()' >/dev/null; sleep 1
CNT_C=$(page 'window.mrState().cards')
TOAST2=$(map_tag "$MERG" "$FOLDER")
info "合并提示：$TOAST2"
echo "$TOAST2" | grep -q "并进已有文件" && ok "store.merge 提示说了并进已有文件" "$TOAST2" || bad "store.merge 提示没说合并" "$TOAST2"
CNT_D=$(page 'window.mrState().cards')
assert_eq "store.merge 合并后总数没变（3 个块各一份）" "$CNT_D" "$CNT_C"
devcat "$NODE_DIR/$FOLDER/$MERG.md"
for h in 原地的第一块 原地的第二块 文件夹里已有的块; do
  grep -q "$h" /tmp/board_check.txt && ok "store.merge 合并后的文件里有「$h」" "ok" || bad "store.merge 合并后丢了「$h」" "缺"
done
$ADB shell "ls $NODE_DIR" 2>/dev/null | tr -d '\r' | grep -q "^$MERG.md$" && bad "store.merge 原地文件还在" "$MERG.md" || ok "store.merge 原地文件已收尾" "只留文件夹里那份"
ROWS=$(page '(async()=>{const f=(await call("idx.files")).filter(x=>x.name==="'"$MERG"'.md");return f.length;})()')
assert_eq "store.merge 同名文件行只剩一个" "$ROWS" "1"
tap '#sheet-close' >/dev/null; sleep 1

# --- D2/D3：整理旧布局（根下的 md 搬进随笔目录，字节一致、块数不变） ---
$ADB shell "printf '# 旧布局块一\n#legacyboard\n第一段正文\n\n# 旧布局块二\n#legacyboard\n第二段正文\n' > /sdcard/Documents/mark-readnotes/$LEGACY.md"
# 调完刷新要让页面把状态取回来（ST.src 是设置的输入源；只调 op 不 reload，读到的还是旧值）
page '(async()=>{await call("idx.refresh");await reload();return true;})()' >/dev/null; sleep 1
BEFORE=$(page 'window.mrState().cards')
LEG=$(page '(ST.src||{}).legacy')
assert_true "store.migrate 待整理数 ≥1（D3 前置）" "$([ "${LEG:-0}" -ge 1 ] && echo True || echo False)"
devcat "/sdcard/Documents/mark-readnotes/$LEGACY.md"
cp /tmp/board_check.txt /tmp/board_legacy_before.txt
tap '#btn-settings' >/dev/null; sleep 1
MB=$(page 'document.querySelector("[data-act=migrate]") ? 1 : 0')
assert_eq "store.migrate 有待整理时才出现这个键" "$MB" "1"
tap '[data-act=migrate]' >/dev/null; sleep 3
ROOTMD2=$($ADB shell "ls /sdcard/Documents/mark-readnotes/*.md 2>/dev/null | wc -l" | tr -d '\r')
assert_eq "store.migrate 根下 md 归零（D2）" "${ROOTMD2:-0}" "0"
$ADB shell "ls $NODE_DIR" 2>/dev/null | tr -d '\r' | grep -q "$LEGACY.md" && ok "store.migrate 文件到了随笔目录" "$LEGACY.md" || bad "store.migrate 随笔目录里没有它" "$LEGACY.md"
devcat "$NODE_DIR/$LEGACY.md"
cmp -s /tmp/board_legacy_before.txt /tmp/board_check.txt && ok "store.migrate 搬完字节一致（D2）" "cmp 相同" || bad "store.migrate 搬完内容不一致" "cmp 不同"
AFTER=$(page 'window.mrState().cards')
assert_eq "store.migrate 搬完块数没变（D3）" "$AFTER" "$BEFORE"
FB=$(page '(async()=>{const fs=await call("idx.files");const f=fs.filter(x=>x.relPath==="随笔/'"$LEGACY"'.md")[0]||{};return f.blocks||-1;})()')
assert_eq "store.migrate 该文件的块都在（D3）" "$FB" "2"
# 收尾：把这个验收文件（2 个块）也删掉，别留在真实的随笔目录里
page '(async()=>{const b=(await call("idx.blocks",{limit:200})).filter(x=>x.file==="'"$LEGACY"'.md");for(const x of b){await call("blk.delete",{id:x.id});}await call("idx.refresh");return true;})()' >/dev/null
LEFTOVER=$($ADB shell "ls $NODE_DIR/$LEGACY.md" 2>/dev/null | tr -d '\r')
assert_eq "store.migrate 验收文件已清掉" "${LEFTOVER:-空}" "空"
LEG2=$(page '(ST.src||{}).legacy')
assert_eq "store.migrate 待整理清零" "$LEG2" "0"

# 收尾：把验收用的标签映射撤掉（不然别的板块的验收块会被落到 boardfolder 里，造成跨板块串味）
page '(async()=>{for(const t of ["'"$TAGF"'","'"$MOVETAG"'","'"$MERG"'"]){await call("src.setTagFolder",{tag:t,folder:""});}await reload();return true;})()' >/dev/null
TF2=$(page '(ST.src.tagFolders||[]).filter(x=>["'"$TAGF"'","'"$MOVETAG"'","'"$MERG"'"].indexOf(x.tag)>=0).map(x=>x.tag+"→"+(x.folder||"随笔")).join(",")||"(空)"')
info "收尾后这些标签的文件夹：$TF2"

finish_board $BOARD
