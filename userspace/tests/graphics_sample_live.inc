/* Ordinary112 external receipt/cleanup transport; no CPU completion peer. */
#include "graphics_sample_owner_ipc.h"

static int sample_live_path(char path[4096],const char *directory,const char *name)
{
    if(!directory || !name)return -EINVAL;
    int n=snprintf(path,4096,"%s/%s",directory,name);
    return n<0 || n>=4096?-ENAMETOOLONG:0;
}
static int sample_live_input(const char *directory,const char *name)
{
    char path[4096];struct stat st;int e=sample_live_path(path,directory,name);if(e)return e;
    if(lstat(path,&st))return -errno;
    return !S_ISREG(st.st_mode) || st.st_size<=0?-EINVAL:0;
}
static int sample_live_session(struct native_kernel_session *s,const struct drm_opengpu_native_program_view programs[2])
{
    if(!s || !programs || !s->profile || !s->profile->advanced || s->profile->missing ||
        s->record_count!=2 || s->count!=2 || s->context_destroyed)return -EINVAL;
    struct opengpu_native_sample_runtime runtime={0};
    for(unsigned i=0;i<117;i++)runtime.observed[i]=(struct opengpu_native_sample_word){s->profile->words[i].address,s->profile->words[i].value};
    runtime.limits=s->profile->limits;runtime.capabilities=s->profile->capabilities;
    runtime.id=runtime.observed[112].value;runtime.shader_config=runtime.observed[116].value;
    int e=opengpu_native_sample_runtime_validate(&runtime);if(e)return e;
    if(s->gpu.hw.capabilities!=runtime.capabilities || !s->gpu.hw.native_layered_transport ||
        !s->gpu.hw.native_sample_transport || !s->gpu.hw.native_advanced_blend_transport ||
        s->gpu.hw.native_sources.available!=runtime.limits.available ||
        s->gpu.hw.native_sources.capacity!=runtime.limits.capacity || s->gpu.hw.native_sources.engines!=runtime.limits.engines ||
        memcmp(s->gpu.hw.native_sources.frame.words,runtime.limits.frame.words,sizeof(runtime.limits.frame.words)))return -EPROTO;
    struct native_mmio_state *m=native_mmio_state(&s->gpu);
    if(m->exported || m->native_submissions || m->native_waiting || m->receipt_loaded ||
        m->cleanup_active || m->cleanup_done || m->cleanup_error)return -EBUSY;
    for(unsigned stage=0;stage<2;stage++){
        const struct drm_opengpu_native_program_view *p=&programs[stage];u64 physical,bytes;
        if(!p->handle || !p->bytes || p->bytes>2048 || ((p->offset|p->bytes)&3) || p->entry_offset || p->pad)return -EINVAL;
        e=native_kernel_original_physical(s,p->handle,&physical,&bytes);if(e)return e;
        if(p->offset>bytes || p->bytes>bytes-p->offset)return -EINVAL;
    }
    for(unsigned index=0;index<2;index++){
        struct native_kernel_record *r=native_session_record(s,index);
        if(!r || !r->job || !r->job->native_owner || r->hardware || r->captured || r->drained || r->verified || r->freed)return -EBUSY;
        for(unsigned stage=0;stage<2;stage++){
            const struct native_original *o=original_handle(s,programs[stage].handle);bool retained=false;
            for(unsigned i=0;i<r->job->object_count;i++)if(r->job->objects[i]==&o->gem.base)retained=true;
            if(!retained || !o->gem.vaddr)return -EINVAL;
        }
        e=graphics_sample_private_code(s,index,programs);if(e)return e;
    }
    return 0;
}
static int sample_live_program_inputs(struct native_kernel_session *s,const char *directory,
    const struct drm_opengpu_native_program_view programs[2])
{
    for(unsigned stage=0;stage<2;stage++){
        char path[4096];int e=sample_live_path(path,directory,stage?"native-real-fs.words":"native-real-vs.words");if(e)return e;
        const struct drm_opengpu_native_program_view *p=&programs[stage];uint8_t code[2048];
        FILE *f=fopen(path,"rb");if(!f)return -errno;
        size_t bytes=fread(code,1,p->bytes,f);int extra=fgetc(f);
        e=ferror(f)?-EIO:bytes!=p->bytes || extra!=EOF?-EINVAL:0;
        if(fclose(f) && !e)e=-EIO;
        const struct native_original *original=original_handle(s,p->handle);
        if(!e && memcmp(code,(const u8 *)original->gem.vaddr+p->offset,p->bytes))e=-EINVAL;
        if(e)return e;
    }
    return 0;
}
static struct native_kernel_held_receipt sample_live_held(const struct graphics_native_observation *v)
{
    struct native_kernel_held_receipt h={.record_index=v->record,.command_id=v->command_id,.completion=v->completion,
        .append_read_bytes=v->append_read_bytes,.held_legacy_status=v->held_legacy_status,.held_status=v->held_status,
        .held_irq=v->held_irq,.held_irq_asserted=v->held_irq_asserted};
    memcpy(h.gnx,v->gnx,sizeof(h.gnx));return h;
}
static bool sample_live_same_held(const struct graphics_native_observation *a,const struct graphics_native_observation *b)
{
    return a->record==b->record && a->command_id==b->command_id && a->completion==b->completion &&
        !memcmp(a->gnx,b->gnx,sizeof(a->gnx)) && a->append_read_bytes==b->append_read_bytes &&
        a->held_legacy_status==b->held_legacy_status && a->held_status==b->held_status &&
        a->held_irq==b->held_irq && a->held_irq_asserted==b->held_irq_asserted;
}
static bool sample_live_same_retired(const struct graphics_native_observation *a,const struct graphics_native_observation *b)
{
    return sample_live_same_held(a,b) && a->retired_status==b->retired_status && a->retired_irq==b->retired_irq &&
        a->retired_irq_asserted==b->retired_irq_asserted && a->external_drained==b->external_drained;
}
int graphics_sample_graphics_host_execute(struct native_kernel_session *s,const char *directory,
    const struct drm_opengpu_native_program_view programs[2])
{
    static const char *const inputs[]={"record-0.source-plan.json","record-1.source-plan.json",
        "compiler-reflection.json","native-real-vs.words","native-real-fs.words"};
    if(!directory)return -EINVAL;
    unsigned timeout;int e=native_mmio_timeout_seconds(&timeout);if(e)return e;
    e=sample_live_session(s,programs);if(e)return e;
    for(unsigned i=0;i<sizeof(inputs)/sizeof(inputs[0]);i++){e=sample_live_input(directory,inputs[i]);if(e)return e;}
    e=sample_live_program_inputs(s,directory,programs);if(e)return e;
    char path[4096],name[64],*temporary=NULL;
    e=sample_live_path(path,directory,"union.memory");if(e)return e;
    e=native_kernel_export_memory(s,path);if(e)return e;
    e=sample_live_path(path,directory,"record-0.capture");if(e)return e;
    e=native_kernel_capture(s,0,path,UINT64_C(100000000),37);if(e)return e;
    e=sample_live_path(path,directory,"ready.json");if(e)return e;
    FILE *f=native_mmio_file(path,&temporary);if(!f)return -EIO;
    fputs("{\"schema\":\"opengpu-native-sample-graphics-ready-v1\",\"ownerProfile\":\"two-cu-vga60-owner-v2-sample-stage\","
        "\"recordCount\":2,\"memory\":\"union.memory\",\"captures\":[\"record-0.capture\",\"record-1.capture\"],"
        "\"sourcePlans\":[\"record-0.source-plan.json\",\"record-1.source-plan.json\"],"
        "\"reflection\":\"compiler-reflection.json\",\"programs\":[\"native-real-vs.words\",\"native-real-fs.words\"]}\n",f);
    e=native_mmio_close(f,path,temporary);if(e)return e;
    struct graphics_native_observation observed[2];
    for(unsigned index=0;index<2;index++){
        struct graphics_native_observation held;
        e=graphics_sample_graphics_held_wait(directory,index,timeout,&held);if(e)return e;
        struct native_kernel_held_receipt witness=sample_live_held(&held);
        snprintf(name,sizeof(name),"record-%u.retire",index);e=sample_live_path(path,directory,name);if(e)return e;
        e=native_kernel_drain_held(s,index,&witness,path);if(e)return e;
        e=graphics_sample_graphics_execution_wait(directory,index,timeout,&observed[index]);if(e)return e;
        if(!sample_live_same_held(&held,&observed[index]))return -EINVAL;
        struct native_kernel_observed_receipt receipt={.held=witness,.retired_status=observed[index].retired_status,
            .retired_irq=observed[index].retired_irq,.retired_irq_asserted=observed[index].retired_irq_asserted,
            .external_drained=observed[index].external_drained};
        e=native_kernel_observe_retired(s,index,&receipt);if(e)return e;
        if(!index){
            e=sample_live_path(path,directory,"record-1.capture");if(e)return e;
            e=native_kernel_capture(s,1,path,UINT64_C(100000000),37);if(e)return e;
        }
    }
    /* All mappings and original references survive both execution receipts.
     * The controller grants this distinct token only after independent source,
     * both image/GNY2 checks; real MMU revocations still require external ACKs. */
    e=graphics_sample_graphics_cleanup_wait(directory,timeout);if(e)return e;
    e=native_kernel_begin_cleanup(s,directory);if(e)return e;
    for(unsigned i=0;i<2;i++){e=native_kernel_finish_observed(s,i);if(e)return e;}
    e=native_kernel_end_cleanup(s);if(e)return e;
    struct graphics_native_observation final[2];
    e=graphics_sample_graphics_final_wait(directory,timeout,final);if(e)return e;
    for(unsigned i=0;i<2;i++)if(!sample_live_same_retired(&observed[i],&final[i]))return -EINVAL;
    return 0;
}
