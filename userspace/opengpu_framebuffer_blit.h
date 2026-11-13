/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_FRAMEBUFFER_BLIT_H
#define OPENGPU_FRAMEBUFFER_BLIT_H

#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include "opengpu_formats.h"

/* CPU admission contract for the private checked-storage GPU pass. No raw
 * address or pixel value crosses this descriptor. Unknown versions/flags are
 * rejected before allocating a context or touching an image. */
#define OPENGPU_FRAMEBUFFER_BLIT_VERSION 1u
#define OPENGPU_FRAMEBUFFER_BLIT_VERSION_2 2u
#define OPENGPU_FRAMEBUFFER_BLIT_VERSION_3 3u
#define OPENGPU_FRAMEBUFFER_BLIT_SCISSOR 1u
#define OPENGPU_FRAMEBUFFER_BLIT_LINEAR 2u
#define OPENGPU_FRAMEBUFFER_BLIT_D24S8 5u
#define OPENGPU_FRAMEBUFFER_BLIT_D24X8 6u
#define OPENGPU_FRAMEBUFFER_BLIT_MAX_AXIS 16384u
#define OPENGPU_FRAMEBUFFER_BLIT_HEADER_WORDS 32u
struct opengpu_framebuffer_blit {
    uint32_t version, bytes, flags, mask;
    uint32_t source_format, destination_format;
    uint32_t source_width, source_height, source_pitch;
    uint32_t destination_width, destination_height, destination_pitch;
    int32_t source_x, source_y, source_w, source_h;
    int32_t destination_x, destination_y, destination_w, destination_h;
    int32_t scissor_x0, scissor_y0, scissor_x1, scissor_y1;
};
struct opengpu_framebuffer_blit_plan {
    uint32_t x, y, width, height, map_words;
    uint32_t source_bytes, destination_bytes;
};

static inline unsigned opengpu_blit_map_step(const struct opengpu_framebuffer_blit *d)
{ return d->version==3 && (d->flags&OPENGPU_FRAMEBUFFER_BLIT_LINEAR) ? 4u : 1u; }

static inline int64_t opengpu_blit_clamp(int64_t value,int64_t lower,int64_t upper)
{ return value<lower ? lower : value>=upper ? upper-1 : value; }

static inline void opengpu_blit_linear_axis(int32_t source,int32_t extent,
    int32_t destination,int32_t destination_extent,int64_t pixel,uint32_t limit,
    uint32_t stride,uint32_t result[4])
{
    uint64_t sw=extent<0 ? -(int64_t)extent : extent;
    uint64_t dw=destination_extent<0 ? -(int64_t)destination_extent : destination_extent;
    uint64_t offset=destination_extent<0 ? (int64_t)destination-pixel-1 : pixel-destination;
    uint64_t n=(2*offset+1)*sw,d=2*dw,r=n%d;
    int64_t integral=extent<0 ? (int64_t)source-(int64_t)(n/d)-(r!=0) : (int64_t)source+(int64_t)(n/d);
    if(extent<0 && r)r=d-r;
    /* Subtract half a texel without a signed remainder. Quantize only the
     * fractional coordinate, to nearest 16-bit fixed point. */
    uint64_t fraction;
    if(2*r<d){integral--;fraction=2*r+d;}
    else fraction=2*r-d;
    uint32_t weight=(uint32_t)((fraction*65536+d)/(2*d));
    int64_t lower=extent<0 ? (int64_t)source+extent : source;
    int64_t upper=extent<0 ? source : (int64_t)source+extent;
    if(lower<0)lower=0;
    if(upper>limit)upper=limit;
    result[0]=(uint32_t)opengpu_blit_clamp(integral,lower,upper)*stride;
    result[1]=(uint32_t)opengpu_blit_clamp(integral+1,lower,upper)*stride;
    result[2]=weight;result[3]=65536-weight;
}

/* Exact integral pixel-centre selection, including reversed endpoints. All
 * signed endpoints are widened before subtraction/absolute value. The largest
 * product is (2*2^31-1)*2^31, which fits uint64_t. */
