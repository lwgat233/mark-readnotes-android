#!/usr/bin/env bash
# 设置板块：状态行（笔记根/块数）与刷新索引
source "$(dirname "$0")/_lib.sh"
BOARD=设置
start_board $BOARD

tap '#btn-settings' >/dev/null; sleep 1
BODY=$(page 'document.getElementById("sheet-body").textContent')
echo "$BODY" | grep -q "笔记根" && ok "settings.root 有笔记根这一行" "笔记根" || bad "settings.root 没有笔记根行" "缺"
echo "$BODY" | grep -q "文件 / 块" && ok "settings.root 有文件/块计数行" "文件 / 块" || bad "settings.root 没有计数行" "缺"
echo "$BODY" | grep -q "最近扫描" && ok "settings.root 有最近扫描时间行" "最近扫描" || bad "settings.root 没有扫描时间" "缺"

# 刷新索引：走的是 exp/idx op，读返回值
R=$(page '(async()=>{const r=await call("idx.refresh");return r;})()')
info "刷新结果：$R"
assert_true "settings.refresh 回包里有文件数" "$(echo "$R" | jget "d['files']>=0")"
WALK=$(logcnt "scan=walk")
assert_true "settings.refresh 主动刷新时才 walk" "$([ "$WALK" -ge 1 ] && echo True || echo False)"

tap '#sheet-close' >/dev/null; sleep 1

finish_board $BOARD
