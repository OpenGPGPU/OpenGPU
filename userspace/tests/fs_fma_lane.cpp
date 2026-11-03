#include "VFp32FmaLane.h"
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>

static float as_float(uint32_t u) { float f; std::memcpy(&f, &u, 4); return f; }
static uint32_t as_bits(float f) { uint32_t u; std::memcpy(&u, &f, 4); return u; }
static void tick(VFp32FmaLane &d) {
  d.clock = 0; d.eval(); d.clock = 1; d.eval(); d.clock = 0; d.eval();
}

int main() {
  const uint32_t values[][3] = {
    {0x3f800001u, 0x3f7ffffeu, 0xbf800000u},
    {0x3fc00000u, 0xc0000000u, 0x40400000u},
    {0x80000000u, 0x40400000u, 0x00000000u},
    {0x41200000u, 0x3e800000u, 0xc0a00000u},
  };
  VFp32FmaLane d;
  d.reset = 1; d.io_in_valid = 0; d.io_out_ready = 1; tick(d);
  d.reset = 0; d.io_in_bits_roundingMode = 0; d.io_in_bits_operation = 0;
  d.io_in_bits_operationModifier = 0;
  for (unsigned sample = 0; sample < 4; ++sample) {
    for (unsigned neg = 0; neg < 8; ++neg) {
      uint32_t a = values[sample][0] ^ ((neg & 1) ? 0x80000000u : 0u);
      uint32_t b = values[sample][1] ^ ((neg & 2) ? 0x80000000u : 0u);
      uint32_t c = values[sample][2] ^ ((neg & 4) ? 0x80000000u : 0u);
      uint32_t want = as_bits(std::fma(as_float(a), as_float(b), as_float(c)));
      d.io_in_bits_operandA = a; d.io_in_bits_operandB = b;
      d.io_in_bits_operandC = c; d.io_in_bits_tag = sample * 8 + neg;
      d.io_in_valid = 1; d.eval();
      if (!d.io_in_ready) return 2;
      tick(d); d.io_in_valid = 0; d.eval();
      unsigned cycles = 0;
      while (!d.io_out_valid && cycles++ < 32) tick(d);
      if (!d.io_out_valid || d.io_out_bits_result != want ||
          d.io_out_bits_tag != sample * 8 + neg) {
        std::fprintf(stderr, "sample=%u neg=%u valid=%u got=%08x want=%08x\n",
                     sample, neg, d.io_out_valid, d.io_out_bits_result, want);
        return 1;
      }
      tick(d);
    }
  }
  std::puts("Fp32FmaLane: 32 signed finite cases PASS");
}
