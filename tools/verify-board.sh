#!/usr/bin/env bash
# 分板块验收跑法：一次只跑一个板块的检查（宿主内存紧，别把模拟器+构建+多套测试一起堆）。
#
#   bash tools/verify-board.sh list               列出板块与功能
#   bash tools/verify-board.sh notes [--keep]     跑随笔板块（--keep 保留模拟器）
#   bash tools/verify-board.sh all                依次跑全部板块（串行）
#
# 每段结束后可以选择停掉模拟器回收内存（默认 all 模式跑完就停）。
set -uo pipefail
ROOT=/vol1/1000/airesults/mark-readnotes
export PATH=/home/lwgat/tools/android-sdk/platform-tools:$HOME/.local/bin:/usr/bin:/bin
export ANDROID_HOME=/home/lwgat/tools/android-sdk JAVA_HOME=/home/lwgat/tools/jdk-17.0.2
unset ANDROID_SDK_ROOT
ADB="adb -s emulator-5554"
PKG=dev.markreadnotes
BOARDS="notes render store export settings"

ensure_emulator() {
  if $ADB shell true >/dev/null 2>&1; then echo "模拟器已在线"; return 0; fi
  echo "启动模拟器（AVD test35）…"
  ( AVD=test35 bash /vol1/1000/aicache/docker/emu.sh start 2>&1 | tail -3 )
  for i in $(seq 1 40); do $ADB shell true >/dev/null 2>&1 && break; sleep 5; done
  $ADB shell true >/dev/null 2>&1 || { echo "模拟器起不来（环境问题）"; return 3; }
  echo "模拟器就绪"
}

install_apk() {
  APK="$ROOT/source/mark-readnotes/app/build/outputs/apk/debug/app-debug.apk"
  [ -f "$APK" ] || { echo "没有构件，先构建：gradle assembleDebug"; return 4; }
  sha256sum "$APK"
  # 装包前先证明构件里有启动类（源文件被写空时构建也会“成功”）
  C=$(unzip -p "$APK" classes3.dex 2>/dev/null | strings | grep -c "Ldev/markreadnotes/MainActivity;")
  [ "${C:-0}" -ge 1 ] || { echo "构件里没有 MainActivity，别装！"; return 4; }
  $ADB install -r "$APK" 2>&1 | tail -1
  $ADB shell am force-stop $PKG >/dev/null
  $ADB shell am start -n $PKG/.MainActivity >/dev/null
  sleep 5
  local pid; pid=$($ADB shell pidof $PKG | tr -d '\r')
  $ADB forward --remove-all >/dev/null
  $ADB forward tcp:9222 localabstract:webview_devtools_remote_$pid >/dev/null
  echo "PID=$pid forward 就绪"
}

run_board() {
  local b="$1"
  local script="$ROOT/tools/boards/$b.sh"
  [ -f "$script" ] || { echo "没有这个板块的脚本：$b"; return 2; }
  local log="$ROOT/evidence/board-$b.txt"
  bash "$script" 2>&1 | tee "$log"
  local rc=${PIPESTATUS[0]}
  tail -1 "$log"
  return $rc
}

case "${1:-}" in
  list)
    python3 - "$ROOT" <<'PY'
import json, re, sys, pathlib
root = pathlib.Path(sys.argv[1])
js = (root / "source/mark-readnotes/app/src/main/assets/ui/registry.js").read_text(encoding="utf-8")
data = json.loads(js[js.index("{"):js.rindex("}") + 1])
for b in sorted(data["boards"], key=lambda x: x["order"]):
    own = [f for f in data["features"] if f["board"] == b["id"]]
    print(f'{b["id"]:9} {b["name"]:4} 功能 {len(own):2} 载体 {b.get("carrier","")}')
PY
    ;;
  all)
    ensure_emulator && install_apk || exit $?
    RC=0
    for b in $BOARDS; do run_board "$b" || RC=1; echo; done
    echo "全部板块跑完（有失败项时退出码 1）"
    if [ "${2:-}" != "--keep" ]; then
      echo "停模拟器回收内存…"
      docker rm -f notifbridge-emu >/dev/null 2>&1
      free -m | head -2
    fi
    exit $RC
    ;;
  ""|help)
    sed -n '2,10p' "$0"
    ;;
  *)
    ensure_emulator && install_apk || exit $?
    run_board "$1"
    RC=$?
    if [ "${2:-}" != "--keep" ]; then
      docker rm -f notifbridge-emu >/dev/null 2>&1
      free -m | head -2
    fi
    exit $RC
    ;;
esac
