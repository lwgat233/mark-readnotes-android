#!/usr/bin/env python3
"""给验收用的极简 WebDAV 服务（只用标准库，不装任何东西）。

支持：OPTIONS / PROPFIND(Depth 0|1) / GET / PUT / DELETE / MKCOL，Basic 认证。
根目录、端口、账号密码由命令行给；每个请求都写进日志（验收就拿日志当证据）。

用法：
  python3 tools/webdav-mini.py --root /vol1/1000/aicache/dav-root --port 8088 --user test --pass testpass --log evidence/dav-server.log
"""
import argparse
import base64
import datetime
import hashlib
import os
import pathlib
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ARGS = None


def http_date(ts: float) -> str:
    return datetime.datetime.fromtimestamp(ts, datetime.timezone.utc).strftime("%a, %d %b %Y %H:%M:%S GMT")


def etag_of(p: pathlib.Path) -> str:
    st = p.stat()
    return hashlib.sha1(f"{st.st_size}:{int(st.st_mtime)}".encode()).hexdigest()[:16]


class Dav(BaseHTTPRequestHandler):
    server_version = "mini-dav/1.0"

    # ---------- 日志 ----------
    def log_message(self, fmt, *a):
        line = "%s - %s\n" % (self.log_date_time_string(), fmt % a)
        sys.stdout.write(line)
        sys.stdout.flush()
        if ARGS.log:
            with open(ARGS.log, "a", encoding="utf-8") as f:
                f.write(line)

    # ---------- 认证 ----------
    def authed(self) -> bool:
        h = self.headers.get("Authorization", "")
        if not h.startswith("Basic "):
            return False
        try:
            u, p = base64.b64decode(h[6:]).decode().split(":", 1)
        except Exception:
            return False
        return u == ARGS.user and p == ARGS.password

    def deny(self):
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="mini-dav"')
        self.send_header("Content-Length", "0")
        self.end_headers()

    # ---------- 路径 ----------
    def fs_path(self, url_path: str) -> pathlib.Path:
        rel = urllib.parse.unquote(url_path.split("?", 1)[0])
        prefix = ARGS.prefix.rstrip("/")            # 请求路径里带着配置的 base（如 /dav），先剥掉
        if prefix and rel.startswith(prefix):
            rel = rel[len(prefix):]
        rel = rel.lstrip("/")
        # 挡掉 ..
        parts = [p for p in rel.split("/") if p not in ("", ".", "..")]
        return pathlib.Path(ARGS.root).joinpath(*parts)

    def href(self, rel: str, is_dir: bool) -> str:
        """rel 是相对 DAV 根的路径（"." 表示根本身）。base 用挂载前缀，不用请求路径 ——
        用请求路径会把「列哪个目录」也拼进去，变成 /dav/随笔/随笔/web.md 这种。"""
        root_url = (ARGS.prefix.rstrip("/") or "") + "/"
        if rel in ("", "."):
            return urllib.parse.quote(root_url)
        return urllib.parse.quote(root_url + rel) + ("/" if is_dir else "")

    # ---------- 方法 ----------
    def do_OPTIONS(self):
        if not self.authed():
            return self.deny()
        self.send_response(200)
        self.send_header("DAV", "1")
        self.send_header("Allow", "OPTIONS, PROPFIND, GET, PUT, DELETE, MKCOL")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_PROPFIND(self):
        if not self.authed():
            return self.deny()
        p = self.fs_path(self.path)
        if not p.exists():
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        depth = self.headers.get("Depth", "0")
        targets = [p]
        if depth != "0" and p.is_dir():
            targets += sorted(p.iterdir(), key=lambda x: x.name)
        out = ['<?xml version="1.0" encoding="utf-8"?>', '<D:multistatus xmlns:D="DAV:">']
        for t in targets:
            is_dir = t.is_dir()
            rel = t.relative_to(ARGS.root).as_posix()
            if rel == ".":
                rel = ""
            out.append("<D:response>")
            out.append(f"<D:href>{self.href(rel, is_dir)}</D:href>")
            out.append("<D:propstat><D:prop>")
            out.append("<D:resourcetype>" + ("<D:collection/>" if is_dir else "") + "</D:resourcetype>")
            if not is_dir:
                st = t.stat()
                out.append(f"<D:getcontentlength>{st.st_size}</D:getcontentlength>")
                out.append(f"<D:getlastmodified>{http_date(st.st_mtime)}</D:getlastmodified>")
                out.append(f"<D:getetag>{etag_of(t)}</D:getetag>")
            out.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")
            out.append("</D:response>")
        out.append("</D:multistatus>")
        body = "\n".join(out).encode("utf-8")
        self.send_response(207)
        self.send_header("Content-Type", "application/xml; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self.authed():
            return self.deny()
        p = self.fs_path(self.path)
        if not p.is_file():
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        data = p.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "text/markdown; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("ETag", etag_of(p))
        self.end_headers()
        self.wfile.write(data)

    def do_PUT(self):
        if not self.authed():
            return self.deny()
        p = self.fs_path(self.path)
        n = int(self.headers.get("Content-Length", "0"))
        data = self.rfile.read(n)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        self.send_response(201 if n >= 0 else 200)
        self.send_header("ETag", etag_of(p))
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_DELETE(self):
        if not self.authed():
            return self.deny()
        p = self.fs_path(self.path)
        ok = False
        if p.is_dir():
            try:
                p.rmdir()
                ok = True
            except OSError:
                ok = False
        elif p.is_file():
            p.unlink()
            ok = True
        self.send_response(204 if ok else 404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_MKCOL(self):
        if not self.authed():
            return self.deny()
        p = self.fs_path(self.path)
        if p.exists():
            code = 405
        else:
            try:
                p.mkdir(parents=True)
                code = 201
            except Exception:
                code = 409
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()


def main():
    global ARGS
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--port", type=int, default=8088)
    ap.add_argument("--user", default="test")
    ap.add_argument("--pass", dest="password", default="testpass")
    ap.add_argument("--prefix", default="/dav")
    ap.add_argument("--log", default="")
    ARGS = ap.parse_args()
    pathlib.Path(ARGS.root).mkdir(parents=True, exist_ok=True)
    print(f"mini-dav 起在 0.0.0.0:{ARGS.port}{ARGS.prefix}/ → {ARGS.root}（用户 {ARGS.user}）", flush=True)
    ThreadingHTTPServer(("0.0.0.0", ARGS.port), Dav).serve_forever()


if __name__ == "__main__":
    main()
