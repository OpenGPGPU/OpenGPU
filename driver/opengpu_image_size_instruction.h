/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_IMAGE_SIZE_INSTRUCTION_H
#define OPENGPU_IMAGE_SIZE_INSTRUCTION_H

/* Internal buffer imageSize, CUSTOM1 function 27. Operands are vector unit
 * indices and target/kind/optional-format controls. No captured parameter
 * tuples are consumed. This encoder does not establish hardware support. */
static inline int opengpu_buffer_image_size_instruction(unsigned *out,
    unsigned destination, unsigned units, unsigned controls, unsigned unmasked)
{
    if (!out || destination >= 32 || units >= 32 || controls >= 32 || unmasked > 1)
        return 0;
    *out = (27u << 26) | (unmasked << 25) | (controls << 20) |
        (units << 15) | (destination << 7) | 0x2bu;
    return 1;
}
#endif
