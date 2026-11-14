#!/usr/bin/env bash
# Run the host-side script tests: CPU/accel selection, display geometry, the
# QMP screendump protocol, and the driver/test-selection checks. No RTL build,
# no guest boot. This is the single entry point used by the CI driver job.
#
# The Verilator FPU harness (scripts/test_gpu_compute_unit_core_fpu.sh) is intentionally not
# included: it builds emitted RTL with sbt and runs a C++ simulation.
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$GPU_DIR"

python3 scripts/test_driver.py
python3 scripts/test_compute_feature_query.py
python3 scripts/test_query_unsupported.py
python3 scripts/test_visibility_query_completion.py
python3 scripts/test_render_attachment_admission.py
python3 scripts/test_render_compare_validation.py
python3 scripts/test_gallium_transfers.py
python3 scripts/test_pipe_transfer_ranges.py
python3 scripts/test_gallium_graphics_constants.py
python3 scripts/test_gallium_index_buffers.py
python3 scripts/test_gallium_resource_shapes.py
python3 scripts/test_linear_image_allocation.py
python3 scripts/test_native_frame.py
python3 scripts/test_tessellation_canonical.py
python3 scripts/test_tessellation_transport_submit.py
python3 scripts/test_arti_small_smoke_args.py
python3 scripts/test_unified_completion_race.py
python3 scripts/test_invalidate_submit_race.py
python3 scripts/test_fence_wait.py
python3 scripts/test_vm_idle.py
python3 scripts/test_snapshot_submission.py
python3 scripts/test_context_fence_wait.py
python3 scripts/test_context_lifetime.py
python3 scripts/test_job_track_mapping.py
python3 scripts/test_blit_aliases.py
python3 scripts/validate_offload_kernel.py
python3 scripts/test_test_selection.py
python3 scripts/test_check_pr_test_results.py
python3 scripts/test_check_ci_job_results.py
python3 scripts/test_check_cached_simulation_tools.py
python3 scripts/test_run_ci_partition.py
python3 scripts/test_changed_test_suites.py
python3 scripts/check_emit_registry.py
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
python3 scripts/test_extract_emitted_rtl.py
python3 scripts/test_report_ppa_sram_inventory.py
python3 scripts/test_verify_yosys_opt_equivalence.py
PYTHONDONTWRITEBYTECODE=1 python3 - <<'PY'
from pathlib import Path
import tempfile
import pytest
temporary = Path('.artifacts')
temporary.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='ppa-host-tests-', dir=temporary) as directory:
    result = pytest.main(['-q', '-p', 'no:cacheprovider',
                          '--basetemp=' + str(Path(directory) / 'fixtures'),
                          'scripts/test_qualify_ppa.py', 'scripts/test_qualify_native_owner_ppa.py'])
raise SystemExit(result)
PY
python3 scripts/test_split_yosys_rtlil.py
python3 scripts/test_verify_yosys_checkpoint.py
python3 scripts/test_prepare_yosys_abc_checkpoint.py
python3 scripts/test_check_yosys_partition.py
python3 scripts/test_qualify_native_product_compiled.py
python3 scripts/test_qualify_native_product_flashsim.py
python3 scripts/test_prepare_native_owner_flashsim.py
python3 scripts/test_prepare_native_sample_owner.py
python3 scripts/test_native_sample_transport.py
python3 scripts/test_native_sample_submit_bridge.py
python3 scripts/test_prepare_owner_executor.py
python3 scripts/test_qualify_equivalent_flashsim_product.py
python3 scripts/test_qualify_parallel_flashsim_product.py
python3 scripts/test_qualify_native_api_compiled.py
python3 scripts/test_qualify_native_api_parallel.py
python3 scripts/test_native_alignment_discovery.py
python3 scripts/test_native_alignment_sequence.py
python3 scripts/test_native_product_observation.py
python3 scripts/test_qualify_native_owner_product.py
python3 scripts/test_native_layered_product.py
python3 scripts/test_qualify_native_layered_product.py
python3 scripts/test_native_sample_layered_product.py
python3 scripts/test_qualify_native_sample_layered_product.py
python3 scripts/test_native_sample_layered_driver_guards.py
python3 scripts/test_native_owner.py
python3 scripts/test_native_sample_owner.py
python3 scripts/test_native_sample_gny2_oracle.py
python3 scripts/test_native_sample_owner_oracle.py
python3 scripts/test_compute_owner.py
python3 scripts/test_native_binned_topology_fixture.py
python3 scripts/test_flashsim_native_adapter.py
python3 scripts/test_build_native_flashsim.py
python3 scripts/test_cache_native_flashsim_compiler.py
python3 scripts/test_build_arti_idle_adapter.py
python3 scripts/test_set_arti_guest_extent.py

echo "script tests: PASS"
