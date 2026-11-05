/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_UNIFORM_COUNTER_SOURCE_PROGRAM_H
#define OPENGPU_UNIFORM_COUNTER_SOURCE_PROGRAM_H
#include <stdbool.h>
#include <stdint.h>

/* Explicit local Linux qualification, not compiler-generated GLES bytecode.
 * Every memory access outside kernarg uses a retained checked resource view.
 * Result planes use SSBO0; x2-derived offsets never authorize ordinary loads. */
enum { OPG_FULL_PLANES=40, OPG_GROUP_ITEMS=128, OPG_RESULT_BASE=65536,
       OPG_GROUP_STRIDE=65536, OPG_PLANE_BYTES=512, OPG_STORAGE_BYTES=134217728 };
struct opg_code { uint32_t *words; unsigned count; bool overflow; };
static inline void opg_emit(struct opg_code *p,uint32_t word)
{
    if(p->count>=512){p->overflow=true;return;}
    p->words[p->count++]=word;
}
static inline uint32_t opg_addi(unsigned rd,unsigned rs,int n)
{
    return ((uint32_t)n&4095)<<20|rs<<15|rd<<7|0x13u;
}
static inline void opg_const(struct opg_code *p,unsigned rd,uint32_t n)
{
    uint32_t hi=(uint32_t)(((uint64_t)n+2048)>>12);
    int lo=(int)((int64_t)n-((int64_t)hi<<12));
    if(hi){opg_emit(p,(hi&0xfffffu)<<12|rd<<7|0x37u);opg_emit(p,opg_addi(rd,rd,lo));}
    else opg_emit(p,opg_addi(rd,0,lo));
}
static inline uint32_t opg_v(unsigned fn,unsigned form,unsigned rd,unsigned a,unsigned b)
{
    return fn<<26|1u<<25|b<<20|a<<15|form<<12|rd<<7|0x57u;
}
static inline uint32_t opg_r(unsigned fn,unsigned rd,unsigned a,unsigned b,unsigned pair)
{
    return fn<<26|1u<<25|b<<20|a<<15|pair<<12|rd<<7|0x2bu;
}
static inline void opg_splat(struct opg_code *p,unsigned rd,unsigned rs)
{
    opg_emit(p,opg_v(23,4,rd,rs,0));
}
static inline void opg_result(struct opg_code *p)
{
    opg_emit(p,opg_r(14,0,10,2,0)); /* captured v10 data */
    opg_emit(p,opg_r(16,0,27,28,0)); /* checked SSBO0 per-item offset */
    opg_emit(p,opg_v(0,4,28,12,28));
}
static inline unsigned opg_full_program(uint32_t words[512],bool fault)
{
    struct opg_code p={words,0,false};
    opg_emit(&p,0xc1087057u); /* VL16, all eight resident warps */
    if(fault){
        opg_const(&p,14,31);opg_splat(&p,3,14);opg_const(&p,15,4096);opg_splat(&p,4,15);
        opg_emit(&p,opg_r(7,30,3,4,0));opg_emit(&p,0x30500073u);
        return p.overflow?0:p.count;
    }
    opg_const(&p,12,OPG_PLANE_BYTES);
    opg_emit(&p,16u<<20|2u<<15|1u<<12|14u<<7|0x13u);
    opg_const(&p,15,OPG_RESULT_BASE);
    opg_emit(&p,15u<<20|14u<<15|14u<<7|0x33u);opg_splat(&p,28,14);
    opg_emit(&p,opg_v(0x25,3,6,2,1));opg_emit(&p,opg_v(0,0,28,6,28));
    opg_emit(&p,opg_v(23,3,27,0,0));opg_emit(&p,opg_v(23,3,2,0,0));
    opg_emit(&p,opg_v(23,0,10,1,0));opg_result(&p); /* plane0: local */
    opg_splat(&p,10,2);opg_result(&p); /* plane1: groupX */
    opg_const(&p,14,0x3f800000u);opg_splat(&p,3,14);
    opg_const(&p,14,0x40000000u);opg_splat(&p,4,14);
    opg_emit(&p,opg_v(0,1,10,3,4));opg_result(&p); /* plane2: real FPU */
    opg_const(&p,15,16380);
    for(unsigned i=0;i<12;i++){
        opg_const(&p,14,i);opg_splat(&p,3,14);opg_emit(&p,opg_v(23,3,4,0,0));
        opg_emit(&p,opg_r(7,10,3,4,0));opg_result(&p); /* planes3..26 */
        opg_splat(&p,4,15);opg_emit(&p,opg_r(7,10,3,4,0));opg_result(&p);
    }
    opg_const(&p,14,31);opg_splat(&p,3,14);
    for(unsigned r=0;r<8;r++){
        opg_const(&p,15,r*512);opg_emit(&p,opg_v(0,4,4,15,6));
        opg_emit(&p,opg_r(7,10,3,4,0));opg_result(&p); /* planes27..34: all1024 */
    }
    opg_const(&p,14,31);opg_emit(&p,opg_v(9,4,3,14,1));opg_emit(&p,opg_v(0x28,3,4,5,1));
    opg_emit(&p,opg_r(3,0,4,2,0));
    for(unsigned i=1;i<8;i++)opg_emit(&p,opg_r(3,0,2,2,i));
    opg_emit(&p,opg_r(5,10,3,2,0));opg_result(&p); /* plane35: 32 texture units */
    opg_emit(&p,opg_v(9,3,3,3,1));opg_emit(&p,opg_r(15,10,3,2,0));opg_result(&p);
    opg_const(&p,15,OPG_STORAGE_BYTES-4);opg_splat(&p,4,15);
    opg_emit(&p,opg_r(15,10,3,4,0));opg_result(&p); /* planes36/37: four endpoints */
    opg_const(&p,14,(14u<<6)|40u);opg_splat(&p,4,14);opg_emit(&p,opg_r(8,0,3,4,0));
    opg_emit(&p,opg_r(8,0,2,2,1));opg_splat(&p,5,2);
    opg_emit(&p,opg_v(0x25,3,5,7,5));opg_emit(&p,opg_v(0,0,5,1,5));
    opg_emit(&p,opg_v(0x28,3,5,2,5));opg_emit(&p,opg_v(0,0,10,1,10));
    opg_emit(&p,opg_r(8,0,10,2,2));opg_emit(&p,opg_r(8,0,2,2,3));
    opg_emit(&p,opg_r(10,0,5,2,0));opg_emit(&p,0x0330000fu);opg_emit(&p,0x0000000bu);
    opg_emit(&p,opg_r(9,10,5,2,0));opg_result(&p); /* plane38: four images */
    opg_emit(&p,opg_v(9,3,3,7,1));opg_emit(&p,opg_r(18,10,3,0,0));opg_result(&p);
    opg_emit(&p,0x0330000fu);opg_emit(&p,0x30500073u); /* plane39: eight old sets */
    return p.overflow?0:p.count;
}

