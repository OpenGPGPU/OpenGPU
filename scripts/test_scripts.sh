#!/usr/bin/env bash
# Run the host-side script tests: CPU/accel selection, display geometry, the
# QMP screendump protocol, and the driver/test-selection checks. No RTL build,
# no guest boot. This is the single entry point used by the CI driver job.
#
# The Verilator FPU harness (scripts/test_gpu_core_fpu.sh) is intentionally not
# included: it builds emitted RTL with sbt and runs a C++ simulation.
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$GPU_DIR"

python3 scripts/test_driver.py
python3 scripts/test_test_selection.py
python3 scripts/test_display_config.py
python3 scripts/test_qmp_screendump_protocol.py
python3 scripts/test_qemu_cpu_config.py

echo "script tests: PASS"
