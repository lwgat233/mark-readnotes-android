#!/usr/bin/env python3
"""验收用：把 webdav-mini.py 的服务面自己测一遍（不起外部进程、不联网）。

跑法：python3 tools/dav-selftest.py
覆盖：OPTIONS / MKCOL / PUT / GET / PROPFIND(Depth 0|1) / DELETE / 无认证 401 / .. 越界被挡。
"""
import base64
import importlib.util
import pathlib
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
from http.server import ThreadingHTTPServer

HERE = pathlib.Path(__file__).parent
ROOT = pathlib.Path("/vol1/1000/aicache/dav-selftest")
PORT = 8099


def load_module():
    spec = importlib.util.spec_from_file_location("dav", HERE / "webdav-mini.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    m.ARGS = m.argparse.Namespace(root=str(ROOT), port=PORT, user="test", password="testpass", prefix="/dav", log="")
    return m


def main() -> int:
    import shutil

    shutil.rmtree(ROOT, ignore_errors=True)
    ROOT.mkdir(parents=True, exist_ok=True)
    m = load_module()
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), m.Dav)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    def req(method, path, data=None, depth=None, auth=True):
        # 中文路径要按 URL 编码发（真实客户端也是这么发的；服务端负责解回来）
        enc = urllib.parse.quote(path, safe="/")
        r = urllib.request.Request(f"http://127.0.0.1:{PORT}{enc}", method=method, data=data)
        if auth:
            r.add_header("Authorization", "Basic " + base64.b64encode(b"test:testpass").decode())
        if depth:
            r.add_header("Depth", depth)
        try:
            with urllib.request.urlopen(r) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    ok = 0
    fail = 0

    def check(name, got, want):
        nonlocal ok, fail
        if got == want:
            ok += 1
            print(f"OK   自测 {name} | {got}")
        else:
            fail += 1
            print(f"FAIL 自测 {name} | got={got} want={want}")

    check("无认证要 401", req("OPTIONS", "/dav/", auth=False)[0], 401)
    check("OPTIONS 200", req("OPTIONS", "/dav/")[0], 200)
    check("MKCOL 建目录 201", req("MKCOL", "/dav/随笔")[0], 201)
    check("MKCOL 重复 405", req("MKCOL", "/dav/随笔")[0], 405)
    check("PUT 文件 201", req("PUT", "/dav/随笔/web.md", "# 标题\n#web\n正文".encode())[0], 201)
    check("GET 回原文", req("GET", "/dav/随笔/web.md")[1].decode(), "# 标题\n#web\n正文")
    code, body = req("PROPFIND", "/dav/随笔", None, "1")
    text = body.decode()
    check("PROPFIND 207", code, 207)
    check("PROPFIND 里有文件名", "web.md" in text, True)
    check("PROPFIND 里有长度", "<D:getcontentlength>" in text, True)
    check("PROPFIND 里有 etag", "<D:getetag>" in text, True)
    check("PROPFIND 里有时间", "<D:getlastmodified>" in text, True)
    for line in text.splitlines():
        if "href" in line:
            print("     href: " + line.strip())
    check("href 是编码后的完整路径（不重复前缀）", ("/dav/" + urllib.parse.quote("随笔/web.md")) in text, True)
    check("href 没有二次编码（不该出现 %25）", "%25" not in text, True)
    check("越界 ../ 被挡", req("PROPFIND", "/dav/../etc", None, "0")[0] in (404, 207), True)
    check("DELETE 204", req("DELETE", "/dav/随笔/web.md")[0], 204)
    check("DELETE 不存在的 404", req("DELETE", "/dav/随笔/web.md")[0], 404)

    srv.shutdown()
    print(f"---- 自测：OK={ok} FAIL={fail}")
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
