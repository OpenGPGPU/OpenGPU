#!/usr/bin/env bash
# Validate a cached simulator and the dependencies needed to build a model.
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 VERILATOR_PREFIX" >&2
  exit 2
fi
verilator="$1/bin/verilator"
[ -x "$verilator" ] || { echo "missing cached Verilator" >&2; exit 1; }
case "$("$verilator" --version)" in
  'Verilator 5.050'|'Verilator 5.050 '*) ;;
  *) echo "unexpected cached Verilator version" >&2; exit 1 ;;
esac
for tool in git perl python3 make g++ ccache curl; do
  command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 1; }
done

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/check.sv" <<'EOF'
module Check(input logic a, b, output logic y);
  assign y = a ^ b;
endmodule
EOF
cat > "$tmp/check.cpp" <<'EOF'
#include "VCheck.h"
int main() {
  VCheck dut;
  dut.a = 1; dut.b = 0; dut.eval(); if (dut.y != 1) return 1;
  dut.b = 1; dut.eval(); return dut.y != 0;
}
EOF
if ! "$verilator" --cc --exe --build -j 1 --CFLAGS '-std=c++17' \
     --top-module Check --Mdir "$tmp/obj" "$tmp/check.sv" "$tmp/check.cpp" \
     > "$tmp/build.log" 2>&1; then
  cat "$tmp/build.log" >&2
  exit 1
fi
"$tmp/obj/VCheck"
printf '#include <zlib.h>\nint main() { return zlibVersion() == 0; }\n' |
  g++ -std=c++17 -x c++ - -lz -o "$tmp/zlib-check"
"$tmp/zlib-check"
