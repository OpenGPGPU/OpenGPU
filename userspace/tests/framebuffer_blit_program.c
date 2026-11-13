/* SPDX-License-Identifier: MIT */
/* Actual emitted instructions + production admission + independent pixel
 * oracle. This is CPU validation, not device scheduling/RTL evidence. */
#include <assert.h>
#include <math.h>
#include <limits.h>
#include <stdio.h>
#include "opengpu_framebuffer_blit.h"
#include "opengpu_shader_validator.h"
#include "cs_word_vm.h"

static unsigned cases;
static void check(struct opengpu_framebuffer_blit d)
{
    uint32_t src[48],saved[48],dst[48],expected[48],maps[128],map_saved[128];
    for(unsigned i=0;i<48;i++) {src[i]=0x472a1501u+i*0x0305090bu;dst[i]=0x8ab6c2d9u+i*0x07030105u;}
    memcpy(saved,src,sizeof(src));memcpy(expected,dst,sizeof(dst));
    memset(maps,0xcd,sizeof(maps));
    struct opengpu_framebuffer_blit_plan p;
    assert(!opengpu_framebuffer_blit_plan_make(&d,sizeof(src),sizeof(dst),&p,maps,128));
    memcpy(map_saved,maps,sizeof(maps));
    /* Independent direct affine mapping. No planner, prepared axes or emitted
     * instruction helpers participate in choosing oracle source pixels. */
    for(unsigned y=0;y<d.destination_height;y++)for(unsigned x=0;x<d.destination_width;x++) {
        long double u=((long double)x+0.5L-d.destination_x)/d.destination_w;
        long double v=((long double)y+0.5L-d.destination_y)/d.destination_h;
        if(u<0 || u>=1 || v<0 || v>=1 || !d.mask)continue;
        if((d.flags&1) && ((int)x<d.scissor_x0 || (int)x>=d.scissor_x1 ||
            (int)y<d.scissor_y0 || (int)y>=d.scissor_y1))continue;
        int64_t sx=(int64_t)floorl(d.source_x+u*d.source_w);
        int64_t sy=(int64_t)floorl(d.source_y+v*d.source_h);
        if(sx<0 || sx>=d.source_width || sy<0 || sy>=d.source_height)continue;
        const unsigned char *s=(const void *)((const char *)src+sy*d.source_pitch+sx*4);
        unsigned char *t=(void *)((char *)expected+y*d.destination_pitch+x*4);
        unsigned sb=d.source_format==2 || d.source_format==4;
        unsigned db=d.destination_format==2 || d.destination_format==4;
        for(unsigned c=0;c<4;c++)if((d.mask&(1u<<c)) && (c!=3 || d.destination_format<3)) {
            unsigned from=c==0&&sb?2:c==2&&sb?0:c;
            unsigned to=c==0&&db?2:c==2&&db?0:c;
            t[to]=c==3&&d.source_format>=3?255:s[from];
        }
    }
    if(p.width && p.height) {
        struct opengpu_blit_program program=opengpu_framebuffer_blit_program(&d,&p);
        assert(program.words<=128 && !program.overflow);
        assert(opengpu_compute_shader_validate_words_storage(program.code,program.words,64,1,0));
        struct cs_vm_view memory[4]={{src,p.source_bytes,1},{dst,p.destination_bytes,3},{maps,p.map_words*4u,1},{0}};
        uint32_t grid[3]={p.width,p.height,1},local[3]={1,1,1};
        for(unsigned y=0;y<p.height;y++)for(unsigned x=0;x<p.width;x++) {
            uint32_t group[3]={x,y,0};
            cs_vm_group(program.code,program.words,grid,group,local,memory);
        }
    }
    if(memcmp(expected,dst,sizeof(dst))) {
        fprintf(stderr,"blit mismatch case%u sf%u df%u mask%u source(%d,%d,%d,%d) destination(%d,%d,%d,%d) plan%u,%u %ux%u\n",
            cases,d.source_format,d.destination_format,d.mask,d.source_x,d.source_y,d.source_w,d.source_h,
            d.destination_x,d.destination_y,d.destination_w,d.destination_h,p.x,p.y,p.width,p.height);
        assert(0);
    }
    assert(!memcmp(src,saved,sizeof(src)) && !memcmp(maps,map_saved,sizeof(maps)));
    cases++;
}

