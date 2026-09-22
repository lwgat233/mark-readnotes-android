#!/usr/bin/env bash
# 同步板块：WebDAV 配置 / 连接 / 上传 / 下载 / 冲突三选
# 判据见 docs/同步规格.md（S1–S8）。需要一个本地 WebDAV 服务（tools/webdav-mini.py）：
#   python3 tools/webdav-mini.py --root /vol1/1000/aicache/dav-root --port 8088 --user test --pass testpass
source "$(dirname "$0")/_lib.sh"
BOARD=同步
start_board $BOARD

DAV_ROOT=/vol1/1000/aicache/dav-root
DAV_URL=http://10.0.2.2:8088/dav
STAMP=$(date +%H%M%S)
NODE_DIR="/sdcard/Documents/mark-readnotes/随笔"

# --- S1 前置：本地 WebDAV 服务必须在（不在就报环境问题，不算产品缺陷） ---
CODE=$(curl -s -o /dev/null -w "%{http_code}" -u test:testpass -X PROPFIND -H "Depth: 0" "http://127.0.0.1:8088/dav/" 2>/dev/null)
if [ "$CODE" != "207" ]; then
  echo "环境问题：本地 WebDAV 服务没起（PROPFIND 回 $CODE）。先跑："
  echo "  python3 tools/webdav-mini.py --root $DAV_ROOT --port 8088 --user test --pass testpass"
  exit 3
fi

# --- S1 配置与连接测试 ---
T=$(page '(async()=>{await call("sync.set",{url:"'"$DAV_URL"'",user:"test",pass:"testpass"});return await call("sync.test");})()')
assert_true "sync.config 能连上服务器（S1）" "$(echo "$T" | jget "d['ok']")"
info "连接结果：$(echo "$T" | jget "d['message']")"

# --- S1b 界面：设置里有入口，打开后配置与三个键在位 ---
tap '#btn-settings' >/dev/null; sleep 1
ENTRY=$(page 'document.getElementById("sheet-body").textContent.indexOf("WebDAV")>=0 ? 1 : 0')
assert_eq "sync.ui 设置里有 WebDAV 这一行" "$ENTRY" "1"
tap '[data-act=davopen]' >/dev/null; sleep 1
UI=$(page '({title:document.getElementById("sheet-title").textContent,url:!!document.getElementById("dav-url"),user:!!document.getElementById("dav-user"),pass:(document.getElementById("dav-pass")||{}).type||"",test:!!document.querySelector("[data-act=davtest]"),save:!!document.querySelector("[data-act=davsave]"),sync:!!document.querySelector("[data-act=davsync]")})')
assert_eq "sync.ui 小窗标题" "$(echo "$UI" | jget "d['title']")" "WebDAV 同步"
assert_true "sync.ui 服务器/账号输入框在位" "$(echo "$UI" | jget "d['url'] and d['user']")"
assert_eq "sync.ui 密码框是 password 类型" "$(echo "$UI" | jget "d['pass']")" "password"
assert_true "sync.ui 测试+保存+同步 三个键在位" "$(echo "$UI" | jget "d['test'] and d['save'] and d['sync']")"
# 真点一次「测试」：提示里必须说能连上
tap '[data-act=davtest]' >/dev/null; sleep 3
TT=$(page 'document.getElementById("toast").textContent' | unq)
info "点测试的提示：$TT"
echo "$TT" | grep -q "能连上" && ok "sync.ui 点「测试」真的去连了" "$TT" || bad "sync.ui 点「测试」没有连上" "$TT"

