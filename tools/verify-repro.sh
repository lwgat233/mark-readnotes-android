#!/usr/bin/env bash
# 复现校验：用归档里的源码快照在一处干净目录重新构建，再和归档构件比。
# 结论口径（2026-09-22 实测）：**条目级一致、整包 sha256 不一致** ——
#   71 个条目逐条 CRC/大小/压缩方式完全相同，但 zip 的对齐与 extra 字段不同，
#   首个差异出现在 local file header 的 extra 长度（16 vs 20 字节），累计差约 48KB。
#   所以：拿“条目级对比”当复现凭证；整包 sha256 只用于标识“这就是交付的那一份”。
#
# 用法：bash tools/verify-repro.sh [要对比的 apk，默认取 apk/ 里最新那个]
set -uo pipefail
export JAVA_HOME=${JAVA_HOME:-/home/lwgat/tools/jdk-17.0.2}
export ANDROID_HOME=${ANDROID_HOME:-/home/lwgat/tools/android-sdk}
unset ANDROID_SDK_ROOT
GRADLE=${GRADLE:-/home/lwgat/tools/gradle-8.7/bin/gradle}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ARCHIVE_APK="${1:-$(ls -t "$ROOT"/apk/*.apk | head -1)}"
REPRO=/vol1/1000/aicache/buildtmp/mrn-repro

echo "归档构件：$ARCHIVE_APK"
sha256sum "$ARCHIVE_APK"

rm -rf "$REPRO"; mkdir -p "$REPRO"
( cd "$ROOT" && git archive HEAD source/mark-readnotes ) | tar -x -C "$REPRO" || { echo "git archive 失败"; exit 1; }
cd "$REPRO/source/mark-readnotes"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
"$GRADLE" assembleDebug --no-daemon -Pkotlin.compiler.execution.strategy=in-process 2>&1 | grep -E "^e:|BUILD" | head -3
REPRO_APK="$REPRO/source/mark-readnotes/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$REPRO_APK" ] || { echo "没有产物，构建失败"; exit 2; }
sha256sum "$REPRO_APK"

python3 - "$ARCHIVE_APK" "$REPRO_APK" <<'PY'
import sys, zipfile
A, B = sys.argv[1], sys.argv[2]
za, zb = zipfile.ZipFile(A), zipfile.ZipFile(B)
na = {i.filename: i for i in za.infolist()}
nb = {i.filename: i for i in zb.infolist()}
print("条目名一致:", set(na) == set(nb), "| 条目数:", len(na), len(nb))
bad = [n for n in na if n in nb and (na[n].CRC, na[n].file_size, na[n].compress_size) != (nb[n].CRC, nb[n].file_size, nb[n].compress_size)]
print("内容（CRC/大小）不一致的条目:", len(bad), bad[:5])
print("整包 sha256 相同:", open(A, 'rb').read() == open(B, 'rb').read())
PY
echo
echo "判定：内容一致（上面『不一致条目 0』）= 源码快照能重建这一版；"
echo "      整包 sha256 不同是打包期的 zip 对齐/extra 字段差异，不是代码差异。"
