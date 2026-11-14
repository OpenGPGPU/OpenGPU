"""Source case identities for actual Mesa owner execution; no GPU results."""
LEGACY_DYNAMIC_CASES = tuple('shared-dynamic-%d' % op for op in range(10)) + tuple(
    'shared-dynamic-i-%d' % op for op in (0, 1, 2, 3, 4, 9))
ADDRESS_CASES = ('shared-address-add', 'shared-address-cas-match', 'shared-address-cas-miss')
DYNAMIC_CASES = LEGACY_DYNAMIC_CASES + ADDRESS_CASES
LEGACY_CASES = ('shared', 'uniforms') + DYNAMIC_CASES
COUNTER_CASE = 'counter-increment'
COUNTER_CASES = (COUNTER_CASE, 'counter-read', 'counter-decrement')
CASES = LEGACY_CASES + COUNTER_CASES


def counter_parameters(name):
    if name not in COUNTER_CASES:
        raise ValueError('unknown original atomic counter source')
    operation = name.removeprefix('counter-')
    return dict(source=operation + '.comp', operation=operation, groups=4, local=4,
                seed=1 if operation == 'decrement' else 0xfffffffe, index=7)


def dynamic_case(name):
    if name not in DYNAMIC_CASES:
        raise ValueError('unknown shared atomic source case')
    if name in ADDRESS_CASES:
        return (1 if name == ADDRESS_CASES[0] else 9), False
    operation = int(name.rsplit('-', 1)[1])
    return operation, '-i-' in name or operation in (5, 6)
