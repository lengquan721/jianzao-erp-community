#!/usr/bin/env python3
"""O05 regression tests: no ERP, DB, or existing listeners are contacted."""

import contextlib
from concurrent.futures import ThreadPoolExecutor
import http.client
import importlib.util
import io
import os
from pathlib import Path
import random
import shutil
import socket
import subprocess
import sys
import threading
import unittest
from unittest.mock import patch

DIR = Path(__file__).resolve().parent
WORK = DIR.parents[1]
SCRIPT = DIR / "smoke-readonly.py"
SPEC = importlib.util.spec_from_file_location("smoke_readonly", SCRIPT)
PROBE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROBE)

HOST = "127.0.0.1"
GOOD = "欢迎使用fixture-ERP后台管理框架，请通过前端地址访问。".encode("utf-8")
MIME = b"Content-Type: text/plain;charset=UTF-8"
STUB = (
    b"#!/bin/bash\n"
    b"# Retired: the legacy write smoke is no longer shipped.\n"
    b"printf '%s\\n' 'REFUSED: legacy write smoke retired. See deploy/debian-native/SMOKE-GATES.md.' >&2\n"
    b"exit 64\n"
)


def wire(body=GOOD, *, status=b"200 OK", mime=MIME, extra=(), length=True):
    headers = [b"HTTP/1.1 " + status, b"Connection: close"]
    if mime is not None:
        headers.append(mime)
    if length:
        headers.append(b"Content-Length: " + str(len(body)).encode("ascii"))
    return b"\r\n".join([*headers, *extra, b"", body])


def bind_unused_port(sock):
    if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
    # Sample the high-port range so this works even when the OS's port-0 range differs.
    for port in random.SystemRandom().sample(range(49152, 65536), 32):
        try:
            sock.bind((HOST, port))
        except OSError:
            continue
        return port
    raise AssertionError("could not own a fresh loopback test port")


def probe_process(port, *, with_proxy_env=False):
    env = {name: os.environ[name] for name in ("SYSTEMROOT", "WINDIR") if name in os.environ}
    env.update(no_proxy="*", NO_PROXY="*")
    if with_proxy_env:
        # Even a regression to proxy use can only talk to our one-shot stub.
        proxy = f"http://{HOST}:{port}"
        env.update(http_proxy=proxy, https_proxy=proxy, all_proxy=proxy,
                   HTTP_PROXY=proxy, HTTPS_PROXY=proxy, ALL_PROXY=proxy, no_proxy="", NO_PROXY="")
    return subprocess.run(
        [sys.executable, "-I", "-B", str(SCRIPT), "--fixture-port", str(port)],
        env=env, capture_output=True, text=True, timeout=7, check=False,
    )