static inline int64_t opengpu_blit_nearest_coordinate(int32_t source,
    int32_t source_extent, int32_t destination, int32_t destination_extent,
    int64_t pixel)
{
    uint64_t sw = source_extent < 0 ? -(int64_t)source_extent : source_extent;
    uint64_t dw = destination_extent < 0 ? -(int64_t)destination_extent : destination_extent;
    uint64_t offset = destination_extent < 0 ? (int64_t)destination-pixel-1 : pixel-destination;
    uint64_t n = (2*offset+1)*sw, d = 2*dw;
    return source_extent < 0 ? (int64_t)source-(int64_t)(n/d)-(n%d != 0) :
                              (int64_t)source+(int64_t)(n/d);
}

/* Returns 0 for a valid (possibly empty) plan, -1 for malformed admission.
 * Only coordinate offsets are prepared on the CPU. The GPU reads, converts,
 * masks and writes every pixel. Maps are private immutable read-only storage. */
static inline int opengpu_framebuffer_blit_plan_make(
    const struct opengpu_framebuffer_blit *d, uint64_t source_allocation,
    uint64_t destination_allocation, struct opengpu_framebuffer_blit_plan *out,
    uint32_t *maps, size_t capacity_words)
{
    struct opengpu_framebuffer_blit_plan p = {0};
    uint32_t starts[2]={0}, counts[2]={0};
    if (!d || !out || !maps ||
        (d->version < OPENGPU_FRAMEBUFFER_BLIT_VERSION || d->version > OPENGPU_FRAMEBUFFER_BLIT_VERSION_3) ||
        d->bytes != sizeof(*d) || (d->flags & ~(OPENGPU_FRAMEBUFFER_BLIT_SCISSOR |
            (d->version==3 ? OPENGPU_FRAMEBUFFER_BLIT_LINEAR : 0))) ||
        !d->source_width || !d->source_height || !d->destination_width || !d->destination_height ||
        d->source_width > OPENGPU_FRAMEBUFFER_BLIT_MAX_AXIS ||
        d->source_height > OPENGPU_FRAMEBUFFER_BLIT_MAX_AXIS ||
        d->destination_width > OPENGPU_FRAMEBUFFER_BLIT_MAX_AXIS ||
        d->destination_height > OPENGPU_FRAMEBUFFER_BLIT_MAX_AXIS ||
        (d->source_pitch & 3) || (d->destination_pitch & 3) ||
        (uint64_t)d->source_width*4 > d->source_pitch ||
        (uint64_t)d->destination_width*4 > d->destination_pitch ||
        ((d->flags & OPENGPU_FRAMEBUFFER_BLIT_SCISSOR) &&
            (d->scissor_x0 > d->scissor_x1 || d->scissor_y0 > d->scissor_y1))) return -1;
    unsigned depth=d->source_format==OPENGPU_FRAMEBUFFER_BLIT_D24S8 ||
        d->source_format==OPENGPU_FRAMEBUFFER_BLIT_D24X8;
    if(depth) {
        if(d->version<2 || (d->flags&OPENGPU_FRAMEBUFFER_BLIT_LINEAR) || d->source_format!=d->destination_format ||
            (d->mask&~48u) || (d->source_format==OPENGPU_FRAMEBUFFER_BLIT_D24X8 && (d->mask&32)))return -1;
    } else if((d->mask&~15u) || d->source_format<OPENGPU_FORMAT_RGBA8 ||
        d->source_format>OPENGPU_FORMAT_BGRX8 || d->destination_format<OPENGPU_FORMAT_RGBA8 ||
        d->destination_format>OPENGPU_FORMAT_BGRX8)return -1;
    uint64_t sb=(uint64_t)(d->source_height-1)*d->source_pitch+d->source_width*4u;
    uint64_t db=(uint64_t)(d->destination_height-1)*d->destination_pitch+d->destination_width*4u;
    if (sb > source_allocation || db > destination_allocation ||
        sb > UINT32_MAX || db > UINT32_MAX ||
        capacity_words < OPENGPU_FRAMEBUFFER_BLIT_HEADER_WORDS+
            ((size_t)d->destination_width+d->destination_height)*opengpu_blit_map_step(d)) return -1;
    p.source_bytes=(uint32_t)sb; p.destination_bytes=(uint32_t)db;
    memset(maps,0,OPENGPU_FRAMEBUFFER_BLIT_HEADER_WORDS*4);
    memcpy(maps,d,sizeof(*d));
    if (!d->mask || !d->source_w || !d->source_h || !d->destination_w || !d->destination_h) {
        *out=p; return 0;
    }
    for (unsigned axis=0;axis<2;axis++) {
        int32_t src=axis ? d->source_y : d->source_x;
        int32_t sw=axis ? d->source_h : d->source_w;
        int32_t dst=axis ? d->destination_y : d->destination_x;
        int32_t dw=axis ? d->destination_h : d->destination_w;
        uint32_t limit=axis ? d->destination_height : d->destination_width;
        uint32_t source_limit=axis ? d->source_height : d->source_width;
        int64_t lower=dw < 0 ? (int64_t)dst+dw : dst;
        int64_t upper=dw < 0 ? dst : (int64_t)dst+dw;
        int64_t sc0=axis ? d->scissor_y0 : d->scissor_x0;
        int64_t sc1=axis ? d->scissor_y1 : d->scissor_x1;
        if (lower < 0) lower=0;
        if (upper > limit) upper=limit;
        if (d->flags & OPENGPU_FRAMEBUFFER_BLIT_SCISSOR) {
            if (lower < sc0) lower=sc0;
            if (upper > sc1) upper=sc1;
        }
        unsigned ended=0;
        for (int64_t i=lower;i<upper;i++) {
            int64_t value=opengpu_blit_nearest_coordinate(src,sw,dst,dw,i);
            if (value < 0 || value >= source_limit) { if (counts[axis]) ended=1; continue; }
            if (ended) return -1; /* An affine map cannot create an interior hole. */
            if (!counts[axis]) starts[axis]=(uint32_t)i;
            size_t index=OPENGPU_FRAMEBUFFER_BLIT_HEADER_WORDS+
                (counts[0]*axis+counts[axis])*opengpu_blit_map_step(d);
            if(opengpu_blit_map_step(d)==4)
                opengpu_blit_linear_axis(src,sw,dst,dw,i,source_limit,axis?d->source_pitch:4u,maps+index);
            else maps[index]=(uint32_t)value*(axis ? d->source_pitch : 4u);
            counts[axis]++;
        }
    }
    p.x=starts[0];p.y=starts[1];p.width=counts[0];p.height=counts[1];
    p.map_words=OPENGPU_FRAMEBUFFER_BLIT_HEADER_WORDS+(p.width+p.height)*opengpu_blit_map_step(d);
    *out=p;return 0;
}

