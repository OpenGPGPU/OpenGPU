#include "opengpu_shader_image.h"
#include "opengpu_texture_image_format_abi.h"
#include <assert.h>
#include <limits.h>
#include <stdio.h>

static void exact_record(void)
{
    /* Independent GIU1 word oracle: one complete writable RGBA32UI image,
     * with its exclusive allocation limit exactly at the 4 GiB boundary. */
    const uint32_t words[64] = {
        0x47495501, 256, 1, 0x11223344, 11, 13, 3,
        1, 1, 4, 1, 1, 26, 26, 1, 0, 0, 15, 1, 0, 0, 0,
        0xfffff000, 0, 1, 0xfffff03f, 8, 1, 1, 1, 128, 0, 0, 0, 0, 0, 0, 16
    };
    struct opengpu_shader_image d;
    unsigned i;
    uint64_t value = 0;
    assert(opengpu_shader_image_init(&d, 0x11223344, 11, 13, 3));
#define SET(field, value) assert(opengpu_shader_image_set(&d, OPENGPU_SHADER_IMAGE_##field##_WORD, value))
    SET(BOUND, 1); SET(COMPLETE, 1); SET(STAGEMASK, 4);
    SET(READABLE, 1); SET(WRITABLE, 1);
    SET(STORAGEFORMAT, OPENGPU_TEXTURE_FORMAT_RGBA32UI);
    SET(IMAGEFORMAT, OPENGPU_TEXTURE_FORMAT_RGBA32UI);
    SET(COMPATIBLEBYSIZE, 1); SET(MAXLEVEL, 15); SET(LAYERED, 1);
    SET(VIEW_ALLOCATIONBASE, 0xfffff000);
    SET(VIEW_ALLOCATIONLIMIT, UINT64_C(1) << 32);
    SET(VIEW_BASE, 0xfffff03f); SET(VIEW_TEXWIDTH, 8); SET(VIEW_TEXHEIGHT, 1);
    SET(VIEW_TEXDEPTH, 1); SET(VIEW_LAYERS, 1); SET(VIEW_ROWPITCH, 128); SET(VIEW_BLOCKBYTES, 16);
#undef SET
    assert(sizeof(d) == 256 && opengpu_shader_image_encoding_valid(&d));
    for (i = 0; i < 256; ++i)
        assert(d.bytes[i] == (uint8_t)(words[i / 4] >> ((i % 4) * 8)));
    assert(opengpu_shader_image_get(&d, 23, &value) && value == (UINT64_C(1) << 32));
}

static void field_and_padding_checks(void)
{
    /* Widths are specified independently of the generated metadata. A zero
     * denotes a header, reserved word, or the continuation of a wide field. */
    const unsigned widths[64] = {
        0, 0, 0, 32, 32, 32, 32, 1, 1, 6, 1, 1, 6, 6, 1, 32, 32, 32, 1, 32,
        3, 4, 32, 33, 0, 32, 14, 14, 14, 16, 33, 0, 33, 0, 33, 0, 1, 5
    };
    struct opengpu_shader_image d, saved;
    unsigned word, bit, fields = 0;
    uint64_t value;
    assert(opengpu_shader_image_init(&d, 0, 0, 0, 0));
    for (word = 0; word < 64; ++word) {
        saved = d;
        assert(opengpu_shader_image_field_bits(word) == widths[word]);
        if (widths[word]) {
            uint64_t max = (UINT64_C(1) << widths[word]) - 1;
            assert(opengpu_shader_image_set(&d, word, max));
            assert(opengpu_shader_image_get(&d, word, &value) && value == max);
            assert(opengpu_shader_image_encoding_valid(&d));
            saved = d;
            assert(!opengpu_shader_image_set(&d, word, max + 1));
            assert(memcmp(&d, &saved, sizeof(d)) == 0);
            assert(!opengpu_shader_image_set(&d, word, UINT64_MAX));
            assert(memcmp(&d, &saved, sizeof(d)) == 0);
            assert(opengpu_shader_image_set(&d, word, 0));
            ++fields;
        } else {
            assert(!opengpu_shader_image_set(&d, word, 0));
            assert(memcmp(&d, &saved, sizeof(d)) == 0);
            value = 42;
            assert(!opengpu_shader_image_get(&d, word, &value) && value == 42);
        }
    }
    assert(fields == 31);
    assert(!opengpu_shader_image_set(&d, UINT_MAX, 0));
    assert(!opengpu_shader_image_set(NULL, 3, 0));
    assert(!opengpu_shader_image_get(NULL, 3, &value));
    assert(!opengpu_shader_image_get(&d, 3, NULL));
    assert(!opengpu_shader_image_init(NULL, 0, 0, 0, 0));
    assert(!opengpu_shader_image_encoding_valid(NULL));
    /* Flip every one of the 2,048 wire bits independently. Header bits and
     * padding must reject, while defined payload bits remain structurally valid. */
    for (word = 0; word < 64; ++word) for (bit = 0; bit < 32; ++bit) {
        unsigned allowed = widths[word] > bit;
        if (word && widths[word - 1] == 33 && bit == 0)
            allowed = 1;
        assert(opengpu_shader_image_init(&d, 0, 0, 0, 0));
        d.bytes[word * 4 + bit / 8] ^= (uint8_t)(1u << (bit % 8));
        assert(opengpu_shader_image_encoding_valid(&d) == (word >= 3 && allowed));
    }
    assert(opengpu_shader_image_init(&d, 0, 0, 0, 0));
    d.bytes[24 * 4] = 2; /* bit 33 is outside the wide field */
    value = 42;
    assert(!opengpu_shader_image_get(&d, 23, &value) && value == 42);
}

static void table_bounds(void)
{
    uint32_t address, unit;
    const uint64_t end = UINT64_C(1) << 32;
    for (unit = 0; unit < 4; ++unit) {
        assert(opengpu_shader_image_record_address(1, 0xfffffc00, end, unit, 4, &address));
        assert(address == 0xfffffc00u + unit * 256u);
    }
    /* The hardware requires 64-byte alignment, not 256-byte alignment. */
    assert(opengpu_shader_image_record_address(1, 64, 1088, 3, 4, &address) && address == 832);
#define REJECT(version, base, limit, unit, count) do { \
    address = 0x12345678; \
    assert(!opengpu_shader_image_record_address(version, base, limit, unit, count, &address)); \
    assert(address == 0x12345678); \
} while (0)
    REJECT(0, 0, 1024, 0, 4); REJECT(2, 0, 1024, 0, 4);
    REJECT(1, 0, 1024, 4, 4); REJECT(1, 0, end, UINT32_MAX, UINT32_MAX);
    REJECT(1, 0, end, UINT32_MAX - 1, UINT32_MAX);
    REJECT(1, 1, 1025, 0, 4); REJECT(1, 0, 1025, 0, 4);
    REJECT(1, 0, 1088, 0, 4); REJECT(1, 1024, 1024, 0, 4);
    REJECT(1, 1024, 0, 0, 4); REJECT(1, 0, end + 256, 0, 4);
    REJECT(1, 0xffffff40, end, 0, 4); REJECT(1, 0xfffffc00, end - 256, 3, 4);
    REJECT(1, 0, 1024, 0, 3);
#undef REJECT
    assert(!opengpu_shader_image_record_address(1, 0, 1024, 0, 4, NULL));
}

int main(void)
{
    exact_record(); field_and_padding_checks(); table_bounds();
    puts("GIU1: exact 256-byte record, 31 fields, 2048 bit mutations and 4GiB table bounds PASS");
    return 0;
}
