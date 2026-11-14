#!/usr/bin/env python3
"""CPU OS boundary: actual Linux compute admission and actual MMU PTEs.

This does not execute a shader or qualify a DUT. All temporary outputs are
created inside this GPU checkout and removed at terminal completion.
"""
import argparse
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[1]


def between(source, first, last):
    start = source.index(first)
    return source[start:source.index(last, start)]


def write_bodies(out, compute_stage=False):
    source = (ROOT / 'driver/opengpu_compute.c').read_text()
    # Preserve complete production bind/unbind bodies and every reachable map
    # helper. Omit the unrelated queued-DMA job helper between these ranges.
    body = between(source, 'static bool opengpu_is_power_of_two(',
                   'static int opengpu_job_map_dma_range(')
    body += between(source, 'int opengpu_compute_resource_bind_ioctl(',
                    'static struct opengpu_resource_binding *\nopengpu_binding_lookup(')
    (out / 'compute_host_bind_under_test.inc').write_text(body)
    context = between(source, "static int opengpu_wait_fence(struct opengpu_device *gpu,\n                              struct dma_fence **fence, bool interruptible)\n{", "/* Jobs hold the context")
    (out / 'compute_host_context_under_test.inc').write_text(context)
    lifetime = between(source, 'static void opengpu_context_release(', 'int opengpu_compute_drm_open(')
    lifetime += between(source, 'int opengpu_compute_context_create_ioctl(', 'static bool opengpu_is_power_of_two(')
    (out / 'compute_host_context_lifetime_under_test.inc').write_text(lifetime)

    memory = (ROOT / 'driver/opengpu_memory.c').read_text()
    (out / 'compute_host_memory_under_test.inc').write_text('\n'.join(
        line for line in memory.splitlines() if not line.startswith('#include ')))
    units = {
        'query': between(source, 'int opengpu_compute_get_param_ioctl(', 'int opengpu_compute_get_fault_ioctl('),
        'run': between(source, 'int opengpu_job_track_mapping(', '    if (job->type == OPENGPU_SCHED_BLIT)') + '    return ERR_PTR(-EINVAL);\n}\n',
        'events': between(source, 'static int opengpu_command_events_init(\n', '/* Virtual mappings used by queued DMA jobs'),
        'lookup': between(source, 'static struct opengpu_resource_binding *\nopengpu_binding_lookup(', 'static int opengpu_resolve_bindings('),
        'wait_reservation': between(source, 'static int opengpu_wait_reservation(', 'static bool opengpu_strided_range_end('),
        'ioctl': between(source, 'struct opengpu_compute_texture_prepare {', 'static int opengpu_self_test('),
    }
    scheduler = (ROOT / 'driver/opengpu_scheduler.c').read_text()
    units['scheduler'] = between(scheduler, 'static void opengpu_sched_free_job(', 'static const struct drm_sched_backend_ops')
    admission = (ROOT / 'driver/opengpu_admission.c').read_text()
    units['admission'] = '\n'.join(line for line in admission.splitlines() if not line.startswith('#include '))
    gem = (ROOT / 'driver/opengpu_drm_device.c').read_text()
    units['gem'] = between(gem, 'int opengpu_gem_backings_proven_disjoint(', 'static struct dma_buf *opengpu_gem_prime_export(')
    for name, body in units.items():
        (out / ('compute_host_' + name + '_under_test.inc')).write_text(body)
    hw = (ROOT / 'driver/opengpu_hw.c').read_text()
    functions = {
        'fault_record': 'static void opengpu_hw_record_fault_locked(',
        'job_id': 'static u32 opengpu_next_job_id(',
        'reset_pending': 'static bool opengpu_hw_unified_reset_pending(',
        'result_error': 'static int opengpu_hw_unified_result_error(',
        'drain': 'static bool opengpu_hw_unified_drain(',
        'irq': 'static irqreturn_t opengpu_irq_handler(',
        'enable_irqs': 'static void opengpu_hw_enable_irqs(',
        'quiesce': 'static int opengpu_hw_unified_quiesce_timeout(',
        'kick': 'static void opengpu_hw_unified_kick(',
        'set_satp': 'int opengpu_hw_set_satp(',
        'activate_locked': 'static int opengpu_hw_activate_vm_locked(',
        'busy': 'static bool opengpu_hw_execution_busy(',
        'wait_idle': 'int opengpu_hw_wait_idle_locked(',
        'activate': 'int opengpu_hw_activate_vm(',
        'enable': 'int opengpu_hw_enable_mmu(',
        'disable': 'int opengpu_hw_disable_mmu_locked(',
        'flush': 'int opengpu_hw_flush_tlbs(',
        'flush_asid': 'int opengpu_hw_flush_tlb_asid(',
        'submit': 'static int opengpu_hw_unified_submit_locked(',
        'invalidate': 'int opengpu_hw_invalidate_buffer_locked(',
        'invalidate_validate': 'static int opengpu_hw_invalidate_validate(',
        'invalidate_sync': 'int opengpu_hw_invalidate_sync(',
        'snapshot_submission': 'int opengpu_hw_snapshot_submission_lock(',
        'compute': 'int opengpu_hw_compute_async(',
    }
    for name, start in functions.items():
        first = hw.index(start)
        while hw.find(';', first, hw.index('{', first)) != -1:
            first = hw.index(start, first + len(start))
        last = hw.index('\n}\n', first) + 2
        (out / ('compute_host_hw_' + name + '_under_test.inc')).write_text(hw[first:last])
    (out / 'compute_host_hw_fence_under_test.inc').write_text(
        between(hw, 'struct opengpu_fence {', 'static u32 opengpu_reg_read('))
    device = (ROOT / 'driver/opengpu_device.h').read_text()
    first = device.index('struct opengpu_fault_snapshot {')
    (out / 'compute_host_fault_under_test.inc').write_text(device[first:device.index('\n};', first)+3])
    # Explicit CPU-shaped discovery data for rejection/admission tests only.
    # The active producer must supply real probe words, never this fixture.
    from prepare_native_owner_flashsim import EXPECTED_WORDS
    from owner_resource_contract import COMPUTE_WORDS
    rows = [(item["address"], item["value"]) for item in (COMPUTE_WORDS if compute_stage else EXPECTED_WORDS)]
    extras = [(0, 0x47550002), (0x60, '(GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE|GPU_CAP_COMPUTE_SCALAR_FPU|GPU_CAP_SHADER_CONFIG_INFO|GPU_CAP_NATIVE_PIXEL_FORMATS|(128u << GPU_CAP_FRAGMENT_BATCH_SHIFT))'),
              (0x68, 0x160), (0x320, 1048576), (0x158, 0x02020810), (0x494, 0x47544d01), (0x43c, 0x47534d01),
              (0x3fc, 0x47494d01), (0x4bc, 0x47414d01), (0x460, 0x47554d01)]
    (out / 'compute_host_test_profile.inc').write_text(
        'static struct compute_host_profile_word test_profile[] = {\n' +
        ''.join('{%su,%s},\n' % (hex(a), v if isinstance(v, str) else hex(v) + 'u')
                for a, v in rows + extras) + '};\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--canonical-root', type=Path,
                        help='fixed checkout providing the actual compute capture C++ parser')
    parser.add_argument('--compute-stage', action='store_true',
                        help='exercise the distinct shared-enabled CPU profile and matching parser')
    args = parser.parse_args()
    parent = ROOT / '.artifacts'
    parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='compute-host-', dir=parent) as temporary:
        out = Path(temporary)
        write_bodies(out, args.compute_stage)
        flags = ['-DOPENGPU_COMPUTE_HOST_TEST_MAIN=1', '-D_POSIX_C_SOURCE=200809L', '-std=c11', '-O1', '-Wall', '-Wextra', '-Werror', '-Wno-unused-function']
        if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        include = [out, ROOT / 'driver', ROOT / 'driver/tests/mmu_stubs',
                   ROOT / 'driver/tests/compute_submit_stubs']
        env = dict(os.environ, TMPDIR=temporary)
        env["UBSAN_OPTIONS"] = "halt_on_error=1"
        env["ASAN_OPTIONS"] = "halt_on_error=1"
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                       ['-I' + str(p) for p in include] +
                       [str(ROOT / 'driver/tests/opengpu_compute_host_fixture.c'),
                        '-o', str(out / 'test')], env=env, check=True)
        # The real producer links this object without either CPU peer macro.
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) +
                       [flag for flag in flags if not flag.startswith('-DOPENGPU_COMPUTE_HOST_TEST_MAIN=')] +
                       ['-I' + str(p) for p in include] + ['-c', str(ROOT / 'driver/tests/opengpu_compute_host_fixture.c'),
                        '-o', str(out / 'producer-boundary.o')], env=env, check=True)
        subprocess.run([str(out / 'test'), str(out)], env=env, check=True)
        if args.canonical_root:
            canonical = args.canonical_root.resolve(strict=True)
            source = canonical / 'scripts/test_compute_owner.cpp'
            cppflags = ['-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror']
            if args.compute_stage:
                cppflags += ['-DOPENGPU_OWNER_COMPUTE_STAGE=1']
            if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
                cppflags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
            binary = out / 'canonical-parser'
            subprocess.run(shlex.split(os.environ.get('CXX', 'c++')) + cppflags +
                           ['-I' + str(canonical / 'scripts/test_stubs/native_host'),
                            '-I' + str(canonical / 'scripts'), str(source), '-o', str(binary)], env=env, check=True)
            subprocess.run([str(binary), str(out / 'union.memory'), str(out / 'record-0.capture')], env=env, check=True)
        for phase in range(1, 4):
            for kind in range(1, 3):
                negative = out / ('negative-%s-%s' % (phase, kind))
                negative.mkdir()
                subprocess.run([str(out / 'test'), str(negative), str(phase), str(kind)], env=env, check=True)


if __name__ == '__main__':
    main()
