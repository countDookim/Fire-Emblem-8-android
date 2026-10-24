#!/usr/bin/env python3
"""Local retrying Maven mirror for cloud sessions (NOT used in CI).

Why: through the session's egress proxy, Maven Central intermittently answers 404 or 429 for artifacts
that exist. Gradle treats 404 as "missing" and does not retry, so builds fail at random. This mirror
retries upstream with backoff and caches successful responses on disk.

    python3 tools/maven_mirror.py --port 8765 --cache ~/.cache/fe8-maven &
    export FE8_MAVEN_MIRROR=http://127.0.0.1:8765     # settings.gradle.kts picks this up

Routes: /central/<path> -> Maven Central, /google/<path> -> Google Maven.
"""
from __future__ import annotations

import argparse
import os
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

UPSTREAMS = {
    "central": ["https://repo.maven.apache.org/maven2/", "https://repo1.maven.org/maven2/"],
    "google": ["https://dl.google.com/dl/android/maven2/"],
}
TRANSIENT_RETRIES = 10   # 429 / 5xx / network errors
NOT_FOUND_RETRIES = 12   # spurious 404s (CDN rate limiting): retry before believing them
CACHE: Path = Path()
LOCK = threading.Lock()


def fetch(route: str, path: str) -> tuple[int, bytes]:
    misses, attempt = 0, 0
    while True:
        base = UPSTREAMS[route][attempt % len(UPSTREAMS[route])]
        attempt += 1
        try:
            with urllib.request.urlopen(urllib.request.Request(base + path, headers={"User-Agent": "fe8-maven-mirror"}), timeout=60) as r:
                return 200, r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                misses += 1
                if misses > NOT_FOUND_RETRIES:
                    return 404, b""
                time.sleep(min(3.0, 0.3 * misses))
                continue
            if attempt > TRANSIENT_RETRIES:
                return e.code, b""
        except Exception:  # noqa: BLE001 - network errors are retried
            if attempt > TRANSIENT_RETRIES:
                return 502, b""
        time.sleep(min(8.0, 0.4 * 2 ** min(attempt, 5)))


class Handler(BaseHTTPRequestHandler):
    def _serve(self, body: bool) -> None:
        if self.path == "/health":
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            if body:
                self.wfile.write(b"ok")
            return
        parts = self.path.lstrip("/").split("/", 1)
        if len(parts) != 2 or parts[0] not in UPSTREAMS or ".." in parts[1]:
            self.send_error(400)
            return
        route, path = parts
        cached = CACHE / route / path
        if cached.is_file():
            data = cached.read_bytes()
        else:
            code, data = fetch(route, path)
            if code != 200:
                self.send_response(code)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            cached.parent.mkdir(parents=True, exist_ok=True)
            tmp = cached.with_name(cached.name + f".{os.getpid()}.{threading.get_ident()}.tmp")
            tmp.write_bytes(data)
            os.replace(tmp, cached)
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if body:
            self.wfile.write(data)

    def do_GET(self) -> None:  # noqa: N802
        self._serve(True)

    def do_HEAD(self) -> None:  # noqa: N802
        self._serve(False)

    def log_message(self, fmt: str, *args: object) -> None:
        pass


def main() -> None:
    global CACHE
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--cache", type=Path, default=Path.home() / ".cache" / "fe8-maven")
    a = ap.parse_args()
    CACHE = a.cache
    CACHE.mkdir(parents=True, exist_ok=True)
    ThreadingHTTPServer(("127.0.0.1", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
