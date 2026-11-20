"""Color-attachment publication phases for complete-owner P11.

Warm and reload blit the retained color attachment into a scratch destination.
The producer blits a known RGBA8 pattern into that same attachment GEM.
"""
import struct

CASES = (
    'attachment-publication-warm',
    'attachment-publication-1',
    'attachment-publication-reload',
)
EPOCH = 1
# After copy-region-0 producer, source (1,1)/(2,1) land at dest offsets 8 and 12.
DATA_PIXEL = struct.pack('<I', (EPOCH << 24) | 0x00000700)
FLAG_PIXEL = struct.pack('<I', (EPOCH << 24) | 0x00000001)
DATA_OFFSET = 8
FLAG_OFFSET = 12
BASE = 0x01000000
ATTACHMENT_BYTES = 64
SCRATCH_BYTES = 64


def require(ok, message):
    if not ok:
        raise ValueError('attachment publication cases: ' + message)


def parameters(case):
    require(case in CASES, 'unknown original attachment publication case')
    if case == CASES[0]:
        return dict(case=case, role='warm', epoch=EPOCH,
                    expected_data=0, expected_flag=0,
                    data_offset=DATA_OFFSET, flag_offset=FLAG_OFFSET,
                    attachment_bytes=ATTACHMENT_BYTES, scratch_bytes=SCRATCH_BYTES)
    expected_data = struct.unpack('<I', DATA_PIXEL)[0]
    expected_flag = struct.unpack('<I', FLAG_PIXEL)[0]
    return dict(case=case, role='producer' if case == CASES[1] else 'reload', epoch=EPOCH,
                expected_data=expected_data, expected_flag=expected_flag,
                data_offset=DATA_OFFSET, flag_offset=FLAG_OFFSET,
                attachment_bytes=ATTACHMENT_BYTES, scratch_bytes=SCRATCH_BYTES)


def producer_source_pattern(size=64):
    """5x3 pitch-20 source with distinctive texels in the 2x2 copy-region window."""
    require(size >= 64, 'producer source too small')
    body = bytearray(b'\x6b' * size)
    # source (1,1) -> dest (2,0) offset 8; source (2,1) -> dest (3,0) offset 12.
    body[24:28] = DATA_PIXEL
    body[28:32] = FLAG_PIXEL
    return bytes(body)


def zero_attachment(size=ATTACHMENT_BYTES):
    return bytes(size)
