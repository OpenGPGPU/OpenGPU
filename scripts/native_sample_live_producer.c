/* Live private qualification producer. Never calls CPU completion/retirement.
 * The external actual-DUT controller owns all execution/oracle authorizations. */
#define _POSIX_C_SOURCE 200809L
#define OPENGPU_NATIVE_MMIO 1
#define OPENGPU_NATIVE_PROFILE_STORAGE_WORDS 117
#define OPENGPU_NATIVE_CAPTURE_MAGIC "OGCAP002"
#define OPENGPU_NATIVE_CAPTURE_DISCOVERY_WORDS 117
#define OPENGPU_NATIVE_SAMPLE_CPU_ENTRY sample_cpu_only_entry_unused
#include "native_sample_submit_producer.c"
#undef OPENGPU_NATIVE_SAMPLE_CPU_ENTRY

static const char *live_directory;
static void live_require(bool ok,const char *message)
{if(!ok){fprintf(stderr,"native sample live producer: %s\n",message);fflush(stderr);_Exit(2);}}
static void live_path(char out[4096],const char *name)
{int n=snprintf(out,4096,"%s/%s",live_directory,name);live_require(n>0 && n<4096,"path overflow");}
static void live_end(FILE *f)
{live_require(fgetc(f)==EOF && !ferror(f),"noncanonical trailing receipt bytes");live_require(!fclose(f),"receipt close");}
static u32 live_u32(FILE *f)
{u32 v;live_require(read_le(f,&v),"truncated wire word");return v;}
static void live_magic(FILE *f,const char *magic)
{char b[8];live_require(fread(b,1,8,f)==8 && !memcmp(b,magic,8),"wrong private wire magic");}
static FILE *live_wait(const char *name)
{
    char path[4096],error[4096];live_path(path,name);live_path(error,"error");
    struct timespec start,now,pause={.tv_nsec=10000000};live_require(!clock_gettime(CLOCK_MONOTONIC,&start),"clock");
    unsigned timeout;live_require(!native_mmio_timeout_seconds(&timeout),"timeout configuration");
    for(;;){FILE *f=fopen(path,"rb");if(f)return f;live_require(errno==ENOENT,"receipt open");
        struct stat st;live_require(lstat(error,&st) && errno==ENOENT,"controller error");
        live_require(!clock_gettime(CLOCK_MONOTONIC,&now) && now.tv_sec-start.tv_sec<(time_t)timeout,"controller receipt timeout");
        live_require(!nanosleep(&pause,NULL) || errno==EINTR,"receipt wait");}
}
static void live_marker(const char *name,const char *text)
{
    char path[4096],temp[4096];live_path(path,name);int n=snprintf(temp,sizeof(temp),"%s.tmp",path);
    live_require(n>0 && (size_t)n<sizeof(temp),"marker path");FILE *f=fopen(temp,"wx");live_require(f!=NULL,"marker exists");
    live_require(fputs(text,f)>=0 && !fclose(f) && !link(temp,path) && !unlink(temp),"marker publication");
}
static void live_profile(struct native_kernel_session *s,const char *path)
{
    FILE *f=fopen(path,"rb");live_require(f!=NULL,"profile open");live_magic(f,"OGNP0002");
    struct native_kernel_profile_word words[117];live_require(live_u32(f)==112,"profile count");
    for(unsigned i=0;i<112;i++){
        words[i]=(struct native_kernel_profile_word){live_u32(f),live_u32(f)};
        live_require(words[i].address==sample_expected_profile[i].address && words[i].value==sample_expected_profile[i].value,"sample profile identity");}
    live_require(live_u32(f)==5,"auxiliary count");
    for(unsigned i=112;i<117;i++){
        words[i]=(struct native_kernel_profile_word){live_u32(f),live_u32(f)};
        live_require(words[i].address==sample_expected_profile[i].address,"auxiliary order");}
    live_end(f);
    struct bridge_profile p={words,117};struct opengpu_native_source_limits limits={0};bool layered=false,sample=false;
    u32 id=profile_read(&p,GPU_REG_ID),caps=profile_read(&p,GPU_REG_CAPABILITIES);
    live_require((id&0xffff0000u)==0x47550000u && profile_read(&p,0x320)==1048576 &&
        opengpu_read_native_sample_transport_limits(id,profile_read(&p,0x368),caps,0x1000,&p,profile_read,&limits,&layered,&sample) &&
        layered && sample,"actual profile cache validation");
    struct opengpu_native_sample_word observed[117];
    for(unsigned i=0;i<117;i++)observed[i]=(struct opengpu_native_sample_word){words[i].address,words[i].value};
    live_require(!opengpu_native_sample_runtime_decode(observed,117,&sample_runtime),"private112 runtime discovery");
    s->profile=calloc(1,sizeof(*s->profile));live_require(s->profile!=NULL,"profile allocation");
    memcpy(s->profile->words,words,sizeof(words));s->profile->limits=limits;s->profile->capabilities=caps;
    s->gpu.hw.capabilities=caps;s->gpu.hw.native_sources.available=sample_runtime.limits.available;
    s->gpu.hw.native_sources.capacity=sample_runtime.limits.capacity;
    s->gpu.hw.native_sources.engines=sample_runtime.limits.engines;
    s->gpu.hw.native_sources.frame=sample_runtime.limits.frame;
    s->gpu.hw.native_layered_transport=true;s->gpu.hw.native_sample_transport=true;
}
static u32 vertex_addi(unsigned rd,unsigned rs,int value)
{return ((u32)value&4095u)<<20|rs<<15|rd<<7|0x13;}
static unsigned vertex_address(u32 *code,unsigned n,unsigned rd,unsigned base,unsigned offset)
{
    /* Native validator tracks this bounded constant slice arithmetic. */
    code[n++]=vertex_addi(10,0,(int)(offset/512));
    code[n++]=9u<<20|10u<<15|1u<<12|10u<<7|0x13;
    code[n++]=10u<<20|base<<15|rd<<7|0x33;
    return n;
}
static unsigned live_vertex(u32 *code)
{
    /* Same real vector/SoA ISA as NativeProductShaderPrograms: x1 kernarg,
     * x8 localLinearBase*4, 128-lane arrays. ABI1 has no shader-written valid
     * flag: VertexShaderStage generates ordinal/valid from the fetched count. */
    unsigned n=0;code[n++]=0x00241393;code[n++]=0x007083b3;code[n++]=0x01007557;
    for(unsigned component=0;component<4;component++){
        n=vertex_address(code,n,5,7,component*512);
        code[n++]=(1u<<25)|(5u<<15)|(6u<<12)|(1u<<7)|7;
        n=vertex_address(code,n,6,7,(7+component)*512);
        code[n++]=(1u<<25)|(6u<<15)|(6u<<12)|(1u<<7)|0x27;
    }
    for(unsigned component=0;component<64;component++){
        /* Exact FP32 (component+1)/16, all slots/components distinct. */
        unsigned value=component+1,top=0;while((1u<<(top+1))<=value)++top;
        u32 bits=(top+123u)<<23|((value<<(23-top))&0x7fffff);
        unsigned high=(bits+2048)>>12;int low=(int)bits-(int)(high<<12);
        code[n++]=high<<12|12u<<7|0x37;if(low)code[n++]=vertex_addi(12,12,low);
        code[n++]=(0x17u<<26)|(1u<<25)|(12u<<15)|(4u<<12)|(1u<<7)|0x57;
        n=vertex_address(code,n,6,7,(11+component)*512);
        code[n++]=(1u<<25)|(6u<<15)|(6u<<12)|(1u<<7)|0x27;
    }
    code[n++]=OPENGPU_SHADER_CEASE;live_require(n<=512,"vertex code capacity");return n;
}
static void live_draw(struct native_test_record *r,struct drm_opengpu_native_layered_draw_plan *plan,unsigned vs_words)
{
    r->draw[17]=1;r->draw[19]=0x40800000;r->draw[26]=4;r->draw[27]=4;r->draw[28]=1;
    r->draw[34]=0;r->draw[35]=1;r->draw[37]=4;r->draw[40]=0xbf000000;r->draw[41]=0x3f800000; /* position 0,0,-.5,1: raster depth .25 */
    r->draw[311]=1;r->draw[325]=1;r->draw[343]=r->draw[344]=0xffffffff;
    r->draw[347]=4;r->draw[348]=4;r->draw[349]=0x3f800000;r->draw[351]=1;
    r->draw[334]=r->draw[335]=1; /* sampler texWidth/texHeight */
    r->state[46]=r->state[47]=1;
    memset(&plan->draw.vertex_uniforms,0,sizeof(plan->draw.vertex_uniforms));
    plan->draw.vertex_program.bytes=vs_words*4;
}
static u32 live_resource_va(const struct opengpu_private_snapshot *owner,u64 physical,u64 bytes)
{
    for(const struct snapshot_resource *r=owner->resources;r;r=r->next)
        for(const struct snapshot_resource_map *m=r->maps;m;m=m->next)
            if(m->mapped && physical>=m->physical && bytes<=m->bytes && physical-m->physical<=m->bytes-bytes)
                return (u32)(r->reservation.va+m->offset+physical-m->physical);
    live_require(false,"original view is not mapped in actual owner");return 0;
}
struct live_pte_output {FILE *file;unsigned count;};
static int live_pte_range(void *opaque,dma_addr_t physical,const void *data,size_t bytes)
{
    struct live_pte_output *out=opaque;(void)data;
    return fprintf(out->file,"%s{\"first\":%llu,\"end\":%llu}",out->count++?",":"",
        (unsigned long long)physical,(unsigned long long)(physical+bytes))<0?-EIO:0;
}
static void live_inputs(struct native_kernel_session *s,struct native_test_record *r,
    const struct sample_program *p,const char *case_id,unsigned samples,u32 job_va,unsigned vs_words)
{
    char path[4096],*temporary=NULL;live_path(path,"sample-inputs.json");FILE *f=native_mmio_file(path,&temporary);live_require(f!=NULL,"inputs open");
    u32 frame=test_read_va(s,job_va+21*4),states=test_read_va(s,frame+292*4),draw=test_read_va(s,job_va+16*4);
    u32 completion=test_read_va(s,job_va+48*4);
    const struct opengpu_private_snapshot *owner=native_record(s,0)->native_owner;
    fprintf(f,"{\"schema\":\"opengpu-native-sample-inputs-v1\",\"caseId\":\"%s\",\"gpuExecuted\":false,\"width\":4,\"height\":4,\"samples\":%u,\"context\":7,\"pass\":8,\"generation\":9,\"origin\":\"lower-left-half-integer\",\"jobVA\":%u,\"frameVA\":%u,\"drawVA\":%u,\"stateVA\":%u,\"fragmentPC\":%u,\"vertexPC\":%u,\"vertexWords\":%u,\"completionVA\":%u,\"completionBytes\":16384,\"fragmentMetadata\":[",
        case_id,samples,job_va,frame,draw,states,test_read_va(s,states+54*4),test_read_va(s,draw+342*4),vs_words,completion);
    for(unsigned i=0;i<15;i++){
        fprintf(f,"%s%u",i?",":"",p->meta[i]);
    }
    fputs("],\"fragmentWords\":[",f);
    for(unsigned i=0;i<p->meta[14];i++){
        fprintf(f,"%s%u",i?",":"",p->code[i]);
    }
    fputs("],\"images\":[",f);
    unsigned images=0;
    for(unsigned i=0;i<r->encoded.resource_count;i++)if(r->encoded.resources[i].role==1){
        const struct opengpu_native_frame_resource *v=&r->encoded.resources[i];u64 pa,bytes;
        live_require(!native_kernel_original_physical(s,v->gem_handle,&pa,&bytes),"image original backing");
        fprintf(f,"%s{\"slot\":%u,\"handle\":%u,\"allocationPhysical\":%llu,\"allocationBytes\":%llu,\"offset\":%llu,\"viewBytes\":%llu,\"viewVA\":%u,\"sampleMode\":%u,\"rowPitch\":%u,\"format\":%u,\"clear\":%u}",images++?",":"",v->index,v->gem_handle,
            (unsigned long long)pa,(unsigned long long)bytes,(unsigned long long)v->offset,(unsigned long long)v->bytes,live_resource_va(owner,pa+v->offset,v->bytes),samples==4?2:samples==2?1:0,4*(v->index==4?4:16)*samples,v->index==4?3:35,v->index==4?0x3f800000:0x3e800000);
    }
    const struct drm_opengpu_native_completion_view *view=(const void *)(uintptr_t)r->submit.reserved[0];u64 pa,bytes;
    live_require(!native_kernel_original_physical(s,view->view.handle,&pa,&bytes),"completion original backing");
    fprintf(f,"],\"completionOriginal\":{\"handle\":%u,\"allocationPhysical\":%llu,\"allocationBytes\":%llu,\"offset\":%llu,\"viewBytes\":16384},\"allocations\":[",view->view.handle,(unsigned long long)pa,(unsigned long long)bytes,(unsigned long long)view->view.offset);
    unsigned allocations=0;for(struct fixture_buffer_record *b=fixture_buffers;b;b=b->next)
        fprintf(f,"%s{\"first\":%llu,\"end\":%llu}",allocations++?",":"",(unsigned long long)b->dma,(unsigned long long)(b->dma+b->size));
    fputs("],\"originalObjects\":[",f);unsigned originals=0;
    for(const struct native_original *original=s->originals;original;original=original->next)
        fprintf(f,"%s{\"handle\":%u,\"physical\":%llu,\"bytes\":%llu,\"references\":%u,\"visible\":%s}",originals++?",":"",
            original->handle,(unsigned long long)original->gem.dma_addr,(unsigned long long)original->gem.base.size,
            original->refs,original->visible?"true":"false");
    fputs("],\"frameResources\":[",f);
    for(unsigned i=0;i<r->encoded.resource_count;i++){
        const struct opengpu_native_frame_resource *resource=&r->encoded.resources[i];u64 physical,allocation;
        live_require(!native_kernel_original_physical(s,resource->gem_handle,&physical,&allocation),"frame plan original backing");
        fprintf(f,"%s{\"role\":%u,\"index\":%u,\"handle\":%u,\"offset\":%llu,\"viewBytes\":%llu,\"viewVA\":%u,\"allocationPhysical\":%llu,\"allocationBytes\":%llu}",i?",":"",
            resource->role,resource->index,resource->gem_handle,(unsigned long long)resource->offset,(unsigned long long)resource->bytes,
            live_resource_va(owner,physical+resource->offset,resource->bytes),(unsigned long long)physical,(unsigned long long)allocation);
    }
    const struct drm_opengpu_native_layered_draw_plan *plan=(const void *)(uintptr_t)r->submit.plans;
    const struct drm_opengpu_compute_uniform_view *scratch=&plan->draw.vertex_scratch;
    live_require(!native_kernel_original_physical(s,scratch->handle,&pa,&bytes),"vertex scratch original backing");
    fprintf(f,"],\"vertexScratch\":{\"handle\":%u,\"offset\":%llu,\"viewBytes\":%llu,\"viewVA\":%u,\"allocationPhysical\":%llu,\"allocationBytes\":%llu}",
        scratch->handle,(unsigned long long)scratch->offset,(unsigned long long)scratch->bytes,live_resource_va(owner,pa+scratch->offset,scratch->bytes),
        (unsigned long long)pa,(unsigned long long)bytes);
    fputs(",\"privateSegments\":[",f);unsigned segments=0;
    for(const struct snapshot_segment *segment=owner->segments;segment;segment=segment->next)
        fprintf(f,"%s{\"va\":%llu,\"physical\":%llu,\"bytes\":%llu,\"allocationBytes\":%llu,\"executable\":%s}",segments++?",":"",
            (unsigned long long)segment->va,(unsigned long long)segment->backing.dma,(unsigned long long)segment->bytes,
            (unsigned long long)segment->backing.size,segment->executable?"true":"false");
    fputs("],\"resourceMaps\":[",f);unsigned maps=0;
    for(const struct snapshot_resource *resource=owner->resources;resource;resource=resource->next)
        for(const struct snapshot_resource_map *map=resource->maps;map;map=map->next)
            fprintf(f,"%s{\"va\":%llu,\"physical\":%llu,\"bytes\":%llu,\"writable\":%s}",maps++?",":"",
                (unsigned long long)(resource->reservation.va+map->offset),(unsigned long long)map->physical,
                (unsigned long long)map->bytes,map->writable?"true":"false");
    fputs("],\"pteRanges\":[",f);struct live_pte_output ptes={.file=f};
    live_require(!fixture_mmu_visit_pte_pages(&s->gpu,live_pte_range,&ptes),"PTE range enumeration");
    fputs("]}\n",f);live_require(!native_mmio_close(f,path,temporary),"inputs publication");
}
static struct native_kernel_held_receipt live_held(FILE *f)
{
    struct native_kernel_held_receipt h={0};h.record_index=live_u32(f);h.command_id=live_u32(f);h.completion=live_u32(f);
    for(unsigned i=0;i<12;i++)h.gnx[i]=live_u32(f);
    h.append_read_bytes=live_u32(f);h.append_read_bytes|=(u64)live_u32(f)<<32;
    h.held_legacy_status=live_u32(f);h.held_status=live_u32(f);h.held_irq=live_u32(f);h.held_irq_asserted=live_u32(f);return h;
}
int main(int argc,char **argv)
{
    live_require(argc==6,"usage: producer program.ognsf profile.ognp session-directory 1|2|4 case-id");
    live_directory=argv[3];struct stat st;live_require(!lstat(live_directory,&st) && S_ISDIR(st.st_mode),"session directory");
    live_require(!strcmp(argv[4],"1") || !strcmp(argv[4],"2") || !strcmp(argv[4],"4"),"sample count");
    live_require(*argv[5]!=0,"case ID");for(const char *q=argv[5];*q;q++)live_require((*q>='a'&&*q<='z')||(*q>='0'&&*q<='9')||*q=='-'||*q=='_',"case ID characters");
    struct sample_program p={0};live_require(read_program(argv[1],&p) && valid_program(&p),"ordinary ABI6 compiler contract");
    struct native_kernel_session *s=native_kernel_session_create(7);live_require(s!=NULL,"session create");live_profile(s,argv[2]);
    unsigned samples=(unsigned)strtoul(argv[4],NULL,10);test_next_handle=3;
    u32 *vs=native_kernel_original_allocate(s,1,4096);live_require(vs!=NULL,"VS GEM");unsigned vs_words=live_vertex(vs);
    live_require(native_kernel_original_allocate(s,2,4096)!=NULL,"defaults GEM");
    void *fs=native_kernel_original_allocate(s,3,4096);live_require(fs!=NULL,"FS GEM");memcpy(fs,p.bytes,p.meta[14]*4);
    struct native_test_record *r=calloc(1,sizeof(*r));struct drm_opengpu_native_layered_draw_plan *plan=calloc(1,sizeof(*plan));live_require(r&&plan,"record allocation");
    make_record(s,r,plan,&p,samples);live_draw(r,plan,vs_words);
    live_require(opengpu_native_shader_validate_words_sample_inputs(vs,vs_words,40960,128,0,1,1,16,0,true,false,false,false),"vertex shader validation");
    live_require(!native_kernel_submit(s,&r->submit,NULL,NULL),"protected ioctl");
    struct opengpu_sched_job *job=native_record(s,0);u32 va=0;live_require(job && !opengpu_private_snapshot_native_layered_job_address(job->native_owner,job->native_job_segment,&va),"private GNJ2");
    u32 frame=test_read_va(s,va+21*4),states=test_read_va(s,frame+292*4);live_require(test_read_va(s,states+29*4)==p.meta[7],"rewritten GFS depth override policy");assert_rx(s,test_read_va(s,states+54*4),&p);
    live_inputs(s,r,&p,argv[5],samples,va,vs_words);
    char path[4096];live_path(path,"union.memory");live_require(!native_kernel_export_memory(s,path),"union export");
    live_path(path,"record-0.capture");live_require(!native_kernel_capture(s,0,path,1000000000,37),"capture enqueue");
    live_marker("READY","native sample live owner waiting for actual DUT receipts; no GPU result\n");
    FILE *f=live_wait("record-0.held.response");live_magic(f,"OGNSH001");struct native_kernel_held_receipt h=live_held(f);live_end(f);
    live_path(path,"record-0.retire");live_require(!native_kernel_drain_held(s,0,&h,path),"actual held drain");
    f=live_wait("record-0.retired.response");live_magic(f,"OGNSR001");struct native_kernel_observed_receipt observed={.held=live_held(f)};
    observed.retired_status=live_u32(f);observed.retired_irq=live_u32(f);observed.retired_irq_asserted=live_u32(f);observed.external_drained=live_u32(f);live_end(f);
    live_require(!native_kernel_observe_retired(s,0,&observed),"actual retired observation");
    f=live_wait("oracle-authorized.response");live_magic(f,"OGNSBA01");live_require(live_u32(f)==1,"oracle authorization");live_end(f);
    live_require(!native_kernel_begin_cleanup(s,live_directory) && !native_kernel_finish_observed(s,0) && !native_kernel_end_cleanup(s),"ACK-gated owner cleanup");
    f=live_wait("final-authorized.response");live_magic(f,"OGNSOK01");live_require(live_u32(f)==1,"final oracle authorization");live_end(f);
    live_require(!native_kernel_session_destroy(s) && !live_allocations && !fixture_buffers,"session destruction");free(plan);free(r);
    puts("native sample live owner: actual observations and oracle authorized cleanup completed");return 0;
}
