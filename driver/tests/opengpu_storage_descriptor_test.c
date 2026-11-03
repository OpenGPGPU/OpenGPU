#include <assert.h>
#include <string.h>
#include "../opengpu_storage_descriptor.h"
#include "../opengpu_storage_slot.h"

int main(void)
{
    unsigned index;
    for (unsigned i=0; i<4; ++i) {
        assert(opengpu_storage_slot_index(13+i,&index) && index==i);
        assert(opengpu_storage_slot_index(35+i,&index) && index==i);
    }
    assert(!opengpu_storage_slot_index(12,&index));
    assert(!opengpu_storage_slot_index(34,&index));
    assert(!opengpu_storage_slot_index(39,&index));
    assert(!opengpu_storage_slot_index(35,0));
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
    {
        struct opengpu_storage_descriptor counter = {
            .context=7, .binding=0, .base=0xffffffdcu,
            .limit=0xfffffffcULL, .allocation_base=0xffffffc0u,
            .allocation_limit=1ULL<<32, .readable=true, .writable=true
        };
        assert(opengpu_encode_atomic_counter_descriptor(words, &counter));
        assert(words[10]==counter.base && words[11]==0xfffffffcu && words[12]==0);
        assert(words[13]==0xffffffc0u && words[14]==0 && words[15]==1);
        assert(!opengpu_encode_storage_descriptor(saved, &counter)); /* storage still needs 256-byte alignment */
        memcpy(saved,words,sizeof(words));
        for (unsigned i=0;i<9;i++) {
            struct opengpu_storage_descriptor bad=counter;
            switch(i) {
            case 0: bad.binding=1; break;
            case 1: bad.readable=false; break;
            case 2: bad.writable=false; break;
            case 3: bad.base++; break;
            case 4: bad.limit++; break;
            case 5: bad.limit=bad.base; break;
            case 6: bad.base-=4; break;
            case 7: bad.allocation_base++; break;
            default: bad.allocation_limit-=64; break;
            }
            assert(!opengpu_encode_atomic_counter_descriptor(words,&bad));
            assert(!memcmp(words,saved,sizeof(words)));
        }
        assert(!opengpu_encode_atomic_counter_descriptor(NULL,&counter));
        assert(!opengpu_encode_atomic_counter_descriptor(words,NULL));
    }
    return 0;
}
