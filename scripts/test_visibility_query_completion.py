#!/usr/bin/env python3
"""Test actual Linux GQV1 completion decoding and legacy payload handling."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
source = (root / 'driver/opengpu_hw.c').read_text()
start = source.index('static int opengpu_hw_unified_result_error(u32 opcode, u32 status)\n{')
end = source.index('static bool opengpu_hw_unified_drain(', start)
with tempfile.TemporaryDirectory(prefix='opengpu-query-completion-') as tmp:
    path = Path(tmp)
    (path / 'query_result_error_under_test.inc').write_text(source[start:end])
    subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + [
        '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', '-I', str(root / 'driver'), '-I', tmp,
        str(root / 'driver/tests/opengpu_visibility_query_completion_fixture.c'), '-o', str(path / 'test')], check=True)
    subprocess.run([str(path / 'test')], check=True)
