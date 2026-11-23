"""Exact image, completion and physical-byte checks for the linked geometry draw."""
import struct

from native_geometry_feedback_oracle import (ACTIONS, DRAW_BYTES, DRAW_COUNT, FILL, RECORD_BYTES, RECOVERY, RENDER_COMMAND,
    packet, raw_primitive, recovery_bytes, verify_feedback, verify_feedback_receipts)
from native_geometry_owner_inputs import verify_inputs
from native_sample_graphics_gny2_oracle import _layout, completion_words, record_layout, values, verify_words
from native_sample_owner_oracle import require, verify_guards


CLEAR = (0.0, 0.0, 0.0, 0.0)


def sample_color(mrt, x, y, sample):
    # The viewport maps the three source vertices to (-2,0), (2,0), (2,2).
    # Visible x is [0,2). The upper edge is y = 1 + x/2, which contains no
    # quarter-pixel sample center. Mask 5 retains sample IDs zero and two.
    qx = 4 * x + (3 if sample & 1 else 1)
    qy = 4 * y + (3 if sample & 2 else 1)
    if not (sample in (0, 2) and 0 <= qx < 8 and 0 <= 2 * qy < 8 + qx):
        return CLEAR
    # Every source has w=1. The smooth seed rises by one across the four
    # horizontal units and by one across the two vertical units. Slot 15
    # keeps the last emitted vertex's seed through both clipped triangles.
    interpolated_seed = (qx + 8) / 16 + qy / 8
    rgb = []
    for channel in range(3):
        original_seed = (channel + 1) / 4
        values_by_slot = [
            original_seed + (2 if slot == 15 else interpolated_seed) + 8 * slot + 1 + 2 * channel
            for slot in range(4 * mrt, 4 * mrt + 4)
        ]
        rgb.append(sum(weight * value for weight, value in enumerate(values_by_slot, 1)))
    return (*rgb, float((0, 1, sample, 1)[mrt]))


def layer_bytes(mrt, layer, *, resolve=False):
    count = (1 if resolve else 4) * 4 * 4
    if layer == 0:
        return bytes(16 * count)
    result = bytearray()
    for y in range(4):
        for x in range(4):
            samples = [sample_color(mrt, x, y, sample) for sample in range(4)]
            if resolve:
                result += struct.pack('<4f', *(sum(values) / 4 for values in zip(*samples, strict=True)))
            else:
                for color in samples:
                    result += struct.pack('<4f', *color)
    return bytes(result)


def geometry_completion(command_id):
    expected = values(command_id, 7)
    lines = record_layout()[1]
    # Scan and execute one GND2, with a GFS and GNG1 fetch on each pass.
    # The two clipped parts share a tile chain, traversed once for each layer.
    expected.update({
        'descriptorReadBytes': 512 + 2048 + 2 * (512 + 1024),
        'frame.submittedGeometryPrimitives': 1,
        'frame.completedGeometryInvocations': 1,
        'frame.emittedGeometryPrimitives': 1,
        'frame.images.images.scanned': 4,
        'frame.images.images.pageReadLines': 2,
        'frame.images.images.recordReadLines': 4 * lines,
        'frame.images.images.storedBytes': 2 * 4 * 4 * 4 * 4 * 16,
        'frame.images.images.resolvedBytes': 2 * 4 * 4 * 4 * 16,
    })
    return completion_words(expected)


def verify_images(returned, description, *, discard=False):
    for mrt, attachment in enumerate(description['attachments']):
        for kind in ('image', 'resolve'):
            view = attachment[kind]
            for layer in range(2):
                address = view['physical'] + view['offset'] + layer * 4096
                expected = layer_bytes(mrt, layer, resolve=(kind == 'resolve'))
                if discard:
                    expected = bytes(len(expected))
                require(returned.read(address, len(expected)) == expected,
                        'geometry %s MRT %d layer %d differs' % (kind, mrt, layer))


def verify(initial, returned, description, programs, capture_data, observed_profile):
    outputs = verify_inputs(initial, description, programs, capture_data, observed_profile)
    if returned is None:
        return outputs.command_id
    verify_images(returned, description)
    verify_words(returned.read(outputs.completion, 16384),
                 geometry_completion(outputs.command_id), label='geometry')
    verify_guards(initial, returned, outputs.writable)
    return outputs.command_id


def feedback_plan(initial, description, programs, capture_data, observed_profile):
    return verify_inputs(initial, description, programs, capture_data, observed_profile).feedback.plan()


def verify_feedback_run(initial, snapshots, description, programs, capture_data, observed_profile, receipts):
    verify_feedback_receipts(receipts)
    outputs = verify_inputs(initial, description, programs, capture_data, observed_profile)
    feedback = outputs.feedback
    discard, rendered = snapshots['discard'], snapshots['render']
    verify_images(discard, description, discard=True)
    verify_images(rendered, description)
    require(discard.read(feedback.checkpoint(0), RECORD_BYTES) == packet(feedback, 0, observed=True),
            'geometry feedback discard lost its begin checkpoint')
    require(discard.read(feedback.stream, DRAW_BYTES) == raw_primitive(),
            'geometry raster discard did not capture one original GS primitive')
    verify_guards(initial, discard, (*outputs.writable,
        (feedback.checkpoint(0), feedback.checkpoint(1)), (feedback.stream, feedback.stream + DRAW_BYTES)))

    verify_feedback(rendered, feedback)
    verify_words(rendered.read(outputs.completion, 16384), geometry_completion(RENDER_COMMAND),
                 label='geometry feedback render')
    verify_guards(discard, rendered, (*outputs.writable,
        (feedback.checkpoint(1), feedback.checkpoint(len(ACTIONS))),
        (feedback.stream + DRAW_BYTES, feedback.stream + DRAW_BYTES * DRAW_COUNT)))

    for case in RECOVERY:
        candidate = snapshots[case.name]
        verify_images(candidate, description)
        checkpoints, raw = recovery_bytes(feedback, case)
        require(candidate.read(feedback.checkpoint(0), len(checkpoints)) == checkpoints,
                'geometry feedback GTF1 mismatch after ' + case.name)
        visible = raw + bytes([FILL]) * (DRAW_COUNT * DRAW_BYTES - len(raw))
        require(candidate.read(feedback.stream, len(visible)) == visible,
                'geometry feedback stream mismatch after ' + case.name)
        gny = candidate.read(outputs.completion, 16384)
        if case.draw_status:
            expected_header = (0x474e5902, 16384, 2, case.draw_command)
            require(struct.unpack_from('<4I', gny) == expected_header,
                    'geometry GNY2 completion identity or error differs after ' + case.name)
            expected = {'identity.context': 7, 'identity.pass': 11, 'identity.generation': 13,
                        'status': case.draw_status, 'faultStage': 3 if case.draw_status == 3 else 0}
            for name, word, _, _ in _layout():
                if name in expected:
                    require(struct.unpack_from('<I', gny, word * 4)[0] == expected[name],
                            'geometry GNY2 ' + name + ' differs after ' + case.name)
        else:
            verify_words(gny, geometry_completion(case.draw_command), label='geometry ' + case.name)
        verify_guards(initial, candidate, (*outputs.writable,
            (feedback.checkpoint(0), feedback.checkpoint(len(ACTIONS))),
            (feedback.stream, feedback.stream + len(raw))))
