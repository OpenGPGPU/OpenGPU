/* SPDX-License-Identifier: MIT */
#define OPENGPU_NATIVE_MMIO 1
#define OPENGPU_NATIVE_PROFILE_STORAGE_WORDS 117
#define OPENGPU_NATIVE_CAPTURE_MAGIC "OGCAP002"
#define OPENGPU_NATIVE_CAPTURE_DISCOVERY_WORDS 117
struct opengpu_device;
#ifdef OPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE
#if OPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE != 1
#error "ordinary112 live transport requires an explicit value of one"
#endif
#ifdef OPENGPU_NATIVE_MMIO_CLEANUP_TEST_PEER
#error "ordinary112 live transport cannot compile a cleanup test peer"
#endif
#else
static void sample_cpu_cleanup(struct opengpu_device *,const char *,unsigned);
#define OPENGPU_NATIVE_MMIO_CLEANUP_TEST_PEER(g,d,s) sample_cpu_cleanup(g,d,s)
#endif
#define OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN unused_native_component_main
#include "opengpu_native_submit_fixture.c"
#undef OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN
#include "graphics_sample_kernel.h"
/* Test control peer only. Read production cleanup trace and acknowledge its
 * exact sequence; neither this ACK nor the synthetic coarse result proves
 * shader execution, GNY2 publication or image correctness. */
