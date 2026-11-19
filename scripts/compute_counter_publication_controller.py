"""One original compiler/producer and one complete DUT for atomic-counter publication."""
import struct
import subprocess
import time
from compute_owner_protocol import (strict_json, held_bytes, retired_bytes,
    checked_cleanup, cleanup_authorization, uint)
from compute_owner_oracle import SparseMemory, verify, union_retained_memory
from compute_image_source import captured_page_tables, verify_cleaned_memory
from compute_counter_publication import (PHASES, matrix_plan, retained_extents,
    check_round)
from compute_counter_publication_source import recipe


def run_publication_matrix(args, command, directory, executor, env, qualified, profile_name):
    from compute_owner_controller import (atomic, wait_file, stop, ready_inputs,
        unchanged_inputs, input_digest, require)
    deadline = time.monotonic() + args.timeout
    def remaining():
        left = deadline - time.monotonic()
        require(left > 0, 'bounded original counter publication matrix timed out')
        return left
    profile = strict_json(directory / 'profile.json')
    atomic(directory / 'counter-publication.plan', matrix_plan())
    inputs = [(directory, {name: input_digest(directory / name) for name in
        ('counter-publication.plan', 'profile.json', 'profile.bin')})]
    bridge = running = None
    local = None
    stage = None
    previous = previous_clean = previous_digest = None
    retained = None
    try:
        with (directory / 'bridge.log').open('w') as log, (directory / 'execution.log').open('w') as output:
            bridge = subprocess.Popen([x.replace('{ipc}', str(directory)) for x in command],
                env=env, stdout=log, stderr=subprocess.STDOUT)
            for stage in PHASES:
                index, role = stage['index'], stage['role']
                local = directory / ('round-' + str(index))
                for name in ('READY', 'union.memory', 'record-0.capture', 'oracle.json', 'code.words'):
                    wait_file(local / name, bridge, running, remaining())
                description, frozen = ready_inputs(local, stage['case'], owner_profile=profile_name, profile=profile)
                with SparseMemory(local / 'union.memory') as upload:
                    _, target, output_view = recipe(upload, description)
                    extent = retained_extents(target, target + 8192)
                    tables = captured_page_tables(upload, (local / 'record-0.capture').read_bytes())
                    if index:
                        require(input_digest(previous_clean) == previous_digest, 'previous cleaned RAM changed')
                        require(retained == extent, 'publication lost its retained counter data extent')
                        atomic(local / 'retained.extents', retained)
                        frozen['retained.extents'] = input_digest(local / 'retained.extents')
                        start, end = struct.unpack('<QQ', retained)
                        with SparseMemory(previous_clean) as old:
                            atomic(local / 'global-before.memory',
                                   union_retained_memory(old, upload, {(start, end)}))
                        frozen['global-before.memory'] = input_digest(local / 'global-before.memory')
                        baseline = local / 'global-before.memory'
                    else:
                        retained = extent
                        baseline = local / 'union.memory'
                        # Uncached counter fabric witness watches the line of counters[1].
                        line = (target + 4) & ~63
                        atomic(directory / 'publication.cache-target',
                               struct.pack('<8sQ', b'OGCCPT01', line))
                        inputs[0][1]['publication.cache-target'] = input_digest(
                            directory / 'publication.cache-target')
                inputs.append((local, frozen))
                for path, identities in inputs:
                    unchanged_inputs(path, identities)
                atomic(local / 'BEGIN_EXECUTION', struct.pack('<8sI', b'OGCMEX01', index))
                if not index:
                    running = subprocess.Popen(
                        [executor, '--active-counter-publication', directory / 'results', directory],
                        env=env, stdout=output, stderr=subprocess.STDOUT)
                destination = directory / 'results' / ('round-' + str(index))
                wait_file(local / 'record-0.held.json', bridge, running, remaining())
                held = strict_json(local / 'record-0.held.json')
                atomic(local / 'record-0.held.response', held_bytes(held))
                wait_file(local / 'record-0.retired.json', bridge, running, remaining())
                retired = strict_json(local / 'record-0.retired.json')
                response = retired_bytes(retired, held)
                for path, identities in inputs:
                    unchanged_inputs(path, identities)
                with SparseMemory(baseline) as before, SparseMemory(destination / 'record-0.memory') as after:
                    verify(before, after, description)
                atomic(local / 'record-0.retired.response', response)
                wait_file(local / 'cleanup_wait', bridge, running, remaining())
                require(bridge.poll() is None and running.poll() is None,
                        'original counter publication owner exited before independent cleanup authorization')
                atomic(local / 'BEGIN_CLEANUP', cleanup_authorization())
                wait_file(local / 'cleanup.json', bridge, running, remaining())
                checked_cleanup(strict_json(local / 'cleanup.json'))
                wait_file(local / 'executor.done', bridge, running, remaining())
                done = check_round(strict_json(local / 'executor.done'), index, held, previous)
                cleaned = destination / 'record-0.cleaned.memory'
                with SparseMemory(destination / 'record-0.memory') as before, SparseMemory(cleaned) as after:
                    verify_cleaned_memory(before, after, tables)
                if index + 1 == len(PHASES):
                    require(running.wait(timeout=remaining()) == 0,
                            'continuous counter publication DUT rejected final cleanup')
                else:
                    require(running.poll() is None,
                            'counter publication DUT exited between original source phases')
                previous, previous_clean, previous_digest = done, cleaned, input_digest(cleaned)
                for path, identities in inputs:
                    unchanged_inputs(path, identities)
                atomic(local / 'FINAL_RESPONSE', b'OGCMOK01\x01\x00\x00\x00')
                print('original counter publication phase=%d role=%s full bytes/cleanup PASS' % (
                    index, role), flush=True)
            require(bridge.wait(timeout=remaining()) == 0, 'original Mesa publication producer failed')
            observation = strict_json(directory / 'publication.cache-observation.json')
            require(observation.get('schema') ==
                    'opengpu-compute-counter-publication-cache-observation-v1',
                    'publication lacked its uncached fabric observation')
            require(observation.get('commandId') == previous['commandId'] and
                    observation.get('uncachedCounterPath') is True and
                    observation.get('warmReadRequestCycle') <
                    observation.get('warmReadResponseCycle') <
                    observation.get('firstTargetWriteCycle') <
                    observation.get('reloadReadRequestCycle') <
                    observation.get('reloadReadResponseCycle'),
                    'publication uncached warm-read/producer-write/reload-read order differs')
            qualified(env, output)
            for path, identities in inputs:
                unchanged_inputs(path, identities)
        print('all three original counter publication phases PASS; one DUT/RAM/reset, '
              'warm/producer/reload, uncached fabric observation, original source/model postchecks',
              flush=True)
    except BaseException as error:
        if local is not None:
            try:
                atomic(local / 'error', b'counter publication failed; no further cleanup authorization\n')
            except (ValueError, OSError):
                pass
        print('counter publication failed at %r: %s' % (stage, error), flush=True)
        for name in ('execution.log', 'bridge.log'):
            path = directory / name
            if path.is_file():
                print(path.read_text(errors='replace')[-5000:], flush=True)
        raise
    finally:
        try:
            stop(running)
        finally:
            stop(bridge)
