#!/usr/bin/env bash
# 分板块验收的公共库：每条断言都打印「读回来的事实」，最后给 OK/FAIL 计数。
# 计数器名字避开 PASS/FAIL（宿主环境变量同名会被静默改写）。
ROOT=/vol1/1000/airesults/mark-readnotes
# node 在 ~/.local/bin（后台/子 shell 的 PATH 常常没有它 → 探针会静默变成空值，假红）
export PATH="$HOME/.local/bin:$PATH"
PROBE="node $ROOT/tools/webview-cdp.mjs"
PKG=dev.markreadnotes
ADB="adb -s emulator-5554"
OKCNT=0
BADCNT=0

ok()  { OKCNT=$((OKCNT+1)); echo "OK   $1 | $2"; }
bad() { BADCNT=$((BADCNT+1)); echo "FAIL $1 | $2"; }
info(){ echo "     $1"; }

page()  { ( cd "$ROOT" && $PROBE eval "$1" ); }
tap()   { ( cd "$ROOT" && $PROBE tap "$1" ); }
rect()  { ( cd "$ROOT" && $PROBE rect "$1" ); }

# 断言：相等
assert_eq() { [ "$2" = "$3" ] && ok "$1" "$2" || bad "$1" "got=$2 want=$3"; }
# 断言：真
assert_true() { [ "$2" = "True" ] && ok "$1" "True" || bad "$1" "got=$2 want=True"; }
# 从 JSON 里取一个字段（python，别用 grep）；取不到就当空，不让脚本炸
jget() { python3 -c "
import sys,json
try:
    d=json.loads(sys.stdin.read())
    print($1)
except Exception:
    print('')
"; }
# 去掉探针输出里的 JSON 引号（字符串比较时用）
unq() { python3 -c "import sys,json;s=sys.stdin.read().strip();
print(json.loads(s) if s.startswith('\"') else s)"; }
# 设备日志里数某个模式出现次数
logcnt() { $ADB logcat -d -s markreadnotes 2>/dev/null | grep -cE "$1"; }
# 宿主上判定中文内容（设备上 grep 中文会假阴性）
devcat() { $ADB shell "cat '$1'" 2>/dev/null > "/tmp/board_check.txt"; }

require_device() {
  $ADB shell true >/dev/null 2>&1 || { echo "环境问题：模拟器不在线（$ADB）"; exit 3; }
}

# 让应用回到前台并重挂 devtools 转发。
# 为什么需要：点链接/系统分享会把应用挤到后台，内存紧的模拟器会把它杀掉 →
# 探针的 fetch 直接失败（现象是一堆 undici 报错），看起来像功能坏了。
refocus_app() {
  $ADB shell input keyevent 4 >/dev/null 2>&1
  sleep 1
  # 顺手把被拉起来的浏览器关掉：模拟器只有 2GB，Chrome 常驻会把我们的应用挤掉
  $ADB shell am force-stop com.android.chrome >/dev/null 2>&1
  $ADB shell am force-stop com.google.android.apps.chrome >/dev/null 2>&1
  $ADB shell am start -n $PKG/.MainActivity >/dev/null 2>&1
  sleep 3
  local pid
  pid=$($ADB shell pidof $PKG | tr -d '\r')
  if [ -z "$pid" ]; then
    echo "环境问题：应用进程不在了（多半是内存紧张被杀）"
    return 1
  fi
  $ADB forward --remove-all >/dev/null 2>&1
  $ADB forward tcp:9222 localabstract:webview_devtools_remote_$pid >/dev/null
  sleep 1
  return 0
}

start_board() {
  echo "==== 板块验收：$1 ===="
  require_device
  refocus_app || { echo "无法把应用拉到前台，中止本板块（环境问题）"; exit 3; }
}

finish_board() {
  echo "---- $1: OK=$OKCNT FAIL=$BADCNT"
  [ "$BADCNT" = "0" ] || return 1
}
