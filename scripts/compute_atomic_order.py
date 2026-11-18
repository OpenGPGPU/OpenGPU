"""Bounded old-value linearizability oracle for original ESSL word atomics.

The inputs are source-level operations and actual returned words. This does
not execute shader instructions or consult an RTL/internal atomic trace.
"""
from functools import lru_cache
from compute_owner_protocol import uint

OPERATIONS = frozenset(('exchange', 'add', 'xor', 'or', 'and', 'signed-min',
    'signed-max', 'unsigned-min', 'unsigned-max', 'compare-swap'))


def result(operation, value, operand, compare):
    if operation not in OPERATIONS:
        raise ValueError('unknown original atomic operation')
    if operation == 'exchange':
        return operand
    if operation == 'add':
        return (value + operand) & 0xffffffff
    if operation == 'xor':
        return value ^ operand
    if operation == 'or':
        return value | operand
    if operation == 'and':
        return value & operand
    if operation == 'compare-swap':
        return operand if value == compare else value
    if operation in ('signed-min', 'signed-max'):
        signed = lambda word: word if word < 0x80000000 else word - 0x100000000
        choose_operand = (signed(operand) < signed(value) if operation == 'signed-min'
                          else signed(operand) > signed(value))
    else:
        choose_operand = operand < value if operation == 'unsigned-min' else operand > value
    return operand if choose_operand else value


def linearization(initial, final, operations, observed, predecessors=None):
    """Return a valid invocation order or reject all possible legal orders.

    At most sixteen source events are considered. Optional predecessor masks
    express original per-invocation program order for later mixed-resource
    recipes; concurrent invocations acquire no invented order.
    """
    initial, final = uint(initial), uint(final)
    if type(operations) not in (tuple, list) or not 0 < len(operations) <= 16:
        raise ValueError('atomic order requires 1..16 bounded source events')
    count = len(operations)
    if type(observed) not in (tuple, list) or len(observed) != count:
        raise ValueError('one actual old word is required per source event')
    observed = tuple(uint(word) for word in observed)
    normalized = []
    for record in operations:
        if (type(record) is not dict or set(record) != {'operation', 'operand', 'compare'} or
                type(record['operation']) is not str or record['operation'] not in OPERATIONS):
            raise ValueError('unknown/malformed original atomic operation')
        normalized.append((record['operation'], uint(record['operand']), uint(record['compare'])))
    full = (1 << count) - 1
    predecessors = (0,) * count if predecessors is None else predecessors
    if type(predecessors) not in (tuple, list) or len(predecessors) != count:
        raise ValueError('one original program-order mask is required per event')
    predecessors = tuple(uint(mask) for mask in predecessors)
    if any(mask & ~full or mask & (1 << index) for index, mask in enumerate(predecessors)):
        raise ValueError('invalid original program-order relation')

    @lru_cache(maxsize=None)
    def search(mask, value):
        if mask == full:
            return () if value == final else None
        for index, (operation, operand, compare) in enumerate(normalized):
            bit = 1 << index
            if not mask & bit and predecessors[index] & mask == predecessors[index] and observed[index] == value:
                remaining = search(mask | bit, result(operation, value, operand, compare))
                if remaining is not None:
                    return (index, *remaining)
        return None

    witness = search(0, initial)
    if witness is None:
        raise ValueError('actual atomic old values/final word have no legal source linearization')
    return witness
