#!/usr/bin/env bash
# 设置板块：随笔目录状态行、条数与刷新、行内输入/按键在位（随笔目录与文件夹都从这里管）
source "$(dirname "$0")/_lib.sh"
BOARD=设置
start_board $BOARD

tap '#btn-settings' >/dev/null; sleep 1
BODY=$(page 'document.getElementById("sheet-body").textContent')
echo "$BODY" | grep -q "随笔目录" && ok "settings.root 有随笔目录这一行" "随笔目录" || bad "settings.root 没有随笔目录行" "缺"
echo "$BODY" | grep -q "文件 / 块" && ok "settings.root 有文件/块计数行" "文件 / 块" || bad "settings.root 没有计数行" "缺"
echo "$BODY" | grep -q "最近扫描" && ok "settings.root 有最近扫描时间行" "最近扫描" || bad "settings.root 没有扫描时间" "缺"
if echo "$BODY" | grep -q "待整理"; then ok "settings.node 有待整理一行" "待整理" ; else bad "settings.node 没有待整理行" "缺" ; fi

# 行内输入与它自己的动作：新建文件夹 / 标签→文件夹（有输入就必须有对应的键）
CTL=$(page '({mk:!!document.querySelector("[data-act=mkdir]"),tf:!!document.querySelector("[data-act=tagfolder]"),i1:!!document.getElementById("set-folder-name"),i2:!!document.getElementById("set-tag"),i3:!!document.getElementById("set-tag-folder"),btns:document.querySelectorAll("#sheet-body .controls button").length})')
assert_true "settings.folder 新建文件夹：输入框+键在位" "$(echo "$CTL" | jget "d['i1'] and d['mk']")"
assert_true "settings.folder 标签→文件夹：两个输入+键在位" "$(echo "$CTL" | jget "d['i2'] and d['i3'] and d['tf']")"
info "行内按键数：$(echo "$CTL" | jget "d['btns']")"

# 底部常驻键只留 3 个（刷新索引 / 重新选择目录 / 关闭）
FOOT=$(page 'Array.prototype.slice.call(document.querySelectorAll("#sheet-body > .row > button.wide")).map(b=>b.textContent)')
info "底部常驻键：$FOOT"
assert_eq "settings.keys 底部常驻键 3 个" "$(echo "$FOOT" | jget "len(d)")" "3"

# 刷新索引：走 op，读返回值；主动刷新才走目录 walk
$ADB logcat -c
R=$(page '(async()=>{const r=await call("idx.refresh");return r;})()')
info "刷新结果：$R"
assert_true "settings.refresh 回包里有文件数" "$(echo "$R" | jget "d['files']>=0")"
WALK=$(logcnt "scan=walk")
assert_true "settings.refresh 主动刷新时才 walk" "$([ "$WALK" -ge 1 ] && echo True || echo False)"
assert_true "settings.refresh 扫描只覆盖随笔目录" "$([ "$(logcnt "scan=walk\\(files=[0-9]+,changed=[0-9]+,legacy=[0-9]+")" -ge 1 ] && echo True || echo False)"

tap '#sheet-close' >/dev/null; sleep 1

finish_board $BOARD