/* Exact separable tap-set proof. Sort actual X/Y tap offsets; for each
 * destination row, binary-search the first source row and first source pixel
 * whose end follows its start. Source rows never overlap because admitted X
 * offsets fit their pitch. This finds the earliest actual read byte without
 * filling skipped rows/columns or scanning a full W*H Cartesian image.
 * Complexity: O((W+H)log(W+H)+H(log W+log H)), bounded scratch 256 KiB.
 * Unknown distinct-handle aliases still require the kernel backing proof. */
static inline int opengpu_blit_compare_u32(const void *left,const void *right)
{
    uint32_t a=*(const uint32_t *)left,b=*(const uint32_t *)right;
    return a>b ? 1 : a<b ? -1 : 0;
}
static inline int opengpu_framebuffer_blit_disjoint(
    const struct opengpu_framebuffer_blit *d,const struct opengpu_framebuffer_blit_plan *p,
    const uint32_t *maps)
{
    if(!p->width || !p->height)return 1;
    if(p->width>OPENGPU_FRAMEBUFFER_BLIT_MAX_AXIS || p->height>OPENGPU_FRAMEBUFFER_BLIT_MAX_AXIS)return 0;
    unsigned step=opengpu_blit_map_step(d),taps=step==4 ? 2u : 1u;
    uint32_t xs[OPENGPU_FRAMEBUFFER_BLIT_MAX_AXIS*2],ys[OPENGPU_FRAMEBUFFER_BLIT_MAX_AXIS*2];
    unsigned nx=p->width*taps,ny=p->height*taps;
    for(unsigned i=0;i<p->width;i++)for(unsigned tap=0;tap<taps;tap++)
        xs[i*taps+tap]=maps[OPENGPU_FRAMEBUFFER_BLIT_HEADER_WORDS+i*step+tap];
    for(unsigned i=0;i<p->height;i++)for(unsigned tap=0;tap<taps;tap++)
        ys[i*taps+tap]=maps[OPENGPU_FRAMEBUFFER_BLIT_HEADER_WORDS+p->width*step+i*step+tap];
    qsort(xs,nx,sizeof(xs[0]),opengpu_blit_compare_u32);
    qsort(ys,ny,sizeof(ys[0]),opengpu_blit_compare_u32);
    for(unsigned destination_row=0;destination_row<p->height;destination_row++) {
        uint64_t start=(uint64_t)(p->y+destination_row)*d->destination_pitch+p->x*4u;
        uint64_t end=start+p->width*4u;
        unsigned lo=0,hi=ny;
        while(lo<hi) {
            unsigned mid=lo+(hi-lo)/2;
            if((uint64_t)ys[mid]+xs[nx-1]+4<=start)lo=mid+1;
            else hi=mid;
        }
        if(lo==ny)continue;
        uint64_t row=ys[lo];lo=0;hi=nx;
        while(lo<hi) {
            unsigned mid=lo+(hi-lo)/2;
            if(row+xs[mid]+4<=start)lo=mid+1;
            else hi=mid;
        }
        if(lo<nx && row+xs[lo]<end)return 0;
    }
    return 1;
}

