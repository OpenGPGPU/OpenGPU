#include "../opengpu_tessellation_job_validate.h"
#include "../opengpu_typed_fragment_state_abi.h"
#include "../opengpu_native_texture_abi.h"
#include "../opengpu_uniform_abi.h"
#include <assert.h>
#include <string.h>

_Static_assert(OPENGPU_TESS_TEXTURE_ROOT_BYTES == OPENGPU_NATIVE_TEXTURE_ROOT_BYTES &&
    OPENGPU_TESS_UNIFORM_ROOT_BYTES == OPENGPU_UNIFORM_ROOT_BYTES, "shader root sizes");
_Static_assert(OPENGPU_TESS_STATE_SHADER_PC_WORD == OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_SHADERPC_WORD,
    "fragment entry PC word position");
_Static_assert(OPENGPU_TESS_STATE_TEXTURE_VERSION_WORD == OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_RESOURCES_VERSION_WORD &&
    OPENGPU_TESS_STATE_TEXTURE_ADDRESS_WORD == OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_RESOURCES_ADDRESS_WORD &&
    OPENGPU_TESS_STATE_UNIFORM_VERSION_WORD == OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_UNIFORMS_VERSION_WORD &&
    OPENGPU_TESS_STATE_UNIFORM_ADDRESS_WORD == OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_UNIFORMS_ADDRESS_WORD,
    "fragment resource root word positions");

static void put(unsigned char *bytes, unsigned int word, unsigned int value)
{
    unsigned int i;
    for (i = 0; i < 4; ++i) bytes[word * 4u + i] = (unsigned char)(value >> (8u * i));
}

static void exercise(const unsigned char *rules, unsigned int count)
{
    unsigned char storage[OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES + 1];
    unsigned char *bytes = storage + 1; /* Test unaligned CPU snapshots. */
    unsigned int values[OPENGPU_NATIVE_TESSELLATION_SEQUENCE_WORDS];
    unsigned int i;

    memset(storage, 0, sizeof(storage));
    for (i = 0; i < count; ++i) {
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
    assert(opengpu_tessellation_canonical_words_n(bytes, count * 4u, rules, count));
    assert(!opengpu_tessellation_canonical_words_n(bytes, count * 4u - 1u, rules, count));
    assert(!opengpu_tessellation_canonical_words_n(NULL, count * 4u, rules, count));
    assert(!opengpu_tessellation_canonical_words_n(bytes, count * 4u, NULL, count));
    for (i = 0; i < count; ++i) {
        unsigned int bits = rules[i] & 0x7fu;
        if (bits == 32u) continue;
        put(bytes, i, values[i] ^ (1u << bits));
        assert(!opengpu_tessellation_canonical_words_n(bytes, count * 4u, rules, count));
        put(bytes, i, values[i]);
    }
    assert(opengpu_tessellation_canonical_words_n(bytes, count * 4u, rules, count));
}

int main(void)
{
    unsigned char signed_rules[OPENGPU_NATIVE_TESSELLATION_SEQUENCE_WORDS];
    exercise(opengpu_tessellation_frame_word_rules, 512);
    exercise(opengpu_tessellation_isoline_word_rules, 512);
    exercise(opengpu_tessellation_triangle_word_rules, 512);
    exercise(opengpu_tessellation_quad_word_rules, 512);
    exercise(opengpu_tessellation_fragment_state_word_rules, 128);
    memcpy(signed_rules, opengpu_tessellation_frame_word_rules, sizeof(signed_rules));
    signed_rules[100] = 0x80u | 5u;
    exercise(signed_rules, 512);
    return 0;
}
