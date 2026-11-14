/* CPU-only private contract/encoder checks; no shader or GPU executes. */
#include "opengpu_native_sample_graphics.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include "sample_profile.inc"
static struct opengpu_native_sample_runtime runtime;
static unsigned reads, fail_read, optional;
static struct opengpu_native_sample_word observed[117];
static int read_word(void *unused,uint32_t address,uint32_t *value)
{
    (void)unused;++reads;if(address>=0x500)++optional;
    if(reads==fail_read)return -EIO;
    for(unsigned i=0;i<117;i++)if(observed[i].address==address){*value=observed[i].value;return 0;}
    return -ENOENT;
}
static void discovery(void)
{
    struct opengpu_native_sample_runtime out,before;
    assert(!opengpu_native_sample_runtime_decode(profile,117,&runtime));
    assert(runtime.limits.capacity==128 && runtime.limits.engines==4 && runtime.limits.lanes==16 && runtime.limits.warps==8);
    assert(runtime.limits.frame.words[0]==0x474e4305 && !opengpu_native_sample_runtime_validate(&runtime));
    for(unsigned i=0;i<112;i++)for(unsigned bit=0;bit<32;bit++){
        memcpy(observed,profile,sizeof(observed));observed[i].value^=1u<<bit;
        memset(&out,0xa5,sizeof(out));before=out;
        assert(opengpu_native_sample_runtime_decode(observed,117,&out)<0 && !memcmp(&out,&before,sizeof(out)));
        memcpy(observed,profile,sizeof(observed));observed[i].address^=1u<<bit;
        assert(opengpu_native_sample_runtime_decode(observed,117,&out)<0 && !memcmp(&out,&before,sizeof(out)));
    }
    for(unsigned count=0;count<119;count++)if(count!=117){
        memset(&out,0xa5,sizeof(out));before=out;
        assert(opengpu_native_sample_runtime_decode(profile,count,&out)<0 && !memcmp(&out,&before,sizeof(out)));
    }
    for(unsigned i=112;i<117;i++){
        memcpy(observed,profile,sizeof(observed));observed[i].address^=4;
        assert(opengpu_native_sample_runtime_decode(observed,117,&out)<0);
        memcpy(observed,profile,sizeof(observed));observed[i].value=0;
        assert(opengpu_native_sample_runtime_decode(observed,117,&out)<0);
    }
    for(unsigned bank=0;bank<256;bank++){
        memcpy(observed,profile,sizeof(observed));observed[116].value=(observed[116].value&~0x00ff0000u)|(bank<<16);
        bool legal=bank>=2 && !(bank&1);
        memset(&out,0xa5,sizeof(out));before=out;
        assert((opengpu_native_sample_runtime_decode(observed,117,&out)==0)==legal);
        if(!legal)assert(!memcmp(&out,&before,sizeof(out)));
    }
    for(unsigned n=0;n<3;n++){
        memcpy(observed,profile,sizeof(observed));observed[116].value^=1u<<(n==0?0:n==1?8:24);
        reads=fail_read=optional=0;
        assert(opengpu_native_sample_runtime_read(0x540,NULL,read_word,&out)<0 && !optional);
    }
    memcpy(observed,profile,sizeof(observed));observed[113].value&=~GPU_CAP_PROTECTED_VM_STATE;
    reads=optional=0;assert(opengpu_native_sample_runtime_read(0x540,NULL,read_word,&out)<0 && reads==3 && !optional);
    memcpy(observed,profile,sizeof(observed));observed[114].value=0;
    reads=optional=0;assert(opengpu_native_sample_runtime_read(0x540,NULL,read_word,&out)<0 && reads==4 && !optional);
    memcpy(observed,profile,sizeof(observed));reads=fail_read=optional=0;
    assert(!opengpu_native_sample_runtime_read(0x540,NULL,read_word,&out));
    unsigned total=reads;assert(total==123 && optional==16);
    for(unsigned fail=1;fail<=total;fail++){
        fail_read=fail;reads=0;memset(&out,0xa5,sizeof(out));before=out;
        assert(opengpu_native_sample_runtime_read(0x540,NULL,read_word,&out)==-EIO && !memcmp(&out,&before,sizeof(out)));
    }
    fail_read=0;reads=optional=0;
    assert(opengpu_native_sample_runtime_read(0x53f,NULL,read_word,&out)<0 && !reads);
    for(unsigned i=0;i<112;i++)if(observed[i].address==0x368)observed[i].value=0xfffff;
    assert(opengpu_native_sample_runtime_read(0x540,NULL,read_word,&out)<0 && reads==2 && !optional);
    out=runtime;out.limits.capacity=64;assert(opengpu_native_sample_runtime_validate(&out)<0);
    out=runtime;out.limits.geometry_words[3]^=1;assert(opengpu_native_sample_runtime_validate(&out)<0);
}
static struct opengpu_native_sample_frame_request request(unsigned samples,unsigned width,unsigned height)
{
    struct opengpu_native_sample_frame_request result={.runtime=&runtime,.source_pitch={4096}};
    struct opengpu_native_frame_request *r=&result.frame;
    r->caps=&runtime.limits.frame;r->width=width;r->height=height;r->samples=samples;r->color_mask=15;
    r->context=7;r->pass=11;r->generation=13;r->state_count=1;r->scratch_stride=49152;
    for(unsigned i=0;i<5;i++){
        struct opengpu_native_frame_attachment *a=&r->attachments[i];
        a->format=i<4?35:3;a->load_op=1;a->channel_mask=i<4?15:0;
        for(unsigned c=0;c<(i<4?4u:1u);c++)a->clear[c]=i<4?0x3e800000:0x3f800000;
        a->image.gem=(struct opengpu_native_frame_gem){.handle=1+i,.offset=0,.bytes=(uint64_t)width*height*samples*(i<4?16:4)};
        a->image.row_pitch=width*samples*(i<4?16:4);a->image.store=1;
    }
    r->primitives=(struct opengpu_native_frame_gem){.handle=6,.bytes=65536};
    r->directory=(struct opengpu_native_frame_gem){.handle=7,.bytes=4u*1024*1024};
    r->pool[0]=(struct opengpu_native_frame_gem){.handle=8,.bytes=4096};
    for(unsigned e=0;e<4;e++)r->scratch[e]=(struct opengpu_native_frame_gem){.handle=9+e,.bytes=49152};
    return result;
}
static void frame_reject(struct opengpu_native_sample_frame_request *r)
{
    struct opengpu_native_frame_encoding out,before;
    memset(&out,0xa5,sizeof(out));before=out;
    assert(opengpu_native_sample_frame_encode(r,&out)<0 && !memcmp(&out,&before,sizeof(out)));
}
static void frames(void)
{
    for(unsigned samples=1;samples<=4;samples*=2){
        struct opengpu_native_sample_frame_request r=request(samples,4,4);
        struct opengpu_native_frame_encoding out;
        assert(!opengpu_native_sample_frame_encode(&r,&out));
        assert(out.resource_count==12 && out.frame.words[0]==0x474e4603 && out.frame.words[2]==3);
        assert(out.frame.words[299]==3 && out.frame.words[320]==1 && out.frame.words[321]==1 && out.frame.words[322]==4096);
        for(unsigned i=0;i<5;i++)assert(out.resources[i].bytes==(uint64_t)16*samples*(i<4?16:4));
        assert(out.tile.tile_log2==(samples==1?4u:3u));
        r=request(samples,2048,2048);assert(!opengpu_native_sample_frame_encode(&r,&out));
        assert(out.tile.tile_count==(samples==1?16384u:65536u));
        r.frame.width=2049;frame_reject(&r);
    }
    struct opengpu_native_sample_frame_request r=request(4,4,4);
    struct opengpu_native_frame_encoding out,before;
    r.frame.tile_log2=4;frame_reject(&r);r.frame.tile_log2=0;
    r.source_pitch[0]=UINT64_C(1)<<32;
    assert(!opengpu_native_sample_frame_encode(&r,&out) && !out.frame.words[322] && out.frame.words[323]==1);
    ++r.source_pitch[0];frame_reject(&r);r.source_pitch[0]=4096;
    r.resolve_pitch[0]=64;frame_reject(&r);r.resolve_pitch[0]=0;
    r.source_pitch[5]=64;frame_reject(&r);r.source_pitch[5]=0;
    r.frame.attachments[0].image.gem.bytes--;frame_reject(&r);r=request(4,4,4);
    r.frame.scratch[3].bytes=49151;frame_reject(&r);r=request(4,4,4);
    r.frame.attachments[1].image.gem.handle=1;frame_reject(&r);r=request(4,4,4);
    assert(opengpu_native_frame_caps_validate(r.frame.caps)==-EOPNOTSUPP);
    memset(&out,0x5a,sizeof(out));before=out;
    assert(opengpu_native_frame_encode(&r.frame,&out)==-EOPNOTSUPP && !memcmp(&before,&out,sizeof(out)));
    struct opengpu_native_binned_host_caps old=runtime.limits.frame;
    old.words[0]=0x474e4303;old.words[2]=0x474e4a01;old.words[7]=0x8813d902;
    assert(!opengpu_native_frame_caps_validate(&old));r.frame.caps=&old;frame_reject(&r);
}
static void jobs_and_plans(void)
{
    struct drm_opengpu_compute_uniform_view completion={.handle=30,.offset=64,.bytes=16384};
    struct opengpu_native_sample_job_encoding job,before;
    assert(!opengpu_native_sample_job_encode(&runtime,7,11,13,1,&completion,&job));
    uint32_t expected[128]={0x474e4a02,512,2,16,16,16,4,4};
    expected[17]=1;expected[20]=2048;expected[24]=7;expected[25]=13;expected[26]=11;expected[54]=1;
    assert(!memcmp(job.job.words,expected,sizeof(expected)) && !memcmp(&job.completion.view,&completion,sizeof(completion)));
    assert(!job.completion.reserved[0] && !job.completion.reserved[1]);
    for(unsigned test=0;test<4;test++){
        struct drm_opengpu_compute_uniform_view bad=completion;
        if(test==0)bad.bytes=16383;if(test==1)bad.offset=1;if(test==2)bad.handle=0;if(test==3)bad.offset=UINT64_MAX-63;
        memset(&job,0xa5,sizeof(job));before=job;
        assert(opengpu_native_sample_job_encode(&runtime,7,11,13,1,&bad,&job)<0 && !memcmp(&job,&before,sizeof(job)));
    }
    struct drm_opengpu_native_draw_plan plan={.vertex_program={.handle=40,.bytes=308*4},.fragment_program={.handle=41,.bytes=301*4},
        .vertex_scratch={.handle=42,.bytes=15*128*4}};
    for(unsigned stage=0;stage<2;stage++){
        struct drm_opengpu_native_uniform_stage *u=stage?&plan.fragment_uniforms:&plan.vertex_uniforms;
        u->active_mask=4095;u->defaults=(struct drm_opengpu_compute_uniform_view){.handle=50+stage,.bytes=4096};
        for(unsigned i=0;i<12;i++)u->blocks[i]=(struct drm_opengpu_compute_uniform_view){.handle=60+12*stage+i,.offset=4+64*i,.bytes=16384};
    }
    plan.bindings[15]=(struct drm_opengpu_compute_uniform_view){.handle=90,.offset=4,.bytes=48};
    struct drm_opengpu_native_layered_draw_plan out,old;
    assert(!opengpu_native_sample_ordinary_plan(&runtime,&plan,&out));
    assert(!memcmp(&out.draw,&plan,sizeof(plan)));
    const unsigned char *tail=(const unsigned char *)&out+sizeof(plan);
    for(size_t i=0;i<sizeof(out)-sizeof(plan);i++)assert(!tail[i]);
    for(unsigned test=0;test<5;test++){
        struct drm_opengpu_native_draw_plan bad=plan;
        if(test==0)bad.fragment_program.bytes=2052;if(test==1)bad.vertex_program.pad=1;
        if(test==2)bad.fragment_uniforms.active_mask=8191;if(test==3)bad.vertex_uniforms.blocks[11].bytes=16388;
        if(test==4)bad.fragment_uniforms.defaults.bytes=4100;
        memset(&out,0xa5,sizeof(out));old=out;
        assert(opengpu_native_sample_ordinary_plan(&runtime,&bad,&out)<0 && !memcmp(&out,&old,sizeof(out)));
    }
}
static void emit(const uint32_t *words,unsigned count)
{for(unsigned i=0;i<count;i++)for(unsigned b=0;b<4;b++)assert(putchar((int)(words[i]>>(8*b)&255))!=EOF);}
int main(int argc,char **argv)
{
    (void)argv;discovery();frames();jobs_and_plans();
    if(argc>1){for(unsigned samples=1;samples<=4;samples*=2){
        struct opengpu_native_sample_frame_request r=request(samples,4,4);struct opengpu_native_frame_encoding out;
        assert(!opengpu_native_sample_frame_encode(&r,&out));emit(out.frame.words,512);
    }}else puts("Private112/GNC5 direct GNJ2/GNF3/GNY2 source plans CPU PASS; no GPU");
    return 0;
}