/* A real ordinary-compute producer fills every default component. Its writable
 * kernarg is that source GEM, in a different context from the uniform consumer. */
static inline unsigned opg_default_producer(uint32_t words[512],uint32_t base)
{
    struct opg_code p={words,0,false};opg_emit(&p,0xc1087057u);
    opg_emit(&p,2u<<20|8u<<15|1u<<12|5u<<7|0x13u);
    opg_emit(&p,5u<<20|1u<<15|5u<<7|0x33u);
    for(unsigned r=0;r<8;r++){
        opg_const(&p,14,base+r*128);opg_emit(&p,opg_v(0,4,10,14,1));
        opg_emit(&p,1u<<25|5u<<15|6u<<12|10u<<7|0x27u);
        opg_emit(&p,opg_addi(5,5,512));
    }
    opg_emit(&p,0x0330000fu);opg_emit(&p,0x30500073u);return p.overflow?0:p.count;
}

/* A separate-context texture consumer sees the original mutable counter GEM.
 * Results use only bounded local-index-derived kernarg pointers. */
static inline unsigned opg_counter_consumer(uint32_t words[512])
{
    struct opg_code p={words,0,false};opg_emit(&p,0xc1087057u);
    opg_emit(&p,opg_v(23,3,2,0,0));opg_emit(&p,opg_v(9,3,3,7,1));
    opg_const(&p,14,1020);opg_emit(&p,opg_v(0,4,3,14,3));opg_emit(&p,opg_r(3,0,3,2,0));
    for(unsigned i=1;i<8;i++)opg_emit(&p,opg_r(3,0,2,2,i));
    opg_emit(&p,opg_r(5,10,2,2,0));
    opg_emit(&p,2u<<20|8u<<15|1u<<12|5u<<7|0x13u);
    opg_emit(&p,5u<<20|1u<<15|5u<<7|0x33u);opg_emit(&p,opg_addi(5,5,64));
    opg_emit(&p,1u<<25|5u<<15|6u<<12|10u<<7|0x27u);
    opg_emit(&p,0x0330000fu);opg_emit(&p,0x30500073u);return p.overflow?0:p.count;
}
#endif
