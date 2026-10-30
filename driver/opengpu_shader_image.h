/* GIU1 descriptor serialization. Callers still own publication and VM lifetime. */
#ifndef OPENGPU_SHADER_IMAGE_H
#define OPENGPU_SHADER_IMAGE_H
#include "opengpu_shader_image_abi.h"
#include <stddef.h>
#include <string.h>

struct opengpu_shader_image {
    uint8_t bytes[OPENGPU_SHADER_IMAGE_BYTES];
};

static inline uint32_t opengpu_image_read_word(const struct opengpu_shader_image *d,
                                              unsigned word)
{
    const uint8_t *p = d->bytes + word * 4u;
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 |
           (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}

static inline void opengpu_image_write_word(struct opengpu_shader_image *d,
                                            unsigned word, uint32_t value)
{
    uint8_t *p = d->bytes + word * 4u;
    unsigned i;
    for (i = 0; i < 4; ++i)
        p[i] = (uint8_t)(value >> (i * 8));
}

/* Reject overflow before touching any byte. High halves, headers and reserved
 * words are not independently writable fields. Values are never truncated. */
static inline int opengpu_shader_image_set(struct opengpu_shader_image *d,
                                          unsigned word, uint64_t value)
{
    unsigned bits = opengpu_shader_image_field_bits(word);
    if (!d || !bits || value >> bits)
        return 0;
    opengpu_image_write_word(d, word, (uint32_t)value);
    if (bits > 32)
        opengpu_image_write_word(d, word + 1u, (uint32_t)(value >> 32));
    return 1;
}

static inline int opengpu_shader_image_get(const struct opengpu_shader_image *d,
                                          unsigned word, uint64_t *value)
{
    unsigned bits = opengpu_shader_image_field_bits(word);
    uint64_t result;
    if (!d || !value || !bits)
        return 0;
    result = opengpu_image_read_word(d, word);
    if (bits > 32)
        result |= (uint64_t)opengpu_image_read_word(d, word + 1u) << 32;
    if (result >> bits)
        return 0;
    *value = result;
    return 1;
}

static inline int opengpu_shader_image_init(struct opengpu_shader_image *d,
    uint32_t context, uint32_t pass, uint32_t generation, uint32_t unit)
{
    if (!d)
        return 0;
    memset(d, 0, sizeof(*d));
    opengpu_image_write_word(d, 0, OPENGPU_SHADER_IMAGE_MAGIC);
    opengpu_image_write_word(d, 1, OPENGPU_SHADER_IMAGE_BYTES);
    opengpu_image_write_word(d, 2, OPENGPU_SHADER_IMAGE_VERSION);
    opengpu_shader_image_set(d, OPENGPU_SHADER_IMAGE_IDENTITY_CONTEXT_WORD, context);
    opengpu_shader_image_set(d, OPENGPU_SHADER_IMAGE_IDENTITY_PASS_WORD, pass);
    opengpu_shader_image_set(d, OPENGPU_SHADER_IMAGE_IDENTITY_GENERATION_WORD, generation);
    opengpu_shader_image_set(d, OPENGPU_SHADER_IMAGE_UNITINDEX_WORD, unit);
    return 1;
}

/* Structural validation only: permissions, format compatibility, level spans
 * and coordinates remain the checked image-access path's responsibility. */
static inline int opengpu_shader_image_encoding_valid(const struct opengpu_shader_image *d)
{
    unsigned word;
    if (!d || opengpu_image_read_word(d, 0) != OPENGPU_SHADER_IMAGE_MAGIC ||
        opengpu_image_read_word(d, 1) != OPENGPU_SHADER_IMAGE_BYTES ||
        opengpu_image_read_word(d, 2) != OPENGPU_SHADER_IMAGE_VERSION)
        return 0;
    for (word = 3; word < OPENGPU_SHADER_IMAGE_WORDS; ++word)
        if (opengpu_image_read_word(d, word) & ~opengpu_shader_image_word_mask(word))
            return 0;
    return 1;
}

/* Resolve one complete record in a version-1 table without wrapping at 4 GiB.
 * image_units is the selected hardware profile, not a capability assertion. */
static inline int opengpu_shader_image_record_address(uint32_t version,
    uint32_t base, uint64_t limit, uint32_t unit, uint32_t image_units, uint32_t *address)
{
    uint64_t begin = (uint64_t)base + (uint64_t)unit * OPENGPU_SHADER_IMAGE_BYTES;
    if (!address || version != 1 || image_units < 4 || unit >= image_units ||
        (base & 63u) || (limit & 63u) || limit <= base || limit > (UINT64_C(1) << 32) ||
        (limit - base) % OPENGPU_SHADER_IMAGE_BYTES || begin + OPENGPU_SHADER_IMAGE_BYTES > limit)
        return 0;
    *address = (uint32_t)begin;
    return 1;
}
#endif
