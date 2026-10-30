#include <assert.h>
#include <string.h>
#include "../opengpu_compute_uniform_records.h"
int main(void) {
    struct opengpu_compute_uniform_root_config r={.context=7,.pass=11,.generation=13,.active_mask=0xfff,
        .bindings_base=0x2000,.bindings_limit=0x2600,.default_present=true,.default_view=0x2800};
    struct opengpu_compute_uniform_binding_config b={.context=7,.pass=11,.generation=13,.index=3,
        .base=0xffffc000u,.limit=1ULL<<32,.allocation_base=0xffffc000u,.allocation_limit=1ULL<<32};
    u32 words[16],saved[16];for(unsigned i=0;i<12;++i)r.mapping[i]=i;
    assert(opengpu_encode_compute_uniform_root(words,&r));
    assert(words[0]==0x47555201u && words[6]==2 && words[7]==0xfff && words[10]==0x2000 && words[11]==0x2600);
    memcpy(saved,words,sizeof(words));r.mapping[3]=24;assert(!opengpu_encode_compute_uniform_root(words,&r));
    assert(!memcmp(saved,words,sizeof(words)));r.mapping[3]=3;r.active_mask=0;assert(!opengpu_encode_compute_uniform_root(words,&r));
    assert(!memcmp(saved,words,sizeof(words)));
    assert(opengpu_encode_compute_uniform_binding(words,&b));
    assert(words[0]==0x47554201u && words[6]==3 && words[7]==4 && words[8]==0xffffc000u && words[9]==0 && words[10]==1);
    assert(words[11]==0xffffc000u && words[12]==0 && words[13]==1 && words[14]==1 && words[15]==0);
    memcpy(saved,words,sizeof(words));b.base=b.allocation_base=0x10000;b.limit=b.base+16385ULL;b.allocation_limit=0x14100;assert(!opengpu_encode_compute_uniform_binding(words,&b));
    assert(!memcmp(saved,words,sizeof(words)));b.index=31;b.base=0xfffff000u;b.limit=1ULL<<32;
    b.allocation_base=b.base;b.allocation_limit=1ULL<<32;assert(opengpu_encode_compute_uniform_binding(words,&b));
    memcpy(saved,words,sizeof(words));
    b.allocation_limit=(1ULL<<32)-1;assert(!opengpu_encode_compute_uniform_binding(words,&b));
    assert(!memcmp(saved,words,sizeof(words)));
    b.allocation_limit=1ULL<<32;b.index=24;assert(!opengpu_encode_compute_uniform_binding(words,&b));
    assert(!memcmp(saved,words,sizeof(words)));
    b.index=31;b.base=0xffffefffu;assert(!opengpu_encode_compute_uniform_binding(words,&b));
    assert(!memcmp(saved,words,sizeof(words)));
    r.active_mask=1;for(unsigned i=0;i<12;++i)r.mapping[i]=0;
    r.bindings_base=0xfffffa00u;r.bindings_limit=1ULL<<32;r.default_view=0xffffffc0u;
    assert(opengpu_encode_compute_uniform_root(words,&r));
    assert(words[11]==0 && words[12]==1 && words[13]==0xffffffc0u);
    memcpy(saved,words,sizeof(words));r.bindings_base=0xfffffa40u;
    assert(!opengpu_encode_compute_uniform_root(words,&r));
    assert(!memcmp(saved,words,sizeof(words)));
    r.bindings_base=0xfffffa00u;r.default_view=0xffffffc4u;
    assert(!opengpu_encode_compute_uniform_root(words,&r));
    assert(!memcmp(saved,words,sizeof(words)));
    return 0;
}
