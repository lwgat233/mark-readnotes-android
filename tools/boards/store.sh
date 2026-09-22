#!/usr/bin/env bash
# 存储板块：授权 / 懒扫描 / 写回（文件是权威源）
source "$(dirname "$0")/_lib.sh"
BOARD=存储
start_board $BOARD

# --- auth：SAF 持久授权在（重启后仍在 = 真的持久化了） ---
S=$(page 'window.mrState().src')
assert_true "store.auth 已授权笔记根" "$(echo "$S" | jget "d['has']")"
ROOT_LABEL=$(echo "$S" | jget "d['rootLabel']")
info "笔记根：$ROOT_LABEL"
assert_true "store.auth 树 URI 非空" "$(echo "$S" | jget "bool(d['treeUri'])")"

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

# --- write：界面存的块与磁盘上的 md 一致（md 是权威源） ---
STAMP=$(date +%H%M%S)
TAG=storetest
tap '#btn-new' >/dev/null; sleep 1
( cd "$ROOT" && $PROBE type '#ed-heading' "存储验收 $STAMP" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-tags' "$TAG" ) >/dev/null
( cd "$ROOT" && $PROBE type '#ed-body' "写在磁盘上的正文 $STAMP" ) >/dev/null
tap '#btn-done' >/dev/null; sleep 2
devcat "/sdcard/Documents/mark-readnotes/$TAG.md"
grep -q "写在磁盘上的正文 $STAMP" /tmp/board_check.txt && ok "store.write 正文在 md 文件里" "$TAG.md" || bad "store.write 文件里没有正文" "$STAMP"
grep -q "#$TAG" /tmp/board_check.txt && ok "store.write 标签行在文件里" "#$TAG" || bad "store.write 没有标签行" "缺"

# 清理
tap '.card' >/dev/null; sleep 1
tap '#btn-more' >/dev/null; sleep 1
tap '[data-act=delete]' >/dev/null; sleep 1
tap '[data-act=delete]' >/dev/null; sleep 2

finish_board $BOARD
