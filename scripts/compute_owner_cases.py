"""Source case identities for actual Mesa owner execution; no GPU results."""
DYNAMIC_CASES = tuple('shared-dynamic-%d' % op for op in range(10)) + tuple(
    'shared-dynamic-i-%d' % op for op in (0, 1, 2, 3, 4, 9))
CASES = ('shared', 'uniforms') + DYNAMIC_CASES


def dynamic_case(name):
    if name not in DYNAMIC_CASES:
        raise ValueError('unknown shared atomic source case')
    operation = int(name.rsplit('-', 1)[1])
    return operation, '-i-' in name or operation in (5, 6)