# --- S2 上传：先造一个本地新块（远端还没有它的文件），再同步 ---
tap '#sheet-close' >/dev/null; sleep 1        # 关掉同步小窗，否则 #btn-new 会被浮层挡住
TAG=syncup$STAMP
tap '#btn-new' >/dev/null; sleep 1
( cd "$ROOT" && $PROBE type '#ed-heading' "同步验收 $STAMP" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-tags' "$TAG" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-body' "这个文件本地才有 $STAMP" ) >/dev/null
tap '#btn-done' >/dev/null; sleep 2
R=$(page '(async()=>{const r=await call("sync.run");return r;})()')
UP=$(echo "$R" | jget "d['uploaded']")
info "本次上传 $(echo "$R" | jget "d['uploaded']") 个 / 下载 $(echo "$R" | jget "d['downloaded']") 个 / 冲突 $(echo "$R" | jget "d['conflicts']")"
assert_true "sync.upload 上传了文件（S2）" "$([ "${UP:-0}" -ge 1 ] && echo True || echo False)"
assert_eq "sync.upload 没有失败项" "$(echo "$R" | jget "len(d['failed'])")" "0"
SRC="随笔/$TAG.md"
info "字节比对用：$SRC"
devcat "$NODE_DIR/$TAG.md"
cp /tmp/board_check.txt /tmp/sync_local.txt
cmp -s /tmp/sync_local.txt "$DAV_ROOT/$SRC" && ok "sync.upload 服务器上的内容与本地一致（S2）" "cmp 相同" || bad "sync.upload 服务器内容不一致" "cmp 不同"

# --- S3 没变化时不再动 ---
P=$(page '(async()=>{const p=await call("sync.plan");return {u:p.upload,d:p.download,k:p.keep,c:p.conflicts};})()')
assert_eq "sync.plan 两边都没动时全是不用动（S3）" "$(echo "$P" | jget "[d['u'],d['d'],d['c']]")" "[0, 0, 0]"
assert_true "sync.plan 有不用动的文件" "$(echo "$P" | jget "d['k']>=1")"

# --- S4 远端新增的文件要下载到本地（并在索引里出现） ---
REMOTE=remoteonly-$STAMP
printf '# 远端来的块\n#%s\n远端写的正文 %s\n' "$REMOTE" "$STAMP" > "$DAV_ROOT/随笔/$REMOTE.md"
BEFORE_BLOCKS=$(page 'window.mrState().cards')
P4=$(page '(async()=>{const p=await call("sync.plan");const r=await call("sync.run");await reload();return {plan:[p.upload,p.download,p.conflicts],up:r.uploaded,down:r.downloaded};})()')
assert_eq "sync.download 远端新增被识别为下载（S4）" "$(echo "$P4" | jget "[d['plan'][1],d['down']]")" "[1, 1]"
$ADB shell "ls $NODE_DIR/$REMOTE.md" >/dev/null 2>&1 && ok "sync.download 本地出现了这个文件" "$REMOTE.md" || bad "sync.download 本地没有这个文件" "$REMOTE.md"
devcat "$NODE_DIR/$REMOTE.md"
grep -q "远端写的正文 $STAMP" /tmp/board_check.txt && ok "sync.download 内容对得上" "ok" || bad "sync.download 内容不对" "缺"
grep -q "#$REMOTE" /tmp/board_check.txt && ok "sync.download 标签行也在" "#$REMOTE" || bad "sync.download 没有标签行" "缺"
AFTER_BLOCKS=$(page 'window.mrState().cards')
assert_eq "sync.download 块数加了 1" "$AFTER_BLOCKS" "$((BEFORE_BLOCKS + 1))"

# --- S5 两边都改 → 冲突，且一个字不动 ---
CONF=conflict-$STAMP
printf '# 冲突原始\n#%s\n原样\n' "$CONF" > "$DAV_ROOT/随笔/$CONF.md"
page '(async()=>{await call("sync.run");await call("idx.refresh");return true;})()' >/dev/null; sleep 1
printf '# 冲突原始\n#%s\n本地改的后半段\n' "$CONF" > /tmp/sync_conf_local.md
$ADB push /tmp/sync_conf_local.md "$NODE_DIR/$CONF.md" >/dev/null
printf '# 冲突原始\n#%s\n远端改的后半段\n' "$CONF" > "$DAV_ROOT/随笔/$CONF.md"
P5=$(page '(async()=>{await call("idx.refresh");const p=await call("sync.plan");const r=await call("sync.run");return {conflicts:p.conflicts,reason:(p.conflictList[0]||{}).reason,runConf:r.conflicts};})()')
assert_eq "sync.conflict 两边都改过要报冲突（S5）" "$(echo "$P5" | jget "[d['conflicts'],d['reason']]")" "[1, '两边都改过']"
assert_eq "sync.conflict 执行时冲突也不动" "$(echo "$P5" | jget "d['runConf']")" "1"
devcat "$NODE_DIR/$CONF.md"
grep -q "本地改的后半段" /tmp/board_check.txt && ok "sync.conflict 冲突后本地还是本地版本" "ok" || bad "sync.conflict 本地被动了" "已被改"
grep -q "远端改的后半段" "$DAV_ROOT/随笔/$CONF.md" && ok "sync.conflict 冲突后远端还是远端版本" "ok" || bad "sync.conflict 远端被动了" "已被改"

