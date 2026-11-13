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
        if(d.source_format>=5) {
            if(d.mask&16)memcpy(t,s,3);
            if(d.mask&32)t[3]=s[3];
            continue;
        }
        unsigned sb=d.source_format==2 || d.source_format==4;
        unsigned db=d.destination_format==2 || d.destination_format==4;
        for(unsigned c=0;c<4;c++)if((d.mask&(1u<<c)) && (c!=3 || d.destination_format<3)) {
            unsigned from=c==0&&sb?2:c==2&&sb?0:c;
            unsigned to=c==0&&db?2:c==2&&db?0:c;
            if(c==3&&d.source_format>=3)t[to]=255;
            else if(d.flags&OPENGPU_FRAMEBUFFER_BLIT_LINEAR) {
                long double cx=d.source_x+u*d.source_w-0.5L,cy=d.source_y+v*d.source_h-0.5L;
                int64_t ix=(int64_t)floorl(cx),iy=(int64_t)floorl(cy);
                long double fx=cx-floorl(cx),fy=cy-floorl(cy);
                int64_t lx=d.source_w<0?(int64_t)d.source_x+d.source_w:d.source_x;
                int64_t hx=d.source_w<0?d.source_x:(int64_t)d.source_x+d.source_w;
                int64_t ly=d.source_h<0?(int64_t)d.source_y+d.source_h:d.source_y;
                int64_t hy=d.source_h<0?d.source_y:(int64_t)d.source_y+d.source_h;
                if(lx<0)lx=0;
                if(ly<0)ly=0;
                if(hx>d.source_width)hx=d.source_width;
                if(hy>d.source_height)hy=d.source_height;
                uint64_t wx=(uint64_t)floorl(fx*65536+0.5L),wy=(uint64_t)floorl(fy*65536+0.5L),sum=0;
                long double ideal=0;
                for(unsigned j=0;j<2;j++)for(unsigned k=0;k<2;k++) {
                    int64_t xx=ix+k,yy=iy+j;
                    if(xx<lx)xx=lx;
                    if(xx>=hx)xx=hx-1;
                    if(yy<ly)yy=ly;
                    if(yy>=hy)yy=hy-1;
                    uint8_t byte=((const uint8_t *)src)[yy*d.source_pitch+xx*4+from];
                    sum+=(uint64_t)byte*(k?wx:65536-wx)*(j?wy:65536-wy);
                    ideal+=byte*(k?fx:1-fx)*(j?fy:1-fy);
                }
                unsigned value=(unsigned)((sum+UINT64_C(0x80000000))>>32);
                assert(fabsl((long double)value-floorl(ideal+0.5L))<=1);
                t[to]=(uint8_t)value;
            } else t[to]=s[from];
        }
    }
    if(p.width && p.height) {
        struct opengpu_blit_program program=opengpu_framebuffer_blit_program(&d,&p);
        assert(program.words<=512 && !program.overflow);
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
        d.version=3;d.flags=2;check(d);d.flags=3;check(d);d.version=1;
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
    BAD(version,0);BAD(version,4);BAD(bytes,sizeof(d)-4);BAD(flags,2);BAD(mask,16);
    BAD(source_format,0);BAD(source_format,5);BAD(destination_format,0);BAD(destination_format,5);
    BAD(source_width,0);BAD(source_height,16385);BAD(destination_width,16385);
    BAD(source_pitch,19);BAD(destination_pitch,20);BAD(source_pitch,UINT_MAX-3);
    d=valid;assert(opengpu_framebuffer_blit_plan_make(&d,103,192,&p,maps,128)==-1);
    assert(opengpu_framebuffer_blit_plan_make(&d,192,151,&p,maps,128)==-1);
    assert(opengpu_framebuffer_blit_plan_make(&d,192,192,&p,maps,32)==-1);
    for(unsigned format=5;format<=6;format++)for(unsigned mask=0;mask<4;mask++)for(unsigned flip=0;flip<4;flip++) {
        if(format==6 && (mask&2))continue;
        d=valid;d.version=2;d.source_format=d.destination_format=format;d.mask=mask<<4;
        d.source_x=flip&1?5:0;d.source_w=flip&1?-5:5;
        d.source_y=flip&2?4:0;d.source_h=flip&2?-4:4;check(d);
    }
    d=valid;d.version=2;d.source_width=d.destination_width=4;
    d.source_height=d.destination_height=4;d.source_pitch=d.destination_pitch=16;
    d.source_w=d.destination_w=2;d.source_h=d.destination_h=4;d.destination_x=2;
    assert(!opengpu_framebuffer_blit_plan_make(&d,64,64,&p,maps,128));
    assert(opengpu_framebuffer_blit_disjoint(&d,&p,maps)); /* Adjacent columns over four rows. */
    d.destination_x=1;assert(!opengpu_framebuffer_blit_plan_make(&d,64,64,&p,maps,128));
    assert(!opengpu_framebuffer_blit_disjoint(&d,&p,maps));
    d.source_x=2;d.source_w=-2;d.destination_x=2;d.source_y=4;d.source_h=-4;
    assert(!opengpu_framebuffer_blit_plan_make(&d,64,64,&p,maps,128));
    assert(opengpu_framebuffer_blit_disjoint(&d,&p,maps)); /* Reversed source rows. */
    d=valid;d.version=2;d.source_format=5;d.destination_format=6;d.mask=16;
    assert(opengpu_framebuffer_blit_plan_make(&d,192,192,&p,maps,128)==-1);
    d.source_format=6;d.mask=32;
    assert(opengpu_framebuffer_blit_plan_make(&d,192,192,&p,maps,128)==-1);
    d.version=3;d.flags=OPENGPU_FRAMEBUFFER_BLIT_LINEAR;d.mask=16;
    assert(opengpu_framebuffer_blit_plan_make(&d,192,192,&p,maps,128)==-1);
    /* Full 40-bit weighted sum against an independent uint64 oracle. Include
     * both endpoint weights, all-ones products and low-limb carry boundaries. */
    d=(struct opengpu_framebuffer_blit){.version=3,.bytes=sizeof(d),.flags=2,.mask=15,
        .source_format=1,.destination_format=1,.source_width=2,.source_height=2,.source_pitch=8,
        .destination_width=1,.destination_height=1,.destination_pitch=4,
        .source_w=2,.source_h=2,.destination_w=1,.destination_h=1};
    p=(struct opengpu_framebuffer_blit_plan){.width=1,.height=1,.map_words=40,.source_bytes=16,.destination_bytes=4};
    struct opengpu_blit_program program=opengpu_framebuffer_blit_program(&d,&p);
    assert(!program.overflow && opengpu_compute_shader_validate_words_storage(program.code,program.words,64,1,0));
    uint32_t pixels[4],pixel,coordinate[40]={0},seed=0x137ac953u;
    const uint32_t weights[]={0,1,16383,32768,49153,65535,65536};
    for(unsigned n=0;n<4145;n++) {
        for(unsigned i=0;i<4;i++){seed=seed*1664525u+1013904223u;pixels[i]=n<7?(n&1?UINT32_MAX:0):seed;}
        seed=seed*1664525u+1013904223u;
        uint32_t wx=n<49?weights[n%7]:seed%65537;
        seed=seed*1664525u+1013904223u;
        uint32_t wy=n<49?weights[n/7]:seed%65537;
        coordinate[32]=0;coordinate[33]=4;coordinate[34]=wx;coordinate[35]=65536-wx;
        coordinate[36]=0;coordinate[37]=8;coordinate[38]=wy;coordinate[39]=65536-wy;
        struct cs_vm_view memory[4]={{pixels,16,1},{&pixel,4,3},{coordinate,160,1},{0}};
        uint32_t grid[3]={1,1,1},group[3]={0},local[3]={1,1,1};pixel=0;
        cs_vm_group(program.code,program.words,grid,group,local,memory);
        for(unsigned c=0;c<4;c++) {
            uint64_t sum=0;
            for(unsigned y=0;y<2;y++)for(unsigned x=0;x<2;x++)
                sum+=(uint64_t)((pixels[y*2+x]>>(c*8))&255)*(x?wx:65536-wx)*(y?wy:65536-wy);
            assert(((pixel>>(c*8))&255)==((sum+UINT64_C(0x80000000))>>32));
        }
    }
    printf("GPU framebuffer blit CPU program: %u affine/format/mask/scissor cases +4145 full bilinear products, strict admission, full guards PASS\n",cases);
    return 0;
}
