"""Original Mesa atomic-counter publication sources for complete-owner P11."""
from pathlib import Path

CASES = (
    'counter-publication-warm',
    'counter-publication-1',
    'counter-publication-reload',
)
EPOCH = 1
DATA_WORD = EPOCH * 7
FLAG_WORD = EPOCH
# counters[1] is the data word (offset 4); counters[0] is the flag (offset 0).
# Hardware counter capacity is eight words, so both live on one retained line.
DATA_INDEX = 1
FLAG_INDEX = 0
COUNTER_COUNT = 8
COUNTER_OFFSET = 0
PREFIX = (
    '#version 310 es\nprecision highp int;\n'
    'layout(local_size_x=1) in;\n'
    'layout(binding=0,offset=0) uniform atomic_uint counters[8];\n'
    'layout(std430,binding=0) buffer Output {uint v[];} output_words;\n'
)


def require(ok, message):
    if not ok:
        raise ValueError('counter publication cases: ' + message)


def parameters(case):
    require(case in CASES, 'unknown original counter publication case')
    if case == CASES[0]:
        return dict(case=case, source='warm.comp', role='warm', epoch=EPOCH,
                    data_word=0, flag_word=0, expected_data=0, expected_flag=0,
                    groups=1, local=1, count=COUNTER_COUNT, offset=COUNTER_OFFSET,
                    data_index=DATA_INDEX, flag_index=FLAG_INDEX)
    if case == CASES[1]:
        return dict(case=case, source='publication-1.comp', role='producer', epoch=EPOCH,
                    data_word=DATA_WORD, flag_word=FLAG_WORD,
                    expected_data=DATA_WORD, expected_flag=FLAG_WORD,
                    groups=1, local=1, count=COUNTER_COUNT, offset=COUNTER_OFFSET,
                    data_index=DATA_INDEX, flag_index=FLAG_INDEX)
    return dict(case=case, source='reload.comp', role='reload', epoch=EPOCH,
                data_word=DATA_WORD, flag_word=FLAG_WORD,
                expected_data=DATA_WORD, expected_flag=FLAG_WORD,
                groups=1, local=1, count=COUNTER_COUNT, offset=COUNTER_OFFSET,
                data_index=DATA_INDEX, flag_index=FLAG_INDEX)


def write_sources(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    reader = (PREFIX +
              'void main(){output_words.v[0]=atomicCounter(counters[%du]);'
              'output_words.v[1]=atomicCounter(counters[%du]);}\n'
              % (DATA_INDEX, FLAG_INDEX))
    (out / 'warm.comp').write_text(reader)
    (out / 'reload.comp').write_text(reader)
    # Unroll increments: counter ops inside control flow remain rejected.
    increments = ''.join(
        'atomicCounterIncrement(counters[%du]);' % DATA_INDEX for _ in range(DATA_WORD))
    (out / 'publication-1.comp').write_text(
        PREFIX +
        'void main(){%smemoryBarrierAtomicCounter();'
        'atomicCounterIncrement(counters[%du]);'
        'output_words.v[0]=atomicCounter(counters[%du]);'
        'output_words.v[1]=atomicCounter(counters[%du]);}\n'
        % (increments, FLAG_INDEX, DATA_INDEX, FLAG_INDEX))