# --- S6 三选：保留本地 / 保留远端 / 跳过 ---
page '(async()=>{await call("sync.resolve",{relPath:"随笔/'"$CONF"'.md",choice:"local"});return true;})()' >/dev/null; sleep 1
grep -q "本地改的后半段" "$DAV_ROOT/随笔/$CONF.md" && ok "sync.resolve 保留本地＝远端变成我的版本" "ok" || bad "sync.resolve 保留本地没生效" "远端没变"
printf '# 冲突原始\n#%s\n远端第二版\n' "$CONF" > "$DAV_ROOT/随笔/$CONF.md"
printf '# 冲突原始\n#%s\n本地第二版\n' "$CONF" > /tmp/sync_conf_local2.md
$ADB push /tmp/sync_conf_local2.md "$NODE_DIR/$CONF.md" >/dev/null
page '(async()=>{await call("idx.refresh");await call("sync.resolve",{relPath:"随笔/'"$CONF"'.md",choice:"remote"});await reload();return true;})()' >/dev/null; sleep 1
devcat "$NODE_DIR/$CONF.md"
grep -q "远端第二版" /tmp/board_check.txt && ok "sync.resolve 保留远端＝本地变成远端版本" "ok" || bad "sync.resolve 保留远端没生效" "本地没变"

# 跳过：不写基线，下次还会报
printf '# 冲突原始\n#%s\n远端第三版\n' "$CONF" > "$DAV_ROOT/随笔/$CONF.md"
printf '# 冲突原始\n#%s\n本地第三版\n' "$CONF" > /tmp/sync_conf_local3.md
$ADB push /tmp/sync_conf_local3.md "$NODE_DIR/$CONF.md" >/dev/null
SK=$(page '(async()=>{await call("idx.refresh");const p1=await call("sync.plan");await call("sync.resolve",{relPath:"随笔/'"$CONF"'.md",choice:"skip"});const p2=await call("sync.plan");return {前:p1.conflicts,后:p2.conflicts};})()')
assert_eq "sync.resolve 跳过之后下次还会报（S6）" "$(echo "$SK" | jget "[d['前'],d['后']]")" "[1, 1]"

# --- S7 收尾：清掉本次验收造的三个文件（本地 + 远端），并确保不留下“幽灵冲突” ---
page '(async()=>{for(const n of ["'"$TAG"'.md","'"$REMOTE"'.md","'"$CONF"'.md"]){const b=(await call("idx.blocks",{limit:200})).filter(x=>x.file===n);for(const x of b){await call("blk.delete",{id:x.id});}}await call("idx.refresh");return true;})()' >/dev/null
rm -f "$DAV_ROOT/随笔/$TAG.md" "$DAV_ROOT/随笔/$REMOTE.md" "$DAV_ROOT/随笔/$CONF.md"
# 别的板块的验收文件也可能被同步上来过：远端同名残留一起清，否则下次同步会报“本地已删除，远端还在”。
# 注意：目录名要显式写死（板块之间不共享变量），且不要用可能为空的变量配通配符 —— 那会删掉整个目录。
for d in "$DAV_ROOT/随笔" "$DAV_ROOT/随笔/boardfolder"; do
  [ -d "$d" ] || continue
  for pat in movetest mergetest syncup remoteonly conflict-; do rm -f "$d/$pat"*.md; done
  rm -f "$d"/legacyboard-*.md
done
P7=$(page '(async()=>{await call("idx.refresh");const p=await call("sync.plan");return {conflicts:p.conflicts,up:p.upload,down:p.download};})()')
assert_eq "sync 收尾 没有幽灵冲突（S7）" "$(echo "$P7" | jget "d['conflicts']")" "0"
info "收尾后服务器随笔目录：$(ls "$DAV_ROOT/随笔/" | tr '\n' ' ')"
info "收尾后服务器文件夹里：$(ls "$DAV_ROOT/随笔/boardfolder/" 2>/dev/null | tr '\n' ' ')"

finish_board $BOARD
