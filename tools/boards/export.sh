#!/usr/bin/env bash
# 导出板块：规则 / 排除 / 预览不写盘 / 写目录 / 分享
source "$(dirname "$0")/_lib.sh"
BOARD=导出
start_board $BOARD

# --- plan：目录层级＝标签顺序 ---
P=$(page '(async()=>{const p=await call("exp.plan",{exclude:[]});return {files:p.files,blocks:p.blocks,dirs:p.dirs,tree:p.tree.map(g=>g.dir+"/("+g.count+")")};})()')
info "计划：$(echo "$P" | jget "d['tree']")"
assert_true "export.opts 有文件可导" "$(echo "$P" | jget "d['files']>=1")"
assert_true "export.opts 目录数 ≥1" "$(echo "$P" | jget "d['dirs']>=1")"

# --- exclude：排除一个标签后文件数变少 ---
FIRST=$(echo "$P" | jget "d['tree'][0].split('/')[0]")
E=$(page "(async()=>{const p=await call('exp.plan',{exclude:['$FIRST']});return {files:p.files,excluded:p.excluded};})()")
assert_true "export.exclude 有块被排除" "$(echo "$E" | jget "d['excluded']>=1")"
F1=$(echo "$P" | jget "d['files']"); F2=$(echo "$E" | jget "d['files']")
assert_true "export.exclude 文件数变少" "$([ "$F2" -lt "$F1" ] && echo True || echo False)"

# --- preview：界面预览不写盘 ---
BEFORE=$($ADB shell "ls /sdcard/Documents/mark-readnotes-export 2>/dev/null | wc -l" | tr -d '\r')
tap '#btn-export' >/dev/null; sleep 2
tap '#ex-preview' >/dev/null; sleep 2
TREE=$(page 'document.querySelectorAll("#ex-tree .tree-dir").length')
assert_true "export.preview 列出目录 ≥1" "$([ "${TREE:-0}" -ge 1 ] && echo True || echo False)"
AFTER=$($ADB shell "ls /sdcard/Documents/mark-readnotes-export 2>/dev/null | wc -l" | tr -d '\r')
assert_eq "export.preview 预览没有改动磁盘" "$AFTER" "$BEFORE"

# --- write：写入目录（同名覆盖，不堆副本） ---
tap '#ex-to-dir' >/dev/null; sleep 3
W=$(page 'window.mrState && document.getElementById("toast").textContent')
info "提示：$W"
CNT=$($ADB shell "ls /sdcard/Documents/mark-readnotes-export -R" 2>/dev/null | grep -c '\.md')
assert_true "export.write 盘上有 md 文件" "$([ "${CNT:-0}" -ge 1 ] && echo True || echo False)"
$ADB shell "ls -R /sdcard/Documents/mark-readnotes-export" 2>/dev/null > /tmp/exp_tree.txt
DUP=$(python3 -c "
import re
t=open('/tmp/exp_tree.txt',encoding='utf-8',errors='replace').read()
print(len(re.findall(r'-[2-9]\.md', t)))")
assert_eq "export.write 没有 -2 之类的副本" "$DUP" "0"

# --- share：交给系统分享 ---
$ADB logcat -c
tap '#ex-preview' >/dev/null; sleep 1
tap '#ex-share' >/dev/null; sleep 4
SH=$(logcnt "exp\.share\(uris=[0-9]+")
assert_true "export.share 发起了系统分享" "$([ "$SH" -ge 1 ] && echo True || echo False)"
FOCUS=$($ADB shell dumpsys window 2>/dev/null | grep -c "ChooserActivity")
assert_true "export.share 前台是系统选择器" "$([ "${FOCUS:-0}" -ge 1 ] && echo True || echo False)"
refocus_app

finish_board $BOARD