struct opengpu_blit_program { uint32_t code[512]; unsigned words, overflow; };
static inline void opengpu_blit_emit(struct opengpu_blit_program *p,uint32_t word)
{
    if(p->words<sizeof(p->code)/sizeof(p->code[0]))p->code[p->words++]=word;
    else p->overflow=1;
}
static inline uint32_t opengpu_blit_vec(unsigned fn,unsigned f3,unsigned rd,unsigned a,unsigned b)
{ return fn<<26 | 1u<<25 | b<<20 | a<<15 | f3<<12 | rd<<7 | 0x57u; }
static inline void opengpu_blit_splat(struct opengpu_blit_program *p,unsigned rd,uint32_t value)
{
    uint32_t hi=(value+0x800u)&0xfffff000u,lo=value&0xfffu;
    if(hi)opengpu_blit_emit(p,hi|10u<<7|0x37u);
    if(lo || !hi)opengpu_blit_emit(p,lo<<20|(hi?10u:0u)<<15|10u<<7|0x13u);
    opengpu_blit_emit(p,opengpu_blit_vec(0x17,4,rd,10,0));
}
static inline void opengpu_blit_op(struct opengpu_blit_program *p,unsigned fn,
    unsigned f3,unsigned rd,unsigned a,unsigned b)
{ opengpu_blit_emit(p,opengpu_blit_vec(fn,f3,rd,a,b)); }
static inline void opengpu_blit_load(struct opengpu_blit_program *p,unsigned rd,
    unsigned offset,unsigned binding)
{
    opengpu_blit_splat(p,24,binding);
    opengpu_blit_emit(p,15u<<26|1u<<25|offset<<20|24u<<15|rd<<7|0x2bu);
}
static inline void opengpu_blit_axis_load(struct opengpu_blit_program *p,
    unsigned id,uint32_t base,unsigned first,unsigned second,unsigned weight,unsigned inverse,unsigned linear)
{
    opengpu_blit_splat(p,6,linear?16:4);opengpu_blit_op(p,0x25,2,5,id,6);
    opengpu_blit_splat(p,6,base);opengpu_blit_op(p,0,0,5,5,6);
    opengpu_blit_load(p,first,5,2);
    if(linear) {
        const unsigned registers[3]={second,weight,inverse};
        for(unsigned i=0;i<3;i++) {
            opengpu_blit_splat(p,6,4);opengpu_blit_op(p,0,0,5,5,6);
            opengpu_blit_load(p,registers[i],5,2);
        }
    }
}
static inline void opengpu_blit_channel(struct opengpu_blit_program *p,unsigned to,unsigned from,unsigned shift)
{
    opengpu_blit_splat(p,6,shift);opengpu_blit_op(p,0x28,0,to,6,from);
    opengpu_blit_splat(p,6,255);opengpu_blit_op(p,9,0,to,to,6);
}
/* A/B/C/D bytes in v20..23; exact 16x16 fixed-point bilinear sum, rounded
 * only once at the end. Split products into 16-bit limbs so all operations
 * remain existing checked-storage uint32 instructions. No 40-bit product is
 * truncated and no new validator or multiply opcode is needed. */
