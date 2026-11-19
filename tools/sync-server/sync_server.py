#!/usr/bin/env python3
"""
Trellis HTTP 同步服务的「零依赖」参考实现（Python 3.8+，只用标准库）。

为什么有这个文件：VPS 上如果暂时没有 JDK，可以直接用这份脚本顶替
`:sync-server` 的 Kotlin 版；协议完全一致，客户端无感。

接口：

    GET  /health                      -> 200 ok（不校验 token，方便网关/隧道探活）
    GET  /v1/object?path=...          -> 对象字节；不存在 404
    PUT  /v1/object?path=...          -> 写入（body 原始字节），204
    GET  /v1/list?prefix=...          -> JSON: [{"path": "...", "size": 123}, ...]

安全：

- 只接受相对路径，拒绝 `..` 和绝对路径，所有文件都必须落在 --root 下；
- 可选 --token（直连调试用；放在网关后面时通常留空，因为网关已经校验并剥掉 token）；
- 可选 --user-header（网关注入用户 id 后按用户分目录，例如 X-Trellis-User）。

运行：

    python3 sync_server.py --host 127.0.0.1 --port 8000 --root /root/workspace/sync-data
"""

import argparse
import json
import os
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

MAX_OBJECT_BYTES = 64 * 1024 * 1024


class SyncState:
    def __init__(self, root: str, token: str, user_header: str, max_bytes: int):
        self.root = os.path.realpath(root)
        self.token = token
        self.user_header = user_header
        self.max_bytes = max_bytes
        self.lock = threading.Lock()
        os.makedirs(self.root, exist_ok=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "TrellisSync/1.0"

    # ---- 基础设施 ---------------------------------------------------------

    @property
    def state(self) -> SyncState:
        return self.server.state  # type: ignore[attr-defined]

    def log_message(self, fmt, *args):
        # 默认日志会带完整 query（可能含 token/path）；这里只留方法/路径/状态。
        sys.stderr.write("%s %s\n" % (self.command, self.path.split("?")[0]))

    def _send(self, code: int, body: bytes, content_type: str = "application/octet-stream"):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, PUT, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "X-Token, Authorization, Content-Type, X-Trellis-User")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, code: int, obj):
        self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"), "application/json")

    def _authorized(self) -> bool:
        if not self.state.token:
            return True
        supplied = self.headers.get("X-Token")
        if not supplied:
            auth = self.headers.get("Authorization", "")
            if auth.lower().startswith("bearer "):
                supplied = auth[7:].strip()
        return supplied == self.state.token

    def _user_root(self):
        if not self.state.user_header:
            return self.state.root
        raw = (self.headers.get(self.state.user_header) or "").strip()
        safe = "".join(c for c in raw if c.isalnum() or c in "._-")[:64]
        if not safe:
            self._json(401, {"error": "missing user header"})
            return None
        return os.path.join(self.state.root, "u-" + safe)

    def _resolve(self, base: str, raw: str):
        if not raw:
            raise ValueError("path is empty")
        if raw.startswith("/") or raw.startswith("\\"):
            raise ValueError("absolute path is not allowed")
        normalized = raw.replace("\\", "/").strip("/")
        parts = normalized.split("/")
        if any(p in ("", ".", "..") for p in parts):
            raise ValueError("illegal path")
        full = os.path.realpath(os.path.join(base, normalized))
        if full != base and not full.startswith(base + os.sep):
            raise ValueError("path escapes root")
        return full

    # ---- 路由 -------------------------------------------------------------

    def do_OPTIONS(self):
        self._send(204, b"")

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            self._send(200, b"ok", "text/plain; charset=utf-8")
            return
        if parsed.path not in ("/v1/object", "/v1/list"):
            self._json(404, {"error": "not found"})
            return
        if not self._authorized():
            self._json(401, {"error": "unauthorized"})
            return
        base = self._user_root()
        if base is None:
            return

        if parsed.path == "/v1/object":
            raw = (parse_qs(parsed.query).get("path") or [""])[0]
            try:
                full = self._resolve(base, raw)
            except ValueError as exc:
                self._json(400, {"error": str(exc)})
                return
            if not os.path.isfile(full):
                self._send(404, b"not found", "text/plain; charset=utf-8")
                return
            with open(full, "rb") as fh:
                self._send(200, fh.read())
            return

        # /v1/list
        prefix = (parse_qs(parsed.query).get("prefix") or [""])[0]
        prefix = prefix.strip("/")
        try:
            start = base if not prefix else self._resolve(base, prefix)
        except ValueError as exc:
            self._json(400, {"error": str(exc)})
            return
        if os.path.isfile(start):
            files = [start]
        elif os.path.isdir(start):
            files = []
            for dirpath, _, names in os.walk(start):
                for name in names:
                    if ".tmp-" in name:
                        continue
                    files.append(os.path.join(dirpath, name))
        else:
            files = []
        out = []
        for full in sorted(files):
            rel = os.path.relpath(full, base).replace(os.sep, "/")
            if prefix and not rel.startswith(prefix):
                continue
            try:
                size = os.path.getsize(full)
            except OSError:
                size = 0
            out.append({"path": rel, "size": size})
        self._json(200, out)

    def do_PUT(self):
        parsed = urlparse(self.path)
        if parsed.path != "/v1/object":
            self._json(404, {"error": "not found"})
            return
        if not self._authorized():
            self._json(401, {"error": "unauthorized"})
            return
        base = self._user_root()
        if base is None:
            return
        raw = (parse_qs(parsed.query).get("path") or [""])[0]
        try:
            full = self._resolve(base, raw)
        except ValueError as exc:
            self._json(400, {"error": str(exc)})
            return
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length > self.state.max_bytes:
            self._json(413, {"error": "too large"})
            return
        body = self.rfile.read(length)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        fd, tmp = tempfile.mkstemp(prefix=".tmp-", dir=os.path.dirname(full))
        try:
            with os.fdopen(fd, "wb") as fh:
                fh.write(body)
            os.replace(tmp, full)
        finally:
            if os.path.exists(tmp):
                try:
                    os.remove(tmp)
                except OSError:
                    pass
        self._send(204, b"")


def main(argv):
    parser = argparse.ArgumentParser(description="Trellis HTTP sync server (zero dependency)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--root", default="sync-data")
    parser.add_argument("--token", default="")
    parser.add_argument("--user-header", default="")
    parser.add_argument("--max-bytes", type=int, default=MAX_OBJECT_BYTES)
    args = parser.parse_args(argv)

    state = SyncState(args.root, args.token, args.user_header, args.max_bytes)
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    httpd.state = state  # type: ignore[attr-defined]
    print("trellis sync server: http://%s:%d  root=%s" % (args.host, httpd.server_address[1], state.root))
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main(sys.argv[1:])
