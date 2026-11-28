#!/usr/bin/env python3
"""Serve exactly the signed public graph, for local activation/verification.

Binds to loopback by default and exposes only the signed /graph.json envelope:
no directory listing, no traversal, no other files. A temporary tunnel (or a
production static host) may put HTTPS in front of it; the model process never
runs this and never holds the signing key.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

FILES = {"/graph.json": ("graph.json", "application/json")}


class Handler(BaseHTTPRequestHandler):
    store = Path(".")

    def do_HEAD(self):
        self._send(head=True)

    def do_GET(self):
        self._send(head=False)

    def _send(self, head):
        entry = FILES.get(self.path)
        if not entry:
            self.send_error(404)
            return
        name, content_type = entry
        try:
            body = (self.store / name).read_bytes()
        except OSError:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if not head:
            self.wfile.write(body)

    def log_message(self, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--store", required=True, type=Path)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8455)
    args = parser.parse_args()
    Handler.store = args.store
    ThreadingHTTPServer((args.bind, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
