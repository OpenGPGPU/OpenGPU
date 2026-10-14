#!/usr/bin/env python3
"""Ask a running QEMU instance to dump its displayed surface as a PPM."""
import json
import pathlib
import socket
import sys
import time


def recv(stream):
    line = stream.readline()
    if not line:
        raise RuntimeError("QMP closed before replying")
    return json.loads(line)


def command(sock, stream, name, arguments=None):
    msg = {"execute": name}
    if arguments:
        msg["arguments"] = arguments
    sock.sendall((json.dumps(msg) + "\r\n").encode())
    while True:
        reply = recv(stream)
        if "return" in reply or "error" in reply:
            if "error" in reply:
                raise RuntimeError(reply["error"])
            return reply


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: qmp_screendump.py QMP_SOCKET OUTPUT.ppm")
    socket_path = sys.argv[1]
    output = pathlib.Path(sys.argv[2])
    output.unlink(missing_ok=True)
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        deadline = time.monotonic() + 10
        while True:
            try:
                sock.connect(socket_path)
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.05)
        sock.settimeout(30)
        with sock.makefile("rb") as stream:
            recv(stream)  # greeting
            command(sock, stream, "qmp_capabilities")
            command(sock, stream, "screendump", {"filename": str(output.resolve())})
    if not output.is_file() or output.stat().st_size == 0:
        raise SystemExit(f"QMP did not create {output}")


if __name__ == "__main__":
    main()
