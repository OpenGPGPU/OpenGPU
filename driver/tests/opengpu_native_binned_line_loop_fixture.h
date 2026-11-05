/* Bounded legacy GNB fixture. This file never executes a GPU model. */
#ifndef OPENGPU_NATIVE_BINNED_LINE_LOOP_FIXTURE_H
#define OPENGPU_NATIVE_BINNED_LINE_LOOP_FIXTURE_H
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include "opengpu_native_binned_abi.h"
#include "opengpu_typed_fragment_state_abi.h"

enum {
    OPENGPU_LL_MEMORY_BYTES = 0xd1000,
    OPENGPU_LL_FRAME = 0x1000, OPENGPU_LL_DRAW = 0x2000,
    OPENGPU_LL_STATE = 0x3000, OPENGPU_LL_INDICES = 0x4000,
    OPENGPU_LL_POSITIONS = 0x5000, OPENGPU_LL_VS = 0x6000,
    OPENGPU_LL_FS = 0x7000, OPENGPU_LL_VSCRATCH = 0x10000,
    OPENGPU_LL_FSCRATCH0 = 0x40000, OPENGPU_LL_FSCRATCH1 = 0x50000,
    OPENGPU_LL_ARENA = 0x80000, OPENGPU_LL_POOL = 0xa0000,
    OPENGPU_LL_DIRECTORY = 0xc0000, OPENGPU_LL_IMAGE = 0xd0000,
    OPENGPU_LL_IMAGE_BYTES = 128, OPENGPU_LL_IMAGE_START = 3,
    OPENGPU_LL_IMAGE_PITCH = 21
};
struct opengpu_ll_region { const char *name; uint32_t offset, bytes; int writable; };
static const struct opengpu_ll_region opengpu_ll_regions[] = {
    {"job",0,512,0},{"frame",OPENGPU_LL_FRAME,2048,0},
    {"draw",OPENGPU_LL_DRAW,2048,0},{"state",OPENGPU_LL_STATE,512,0},
    {"indices",OPENGPU_LL_INDICES,64,0},{"positions",OPENGPU_LL_POSITIONS,128,0},
    {"vertex-code",OPENGPU_LL_VS,1024,0},{"fragment-code",OPENGPU_LL_FS,1024,0},
    {"vertex-scratch",OPENGPU_LL_VSCRATCH,0x20000,1},
    {"fragment-scratch0",OPENGPU_LL_FSCRATCH0,0x10000,1},
    {"fragment-scratch1",OPENGPU_LL_FSCRATCH1,0x10000,1},
    {"arena",OPENGPU_LL_ARENA,0x10000,1},{"pool",OPENGPU_LL_POOL,0x10000,1},
    {"directory",OPENGPU_LL_DIRECTORY,64,1},{"image",OPENGPU_LL_IMAGE,128,1}
};
/* Exact diamond-exit oracle, computed independently in the Python CPU check.
 * Missing close(2,0): pixels5/10/15. Missing close(5,3):13/14.
 * A false restart bridge(2,3) adds a second contribution at13/14/15.
 */
static const uint8_t opengpu_ll_coverage[16] = {
    1,1,1,1, 1,1,0,1, 1,1,1,1, 1,1,1,1
};
static const uint16_t opengpu_ll_indices[7] = {0,1,2,0xffff,3,4,5};
/* Clip coordinates map exactly to the half-pixel window endpoints
 * (.5,.5),(3.5,.5),(3.5,3.5),(.5,3.5),(.5,1.5),(2.5,3.5).
 */
