"""Original Mesa and raw RV32 source case identities; no GPU results."""
LEGACY_DYNAMIC_CASES = tuple('shared-dynamic-%d' % op for op in range(10)) + tuple(
    'shared-dynamic-i-%d' % op for op in (0, 1, 2, 3, 4, 9))
ADDRESS_CASES = ('shared-address-add', 'shared-address-cas-match', 'shared-address-cas-miss')
DYNAMIC_CASES = LEGACY_DYNAMIC_CASES + ADDRESS_CASES
LEGACY_CASES = ('shared', 'uniforms') + DYNAMIC_CASES
COUNTER_CASE = 'counter-increment'
STATIC_COUNTER_CASES = (COUNTER_CASE, 'counter-read', 'counter-decrement')
DYNAMIC_COUNTER_CASES = tuple('counter-dynamic-' + op for op in ('increment','read','decrement')) + tuple(
    'counter-dynamic-all-' + op for op in ('increment','read','decrement'))
SCALAR_COUNTER_CASE = 'counter-scalar'
COEXIST_COUNTER_CASE = 'counter-coexist'
COUNTER_CASES = STATIC_COUNTER_CASES + DYNAMIC_COUNTER_CASES + (SCALAR_COUNTER_CASE,COEXIST_COUNTER_CASE)
from compute_image_cases import IMAGE_CASES as LEGACY_IMAGE_CASES
from compute_contended_image_cases import CASES as CONTENDED_IMAGE_CASES
from compute_mixed_atomic_cases import CASES as MIXED_ATOMIC_CASES
IMAGE_CASES = LEGACY_IMAGE_CASES + CONTENDED_IMAGE_CASES + MIXED_ATOMIC_CASES
from compute_storage_atomic_cases import CASES as STORAGE_ATOMIC_CASES
from compute_storage_publication_cases import CASES as STORAGE_PUBLICATION_CASES
from compute_image_publication_cases import CASES as IMAGE_PUBLICATION_CASES
from compute_texture_publication_cases import CASES as TEXTURE_PUBLICATION_CASES
from compute_counter_publication_cases import CASES as COUNTER_PUBLICATION_CASES
from compute_shared_publication_cases import CASES as SHARED_PUBLICATION_CASES
from compute_dma_publication_cases import CASES as DMA_PUBLICATION_CASES
from compute_l1_publication_cases import CASES as L1_PUBLICATION_CASES
from compute_global_atomic_cases import CASES as GLOBAL_ATOMIC_CASES
from compute_p12_counter_cases import CASES as P12_COUNTER_CASES, COUNTER_OFFSET as P12_COUNTER_OFFSET
COUNTER_CASES = COUNTER_CASES + P12_COUNTER_CASES
from compute_p12_shared_cases import CASES as P12_SHARED_CASES, parameters as p12_shared_source_parameters
from compute_p12_texture_cases import CASES as P12_TEXTURE_CASES, parameters as p12_texture_source_parameters
STORAGE_LOOP_CASE = "storage-loop"
NESTED_LOOP_CASE = "nested-loop"
MASKED_STORAGE_LOOP_CASE = "masked-storage-loop"
LOOP_BARRIER_CASE = "loop-branch"
COMPUTE_LOOP_CASES = (STORAGE_LOOP_CASE, NESTED_LOOP_CASE, "constant-loop", "dynamic-loop", "loop-break",
                      "continue-loop", MASKED_STORAGE_LOOP_CASE, LOOP_BARRIER_CASE)
COMPUTE_LOOP_SCHEMAS = {case: "opengpu-compute-" + case + "-oracle-v1" for case in COMPUTE_LOOP_CASES}
STORAGE_LOOP_SCHEMA = COMPUTE_LOOP_SCHEMAS[STORAGE_LOOP_CASE]
CASES = (LEGACY_CASES + COMPUTE_LOOP_CASES + COUNTER_CASES + IMAGE_CASES + STORAGE_ATOMIC_CASES +
         STORAGE_PUBLICATION_CASES + IMAGE_PUBLICATION_CASES + TEXTURE_PUBLICATION_CASES +
         COUNTER_PUBLICATION_CASES + SHARED_PUBLICATION_CASES + DMA_PUBLICATION_CASES +
         L1_PUBLICATION_CASES + GLOBAL_ATOMIC_CASES + P12_SHARED_CASES + P12_TEXTURE_CASES)


def p12_shared_parameters(name):
    if name not in P12_SHARED_CASES:
        raise ValueError('unknown P12 shared coexist source')
    p = p12_shared_source_parameters(name)
    return dict(source=name + '.comp', groups=p['groups'], local=p['local'], shared_bytes=p['shared_bytes'])


def p12_texture_parameters(name):
    if name not in P12_TEXTURE_CASES:
        raise ValueError('unknown P12 texture coexist source')
    p = p12_texture_source_parameters(name)
    return dict(source=name + '.comp', groups=p['groups'], local=p['local'], viewBytes=p['viewBytes'])


def p12_counter_parameters(name):
    if name not in P12_COUNTER_CASES:
        raise ValueError('unknown P12 counter coexist source')
    return dict(source=name + '.comp', operation='increment', groups=1, local=128,
                dynamic=True, seed=0xfffffffe, index=0, offset=P12_COUNTER_OFFSET, count=8)


def counter_parameters(name):
    if name in P12_COUNTER_CASES:
        return p12_counter_parameters(name)
    if name not in COUNTER_CASES:
        raise ValueError('unknown original atomic counter source')
    if name == SCALAR_COUNTER_CASE:
        return dict(source='scalar.comp', operation='increment', groups=4, local=4,
                    dynamic=False, seed=0xfffffffe, index=0, offset=8188, count=1)
    if name == COEXIST_COUNTER_CASE:
        return dict(source='coexist.comp', operation='increment', groups=4, local=4,
                    dynamic=False, seed=0xfffffffe, index=7, offset=4080, count=8)
    source = name.removeprefix('counter-')
    dynamic = name in DYNAMIC_COUNTER_CASES
    all_counters=name.startswith('counter-dynamic-all-')
    operation = source.removeprefix('dynamic-').removeprefix('all-')
    source=('dynamic-' if dynamic else '')+operation
    return dict(source=source + '.comp', operation=operation, groups=(2 if all_counters else 1) if dynamic else 4, local=4,
                dynamic=dynamic, seed=(0 if dynamic else 1) if operation == 'decrement' else 0xfffffffe,
                index=(0 if all_counters else 4) if dynamic else 7, offset=4080, count=8)


def dynamic_case(name):
    if name not in DYNAMIC_CASES:
        raise ValueError('unknown shared atomic source case')
    if name in ADDRESS_CASES:
        return (1 if name == ADDRESS_CASES[0] else 9), False
    operation = int(name.rsplit('-', 1)[1])
    return operation, '-i-' in name or operation in (5, 6)
