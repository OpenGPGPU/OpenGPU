"""Original Mesa SSBO→host-visible GEM publication sources for complete-owner P11 DMA."""
from pathlib import Path

CASES = (
    'dma-publication-warm',
    'dma-publication-1',
    'dma-publication-reload',
)
EPOCH = 1
DATA_WORD = EPOCH * 7
FLAG_WORD = EPOCH
PREFIX = (
    '#version 310 es\nprecision highp int;\n'
    'layout(local_size_x=1) in;\n'
    'layout(std430,binding=0) buffer Data {uint v[];} data;\n'
    'layout(std430,binding=1) buffer Output {uint v[];} output_words;\n'
)


def require(ok, message):
    if not ok:
        raise ValueError('dma publication cases: ' + message)


def parameters(case):
    require(case in CASES, 'unknown original dma publication case')
    if case == CASES[0]:
        return dict(case=case, source='warm.comp', role='warm', epoch=EPOCH,
                    data_word=0, flag_word=0, expected_data=0, expected_flag=0,
                    groups=1, local=1)
    if case == CASES[1]:
        return dict(case=case, source='publication-1.comp', role='producer', epoch=EPOCH,
                    data_word=DATA_WORD, flag_word=FLAG_WORD,
                    expected_data=DATA_WORD, expected_flag=FLAG_WORD,
                    groups=1, local=1)
    return dict(case=case, source='reload.comp', role='reload', epoch=EPOCH,
                data_word=DATA_WORD, flag_word=FLAG_WORD,
                expected_data=DATA_WORD, expected_flag=FLAG_WORD,
                groups=1, local=1)


def write_sources(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    reader = (PREFIX +
              'void main(){output_words.v[0]=data.v[1];output_words.v[1]=data.v[17];}\n')
    (out / 'warm.comp').write_text(reader)
    (out / 'reload.comp').write_text(reader)
    (out / 'publication-1.comp').write_text(
        '#version 310 es\nprecision highp int;\n'
        'layout(local_size_x=1) in;\n'
        'layout(std430,binding=0) buffer Data {uint v[];} data;\n'
        'layout(std430,binding=1) buffer Output {uint v[];} output_words;\n'
        'void main(){data.v[1]=%du;memoryBarrierBuffer();data.v[17]=%du;'
        'output_words.v[0]=data.v[1];output_words.v[1]=data.v[17];}\n'
        % (DATA_WORD, FLAG_WORD))
