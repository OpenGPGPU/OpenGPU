#include <assert.h>
#include <string.h>
#include "../opengpu_compute_indirect_source.h"
#include "../opengpu_compute_uniform_records.h"
static void counts(unsigned char b[12],u32 x,u32 y,u32 z)
{
    u32 g[3]={x,y,z};
    for(unsigned i=0;i<3;i++)for(unsigned j=0;j<4;j++)b[i*4+j]=(unsigned char)(g[i]>>(8*j));
}
static void indirect_boundaries(void)
{
    struct opengpu_indirect_source_plan p={77,88},saved=p;
    assert(!opengpu_compute_indirect_source_plan(0xffffe000ull,8192,4092,&p));
    assert(p.offset==4092&&p.bytes==8192);
    assert(!opengpu_compute_indirect_source_plan(0xffffe000ull,8192,8192-12,&p));
    assert(p.offset==4084&&p.bytes==4096);
    assert(!opengpu_compute_indirect_source_plan(0x10000,8192,4096,&p));
    assert(!p.offset&&p.bytes==4096);
    p=saved;
    assert(opengpu_compute_indirect_source_plan(0xfffff000ull,8192,0,&p)==-34);
    assert(opengpu_compute_indirect_source_plan(0x10000,8192,8192-8,&p)==-22);
    assert(opengpu_compute_indirect_source_plan(0x10000,8192,~0ull-3,&p)==-22);
    assert(opengpu_compute_indirect_source_plan(0x10000,8192,2,&p)==-22);
    assert(opengpu_compute_indirect_source_plan(0x10004,8192,0,&p)==-22);
    assert(!memcmp(&p,&saved,sizeof(p)));
    unsigned char b[12];u32 max[3]={0xffffffffu,0xffffffffu,0xffffffffu},g[3]={77,88,99};
    opengpu_kernarg_u64 total=123;
    counts(b,65535,65535,65535);
    assert(!opengpu_compute_indirect_counts(b,max,g,&total)&&total==281462092005375ull);
    counts(b,0xffffffffu,1,1);
    assert(!opengpu_compute_indirect_counts(b,max,g,&total)&&g[0]==0xffffffffu&&total==0xffffffffu);
    max[0]=0x7fffffffu;
    assert(opengpu_compute_indirect_counts(b,max,g,&total)==-34&&g[0]==0xffffffffu);
    counts(b,0,0xffffffffu,0xffffffffu);
    assert(!opengpu_compute_indirect_counts(b,max,g,&total)&&!total&&g[1]==0xffffffffu);
    max[0]=0;
    assert(opengpu_compute_indirect_counts(b,max,g,&total)==-22);
    max[0]=0xffffffffu;counts(b,0xffffffffu,0xffffffffu,0xffffffffu);
    assert(opengpu_compute_indirect_counts(b,max,g,&total)==-34&&!total&&!g[0]);
}
int main(void) {
    indirect_boundaries();
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
