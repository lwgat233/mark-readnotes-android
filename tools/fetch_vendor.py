#!/usr/bin/env python3
"""拉取渲染层要内置的前端库（MIT），校验 npm 声明的 integrity，再装进 assets。

用法：python3 tools/fetch_vendor.py
产出：
  source/mark-readnotes/app/src/main/assets/ui/vendor/markdown-it/{markdown-it.min.js}
  …/vendor/markdown-it-texmath/{texmath.js,texmath.css}
  …/vendor/markdown-it-task-lists/{markdown-it-task-lists.min.js}
  evidence/vendor-provenance.txt（来源 + 哈希校验结果）
缓存：/vol1/1000/aicache/tools_dl/vendor/<name>.tgz（下载一次长期复用）
"""
import base64
import hashlib
import json
import pathlib
import tarfile
import urllib.request

ROOT = pathlib.Path("/vol1/1000/airesults/mark-readnotes")
VENDOR = ROOT / "source/mark-readnotes/app/src/main/assets/ui/vendor"
CACHE = pathlib.Path("/vol1/1000/aicache/tools_dl/vendor")
MIRROR = "https://registry.npmmirror.com"

# 包名 → (版本, 从包里取哪些文件 → 装到哪)
PKGS = {
    "markdown-it": ("14.1.0", {"dist/markdown-it.min.js": "markdown-it.min.js"}),
    "markdown-it-texmath": ("1.0.0", {"texmath.js": "texmath.js", "css/texmath.css": "texmath.css"}),
    "markdown-it-task-lists": ("2.1.1", {"dist/markdown-it-task-lists.min.js": "markdown-it-task-lists.min.js"}),
}


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def main() -> int:
    CACHE.mkdir(parents=True, exist_ok=True)
    lines = ["渲染层内置前端库的来源与校验（照 KaTeX 的做法）", ""]
    for name, (ver, files) in PKGS.items():
        meta = json.loads(fetch(f"{MIRROR}/{name}/{ver}").decode())
        integ = meta["dist"]["integrity"]
        alg, b64 = integ.split("-", 1)
        tgz = CACHE / f"{name}-{ver}.tgz"
        if not tgz.exists():
            url = f"https://cdn.npmmirror.com/packages/{name}/{ver}/{name}-{ver}.tgz"
            tgz.write_bytes(fetch(url))
        data = tgz.read_bytes()
        got = base64.b64encode(hashlib.new(alg, data).digest()).decode()
        same = got == b64
        sha256 = hashlib.sha256(data).hexdigest()
        lines.append(f"{name} {ver}")
        lines.append(f"  许可: {meta.get('license')}")
        lines.append(f"  来源: https://cdn.npmmirror.com/packages/{name}/{ver}/{name}-{ver}.tgz")
        lines.append(f"  tarball sha256: {sha256}")
        lines.append(f"  与 npm 声明的 integrity({alg}) 一致: {same}")
        if not same:
            print(f"[x] {name} 校验不一致，停止")
            return 1

        out_dir = VENDOR / name
        out_dir.mkdir(parents=True, exist_ok=True)
        with tarfile.open(tgz) as tf:
            names = tf.getnames()
            for src, dst in files.items():
                member = next((n for n in names if n.endswith("/" + src)), None)
                if member is None:
                    print(f"[x] {name} 包里没有 {src}；可用：{names[:12]}")
                    return 1
                (out_dir / dst).write_bytes(tf.extractfile(member).read())
                size = (out_dir / dst).stat().st_size
                lines.append(f"  装入: vendor/{name}/{dst} ({size} 字节)")
        print(f"[ok] {name} {ver} → {out_dir}")

    (ROOT / "evidence").mkdir(exist_ok=True)
    (ROOT / "evidence/vendor-provenance.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("来源与校验写入 evidence/vendor-provenance.txt")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
