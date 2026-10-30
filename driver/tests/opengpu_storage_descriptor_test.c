#include <assert.h>
#include <string.h>
#include "../opengpu_storage_descriptor.h"

int main(void)
{
    struct opengpu_storage_descriptor d = {
        .context = 7, .pass = 11, .generation = 13, .binding = 3,
        .base = 0xffffff00u, .allocation_base = 0xffffff00u,
        .limit = 1ULL << 32, .allocation_limit = 1ULL << 32,
        .readable = true, .writable = true
    };
    u32 words[16], saved[16];
    assert(opengpu_encode_storage_descriptor(words, &d));
    assert(words[0] == 0x47534201u && words[1] == 64 && words[2] == 1);
    assert(words[3] == 7 && words[4] == 11 && words[5] == 13 && words[6] == 3);
    assert(words[7] == 1 && words[8] == 1 && words[9] == 1 && words[10] == d.base);
    assert(words[11] == 0 && words[12] == 1 && words[13] == d.allocation_base);
    assert(words[14] == 0 && words[15] == 1);
    memcpy(saved, words, sizeof(words));
    for (unsigned i = 0; i < 8; ++i) {
        struct opengpu_storage_descriptor bad = d;
        switch (i) {
        case 0: bad.binding = 4; break;
        case 1: bad.readable = bad.writable = false; break;
        case 2: bad.base += 1; break;
        case 3: bad.allocation_base += 1; break;
        case 4: bad.allocation_limit -= 1; break;
        case 5: bad.limit = bad.base; break;
        case 6: bad.limit += 1; break;
        default: bad.allocation_limit = bad.base; break;
        }
        assert(!opengpu_encode_storage_descriptor(words, &bad));
        assert(memcmp(words, saved, sizeof(words)) == 0);
    }
    assert(!opengpu_encode_storage_descriptor(0, &d));
    assert(!opengpu_encode_storage_descriptor(words, 0));
    d.base = d.allocation_base = 0;
    d.limit = d.allocation_limit = OPENGPU_STORAGE_DESCRIPTOR_MAX_BYTES;
    assert(opengpu_encode_storage_descriptor(words, &d));
    memcpy(saved, words, sizeof(words));
    d.limit += 1;
    d.allocation_limit += 64;
    assert(!opengpu_encode_storage_descriptor(words, &d));
    assert(memcmp(words, saved, sizeof(words)) == 0);
    return 0;
}
