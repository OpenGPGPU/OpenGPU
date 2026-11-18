"""Original checked-SSBO ESSL operations and contending source identities."""
from pathlib import Path

OPERATIONS = ('exchange', 'add', 'xor', 'or', 'and', 'signed-min', 'signed-max',
              'unsigned-min', 'unsigned-max', 'compare-match', 'compare-miss',
              'exchange-lane', 'add-lane', 'compare-lane')
CASES = tuple('storage-atomic-' + operation for operation in OPERATIONS)
SEEDS = (10, 0xfffffff0, 10, 0x80000000, 0xffffffff, 0x7fffffff, 0x80000000,
         0xffffffff, 0x7fffffff, 10, 10, 0x13579bdf, 0xfffffff0, 0)
OPERANDS = (7, 5, 0x80000007, 0x80000007, 0x80000007, 0xfffffff9, 7,
            7, 0x80000007, 7, 7, 0, 0, 0)


def parameters(case):
    if case not in CASES:
        raise ValueError('unknown original storage atomic case')
    index = CASES.index(case)
    return dict(case=case, source=OPERATIONS[index] + '.comp', operation=OPERATIONS[index],
                seed=SEEDS[index], operand=OPERANDS[index], compare=10 if index == 9 else 11 if index == 10 else 0,
                groups=2, local=4, invocations=8, target_offset=0)


def invocations(case):
    p = parameters(case)
    result = []
    for lane in range(p['invocations']):
        name, operand, compare = p['operation'], p['operand'], p['compare']
        if name == 'exchange-lane':
            name, operand = 'exchange', 0x80000000 + lane
        elif name == 'add-lane':
            name, operand = 'add', lane + 1
        elif name == 'compare-lane':
            name, operand = 'compare-swap', lane + 1
        elif name in ('compare-match', 'compare-miss'):
            name = 'compare-swap'
        result.append(dict(operation=name, operand=operand, compare=compare))
    return result


def write_sources(out):
    # Keep the established eleven original ESSL inputs byte-identical. The
    # additional lane-dependent inputs distinguish legal reorderings from
    # response/lane mixups, including the identity of a winning CAS invocation.
    from test_gallium_compute_atomics import write_sources as original_sources
    out = Path(out)
    original_sources(out)
    template = (out / 'exchange.comp').read_text()
    for name, expression in (
            ('exchange-lane', 'atomicExchange(target.v[0u],0x80000000u+id)'),
            ('add-lane', 'atomicAdd(target.v[0u],id+1u)'),
            ('compare-lane', 'atomicCompSwap(target.v[0u],0u,id+1u)')):
        source = template.replace('atomicExchange(target.v[0u],7u)', expression)
        if source == template:
            raise ValueError('original atomic source shape changed')
        (out / (name + '.comp')).write_text(source)
