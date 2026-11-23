"""Independent GTF1 and pre-clip, pre-flat stream checks for the linked GS."""
from dataclasses import dataclass
import struct

from compute_owner_protocol import required_flags, uint
from native_sample_owner_oracle import require


CONTEXT = 0x71000007
COOKIE = 101
ACTIONS = (0, 2, 3, 1)  # Begin, pause, resume, end.
POOL_BYTES = 4096
RECORD_OFFSET = 512
RECORD_BYTES = 512
DATA_OFFSET = 2620
SELECTORS = (*range(4), *(4 + 4 * slot + channel for slot in range(16) for channel in range(3)))
DRAW_BYTES = 3 * len(SELECTORS) * 4
DRAW_COUNT = 2
FILL = 0xa7
COMMAND_BASE = 192
RENDER_COMMAND = COMMAND_BASE + 4


@dataclass(frozen=True)
class Feedback:
    virtual: int
    physical: int
    root: int
    program: int

    def checkpoint(self, index):
        return self.physical + RECORD_OFFSET + index * RECORD_BYTES

    @property
    def stream(self):
        return self.physical + DATA_OFFSET

    def plan(self):
        return b'OGGTF001' + struct.pack('<4I', self.virtual, self.physical, self.root, self.program)


def packet(feedback, index, *, observed=False):
    action = ACTIONS[index]
    words = [0] * 128
    words[:11] = [0x47544601, RECORD_BYTES, 1, action, CONTEXT, COOKIE, feedback.program,
                  2, 3 if observed and action == 2 else 1, 1, len(SELECTORS)]
    words[16:18] = [feedback.virtual + DATA_OFFSET, feedback.virtual + DATA_OFFSET + DRAW_COUNT * DRAW_BYTES]
    words[41] = feedback.root
    words[48:48 + len(SELECTORS)] = SELECTORS
    if observed:
        captures = (0, 1, 1, DRAW_COUNT)[index]
        words[28], words[36], words[38] = captures * DRAW_BYTES, captures, 3 * captures
        words[43] = 6 if action == 1 else 7
    return struct.pack('<128I', *words)


def preflight(memory, feedback):
    for index, _ in enumerate(ACTIONS):
        require(memory.read(feedback.checkpoint(index), RECORD_BYTES) == packet(feedback, index),
                'original GTF1 checkpoint input differs from the linked protected draw')
    guard = feedback.checkpoint(len(ACTIONS))
    length = feedback.physical + POOL_BYTES - guard
    require(memory.read(guard, length) == bytes([FILL]) * length,
            'geometry feedback initial stream or guard changed')


def raw_primitive():
    # The original GS emits the three inputs in order. Position and varying
    # components bypass both clipping and provoking-vertex substitution.
    values = []
    for vertex, xy in enumerate(((-2, -1), (0, -1), (0, 0))):
        values.extend((*xy, 0, 1))
        values.extend(vertex + (channel + 1) / 4 + 8 * slot + 1 + 2 * channel
                      for slot in range(16) for channel in range(3))
    return struct.pack('<156f', *values)


def verify_feedback(memory, feedback):
    for index, action in enumerate(ACTIONS):
        require(memory.read(feedback.checkpoint(index), RECORD_BYTES) == packet(feedback, index, observed=True),
                'geometry GTF1 checkpoint differs after action ' + str(action))
    require(memory.read(feedback.stream, DRAW_COUNT * DRAW_BYTES) == raw_primitive() * DRAW_COUNT,
            'geometry feedback differs from the unclipped, unflattened GS output')


def verify_feedback_receipts(value):
    required_flags(value, dict(schema='opengpu-native-geometry-feedback-v1', resetCount=1))
    require(uint(value.get('commitAckHeldCycles')) >= 17, 'geometry GTF1 commit ACK was not held')
    receipts = value.get('receipts')
    phases = ((12, 7), (7, None), (12, 7), (12, 7), (7, None), (12, 6))
    require(type(receipts) is list and len(receipts) == len(phases), 'geometry feedback lost phase receipts')
    for command, ((opcode, data), result) in enumerate(zip(phases, receipts, strict=True), COMMAND_BASE):
        completion = 0x8000 | (opcode & 7) << 8 | (opcode >> 3) << 16 | command
        required_flags(result, dict(command=command, opcode=opcode, completion=completion,
                                     heldIrq=3, retiredStatus=1, retiredIrq=1))
        require(uint(result.get('heldStatus')) in (0x22, 0x23),
                'geometry feedback completion did not protect its VM while held')
        observed = uint(result.get('data'))
        require(data is None or observed == data, 'geometry feedback control flags changed')
