#!/usr/bin/env python3
"""Check or regenerate the fixed nearest-even FP32-to-sRGB8 transition ROM."""
import argparse
from decimal import Decimal, localcontext
from pathlib import Path
import struct


def generate():
    values = []
    with localcontext() as context:
        context.prec = 100
        for index in range(255):
            encoded = (Decimal(index) + Decimal('.5')) / 255
            linear = (encoded / Decimal('12.92') if encoded <= Decimal('.04045') else
                      ((encoded + Decimal('.055')) / Decimal('1.055')) ** Decimal('2.4'))
            rounded = struct.unpack('<f', struct.pack('<f', float(linear)))[0]
            bits = struct.unpack('<I', struct.pack('<f', rounded))[0]
            if Decimal(rounded) < linear:
                bits += 1
            values.append(bits)
    rows = ',\n'.join('    ' + ','.join('"h%08x".U(32.W)' % value for value in values[start:start + 8])
                      for start in range(0, 255, 8))
    return '''package opengpu.graphics

import chisel3._
import chisel3.util._

/** Smallest positive FP32 input reaching each nearest8-bit sRGB code.
  * Generated with100-digit decimal inverse transfer; fixed constants make
  * elaboration independent of host floating-point/libm behavior.
  */
private[graphics] object Srgb8Thresholds {
  def values: Vec[UInt]=VecInit(Seq(
''' + rows + '))\n}\n'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='replace the checked-in Scala ROM')
    args = parser.parse_args()
    target = Path(__file__).resolve().parents[1] / 'src/main/scala/opengpu/graphics/Srgb8Thresholds.scala'
    generated = generate()
    if args.write:
        target.write_text(generated)
    elif target.read_text() != generated:
        raise SystemExit('sRGB8 ROM differs; regenerate with --write')
    print('SRGB8_ROM transitions=255 precision=100decimal reproducible=true')
