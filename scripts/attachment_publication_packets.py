"""Build continuous warm/producer/reload color-attachment blit packets."""
from pathlib import Path
import json
import math
import struct

from attachment_publication_cases import (CASES, BASE, ATTACHMENT_BYTES, SCRATCH_BYTES,
    producer_source_pattern, zero_attachment, parameters, DATA_OFFSET, FLAG_OFFSET)
from framebuffer_blit_fixture import BlitFixture


def require(ok, message):
    if not ok:
        raise ValueError('attachment publication packets: ' + message)


def clamp(value, lo, hi):
    return max(lo, min(value, hi - 1))


def affine_expected(source, destination, descriptor):
    d = descriptor
    result = bytearray(destination)
    for y in range(d['destination_height']):
        for x in range(d['destination_width']):
            u = (x + 0.5 - d['destination_x']) / d['destination_w']
            v = (y + 0.5 - d['destination_y']) / d['destination_h']
            if u < 0 or u >= 1 or v < 0 or v >= 1 or not d['mask']:
                continue
            if (d['flags'] & 1) and (x < d['scissor_x0'] or x >= d['scissor_x1'] or
                                     y < d['scissor_y0'] or y >= d['scissor_y1']):
                continue
            cx = d['source_x'] + u * d['source_w']
            cy = d['source_y'] + v * d['source_h']
            sx, sy = int(math.floor(cx)), int(math.floor(cy))
            if sx < 0 or sx >= d['source_width'] or sy < 0 or sy >= d['source_height']:
                continue
            target = y * d['destination_pitch'] + x * 4
            sb = d['source_format'] in (2, 4)
            db = d['destination_format'] in (2, 4)
            for c in range(4):
                if not (d['mask'] & (1 << c)) or (c == 3 and d['destination_format'] >= 3):
                    continue
                frm = 2 if c == 0 and sb else 0 if c == 2 and sb else c
                to = 2 if c == 0 and db else 0 if c == 2 and db else c
                if c == 3 and d['source_format'] >= 3:
                    value = 255
                elif not (d['flags'] & 2):
                    value = source[sy * d['source_pitch'] + sx * 4 + frm]
                else:
                    raise ValueError('linear filter not used by attachment publication')
                result[target + to] = value
    return bytes(result)


def descriptor_dict(fields):
    names = ('version', 'bytes', 'flags', 'mask', 'source_format', 'destination_format',
             'source_width', 'source_height', 'source_pitch', 'destination_width',
             'destination_height', 'destination_pitch', 'source_x', 'source_y', 'source_w',
             'source_h', 'destination_x', 'destination_y', 'destination_w', 'destination_h',
             'scissor_x0', 'scissor_y0', 'scissor_x1', 'scissor_y1')
    raw = list(fields)
    for i in range(12, 24):
        if raw[i] >= 0x80000000:
            raw[i] -= 0x100000000
    return dict(zip(names, raw))


def write_packet(path, template: BlitFixture, source, destination, expected, handles):
    require(len(source) == template.views[0].allocation, 'source allocation differs')
    require(len(destination) == template.views[1].allocation, 'destination allocation differs')
    require(len(expected) == template.views[1].allocation, 'expected allocation differs')
    header = struct.pack('<8s4I', b'OGBLT001', 1, template.context, 2048, 64)
    header += struct.pack('<6I', *template.grid, *template.local)
    for view, handle in zip(template.views, handles):
        header += struct.pack('<4I', handle, view.allocation, view.limit, view.access)
    body = header + template.shader + template.kernarg + source + destination + template.coordinates + expected
    path.write_bytes(body)
    BlitFixture.read(body)


def build_packets(producer_template_path, consumer_template_path, out_dir):
    producer_template = BlitFixture.read(Path(producer_template_path).read_bytes())
    consumer_template = BlitFixture.read(Path(consumer_template_path).read_bytes())
    producer_desc = descriptor_dict(struct.unpack_from('<24I', producer_template.coordinates))
    consumer_desc = descriptor_dict(struct.unpack_from('<24I', consumer_template.coordinates))
    require(producer_desc['flags'] == 0 and producer_desc['source_format'] == 1 and
            producer_desc['destination_format'] == 1 and
            producer_template.views[1].allocation == ATTACHMENT_BYTES,
            'producer template must be nearest RGBA8 into a 64-byte color attachment')
    require(consumer_desc['flags'] == 0 and consumer_desc['source_format'] == 1 and
            consumer_desc['destination_format'] == 1 and
            consumer_template.views[0].allocation == ATTACHMENT_BYTES and
            consumer_template.views[1].allocation == SCRATCH_BYTES,
            'consumer template must blit a 64-byte color attachment into a 64-byte scratch')

    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    pattern = producer_source_pattern(producer_template.views[0].allocation)
    zeros = zero_attachment(ATTACHMENT_BYTES)
    scratch = bytes([0xa7]) * SCRATCH_BYTES
    attachment_handle = 10

    # Producer first so consumers can reuse its published expected attachment bytes.
    producer_expected = affine_expected(pattern, zeros, producer_desc)
    require(struct.unpack_from('<I', producer_expected, DATA_OFFSET)[0] ==
            parameters(CASES[1])['expected_data'] and
            struct.unpack_from('<I', producer_expected, FLAG_OFFSET)[0] ==
            parameters(CASES[1])['expected_flag'],
            'producer oracle missed the retained data/flag attachment pixels')
    write_packet(out / (CASES[1] + '.blit'), producer_template, pattern, zeros, producer_expected,
                 (20, attachment_handle, 22))

    warm_expected = affine_expected(zeros, scratch, consumer_desc)
    write_packet(out / (CASES[0] + '.blit'), consumer_template, zeros, scratch, warm_expected,
                 (attachment_handle, 11, 12))

    reload_expected = affine_expected(producer_expected, scratch, consumer_desc)
    write_packet(out / (CASES[2] + '.blit'), consumer_template, producer_expected, scratch,
                 reload_expected, (attachment_handle, 11, 12))

    (out / 'attachment-publication.plan').write_bytes(struct.pack('<8sIIQ', b'OGAPPM01', 3, 1, BASE))
    line = (BASE + DATA_OFFSET) & ~63
    (out / 'publication.cache-target').write_bytes(struct.pack('<8sQ', b'OGAPPT01', line))
    (out / 'manifest.json').write_text(json.dumps({
        'cases': list(CASES),
        'base': BASE,
        'attachment_handle': attachment_handle,
        'data_offset': DATA_OFFSET,
        'flag_offset': FLAG_OFFSET,
        'roles': [parameters(c)['role'] for c in CASES],
    }, indent=2) + '\n')
    return out