static inline void opengpu_blit_bilinear(struct opengpu_blit_program *p)
{
    opengpu_blit_op(p,0x25,2,20,20,25);opengpu_blit_op(p,0x25,2,21,21,14);
    opengpu_blit_op(p,0,0,20,20,21);
    opengpu_blit_op(p,0x25,2,22,22,25);opengpu_blit_op(p,0x25,2,23,23,14);
    opengpu_blit_op(p,0,0,22,22,23);
    opengpu_blit_splat(p,6,65535);
    opengpu_blit_op(p,9,0,7,20,6);opengpu_blit_op(p,9,0,8,22,6);
    opengpu_blit_op(p,0x25,2,7,7,26);opengpu_blit_op(p,0x25,2,8,8,16);
    opengpu_blit_splat(p,6,16);
    opengpu_blit_op(p,0x28,0,20,6,20);opengpu_blit_op(p,0x28,0,22,6,22);
    opengpu_blit_op(p,0x25,2,20,20,26);opengpu_blit_op(p,0x25,2,22,22,16);
    opengpu_blit_op(p,0,0,20,20,22);
    opengpu_blit_splat(p,6,65535);
    opengpu_blit_op(p,9,0,13,7,6);opengpu_blit_op(p,9,0,15,8,6);
    opengpu_blit_op(p,0,0,13,13,15);
    opengpu_blit_splat(p,6,16);
    opengpu_blit_op(p,0x28,0,13,6,13);
    opengpu_blit_op(p,0x28,0,7,6,7);opengpu_blit_op(p,0x28,0,8,6,8);
    opengpu_blit_op(p,0,0,20,20,13);opengpu_blit_op(p,0,0,20,20,7);opengpu_blit_op(p,0,0,20,20,8);
    opengpu_blit_splat(p,6,32768);opengpu_blit_op(p,0,0,20,20,6);
    opengpu_blit_splat(p,6,16);opengpu_blit_op(p,0x28,0,12,6,20);
}
/* One invocation per destination pixel; x2/x3 are the hardware block IDs.
 * Existing resource instructions check the exact source/destination/map views.
 * No pixel load/store uses ordinary vector memory or a supplied raw pointer. */
