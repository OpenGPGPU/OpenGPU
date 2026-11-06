#!/usr/bin/env python3
"""CPU-only production staging checks on a private pinned Mesa source subset."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

from apply_mesa_uniform_index_patch import BEFORE, AFTER, SOURCE

ROOT = Path(__file__).resolve().parents[1]
BASE = "234055882d7a25f843369c1022a1f5ed149b90cd"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mesa", type=Path, required=True, help="read-only pinned Mesa Git source")
    parser.add_argument("--out", type=Path, required=True, help="new private output directory")
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    mesa = out / "mesa"
    paths = ["VERSION", str(SOURCE), "meson.build", "meson_options.txt", "src/gallium/meson.build",
             "src/mesa/main/extensions_table.h", "src/mapi/glapi/gen/ARB_sync.xml",
             "src/egl/drivers/dri2/egl_dri2.c", "src/gbm/backends/dri/gbm_dri.c",
             "src/gallium/auxiliary/target-helpers/drm_helper.h",
             "src/gallium/auxiliary/target-helpers/drm_helper_public.h",
             "src/gallium/auxiliary/pipe-loader/pipe_loader_drm.c",
             "src/gallium/targets/dri/target.c", "src/gallium/targets/dri/meson.build"]
    for relative in paths:
        data = subprocess.check_output(["git", "show", BASE + ":" + relative], cwd=args.mesa)
        target = mesa / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    source = mesa / SOURCE
    assert hashlib.sha256(source.read_bytes()).hexdigest() == BEFORE
    env = os.environ.copy()
    env["MESA"] = str(mesa)
    commands = []

    def run(argv, ok=True):
        commands.append(list(map(str, argv)))
        result = subprocess.run(argv, cwd=ROOT, env=env, capture_output=True, text=True)
        with (out / "test.log").open("a") as log:
            log.write(result.stdout + result.stderr)
        assert (result.returncode == 0) == ok, result.stderr

    stage = ["bash", str(ROOT / "scripts/stage_mesa_opengpu.sh")]
    check = ["python3", str(ROOT / "scripts/apply_mesa_uniform_index_patch.py"), "--mesa", str(mesa), "--check"]
    run(check, False)
    run(stage)
    first = {str(p.relative_to(mesa)): hashlib.sha256(p.read_bytes()).hexdigest()
             for p in mesa.rglob("*") if p.is_file()}
    assert first[str(SOURCE)] == AFTER
    run(check)
    run(stage)
    assert first == {str(p.relative_to(mesa)): hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in mesa.rglob("*") if p.is_file()}
    source.write_bytes(source.read_bytes() + b"\n/* local unknown change */\n")
    before = source.read_bytes()
    run(stage, False)
    assert source.read_bytes() == before
    # Restore only this private test's intentional corruption, then check the
    # version guard too. No shared source/build or GPU checkout is written.
    source.write_bytes(before[:-len(b"\n/* local unknown change */\n")])
    (mesa / "VERSION").write_text("22.3.7\n")
    run(check, False)
    (out / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
    (out / "result.json").write_text(json.dumps({"status": "PASS", "Mesa_base": BASE,
        "before": BEFORE, "after": AFTER, "cases": ["unstaged rejected", "production stage",
        "exact postimage", "idempotent full stage", "unknown source rejected unchanged", "wrong version rejected"]}, indent=2) + "\n")
    print("Mesa production typed-uniform staging checks PASS")


if __name__ == "__main__":
    main()
