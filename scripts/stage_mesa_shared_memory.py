#!/usr/bin/env python3
"""Privately stage the pinned Mesa22.3.6 shared MEMORY declaration binding.

Includes the existing compute buffer TXF mask correction. No shared Mesa
source/build is changed, and this script does not compile or execute a GPU.
"""
import argparse
from pathlib import Path
from stage_mesa_buffer_txf_mask import SOURCE, stage as stage_mask, sha

AFTER = "8f543c76eecc84c39300d015a3ce9b44025e1c406e982db3595d9cc264779646"
OLD = "      memory = ureg_src_register(TGSI_FILE_MEMORY, 0);"
NEW = "      memory = ureg_src_register(TGSI_FILE_MEMORY, TGSI_MEMORY_TYPE_SHARED);"

def stage(mesa, out):
    target = stage_mask(mesa, out)
    text = target.read_text()
    if text.count(OLD) != 1:
        raise ValueError("unexpected shared NTT memory binding")
    target.write_text(text.replace(OLD, NEW))
    if sha(target) != AFTER:
        raise ValueError("private Mesa shared binding postimage differs")
    return target

if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--mesa", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    print(stage(args.mesa, args.out))
