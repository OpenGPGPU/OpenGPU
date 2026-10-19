#!/usr/bin/env python3
"""Check or generate nearest-even FP32 values for every sRGB8 code."""
import argparse
from decimal import Decimal, localcontext
from pathlib import Path
import struct


def generate():
    values = []
    with localcontext() as context:
        context.prec = 100
        for index in range(256):
            encoded = Decimal(index) / 255
            linear = encoded / Decimal('12.92') if encoded <= Decimal('.04045') else ((encoded + Decimal('.055')) / Decimal('1.055')) ** Decimal('2.4')
            estimate = struct.unpack('<I', struct.pack('<f', float(linear)))[0]
            candidates = range(max(0, estimate - 1), min(0x3f800000, estimate + 1) + 1)
            def distance(bits):
                exact = Decimal(struct.unpack('<f', struct.pack('<I', bits))[0])
                return abs(exact - linear), bits & 1
            values.append(min(candidates, key=distance))
    rows = ',\n'.join('    ' + ','.join('"h%08x".U(32.W)' % v for v in values[start:start + 8]) for start in range(0, 256, 8))
    return '''package opengpu.graphics

import chisel3._
import chisel3.util._

/** FP32 RNE inverse sRGB for each8-bit code, generated at100decimal digits. */
private[graphics] object Srgb8Decode {
  def values: Vec[UInt]=VecInit(Seq(
''' + rows + '))\n}\n'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    target = Path(__file__).resolve().parents[1] / 'src/main/scala/opengpu/graphics/Srgb8Decode.scala'
    generated = generate()
    if args.write:
        target.write_text(generated)
    elif target.read_text() != generated:
        raise SystemExit('sRGB decode ROM differs; regenerate with --write')
    print('SRGB8_DECODE codes=256 precision=100decimal reproducible=true')
