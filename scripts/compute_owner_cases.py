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
from compute_global_atomic_cases import CASES as GLOBAL_ATOMIC_CASES
CASES = LEGACY_CASES + COUNTER_CASES + IMAGE_CASES + STORAGE_ATOMIC_CASES + GLOBAL_ATOMIC_CASES


def counter_parameters(name):
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
