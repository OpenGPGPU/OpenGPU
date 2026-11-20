"""Original Mesa shared→SSBO publication sources for complete-owner P11."""
from pathlib import Path

CASES = (
    'shared-publication-warm',
    'shared-publication-1',
    'shared-publication-reload',
)
EPOCH = 1
DATA_WORD = EPOCH * 7
FLAG_WORD = EPOCH
PRODUCER_LOCAL = 16
# Two own-stripe spans (data + flag). Emitter admits EXPR_PEER only for
# local_size=128 with id^16; float DUT uses a single warp and dual own-stripe.
SHARED_SCRATCH_BYTES = PRODUCER_LOCAL * 4 * 2
READER_PREFIX = (
    '#version 310 es\nprecision highp int;\n'
    'layout(local_size_x=1) in;\n'
    'layout(std430,binding=0) buffer Data {uint v[];} data;\n'
    'layout(std430,binding=1) buffer Output {uint v[];} output_words;\n'
)
# Unconditional own-stripe stores; arithmetic avoids boolean/ternary CF.
# memoryBarrierShared();barrier() order matches GALLIUM_SHARED_COMPUTE.
PRODUCER = (
    '#version 310 es\nprecision highp int;\n'
    'layout(local_size_x=16) in;\n'
    'shared uint scratch[32];\n'
    'layout(std430,binding=0) buffer Data {uint v[];} data;\n'
    'layout(std430,binding=1) buffer Output {uint v[];} output_words;\n'
    'void main(){uint id=gl_LocalInvocationID.x;'
    'scratch[id]=%du-6u*min(id,1u);'
    'scratch[16u+id]=%du;'
    'memoryBarrierShared();barrier();'
    'uint published_data=scratch[id];uint published_flag=scratch[16u+id];'
    'if(id==0u){data.v[1]=published_data;memoryBarrierBuffer();data.v[17]=published_flag;'
    'output_words.v[0]=data.v[1];output_words.v[1]=data.v[17];}}\n'
)


def require(ok, message):
    if not ok:
        raise ValueError('shared publication cases: ' + message)


def parameters(case):
    require(case in CASES, 'unknown original shared publication case')
    if case == CASES[0]:
        return dict(case=case, source='warm.comp', role='warm', epoch=EPOCH,
                    data_word=0, flag_word=0, expected_data=0, expected_flag=0,
                    groups=1, local=1, shared_bytes=0)
    if case == CASES[1]:
        return dict(case=case, source='publication-1.comp', role='producer', epoch=EPOCH,
                    data_word=DATA_WORD, flag_word=FLAG_WORD,
                    expected_data=DATA_WORD, expected_flag=FLAG_WORD,
                    groups=1, local=PRODUCER_LOCAL, shared_bytes=SHARED_SCRATCH_BYTES)
    return dict(case=case, source='reload.comp', role='reload', epoch=EPOCH,
                data_word=DATA_WORD, flag_word=FLAG_WORD,
                expected_data=DATA_WORD, expected_flag=FLAG_WORD,
                groups=1, local=1, shared_bytes=0)


def write_sources(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    reader = (READER_PREFIX +
              'void main(){output_words.v[0]=data.v[1];output_words.v[1]=data.v[17];}\n')
    (out / 'warm.comp').write_text(reader)
    (out / 'reload.comp').write_text(reader)
    (out / 'publication-1.comp').write_text(PRODUCER % (DATA_WORD, FLAG_WORD))