int main(void)
{
    struct opengpu_framebuffer_blit d={.version=1,.bytes=sizeof(d),.mask=15,
        .source_format=1,.destination_format=2,.source_width=5,.source_height=4,.source_pitch=28,
        .destination_width=6,.destination_height=5,.destination_pitch=32,
        .source_w=5,.source_h=4,.destination_w=6,.destination_h=5};
    for(unsigned sf=1;sf<=4;sf++)for(unsigned df=1;df<=4;df++)
    for(unsigned mask=0;mask<16;mask++)for(unsigned flip=0;flip<16;flip++) {
        d.source_format=sf;d.destination_format=df;d.mask=mask;
        d.source_x=flip&1?5:0;d.source_w=flip&1?-5:5;
        d.source_y=flip&2?4:0;d.source_h=flip&2?-4:4;
        d.destination_x=flip&4?6:0;d.destination_w=flip&4?-6:6;
        d.destination_y=flip&8?5:0;d.destination_h=flip&8?-5:5;
        d.flags=0;check(d);
        d.flags=1;d.scissor_x0=1;d.scissor_x1=5;d.scissor_y0=1;d.scissor_y1=4;check(d);
    }
    /* Clipping retains the original affine map, rather than stretching the
     * clipped source. Both large signed endpoint directions are bounded. */
    d.flags=0;d.mask=15;d.source_format=1;d.destination_format=2;
    const int32_t rectangles[][8]={
        {-2,-1,8,6,-1,-2,8,8},{0,0,5,4,1,1,3,2},{7,6,-9,-8,-3,-2,10,9},
        {INT_MIN,INT_MIN,INT_MAX,INT_MAX,0,0,6,5},
        {INT_MAX,INT_MAX,INT_MIN,INT_MIN,0,0,6,5},
        {0,0,5,4,INT_MIN,0,INT_MAX,5},
    };
    for(unsigned i=0;i<sizeof(rectangles)/sizeof(rectangles[0]);i++) {
        memcpy(&d.source_x,rectangles[i],sizeof(rectangles[i]));check(d);
    }
    uint32_t maps[128];struct opengpu_framebuffer_blit_plan p;
    d.source_x=d.source_y=d.destination_x=d.destination_y=0;
    d.source_w=5;d.source_h=4;d.destination_w=6;d.destination_h=5;
    const struct opengpu_framebuffer_blit valid=d;
    #define BAD(field,value) do { d=valid;d.field=(value);assert(opengpu_framebuffer_blit_plan_make(&d,192,192,&p,maps,128)==-1); } while(0)
    BAD(version,0);BAD(version,2);BAD(bytes,sizeof(d)-4);BAD(flags,2);BAD(mask,16);
    BAD(source_format,0);BAD(source_format,5);BAD(destination_format,0);BAD(destination_format,5);
    BAD(source_width,0);BAD(source_height,16385);BAD(destination_width,16385);
    BAD(source_pitch,19);BAD(destination_pitch,20);BAD(source_pitch,UINT_MAX-3);
    d=valid;assert(opengpu_framebuffer_blit_plan_make(&d,103,192,&p,maps,128)==-1);
    assert(opengpu_framebuffer_blit_plan_make(&d,192,151,&p,maps,128)==-1);
    assert(opengpu_framebuffer_blit_plan_make(&d,192,192,&p,maps,32)==-1);
    printf("GPU framebuffer blit CPU program: %u affine/format/mask/scissor cases, strict admission, full guards PASS\n",cases);
    return 0;
}
