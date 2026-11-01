/* Experimental GNZ1 decoding; this does not advertise a tessellation capability. */
#ifndef OPENGPU_TESSELLATION_COMPLETION_H
#define OPENGPU_TESSELLATION_COMPLETION_H
#include <stddef.h>
#include <stdint.h>
#include "opengpu_native_tessellation_completion_abi.h"

struct opengpu_tessellation_completion {
    uint32_t status, reader_stage, image_stage, input_fault_valid, tile_valid;
    uint32_t reader_address, reader_draw, tile;
    uint32_t frame_status, image_status, source_status, bins_status;
    uint32_t producer_status, bin_producer_status;
    uint32_t context, pass, generation, input_address, input_task;
};

/* words are host-order MMIO register values read while the completion is held.
 * Reject unknown/reserved encodings and leave *out unchanged on failure. Reader
 * address/draw are raw owner fields; input and tile fields require valid bits. */
static inline int opengpu_tessellation_completion_decode(
    const uint32_t *words, size_t count, struct opengpu_tessellation_completion *out)
{
    struct opengpu_tessellation_completion decoded;
    if (!words || !out || count < OPENGPU_NATIVE_TESSELLATION_COMPLETION_WORDS)
        return 0;
    if (words[OPENGPU_NATIVE_TESSELLATION_COMPLETION_MAGIC_WORD] != OPENGPU_NATIVE_TESSELLATION_COMPLETION_MAGIC ||
        (words[OPENGPU_NATIVE_TESSELLATION_COMPLETION_STATUS_WORD] & ~(OPENGPU_NATIVE_TESSELLATION_COMPLETION_STATUS_MASK |
                      OPENGPU_NATIVE_TESSELLATION_COMPLETION_READER_STAGE_MASK |
                      OPENGPU_NATIVE_TESSELLATION_COMPLETION_IMAGE_STAGE_MASK |
                      OPENGPU_NATIVE_TESSELLATION_COMPLETION_INPUT_FAULT_VALID_MASK |
                      OPENGPU_NATIVE_TESSELLATION_COMPLETION_TILE_VALID_MASK)) ||
        (words[OPENGPU_NATIVE_TESSELLATION_COMPLETION_FRAME_STATUS_WORD] & ~(OPENGPU_NATIVE_TESSELLATION_COMPLETION_FRAME_STATUS_MASK |
                      OPENGPU_NATIVE_TESSELLATION_COMPLETION_IMAGE_STATUS_MASK |
                      OPENGPU_NATIVE_TESSELLATION_COMPLETION_SOURCE_STATUS_MASK |
                      OPENGPU_NATIVE_TESSELLATION_COMPLETION_BINS_STATUS_MASK |
                      OPENGPU_NATIVE_TESSELLATION_COMPLETION_PRODUCER_STATUS_MASK |
                      OPENGPU_NATIVE_TESSELLATION_COMPLETION_BIN_PRODUCER_STATUS_MASK)) ||
        words[OPENGPU_NATIVE_TESSELLATION_COMPLETION_RESERVED_WORD] ||
        (!(words[OPENGPU_NATIVE_TESSELLATION_COMPLETION_TILE_VALID_WORD] & OPENGPU_NATIVE_TESSELLATION_COMPLETION_TILE_VALID_MASK) &&
         words[OPENGPU_NATIVE_TESSELLATION_COMPLETION_TILE_WORD]) ||
        (!(words[OPENGPU_NATIVE_TESSELLATION_COMPLETION_INPUT_FAULT_VALID_WORD] & OPENGPU_NATIVE_TESSELLATION_COMPLETION_INPUT_FAULT_VALID_MASK) &&
         (words[OPENGPU_NATIVE_TESSELLATION_COMPLETION_INPUT_ADDRESS_WORD] || words[OPENGPU_NATIVE_TESSELLATION_COMPLETION_INPUT_TASK_WORD])))
        return 0;
#define GNZ1_FIELD(name) ((words[OPENGPU_NATIVE_TESSELLATION_COMPLETION_##name##_WORD] & \
                           OPENGPU_NATIVE_TESSELLATION_COMPLETION_##name##_MASK) >> \
                          OPENGPU_NATIVE_TESSELLATION_COMPLETION_##name##_SHIFT)
    decoded.status=GNZ1_FIELD(STATUS); decoded.reader_stage=GNZ1_FIELD(READER_STAGE);
    decoded.image_stage=GNZ1_FIELD(IMAGE_STAGE); decoded.input_fault_valid=GNZ1_FIELD(INPUT_FAULT_VALID);
    decoded.tile_valid=GNZ1_FIELD(TILE_VALID); decoded.reader_address=GNZ1_FIELD(READER_ADDRESS);
    decoded.reader_draw=GNZ1_FIELD(READER_DRAW); decoded.tile=GNZ1_FIELD(TILE);
    decoded.frame_status=GNZ1_FIELD(FRAME_STATUS); decoded.image_status=GNZ1_FIELD(IMAGE_STATUS);
    decoded.source_status=GNZ1_FIELD(SOURCE_STATUS); decoded.bins_status=GNZ1_FIELD(BINS_STATUS);
    decoded.producer_status=GNZ1_FIELD(PRODUCER_STATUS); decoded.bin_producer_status=GNZ1_FIELD(BIN_PRODUCER_STATUS);
    decoded.context=GNZ1_FIELD(CONTEXT); decoded.pass=GNZ1_FIELD(PASS); decoded.generation=GNZ1_FIELD(GENERATION);
    decoded.input_address=GNZ1_FIELD(INPUT_ADDRESS); decoded.input_task=GNZ1_FIELD(INPUT_TASK);
#undef GNZ1_FIELD
    *out=decoded;
    return 1;
}
#endif