static const uint32_t opengpu_ll_positions[6][4] = {
    {0xbf400000,0xbf400000,0,0x3f800000},
    {0x3f400000,0xbf400000,0,0x3f800000},
    {0x3f400000,0x3f400000,0,0x3f800000},
    {0xbf400000,0x3f400000,0,0x3f800000},
    {0xbf400000,0xbe800000,0,0x3f800000},
    {0x3e800000,0x3f400000,0,0x3f800000}
};
struct opengpu_ll_fixture {
    uint8_t *memory;
    uint32_t base, generation, command_id, expected_status, red_quantum;
    uint8_t expected_image[OPENGPU_LL_IMAGE_BYTES];
    unsigned vertex_words, fragment_words;
};
static inline void opengpu_ll_put(uint8_t *p, unsigned word, uint32_t value)
{
    for (unsigned b=0;b<4;b++) p[word*4+b]=(uint8_t)(value>>(8*b));
}
static inline uint32_t opengpu_ll_get(const uint8_t *p, unsigned word)
{
    uint32_t v=0;
    for(unsigned b=0;b<4;b++) v|=(uint32_t)p[word*4+b]<<(8*b);
    return v;
}
static inline void opengpu_ll_header(uint8_t *p,uint32_t magic,unsigned bytes)
{
    opengpu_ll_put(p,0,magic);opengpu_ll_put(p,1,bytes);opengpu_ll_put(p,2,1);
    opengpu_ll_put(p,3,16);opengpu_ll_put(p,4,16);opengpu_ll_put(p,5,16);
    opengpu_ll_put(p,6,4);opengpu_ll_put(p,7,4);
}
static inline uint32_t opengpu_ll_addi(unsigned rd,unsigned rs,int value)
{ return ((uint32_t)value&4095u)<<20|rs<<15|rd<<7|0x13; }
static inline unsigned opengpu_ll_address(uint32_t *code,unsigned n,unsigned rd,unsigned rs,unsigned offset)
{
    if(offset<2048) code[n++]=opengpu_ll_addi(rd,rs,(int)offset);
    else {
        unsigned hi=(offset+2048)>>12;
        code[n++]=hi<<12|rd<<7|0x37;
        code[n++]=rd<<20|rs<<15|rd<<7|0x33;
        code[n++]=opengpu_ll_addi(rd,rd,(int)offset-(int)(hi<<12));
    }
    return n;
}
static inline uint32_t opengpu_ll_vm(unsigned base,unsigned v,int write)
{ return 1u<<25|base<<15|6u<<12|v<<7|(write?0x27u:7u); }
static inline void opengpu_ll_shader(struct opengpu_ll_fixture *f)
{
    uint32_t code[256]={0x00241393,0x007083b3,0xc1087057};unsigned n=3;
    for(unsigned c=0;c<4;c++) {
        n=opengpu_ll_address(code,n,5,7,c*512);code[n++]=opengpu_ll_vm(5,1,0);
        n=opengpu_ll_address(code,n,6,7,(7+c)*512);code[n++]=opengpu_ll_vm(6,1,1);
    }
    code[n++]=0x30500073;f->vertex_words=n;
    for(unsigned i=0;i<n;i++) opengpu_ll_put(f->memory+OPENGPU_LL_VS,i,code[i]);
    n=3;
    for(unsigned location=0;location<4;location++) for(unsigned c=0;c<4;c++) {
        uint32_t value=c==0?(f->red_quantum==64?0x3e800000u:0x3e000000u):(c==3?0x3f800000u:0);
        code[n++]=(value&0xfffff000u)|13u<<7|0x37;
        code[n++]=0x17u<<26|1u<<25|13u<<15|4u<<12|1u<<7|0x57;
        n=opengpu_ll_address(code,n,6,7,(10+4*location+c)*512);
        code[n++]=opengpu_ll_vm(6,1,1);
    }
    code[n++]=opengpu_ll_addi(13,0,61);
    code[n++]=0x17u<<26|1u<<25|13u<<15|4u<<12|1u<<7|0x57;
    n=opengpu_ll_address(code,n,6,7,27*512);code[n++]=opengpu_ll_vm(6,1,1);
    code[n++]=0x30500073;f->fragment_words=n;
    for(unsigned i=0;i<n;i++) opengpu_ll_put(f->memory+OPENGPU_LL_FS,i,code[i]);
}
static inline int opengpu_ll_build(struct opengpu_ll_fixture *f,unsigned submission)
{
    if(!f || submission>2) return -1;
    memset(f,0,sizeof(*f));f->memory=(uint8_t *)calloc(1,OPENGPU_LL_MEMORY_BYTES);
    if(!f->memory) return -1;
    f->base=0x100000u+submission*0x200000u;f->generation=9+submission;
    f->command_id=41+submission;f->expected_status=submission==1?2:0;
    f->red_quantum=submission==2?64:32;
    uint8_t *j=f->memory,*p=f->memory+OPENGPU_LL_FRAME,*d=f->memory+OPENGPU_LL_DRAW,*s=f->memory+OPENGPU_LL_STATE;
#define J(name,v) opengpu_ll_put(j,OPENGPU_NATIVE_BINNED_JOB_##name##_WORD,(v))
#define F(name,v) opengpu_ll_put(p,OPENGPU_NATIVE_BINNED_FRAME_##name##_WORD,(v))
#define D(name,v) opengpu_ll_put(d,OPENGPU_NATIVE_BINNED_DRAW_##name##_WORD,(v))
#define S(name,v) opengpu_ll_put(s,OPENGPU_TYPED_FRAGMENT_STATE_##name##_WORD,(v))
#define A(offset) (f->base+(offset))
    opengpu_ll_header(j,OPENGPU_NATIVE_BINNED_JOB_MAGIC,512);
    opengpu_ll_header(p,OPENGPU_NATIVE_BINNED_FRAME_MAGIC,2048);
    opengpu_ll_header(d,OPENGPU_NATIVE_BINNED_DRAW_MAGIC,2048);
    J(JOB_IDENTITY_CONTEXT,7);J(JOB_IDENTITY_PASS,8);J(JOB_IDENTITY_GENERATION,f->generation);
    J(JOB_FRAMEBASE,A(OPENGPU_LL_FRAME));J(JOB_FRAMELIMIT,A(OPENGPU_LL_FRAME+2048));
    J(JOB_DRAWBASE,A(OPENGPU_LL_DRAW));J(JOB_DRAWLIMIT,A(OPENGPU_LL_DRAW+2048));J(JOB_DRAWCOUNT,1);J(JOB_DRAWSTRIDE,2048);
    F(FRAME_VERSION,1);F(FRAME_BINS_PRODUCER_VERSION,1);F(FRAME_BINS_PRODUCER_ARENA_VERSION,1);
    F(FRAME_BINS_PRODUCER_ARENA_BASE,A(OPENGPU_LL_ARENA));F(FRAME_BINS_PRODUCER_ARENA_LIMIT,A(OPENGPU_LL_ARENA+0x10000));
    F(FRAME_BINS_PRODUCER_ARENA_IDENTITY_CONTEXT,7);F(FRAME_BINS_PRODUCER_ARENA_IDENTITY_PASS,8);F(FRAME_BINS_PRODUCER_ARENA_IDENTITY_GENERATION,f->generation);
    F(FRAME_BINS_PRODUCER_SCREENWIDTH,4);F(FRAME_BINS_PRODUCER_SCREENHEIGHT,4);F(FRAME_BINS_PRODUCER_TILELOG2,2);
    F(FRAME_BINS_BINS_VERSION,3);F(FRAME_BINS_BINS_TILECOUNT,1);F(FRAME_BINS_BINS_DIRECTORYBASE,A(OPENGPU_LL_DIRECTORY));F(FRAME_BINS_BINS_DIRECTORYLIMIT,A(OPENGPU_LL_DIRECTORY+64));
    F(FRAME_BINS_BINS_POOL_VERSION,1);F(FRAME_BINS_BINS_POOL_BASE,A(OPENGPU_LL_POOL));F(FRAME_BINS_BINS_POOL_LIMIT,A(OPENGPU_LL_POOL+0x10000));F(FRAME_BINS_BINS_POOL_PAGESIZELOG2,12);
    F(FRAME_BINS_BINS_POOL_IDENTITY_CONTEXT,7);F(FRAME_BINS_BINS_POOL_IDENTITY_PASS,8);F(FRAME_BINS_BINS_POOL_IDENTITY_GENERATION,f->generation);
    F(FRAME_STATES_VERSION,1);F(FRAME_STATES_BASE,A(OPENGPU_LL_STATE));F(FRAME_STATES_LIMIT,A(OPENGPU_LL_STATE+512));
    F(FRAME_STATES_IDENTITY_CONTEXT,7);F(FRAME_STATES_IDENTITY_PASS,8);F(FRAME_STATES_IDENTITY_GENERATION,f->generation);
    F(FRAME_SCRATCHSTRIDE,65536);F(FRAME_SCRATCH0_BASE,A(OPENGPU_LL_FSCRATCH0));F(FRAME_SCRATCH0_LIMIT,A(OPENGPU_LL_FSCRATCH0+65536));
    F(FRAME_SCRATCH1_BASE,A(OPENGPU_LL_FSCRATCH1));F(FRAME_SCRATCH1_LIMIT,A(OPENGPU_LL_FSCRATCH1+65536));
    F(FRAME_IMAGE_VERSION,1);F(FRAME_IMAGE_PIXELS_VERSION,1);F(FRAME_IMAGE_PIXELS_COLORS_VERSION,1);
    F(FRAME_IMAGE_PIXELS_COLORS_IDENTITY_CONTEXT,7);F(FRAME_IMAGE_PIXELS_COLORS_IDENTITY_PASS,8);F(FRAME_IMAGE_PIXELS_COLORS_IDENTITY_GENERATION,f->generation);
    F(FRAME_IMAGE_PIXELS_COLORS_TILELOG2,2);F(FRAME_IMAGE_PIXELS_COLORS_TILEWIDTH,4);F(FRAME_IMAGE_PIXELS_COLORS_TILEHEIGHT,4);
    F(FRAME_IMAGE_PIXELS_COLORS_COLORS0_ENABLED,1);F(FRAME_IMAGE_PIXELS_COLORS_COLORS0_FORMAT,3);F(FRAME_IMAGE_PIXELS_COLORS_COLORS0_CHANNELMASK,15);F(FRAME_IMAGE_PIXELS_COLORS_COLORS0_LOADOP,1);
    F(FRAME_IMAGE_VIEWS0_BASE,A(OPENGPU_LL_IMAGE+3));F(FRAME_IMAGE_VIEWS0_LIMIT,A(OPENGPU_LL_IMAGE+3+3*21+16));
    F(FRAME_IMAGE_VIEWS0_ALLOCATIONBASE,A(OPENGPU_LL_IMAGE));F(FRAME_IMAGE_VIEWS0_ALLOCATIONLIMIT,A(OPENGPU_LL_IMAGE+128));
    F(FRAME_IMAGE_VIEWS0_IMAGEWIDTH,4);F(FRAME_IMAGE_VIEWS0_IMAGEHEIGHT,4);F(FRAME_IMAGE_VIEWS0_ROWPITCH,21);F(FRAME_IMAGE_VIEWS0_READABLE,1);F(FRAME_IMAGE_VIEWS0_WRITABLE,1);F(FRAME_IMAGE_VIEWS0_STORE,1);
    D(DRAW_VERSION,1);D(DRAW_TOKEN,0xa0000041u);D(DRAW_VERTEX_GEOMETRY_TOPOLOGY,2);
    D(DRAW_VERTEX_VIEWPORT_VERSION,1);D(DRAW_VERTEX_VIEWPORT_EXTENTX,4);D(DRAW_VERTEX_VIEWPORT_EXTENTY,4);D(DRAW_VERTEX_VIEWPORT_FARDEPTH,0x3f800000u);
    D(DRAW_RASTER_VERSION,1);D(DRAW_RASTER_SCREENW,4);D(DRAW_RASTER_SCREENH,4);D(DRAW_RASTER_LINEWIDTH,0x3f800000u);D(DRAW_RASTER_POINTSIZE,0x3f800000u);D(DRAW_RASTER_FRONTFACECCW,1);
    D(DRAW_VERTEX_GEOMETRY_VERTEX_VERSION,1);D(DRAW_VERTEX_GEOMETRY_VERTEX_SHADERPC,A(OPENGPU_LL_VS));D(DRAW_VERTEX_GEOMETRY_VERTEX_ATTRIBUTESLOTS,1);D(DRAW_VERTEX_GEOMETRY_VERTEX_VARYINGSLOTS,1);
    D(DRAW_VERTEX_GEOMETRY_VERTEX_SAMPLER_TEXWIDTH,1);D(DRAW_VERTEX_GEOMETRY_VERTEX_SAMPLER_TEXHEIGHT,1);
    D(DRAW_VERTEX_GEOMETRY_VERTEX_SCRATCH_BASE,A(OPENGPU_LL_VSCRATCH));D(DRAW_VERTEX_GEOMETRY_VERTEX_SCRATCH_LIMIT,A(OPENGPU_LL_VSCRATCH+0x20000));
    D(DRAW_VERTEX_GEOMETRY_VERTEX_SCRATCH_ALLOCATIONBASE,A(OPENGPU_LL_VSCRATCH));D(DRAW_VERTEX_GEOMETRY_VERTEX_SCRATCH_ALLOCATIONLIMIT,A(OPENGPU_LL_VSCRATCH+0x20000));
    D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_VERSION,1);D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_COUNT,7);D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_INSTANCES,1);D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_INDEXFORMAT,2);D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_RESTART,1);
    D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_IDENTITY_CONTEXT,7);D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_IDENTITY_PASS,8);D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_IDENTITY_GENERATION,f->generation);
    D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_INDICES_BASE,A(OPENGPU_LL_INDICES));D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_INDICES_LIMIT,A(OPENGPU_LL_INDICES+(submission==1?13:14)));
    D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_INDICES_ALLOCATIONBASE,A(OPENGPU_LL_INDICES));D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_INDICES_ALLOCATIONLIMIT,A(OPENGPU_LL_INDICES+64));
    D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_ATTRIBUTES0_ENABLED,1);D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_ATTRIBUTES0_COMPONENTS,4);
    D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_BINDINGS0_BASE,A(OPENGPU_LL_POSITIONS));D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_BINDINGS0_LIMIT,A(OPENGPU_LL_POSITIONS+96));D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_BINDINGS0_STRIDE,16);
    D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_BINDINGS0_ALLOCATIONBASE,A(OPENGPU_LL_POSITIONS));D(DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_BINDINGS0_ALLOCATIONLIMIT,A(OPENGPU_LL_POSITIONS+128));
    opengpu_ll_put(s,0,OPENGPU_TYPED_FRAGMENT_STATE_MAGIC);opengpu_ll_put(s,1,512);opengpu_ll_put(s,2,1);opengpu_ll_put(s,3,4);opengpu_ll_put(s,4,7);opengpu_ll_put(s,5,8);opengpu_ll_put(s,6,f->generation);
    S(VERSION,1);S(ENGINECOUNT,2);S(FRAGMENT_VERSION,1);S(FRAGMENT_SHADERPC,A(OPENGPU_LL_FS));S(FRAGMENT_VARYINGSLOTS,1);S(FRAGMENT_COLORSLOTS,4);S(FRAGMENT_COLORMASK,1);
    S(FRAGMENT_IDENTITY_CONTEXT,7);S(FRAGMENT_IDENTITY_PASS,8);S(FRAGMENT_IDENTITY_GENERATION,f->generation);
    S(FRAGMENT_SAMPLER_TEXWIDTH,1);S(FRAGMENT_SAMPLER_TEXHEIGHT,1);
    S(FRAGMENT_SCRATCH_BASE,A(OPENGPU_LL_FSCRATCH0));S(FRAGMENT_SCRATCH_LIMIT,A(OPENGPU_LL_FSCRATCH0+65536));S(FRAGMENT_SCRATCH_ALLOCATIONBASE,A(OPENGPU_LL_FSCRATCH0));S(FRAGMENT_SCRATCH_ALLOCATIONLIMIT,A(OPENGPU_LL_FSCRATCH0+65536));
    S(SCRATCH0_BASE,A(OPENGPU_LL_FSCRATCH1));S(SCRATCH0_LIMIT,A(OPENGPU_LL_FSCRATCH1+65536));S(SCRATCH0_ALLOCATIONBASE,A(OPENGPU_LL_FSCRATCH1));S(SCRATCH0_ALLOCATIONLIMIT,A(OPENGPU_LL_FSCRATCH1+65536));
    S(TESTS_VERSION,1);S(BLEND_VERSION,1);S(BLEND_ENABLE,1);S(BLEND_SRCRGB,1);S(BLEND_DSTRGB,1);S(BLEND_SRCALPHA,1);S(BLEND_DSTALPHA,1);
    for(unsigned i=0;i<7;i++) for(unsigned b=0;b<2;b++) f->memory[OPENGPU_LL_INDICES+2*i+b]=(uint8_t)(opengpu_ll_indices[i]>>(8*b));
    for(unsigned i=0;i<6;i++) for(unsigned c=0;c<4;c++) opengpu_ll_put(f->memory+OPENGPU_LL_POSITIONS,4*i+c,opengpu_ll_positions[i][c]);
    opengpu_ll_shader(f);
    memset(f->memory+OPENGPU_LL_IMAGE,0xa7,128);memset(f->expected_image,0xa7,128);
    if(submission!=1) for(unsigned pixel=0;pixel<16;pixel++) {
        unsigned pos=3+(pixel/4)*21+(pixel%4)*4;f->expected_image[pos]=(uint8_t)(f->red_quantum*opengpu_ll_coverage[pixel]);
        f->expected_image[pos+1]=f->expected_image[pos+2]=0;f->expected_image[pos+3]=opengpu_ll_coverage[pixel]?255:0;
    }
#undef J
#undef F
#undef D
#undef S
#undef A
    return 0;
}
static inline void opengpu_ll_destroy(struct opengpu_ll_fixture *f)
{ free(f->memory);f->memory=NULL; }
#endif