static inline struct opengpu_blit_program opengpu_framebuffer_blit_program(
    const struct opengpu_framebuffer_blit *d,const struct opengpu_framebuffer_blit_plan *plan)
{
    struct opengpu_blit_program p={0};
    opengpu_blit_emit(&p,0x01007557u);
    opengpu_blit_op(&p,0x17,4,2,2,0); /* x */
    opengpu_blit_op(&p,0x17,4,3,3,0); /* y */
    opengpu_blit_splat(&p,4,2);
    unsigned linear=opengpu_blit_map_step(d)==4;
    opengpu_blit_axis_load(&p,2,OPENGPU_FRAMEBUFFER_BLIT_HEADER_WORDS*4,7,13,14,25,linear);
    opengpu_blit_axis_load(&p,3,(OPENGPU_FRAMEBUFFER_BLIT_HEADER_WORDS+
        plan->width*opengpu_blit_map_step(d))*4,8,15,16,26,linear);
    opengpu_blit_op(&p,0,0,5,7,8);opengpu_blit_load(&p,9,5,0);
    if(linear) {
        opengpu_blit_op(&p,0,0,5,13,8);opengpu_blit_load(&p,17,5,0);
        opengpu_blit_op(&p,0,0,5,7,15);opengpu_blit_load(&p,18,5,0);
        opengpu_blit_op(&p,0,0,5,13,15);opengpu_blit_load(&p,19,5,0);
    }
    opengpu_blit_splat(&p,6,plan->y);
    opengpu_blit_op(&p,0,0,5,3,6);
    opengpu_blit_splat(&p,6,d->destination_pitch);
    opengpu_blit_op(&p,0x25,2,5,5,6);
    opengpu_blit_splat(&p,6,plan->x);
    opengpu_blit_op(&p,0,0,6,2,6);
    opengpu_blit_op(&p,0x25,0,6,4,6);
    opengpu_blit_op(&p,0,0,5,5,6);opengpu_blit_load(&p,10,5,1);
    unsigned source_bgra=d->source_format==OPENGPU_FORMAT_BGRA8 || d->source_format==OPENGPU_FORMAT_BGRX8;
    unsigned destination_bgra=d->destination_format==OPENGPU_FORMAT_BGRA8 || d->destination_format==OPENGPU_FORMAT_BGRX8;
    unsigned source_alpha=d->source_format==OPENGPU_FORMAT_RGBA8 || d->source_format==OPENGPU_FORMAT_BGRA8;
    unsigned destination_alpha=d->destination_format==OPENGPU_FORMAT_RGBA8 || d->destination_format==OPENGPU_FORMAT_BGRA8;
    uint32_t mask=0;
    opengpu_blit_splat(&p,11,0);
    if(d->source_format==OPENGPU_FRAMEBUFFER_BLIT_D24S8 || d->source_format==OPENGPU_FRAMEBUFFER_BLIT_D24X8) {
        mask=(d->mask&16?0xffffffu:0)|(d->mask&32?0xff000000u:0);
        opengpu_blit_splat(&p,6,mask);opengpu_blit_op(&p,9,0,11,9,6);
    } else for(unsigned c=0;c<4;c++)if((d->mask&(1u<<c)) && (c!=3 || destination_alpha)) {
        unsigned ss=(c==0 && source_bgra)?2:(c==2 && source_bgra)?0:c;
        unsigned ds=(c==0 && destination_bgra)?2:(c==2 && destination_bgra)?0:c;
        mask|=255u<<(ds*8);
        if(c==3 && !source_alpha)opengpu_blit_splat(&p,12,255);
        else if(linear) {
            opengpu_blit_channel(&p,20,9,ss*8);opengpu_blit_channel(&p,21,17,ss*8);
            opengpu_blit_channel(&p,22,18,ss*8);opengpu_blit_channel(&p,23,19,ss*8);
            opengpu_blit_bilinear(&p);
        }
        else {
            opengpu_blit_splat(&p,6,ss*8);opengpu_blit_op(&p,0x28,0,12,6,9);
            opengpu_blit_splat(&p,6,255);opengpu_blit_op(&p,9,0,12,12,6);
        }
        opengpu_blit_splat(&p,6,ds*8);opengpu_blit_op(&p,0x25,0,12,6,12);
        opengpu_blit_op(&p,10,0,11,11,12);
    }
    opengpu_blit_splat(&p,6,~mask);opengpu_blit_op(&p,9,0,10,10,6);
    opengpu_blit_op(&p,10,0,22,10,11);
    opengpu_blit_splat(&p,24,1);
    opengpu_blit_emit(&p,14u<<26|1u<<25|22u<<15|0x2bu);
    opengpu_blit_emit(&p,16u<<26|1u<<25|5u<<20|24u<<15|0x2bu);
    opengpu_blit_emit(&p,0x0330000fu);opengpu_blit_emit(&p,0x30500073u);
    return p;
}
#endif
