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
python3 scripts/validate_offload_kernel.py
python3 scripts/test_test_selection.py
python3 scripts/test_check_pr_test_results.py
python3 scripts/test_check_ci_job_results.py
python3 scripts/test_run_ci_partition.py
python3 scripts/test_changed_test_suites.py
python3 scripts/test_ci_case_partitions.py
python3 scripts/test_ci_changed_host_cases.py
python3 scripts/test_ci_telemetry_coverage.py
python3 scripts/test_prefetch_scala_bridge.py
python3 scripts/test_display_config.py
python3 scripts/test_qmp_screendump_protocol.py
python3 scripts/test_verify_gtk_scanout.py
python3 scripts/test_qemu_cpu_config.py
python3 scripts/test_analyze_shared_graphics_trace.py
python3 scripts/test_yosys_hierarchy.py

echo "script tests: PASS"
