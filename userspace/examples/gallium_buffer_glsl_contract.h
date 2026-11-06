/* SPDX-License-Identifier: MIT */
/* Arithmetic/result oracle only: no shader generator or instruction decoder. */
#ifndef OPENGPU_GALLIUM_BUFFER_GLSL_CONTRACT_H
#define OPENGPU_GALLIUM_BUFFER_GLSL_CONTRACT_H
#include <assert.h>
#include <stdint.h>
#include <string.h>
#define GB_VIEW_BYTES (128u*1024u*1024u)
#define GB_TEXTURE_BYTES (4u*1024u*1024u)
#define GB_PREFIX_BYTES 8192u
#define GB_TAIL_BYTES 64u
#define GB_REGION_WORDS ((GB_PREFIX_BYTES+GB_TAIL_BYTES)/4u)
#define GB_GUARD UINT32_C(0xdeadface)
#define GB_ITEMS 16u
#define GB_BOS 41u /* 40 independent baseline BOs plus one retained replacement. */
#define GB_PROGRAMS 6u
static const char *const gb_names[GB_PROGRAMS]={"producer","textures-0","textures-1","textures-2","images","fault"};
static const unsigned gb_texture_first[3]={0,11,22},gb_texture_count[3]={11,11,10},gb_texture_query[3]={0,11,31};
static unsigned gb_items=GB_ITEMS;
static uint32_t gb_local[3]={8,2,1};
static const uint32_t gb_grid[3]={1,1,1};
static inline int gb_select_items(unsigned items)
{
    if(items!=16 && items!=128) return 0;
    gb_items=items; gb_local[1]=items==16?2:4; gb_local[2]=items==16?1:4;
    return 1;
}
struct gb_model {
    uint32_t words[GB_BOS][GB_REGION_WORDS];
    unsigned count,epoch,ssbo[4],image[4],texture[32],image_offset[4];
};
static inline unsigned gb_bytes(unsigned bo) { return bo>=8 && bo<40?GB_TEXTURE_BYTES:GB_VIEW_BYTES; }
static inline unsigned gb_region_bytes(unsigned tail) { return tail?GB_TAIL_BYTES:GB_PREFIX_BYTES; }
static inline unsigned gb_region_offset(unsigned bo,unsigned tail) { return tail?gb_bytes(bo)-GB_TAIL_BYTES:0; }
static inline unsigned gb_slot(unsigned bo,unsigned byte)
{
    assert(!(byte&3) && byte<gb_bytes(bo));
    if(byte<GB_PREFIX_BYTES) return byte/4;
    assert(byte>=gb_bytes(bo)-GB_TAIL_BYTES);
    return GB_PREFIX_BYTES/4+(byte-(gb_bytes(bo)-GB_TAIL_BYTES))/4;
}
static inline uint32_t gb_read(const struct gb_model *m,unsigned bo,unsigned byte)
{ assert(bo<m->count); return m->words[bo][gb_slot(bo,byte)]; }
static inline void gb_write(struct gb_model *m,unsigned bo,unsigned byte,uint32_t value)
{ assert(bo<m->count); m->words[bo][gb_slot(bo,byte)]=value; }
static inline uint32_t gb_uniform(unsigned epoch,unsigned slot,unsigned word)
{ if(!slot && word==3) return (epoch==2?GB_TEXTURE_BYTES:GB_VIEW_BYTES)/4-1;
  return UINT32_C(0x31415926)^epoch*UINT32_C(0x1020301)^slot*UINT32_C(0x10001)^word*17u; }
static inline uint32_t gb_checksum(unsigned epoch)
{
    uint32_t a=gb_uniform(epoch,0,2)^gb_uniform(epoch,0,1023);
    for(unsigned b=1;b<=12;b++) a=a*33u^gb_uniform(epoch,b,0)^gb_uniform(epoch,b,4095);
    return a;
}
static inline unsigned gb_at(unsigned id,unsigned bytes) { return id==gb_items-1?bytes/4-1:id; }
static inline void gb_initialize(struct gb_model *m,unsigned epoch)
{
    memset(m,0,sizeof(*m)); m->count=40; m->epoch=epoch;
    for(unsigned bo=0;bo<40;bo++) for(unsigned w=0;w<GB_REGION_WORDS;w++) m->words[bo][w]=GB_GUARD;
    for(unsigned b=0;b<4;b++) { m->ssbo[b]=b; m->image[b]=4+b; }
    for(unsigned b=0;b<32;b++) m->texture[b]=8+b;
    if(epoch==2) m->ssbo[0]=39;
    for(unsigned bo=4;bo<40;bo++) for(unsigned id=0;id<gb_items;id++)
        gb_write(m,bo,4*gb_at(id,gb_bytes(bo)),UINT32_C(0x91820000)^epoch*65537u^bo*257u^id*17u);
}
static inline void gb_rebind(struct gb_model *m)
{
    /* The ordinary producer temporarily used the complete4MiB texture31 as
     * SSBO0; do not truncate a128MiB BO to fit a sampler window. Restore all
     * four128MiB SSBO views and swap producer SSBO1 into image0. */
    m->ssbo[0]=0; m->ssbo[1]=4; m->image[0]=1; m->image_offset[0]=64;
    m->count=41; m->image[3]=40;
    for(unsigned w=0;w<GB_REGION_WORDS;w++) m->words[40][w]=GB_GUARD;
    for(unsigned id=0;id<gb_items;id++) gb_write(m,40,4*gb_at(id,GB_VIEW_BYTES),
        UINT32_C(0x91820000)^m->epoch*65537u^7u*257u^id*17u);
}
static inline void gb_reference(struct gb_model *m,unsigned program)
{
    assert(program<5);
    for(unsigned id=0;id<gb_items;id++) {
        unsigned at=gb_at(id,GB_VIEW_BYTES); uint32_t a;
        if(program==0) {
            a=gb_checksum(m->epoch);
            for(unsigned b=0;b<4;b++) gb_write(m,m->ssbo[b],gb_at(id,gb_bytes(m->ssbo[b]))*4,a^id^(66051u*b));
        } else if(program>=1 && program<=3) {
            unsigned first=gb_texture_first[program-1],q=gb_texture_query[program-1];
            a=gb_bytes(m->texture[q])/4;
            for(unsigned t=first;t<first+gb_texture_count[program-1];t++) {
                unsigned bo=m->texture[t],ix=gb_at(id,gb_bytes(bo));
                a=a*33u^gb_read(m,bo,ix*4);
            }
            for(unsigned b=0;b<4;b++) gb_write(m,m->ssbo[b],at*4,gb_read(m,m->ssbo[b],at*4)^a^(66051u*b));
        } else {
            uint32_t value[4]; unsigned byte[4]; a=0;
            for(unsigned b=0;b<4;b++) {
                unsigned bytes=gb_bytes(m->image[b])-m->image_offset[b];
                byte[b]=m->image_offset[b]+gb_at(id,bytes)*4;
                value[b]=gb_read(m,m->image[b],byte[b]); a=a*33u^value[b]^(bytes/4);
            }
            for(unsigned b=0;b<4;b++) gb_write(m,m->image[b],byte[b],value[b]^id^(0x40000u+b));
            for(unsigned b=0;b<4;b++) gb_write(m,m->ssbo[b],at*4,gb_read(m,m->ssbo[b],at*4)^a^(0x20000u+b));
        }
    }
}
#endif
