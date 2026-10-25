#!/usr/bin/env python3
"""Check the source expressions and prove the bounded UNORM replacement.

Requires z3-solver. This proves the numeric expression for all 32-bit raw inputs
and widths 0..10; it does not prove arbitrary Chisel RTL or whole-host behavior.
No output artifacts are generated.
"""
from pathlib import Path
import re

try:
    import z3
except ImportError:
    raise SystemExit("Install z3-solver in a local virtual environment before running this proof.")


def check_source():
    root = Path(__file__).resolve().parents[1]
    path = root / "src/main/scala/opengpu/graphics/PreparedTypedColorEncoder.scala"
    source = re.sub(r"//[^\n]*|/\*.*?\*/", "", path.read_text(), flags=re.S)
    source = re.sub(r"\s+", "", source)
    expected = (
        "privatevalunormBits=10",
        ".forall(_.widths.forall(_<=unormBits))",
        "numericProduct=Reg(UInt(34.W));privatevalnumericShift=Reg(UInt(6.W))",
        "numericProduct:=prepared.scaled-prepared.significand",
        "valtruncated=(numericProduct>>numericShift)(unormBits-1,0)",
        "valremainder=numericProduct&((1.U(36.W)<<numericShift)-1.U)(33,0)",
        "valhalf=1.U(36.W)<<(numericShift-1.U)",
        "valrounded=truncated+&((remainder>half)||(remainder===half&&truncated(0)))",
        "valunorm=Mux(numericZero,0.U,Mux(numericSaturate,numericMaximum,rounded))",
        "packValue:=Mux(numericUnorm,unorm,numericOther)",
        "valmaximum=((1.U(11.W)<<bits)-1.U)(9,0)",
        "valdistance=150.U(9.W)-raw(30,23)",
        "valsignificand=Cat(raw(30,23).orR,raw(22,0)).pad(34)",
        "valscale=Mux(bits>10.U,10.U,bits)(3,0)",
        "prepared.scaled:=(significand<<scale)(33,0);prepared.significand:=significand",
        "prepared.shift:=Mux(raw(30,23)===0.U||distance>35.U,35.U,distance)(5,0)",
        'prepared.zero:=raw(31)||Fp32ViewportState.nan(raw);prepared.saturate:=raw>="h3f800000".U',
    )
    for expression in expected:
        if expression not in source:
            raise SystemExit("Proof/source correspondence needs review: " + expression)
    viewport = root / "src/main/scala/opengpu/graphics/Fp32ViewportStage.scala"
    if "defnan(bits:UInt):Bool=bits(30,23)===255.U&&bits(22,0).orR" not in re.sub(r"\s+", "", viewport.read_text()):
        raise SystemExit("Proof/source correspondence needs review: NaN classification")


def prove():
    raw = z3.BitVec("raw", 32)
    exponent, fraction = z3.Extract(30, 23, raw), z3.Extract(22, 0, raw)
    significand = z3.ZeroExt(10, z3.Concat(
        z3.If(exponent != 0, z3.BitVecVal(1, 1), z3.BitVecVal(0, 1)), fraction))
    distance = z3.BitVecVal(150, 9) - z3.ZeroExt(1, exponent)
    shift = z3.Extract(5, 0, z3.If(z3.Or(exponent == 0, z3.UGT(distance, 35)),
                                  z3.BitVecVal(35, 9), distance))
    zero = z3.Or(z3.Extract(31, 31, raw) == 1, z3.And(exponent == 255, fraction != 0))
    saturate = z3.UGE(raw, z3.BitVecVal(0x3f800000, 32))
    for bits in range(11):
        product = (significand << bits) - significand
        truncated = z3.LShR(product, z3.ZeroExt(28, shift))
        mask = z3.Extract(33, 0, (z3.BitVecVal(1, 99) << z3.ZeroExt(93, shift)) - 1)
        remainder = z3.ZeroExt(65, product & mask)
        half = z3.BitVecVal(1, 99) << z3.ZeroExt(93, shift - z3.BitVecVal(1, 6))
        increment = z3.Or(z3.UGT(remainder, half),
                          z3.And(remainder == half, z3.Extract(0, 0, truncated) == 1))
        wide = z3.ZeroExt(1, truncated) + z3.If(increment, z3.BitVecVal(1, 35), z3.BitVecVal(0, 35))
        bounded = z3.ZeroExt(1, z3.Extract(9, 0, truncated)) + z3.If(
            increment, z3.BitVecVal(1, 11), z3.BitVecVal(0, 11))
        maximum = z3.BitVecVal((1 << bits) - 1, 32)
        old = z3.If(zero, z3.BitVecVal(0, 32),
                    z3.If(saturate, maximum, z3.Extract(31, 0, wide)))
        new = z3.If(zero, z3.BitVecVal(0, 32),
                    z3.If(saturate, maximum, z3.ZeroExt(21, bounded)))
        solver = z3.Solver()
        solver.set(timeout=60000)
        solver.add(old != new)
        result = solver.check()
        if result != z3.unsat:
            detail = solver.model() if result == z3.sat else solver.reason_unknown()
            raise SystemExit(f"Equivalence unproven for width {bits}: {result}; {detail}")
        print(f"UNORM width {bits}: all 32-bit raw inputs equivalent (UNSAT)")
    print("PASS: source correspondence and all 11 width proofs; Z3 " + z3.get_full_version())


if __name__ == "__main__":
    check_source()
    prove()
