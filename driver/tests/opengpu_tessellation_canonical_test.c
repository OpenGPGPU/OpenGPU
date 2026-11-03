#include "../opengpu_tessellation_job_validate.h"
#include <assert.h>
#include <string.h>

static void put(unsigned char *bytes, unsigned int word, unsigned int value)
{
    unsigned int i;
    for (i = 0; i < 4; ++i) bytes[word * 4u + i] = (unsigned char)(value >> (8u * i));
}

static void exercise(const unsigned char *rules)
{
    unsigned char storage[OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES + 1];
    unsigned char *bytes = storage + 1; /* Test unaligned CPU snapshots. */
    unsigned int values[OPENGPU_NATIVE_TESSELLATION_SEQUENCE_WORDS];
    unsigned int i;

    memset(storage, 0, sizeof(storage));
    for (i = 0; i < OPENGPU_NATIVE_TESSELLATION_SEQUENCE_WORDS; ++i) {
        unsigned int bits = rules[i] & 0x7fu;
        unsigned int value = 0x9e3779b9u * (i + 1u);
        if (!bits) value = 0;
        else if (bits < 32u) {
            value &= (1u << bits) - 1u;
            if ((rules[i] & 0x80u) && (value & (1u << (bits - 1u))))
                value |= ~((1u << bits) - 1u);
        }
        values[i] = value;
        put(bytes, i, value);
    }
    assert(opengpu_tessellation_canonical_words(bytes, sizeof(storage) - 1, rules));
    assert(!opengpu_tessellation_canonical_words(bytes, sizeof(storage) - 2, rules));
    assert(!opengpu_tessellation_canonical_words(NULL, sizeof(storage) - 1, rules));
    assert(!opengpu_tessellation_canonical_words(bytes, sizeof(storage) - 1, NULL));
    for (i = 0; i < OPENGPU_NATIVE_TESSELLATION_SEQUENCE_WORDS; ++i) {
        unsigned int bits = rules[i] & 0x7fu;
        if (bits == 32u) continue;
        put(bytes, i, values[i] ^ (1u << bits));
        assert(!opengpu_tessellation_canonical_words(bytes, sizeof(storage) - 1, rules));
        put(bytes, i, values[i]);
    }
    assert(opengpu_tessellation_canonical_words(bytes, sizeof(storage) - 1, rules));
}

int main(void)
{
    unsigned char signed_rules[OPENGPU_NATIVE_TESSELLATION_SEQUENCE_WORDS];
    exercise(opengpu_tessellation_frame_word_rules);
    exercise(opengpu_tessellation_isoline_word_rules);
    exercise(opengpu_tessellation_triangle_word_rules);
    exercise(opengpu_tessellation_quad_word_rules);
    memcpy(signed_rules, opengpu_tessellation_frame_word_rules, sizeof(signed_rules));
    signed_rules[100] = 0x80u | 5u;
    exercise(signed_rules);
    return 0;
}
