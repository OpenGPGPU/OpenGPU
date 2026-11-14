"""Source case identities for actual Mesa owner execution; no GPU results."""
LEGACY_DYNAMIC_CASES = tuple('shared-dynamic-%d' % op for op in range(10)) + tuple(
    'shared-dynamic-i-%d' % op for op in (0, 1, 2, 3, 4, 9))
ADDRESS_CASES = ('shared-address-add', 'shared-address-cas-match', 'shared-address-cas-miss')
DYNAMIC_CASES = LEGACY_DYNAMIC_CASES + ADDRESS_CASES
CASES = ('shared', 'uniforms') + DYNAMIC_CASES


def dynamic_case(name):
    if name not in DYNAMIC_CASES:
        raise ValueError('unknown shared atomic source case')
    if name in ADDRESS_CASES:
        return (1 if name == ADDRESS_CASES[0] else 9), False
    operation = int(name.rsplit('-', 1)[1])
    return operation, '-i-' in name or operation in (5, 6)
