#!/usr/bin/env python3
"""One read-only HTTP check of an explicitly selected, isolated ERP backend."""

import http.client
import queue
import re
import socket
import sys
import threading

LOOPBACK = "127.0.0.1"
MIN_PORT = 49152
MAX_PORT = 65535
IO_TIMEOUT = 1.0  # seconds, per socket operation
DEADLINE = 3.0  # seconds, entire probe, even if bytes keep arriving
MAX_BODY = 1024  # UTF-8 response bytes

USAGE = "Usage: python3 smoke-readonly.py --fixture-port PORT (49152..65535; isolated loopback only)"
CONTENT_TYPE = re.compile(r'[ \t]*text/plain[ \t]*;[ \t]*charset[ \t]*=[ \t]*(?:utf-8|"utf-8")[ \t]*', re.ASCII | re.I)
# IndexController's backend-only branch. Deliberately do not accept SPA HTML.
WELCOME = re.compile(r"欢迎使用([\w. -]{1,128})后台管理框架，请通过前端地址访问。")


def check(port):
    # No URL parser, proxy discovery, authentication, cookies, redirect or retry.
    connection = http.client.HTTPConnection(LOOPBACK, port, timeout=IO_TIMEOUT)
    try:
        connection.request("GET", "/", headers={"Accept": "text/plain", "Connection": "close"})
        with connection.getresponse() as response:
            if response.status != 200:
                return f"HTTP {response.status}; expected 200"
            types = response.headers.get_all("Content-Type", [])
            if len(types) != 1 or not CONTENT_TYPE.fullmatch(types[0]):
                return "expected text/plain with UTF-8 charset"
            if response.getheader("Content-Encoding", "identity").strip().lower() != "identity":
                return "unexpected Content-Encoding"

            lengths = response.headers.get_all("Content-Length", [])
            transfer = response.getheader("Transfer-Encoding")
            if transfer is not None and (transfer.lower() != "chunked" or lengths):
                return "invalid response framing"
            length = None
            if lengths:
                if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,10}", lengths[0].strip(" \t")):
                    return "invalid Content-Length"
                length = int(lengths[0])
                if length > MAX_BODY:
                    return "response body exceeds limit"
            body = response.read(MAX_BODY + 1)
            if len(body) > MAX_BODY:
                return "response body exceeds limit"
            if length is not None and len(body) != length:
                return "truncated response body"

            match = WELCOME.fullmatch(body.decode("utf-8"))
            if match is None or not match[1].strip():
                return "unexpected backend welcome shape"
    except socket.timeout:
        return "socket I/O timeout"
    except UnicodeDecodeError:
        return "invalid UTF-8 response body"
    except (OSError, http.client.HTTPException, ValueError):
        # Exception strings and bodies may contain arbitrary data; do not log them.
        return "connection or HTTP response error"
    finally:
        connection.close()
    return None


def main(argv):
    if len(argv) != 2 or argv[0] != "--fixture-port" or not re.fullmatch(r"[1-9][0-9]{0,4}", argv[1]):
        print(f"REFUSED: {USAGE}", file=sys.stderr)
        return 2
    port = int(argv[1])
    if not MIN_PORT <= port <= MAX_PORT:
        print(f"REFUSED: {USAGE}", file=sys.stderr)
        return 2

    # Socket timeouts alone are not total timeouts. This is a one-shot CLI;
    # on deadline the process exits without waiting on a stalled daemon worker.
    result = queue.Queue(maxsize=1)
    threading.Thread(target=lambda: result.put(check(port)), daemon=True).start()
    try:
        failure = result.get(timeout=DEADLINE)
    except queue.Empty:
        failure = "probe deadline exceeded"
    if failure is not None:
        print(f"FAIL GET /: {failure}", file=sys.stderr)
        return 1
    print("PASS GET /: HTTP 200 and ERP backend welcome")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