static void sample_path(char *path,size_t bytes,const char *directory,const char *name)
{int n=snprintf(path,bytes,"%s/%s",directory,name);assert(n>0 && (size_t)n<bytes);}
#ifndef OPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE
static void sample_cpu_cleanup(struct opengpu_device *gpu,const char *directory,unsigned sequence)
{
    (void)gpu;char path[4096],name[64];snprintf(name,sizeof(name),"cleanup-%u.trace",sequence);
    sample_path(path,sizeof(path),directory,name);FILE *f=fopen(path,"rb");assert(f);char magic[8];u32 count;
    assert(fread(magic,1,8,f)==8 && !memcmp(magic,"OGCLN001",8) && !native_mmio_read_le(f,&count));
    for(u32 i=0;i<count;i++){u32 words[4];for(unsigned j=0;j<4;j++)assert(!native_mmio_read_le(f,&words[j]));}
    assert(fgetc(f)==EOF && !fclose(f));snprintf(name,sizeof(name),"cleanup-%u.observed",sequence);
    sample_path(path,sizeof(path),directory,name);f=fopen(path,"wx");assert(f);
    fwrite("OGCLA001",1,8,f);native_mmio_le(f,sequence,4);native_mmio_le(f,GPU_UCMD_STATUS_READY,4);
    native_mmio_le(f,GPU_IRQ_ENABLE,4);native_mmio_le(f,0,4);native_mmio_le(f,1,4);assert(!fclose(f));
}
static struct native_kernel_held_receipt sample_cpu_held(struct native_kernel_session *s,unsigned index)
{
    struct native_kernel_held_receipt h={.record_index=index,.command_id=s->gpu.hw.unified_command_id,
        .completion=0x8700u|s->gpu.hw.unified_command_id,.append_read_bytes=(1ull<<32)+64,
        .held_legacy_status=GPU_STATUS_DONE,.held_status=GPU_UCMD_STATUS_READY|GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY,
        .held_irq=GPU_IRQ_ENABLE|GPU_IRQ_PENDING,.held_irq_asserted=1};
    h.gnx[0]=OPENGPU_NATIVE_BINNED_COMPLETION_MAGIC;h.gnx[6]=s->gpu.hw.native_context;
    h.gnx[7]=s->gpu.hw.native_pass;h.gnx[8]=s->gpu.hw.native_generation;return h;
}
#endif
void graphics_native_source_observer(void *record,uint32_t handle,uint64_t offset,uint32_t bytes,const void *source)
{assert(!native_graphics_source_observe(record,handle,offset,bytes,source));}
int graphics_native_source_write(struct native_kernel_session *s,FILE *f,struct native_graphics_source_record *r)
{
    uint64_t physical,bytes;int e=native_kernel_original_physical(s,r->color_handle,&physical,&bytes);
    if(!e)e=native_graphics_source_allocation(r,r->color_handle,physical,bytes);
    return e?e:native_graphics_source_write(f,r);
}
int graphics_sample_profile_read(const char *path,struct opengpu_native_sample_runtime *out)
{
    struct opengpu_native_sample_word words[117];struct opengpu_native_sample_runtime value;u32 count;char magic[8];
    if(!path || !out)return -EINVAL;
    FILE *f=fopen(path,"rb");if(!f)return -errno;
    int e=-EINVAL;
    if(fread(magic,1,8,f)!=8 || memcmp(magic,"OGNP0002",8) || native_mmio_read_le(f,&count) || count!=112)goto done;
    for(unsigned i=0;i<112;i++)if(native_mmio_read_le(f,&words[i].address) || native_mmio_read_le(f,&words[i].value))goto done;
    if(native_mmio_read_le(f,&count) || count!=5)goto done;
    for(unsigned i=112;i<117;i++)if(native_mmio_read_le(f,&words[i].address) || native_mmio_read_le(f,&words[i].value))goto done;
    if(fgetc(f)!=EOF || ferror(f))goto done;
    e=opengpu_native_sample_runtime_decode(words,117,&value);
done:if(fclose(f) && !e)e=-EIO;if(!e)*out=value;return e;
}
struct native_kernel_session *graphics_sample_session_create(unsigned id,const struct opengpu_native_sample_runtime *runtime)
{
    if(opengpu_native_sample_runtime_validate(runtime))return NULL;
    struct native_kernel_session *s=native_kernel_session_create(id);if(!s)return NULL;
    s->profile=calloc(1,sizeof(*s->profile));assert(s->profile);
    for(unsigned i=0;i<117;i++)s->profile->words[i]=(struct native_kernel_profile_word){runtime->observed[i].address,runtime->observed[i].value};
    s->profile->limits=runtime->limits;s->profile->capabilities=runtime->capabilities;s->profile->advanced=true;
    s->gpu.hw.capabilities=runtime->capabilities;s->gpu.hw.native_sources.available=runtime->limits.available;
    s->gpu.hw.native_sources.capacity=runtime->limits.capacity;s->gpu.hw.native_sources.engines=runtime->limits.engines;
    s->gpu.hw.native_sources.frame=runtime->limits.frame;
    s->gpu.hw.native_layered_transport=true;s->gpu.hw.native_sample_transport=true;
    s->gpu.hw.native_advanced_blend_transport=true;return s;
}
int graphics_sample_private_code(struct native_kernel_session *s,unsigned index,const struct drm_opengpu_native_program_view programs[2])
{
    struct opengpu_sched_job *j=native_record(s,index);if(!j || !programs)return -EINVAL;
    u32 job;int error=opengpu_private_snapshot_native_layered_job_address(j->native_owner,j->native_job_segment,&job);
    if(error)return error; /* actual protected job */
    u32 draw=test_read_va(s,job+OPENGPU_NATIVE_BINNED_JOB_JOB_DRAWBASE_WORD*4);
    u32 frame=test_read_va(s,job+OPENGPU_NATIVE_BINNED_JOB_JOB_FRAMEBASE_WORD*4);
    u32 state=test_read_va(s,frame+OPENGPU_NATIVE_BINNED_FRAME_FRAME_STATES_BASE_WORD*4);
    u32 pcs[2]={test_read_va(s,draw+OPENGPU_NATIVE_BINNED_DRAW_DRAW_VERTEX_GEOMETRY_VERTEX_SHADERPC_WORD*4),
        test_read_va(s,state+OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_SHADERPC_WORD*4)};
    for(unsigned stage=0;stage<2;stage++){
        struct native_original *o=original_handle(s,programs[stage].handle);if(!o)return -EINVAL;
        for(unsigned i=0;i<programs[stage].bytes;i+=4){u32 want;memcpy(&want,(u8 *)o->gem.vaddr+programs[stage].offset+i,4);
            if(test_read_va(s,pcs[stage]+i)!=want){fprintf(stderr,"private ABI1 code mismatch stage=%u pc=%08x offset=%u actual=%08x original=%08x\n",stage,pcs[stage],i,test_read_va(s,pcs[stage]+i),want);return -EINVAL;}}
    }
    return 0;
}
#include "graphics_sample_source_plan.inc"
#ifdef OPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE
#include "graphics_sample_live.inc"
#else
int graphics_sample_cpu_capture(struct native_kernel_session *s,const char *directory,struct native_graphics_source_record records[2])
{
    char path[4096];sample_path(path,sizeof(path),directory,"union.memory");
    int e=native_kernel_export_memory(s,path);if(e)return e;
    for(unsigned i=0;i<2;i++){
        char name[64];snprintf(name,sizeof(name),"record-%u.capture",i);sample_path(path,sizeof(path),directory,name);
        e=native_kernel_capture(s,i,path,10000000,16);if(e)return e;
        /* Synthetic control values only: no GPU writes or image/GNY2 success. */
        struct native_kernel_held_receipt held=sample_cpu_held(s,i);
        snprintf(name,sizeof(name),"record-%u.cpu-retire",i);sample_path(path,sizeof(path),directory,name);
        e=native_kernel_drain_held(s,i,&held,path);if(e)return e;
        struct native_kernel_observed_receipt observed={.held=held,.retired_status=GPU_UCMD_STATUS_READY,
            .retired_irq=GPU_IRQ_ENABLE,.external_drained=1};
        e=native_kernel_observe_retired(s,i,&observed);if(e)return e;
        snprintf(name,sizeof(name),"native%s.source.json",i?"-next":"");sample_path(path,sizeof(path),directory,name);
        FILE *f=fopen(path,"wx");if(!f)return -errno;
        e=graphics_native_source_write(s,f,&records[i]);if(fclose(f) && !e)e=-EIO;if(e)return e;
    }
    e=native_kernel_begin_cleanup(s,directory);if(e)return e;
    for(unsigned i=0;i<2;i++){e=native_kernel_finish_observed(s,i);if(e)return e;}
    return native_kernel_end_cleanup(s);
}
#endif