class ReadonlySmokeTests(unittest.TestCase):
    def exchange(self, reply, *, with_proxy_env=False):
        # Single accept, bounded timeouts, and no reuse of existing listeners.
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            port = bind_unused_port(listener)
            listener.listen(1)
            listener.settimeout(5)
            finished = threading.Event()

            def serve_one():
                conn, address = listener.accept()
                with conn:
                    conn.settimeout(5)
                    with conn.makefile("rb") as stream:
                        request_line = stream.readline(4096)
                        headers = http.client.parse_headers(stream)
                    if callable(reply):
                        reply(conn, finished)
                    else:
                        conn.sendall(reply)
                    return address, request_line, headers

            with ThreadPoolExecutor(max_workers=1) as worker:
                task = worker.submit(serve_one)
                try:
                    run = probe_process(port, with_proxy_env=with_proxy_env)
                finally:
                    finished.set()
                address, request_line, headers = task.result(timeout=6)
            self.assertEqual(address[0], HOST)
            self.assertEqual(request_line, b"GET / HTTP/1.1\r\n")
            self.assertEqual(headers["Host"], f"{HOST}:{port}")
            for forbidden in ("Authorization", "Proxy-Authorization", "Cookie", "Content-Length", "Transfer-Encoding"):
                self.assertNotIn(forbidden, headers)
            listener.setblocking(False)
            with self.assertRaises(BlockingIOError):
                with listener.accept()[0]:
                    pass  # Any second connection (redirect/retry) is a failure.
            return run

    def assert_failed(self, run, reason):
        self.assertEqual((run.returncode, run.stdout), (1, ""))
        self.assertEqual(run.stderr, f"FAIL GET /: {reason}\n")

    def test_refuses_missing_url_extra_or_invalid_port_before_network(self):
        invalid = [[], ["--help"], ["--fixture-port"], ["--base-url", "http://fixture.invalid/"],
                   ["--fixture-port", "49152", "--allow-write"]]
        invalid += [["--fixture-port", port] for port in (
            "0", "8080", "49151", "65536", "-49152", "+49152", "049152", "４９１５２", "49152\n",
            "127.0.0.1", "http://fixture.invalid/", "49152/path", "49152?x=1",
        )]
        for argv in invalid:
            with self.subTest(argv=argv), patch.object(PROBE, "check") as check, \
                 contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
                self.assertEqual(PROBE.main(argv), 2)
                check.assert_not_called()
                self.assertEqual(out.getvalue(), "")
                self.assertTrue(err.getvalue().startswith("REFUSED: Usage: "))
                self.assertNotIn("fixture.invalid", err.getvalue())

    def test_accepts_real_backend_response_shape_and_ignores_proxy_env(self):
        run = self.exchange(wire(), with_proxy_env=True)
        self.assertEqual((run.returncode, run.stderr), (0, ""))
        self.assertEqual(run.stdout, "PASS GET /: HTTP 200 and ERP backend welcome\n")

    def test_rejects_every_non_200_status_even_with_valid_body(self):
        for status in (b"204 No Content", b"302 Found", b"401 Unauthorized", b"404 Not Found", b"500 Internal Server Error"):
            with self.subTest(status=status):
                self.assert_failed(self.exchange(wire(status=status, extra=(b"Location: /must-not-follow",))),
                                   f"HTTP {status[:3].decode('ascii')}; expected 200")

    def test_requires_one_utf8_plain_text_content_type(self):
        for mime, extra in ((None, ()), (b"Content-Type: text/html", ()),
                            (b"Content-Type: text/plain;charset=iso-8859-1", ()), (MIME, (MIME,))):
            with self.subTest(mime=mime, extra=extra):
                self.assert_failed(self.exchange(wire(mime=mime, extra=extra)),
                                   "expected text/plain with UTF-8 charset")

    def test_rejects_empty_wrong_shape_and_invalid_utf8_without_logging_body(self):
        for body in (b"", b"<html>200 UP</html>", b'{"code":200}', GOOD + b" debug suffix",
                     "欢迎使用   后台管理框架，请通过前端地址访问。".encode("utf-8")):
            with self.subTest(body=body):
                self.assert_failed(self.exchange(wire(body)), "unexpected backend welcome shape")
        self.assert_failed(self.exchange(wire(b"\xff")), "invalid UTF-8 response body")

    def test_bounded_framing_and_no_content_decoding(self):
        large = b"x" * 1025
        self.assert_failed(self.exchange(wire(large)), "response body exceeds limit")
        self.assert_failed(self.exchange(wire(large, length=False)), "response body exceeds limit")
        short = wire(length=False, extra=(f"Content-Length: {len(GOOD) + 1}".encode("ascii"),))
        self.assert_failed(self.exchange(short), "truncated response body")
        compressed = wire(extra=(b"Content-Encoding: gzip",))
        self.assert_failed(self.exchange(compressed), "unexpected Content-Encoding")
        mixed = wire(extra=(b"Transfer-Encoding: chunked",))
        self.assert_failed(self.exchange(mixed), "invalid response framing")
        chunk = f"{len(GOOD):x}\r\n".encode("ascii") + GOOD + b"\r\n"
        for tail, expected in ((b"0\r\n\r\n", 0), (b"", 1)):
            with self.subTest(tail=tail):
                response = wire(chunk + tail, length=False, extra=(b"Transfer-Encoding: chunked",))
                self.assertEqual(self.exchange(response).returncode, expected)

    def test_connection_failure_on_owned_non_listening_port(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as held:
            run = probe_process(bind_unused_port(held))
        self.assertEqual((run.returncode, run.stdout), (1, ""))
        # Windows may wait out the connect timeout instead of immediately refusing.
        self.assertIn(run.stderr, ("FAIL GET /: connection or HTTP response error\n",
                                   "FAIL GET /: socket I/O timeout\n"))

    def test_rejects_non_http_response(self):
        self.assert_failed(self.exchange(b"not HTTP\r\n\r\n"), "connection or HTTP response error")

    def test_socket_io_timeout(self):
        def stall(_conn, done):
            done.wait(5)
        self.assert_failed(self.exchange(stall), "socket I/O timeout")

    def test_total_deadline_even_with_trickling_peer(self):
        def trickle(conn, done):
            conn.sendall(b"HTTP/1.1 200 OK")
            for _ in range(25):
                if done.wait(0.2):
                    return
                try:
                    conn.sendall(b" ")
                except OSError:
                    return
        self.assert_failed(self.exchange(trickle), "probe deadline exceeded")

    def test_all_four_retired_stubs_refuse_default_and_opt_in_attempts(self):
        bash = shutil.which("bash")
        self.assertIsNotNone(bash, "bash is required to verify the retired entry points")
        clean_env = {name: os.environ[name] for name in ("PATH", "SYSTEMROOT", "WINDIR") if name in os.environ}
        for directory in ("deploy/debian-native", "upload/scripts"):
            for name in ("08-smoke-test.sh", "09-smoke-test-v2.sh"):
                relative = f"{directory}/{name}"
                with self.subTest(script=relative):
                    # Never run this entry point if any of the old implementation remains.
                    self.assertTrue((WORK / relative).read_bytes() == STUB, "not the inert retirement stub")
                    for args, env in (([], clean_env), (["--allow-write", "--force"],
                                        dict(clean_env, ERP_SMOKE_ALLOW_WRITE="1", BASE="http://fixture.invalid/"))):
                        run = subprocess.run([bash, "--noprofile", "--norc", relative, *args], cwd=WORK,
                                             env=env, capture_output=True, text=True, timeout=5, check=False)
                        self.assertEqual((run.returncode, run.stdout), (64, ""))
                        self.assertEqual(run.stderr, "REFUSED: legacy write smoke retired. See deploy/debian-native/SMOKE-GATES.md.\n")


if __name__ == "__main__":
    unittest.main()
