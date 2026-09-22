#!/usr/bin/env python3
"""给「网络图片」验收用的极简 HTTP 服务：把每个请求写进日志文件，便于事后核对请求真的到了。
用法：python3 netimg-server.py <dir> <port> <logfile>"""
import http.server, socketserver, sys, os, datetime

DIR = sys.argv[1]
PORT = int(sys.argv[2])
LOG = sys.argv[3]


class H(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=DIR, **kw)

    def log_message(self, fmt, *args):
        line = "%s %s %s\n" % (datetime.datetime.now().strftime("%H:%M:%S"), self.client_address[0], fmt % args)
        with open(LOG, "a") as f:
            f.write(line)
        sys.stderr.write(line)


socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("0.0.0.0", PORT), H) as httpd:
    httpd.serve_forever()
