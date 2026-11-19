"""Original Mesa coherent buffer-image publication sources for complete-owner P11."""
from pathlib import Path

CASES = (
    'image-publication-warm',
    'image-publication-1',
    'image-publication-reload',
)
EPOCH = 1
DATA_WORD = EPOCH * 7
FLAG_WORD = EPOCH
PREFIX = (
    '#version 310 es\n#extension GL_EXT_texture_buffer : require\n'
    'precision highp int;\nlayout(local_size_x=1) in;\n'
    'layout(r32ui,binding=0) coherent uniform highp uimageBuffer image0;\n'
    'layout(std430,binding=0) buffer Output {uint v[];} output_words;\n'
)


def require(ok, message):
    if not ok:
        raise ValueError('image publication cases: ' + message)


def parameters(case):
    require(case in CASES, 'unknown original image publication case')
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
              'void main(){output_words.v[0]=imageLoad(image0,1).x;'
              'output_words.v[1]=imageLoad(image0,17).x;}\n')
    (out / 'warm.comp').write_text(reader)
    (out / 'reload.comp').write_text(reader)
    (out / 'publication-1.comp').write_text(
        PREFIX +
        'void main(){imageStore(image0,1,uvec4(%du));memoryBarrierImage();'
        'imageStore(image0,17,uvec4(%du));'
        'output_words.v[0]=imageLoad(image0,1).x;'
        'output_words.v[1]=imageLoad(image0,17).x;}\n'
        % (DATA_WORD, FLAG_WORD))
