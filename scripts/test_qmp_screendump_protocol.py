#!/usr/bin/env python3
"""Unit-test QMP event/reply framing without opening a host socket."""
import io
import json
import unittest

import qmp_screendump


class FakeSocket:
    def __init__(self):
        self.sent = []

    def sendall(self, payload):
        self.sent.append(json.loads(payload.decode().strip()))


class QmpProtocolTest(unittest.TestCase):
    def test_events_are_skipped_until_command_reply(self):
        stream = io.BytesIO(
            b'{"event":"RESUME"}\r\n'
            b'{"event":"STOP"}\r\n'
            b'{"return":{}}\r\n')
        sock = FakeSocket()
        self.assertEqual(qmp_screendump.command(sock, stream, "screendump",
                                                {"filename": "/tmp/x.ppm"}),
                         {"return": {}})
        self.assertEqual(sock.sent, [{"execute": "screendump",
                                      "arguments": {"filename": "/tmp/x.ppm"}}])

    def test_qmp_error_is_reported(self):
        stream = io.BytesIO(b'{"error":{"class":"GenericError",'
                            b'"desc":"no display"}}\r\n')
        with self.assertRaisesRegex(RuntimeError, "no display"):
            qmp_screendump.command(FakeSocket(), stream, "screendump")


if __name__ == "__main__":
    unittest.main()
