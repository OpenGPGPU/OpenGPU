/* New112-only observation sidecar. No expected GPU result or legacy schema. */
static u32 source_plan_va(const struct opengpu_private_snapshot *owner,u64 physical,u64 bytes)
{
    for(const struct snapshot_resource *r=owner->resources;r;r=r->next)
        for(const struct snapshot_resource_map *m=r->maps;m;m=m->next)
            if(m->mapped && physical>=m->physical && bytes<=m->bytes && physical-m->physical<=m->bytes-bytes)
                return (u32)(r->reservation.va+m->offset+physical-m->physical);
    return 0;
}
static int source_plan_original(FILE *f,struct native_kernel_session *s,const struct drm_opengpu_compute_uniform_view *v)
{
    u64 physical,bytes;int e=native_kernel_original_physical(s,v->handle,&physical,&bytes);if(e)return e;
    if(v->offset>bytes || !v->bytes || v->bytes>bytes-v->offset)return -EINVAL;
    return fprintf(f,"\"handle\":%u,\"allocationPhysical\":%llu,\"allocationBytes\":%llu,\"offset\":%llu,\"bytes\":%u",
        v->handle,(unsigned long long)physical,(unsigned long long)bytes,(unsigned long long)v->offset,v->bytes)<0?-EIO:0;
}
struct source_plan_pte_output {FILE *file;unsigned count;};
static int source_plan_pte_range(void *opaque,dma_addr_t physical,const void *data,size_t bytes)
{
    struct source_plan_pte_output *o=opaque;(void)data;
    return fprintf(o->file,"%s{\"first\":%llu,\"end\":%llu}",o->count++?",":"",(unsigned long long)physical,
        (unsigned long long)(physical+bytes))<0?-EIO:0;
}
static void source_plan_pte(FILE *f,struct native_kernel_session *s,u32 va,u64 bytes,unsigned *count)
{
    for(u64 at=va&~4095u;at<((u64)va+bytes+4095u)/4096u*4096u;at+=4096){
        const struct opengpu_buffer *table=vm_live_l1(&s->context.vm,(u32)at/MMU_SUPERPAGE_SIZE);assert(table);
        u32 root=((const u32 *)s->context.vm.root.cpu)[at>>22];
        u32 leaf=((const u32 *)table->cpu)[(at>>12)&1023];
        fprintf(f,"%s{\"va\":%u,\"rootAddress\":%llu,\"rootWord\":%u,\"leafAddress\":%llu,\"leafWord\":%u}",
            (*count)++?",":"",(u32)at,(unsigned long long)(s->context.vm.root.dma+4*(at>>22)),root,
            (unsigned long long)(table->dma+4*((at>>12)&1023)),leaf);
    }
}
int graphics_sample_source_plan_write(struct native_kernel_session *s,unsigned index,const char *path,
    const struct graphics_sample_source_plan *plan)
{
    if(!s || !path || !plan || index>1 || plan->resource_count>OPENGPU_NATIVE_FRAME_MAX_RESOURCES)return -EINVAL;
    struct opengpu_sched_job *j=native_record(s,index);if(!j)return -ENOENT;
    const struct opengpu_private_snapshot *owner=j->native_owner;u32 job;
    int e=opengpu_private_snapshot_native_layered_job_address(owner,j->native_job_segment,&job);if(e)return e;
    u32 draw=test_read_va(s,job+16*4),frame=test_read_va(s,job+21*4),state=test_read_va(s,frame+292*4);
    u32 pc[2]={test_read_va(s,draw+342*4),test_read_va(s,state+54*4)};
    u32 roots[2]={test_read_va(s,draw+507*4),test_read_va(s,state+119*4)};
    FILE *f=fopen(path,"wx");if(!f)return -errno;
    fprintf(f,"{\"schema\":\"opengpu-native-sample-graphics-source-v1\",\"recipe\":\"original-abi1-uniform-triangle-v1\",\"shaderABI\":1,\"record\":%u,\"gpuExecution\":false,\"context\":%u,\"pass\":11,\"generation\":13,\"width\":4,\"height\":4,\"samples\":1,\"jobVA\":%u,\"frameVA\":%u,\"drawVA\":%u,\"stateVA\":%u,\"rootPhysical\":%llu,\"asid\":%u,\"satp\":%u,\"programs\":[",
        index,s->context.id,job,frame,draw,state,(unsigned long long)s->context.vm.root.dma,s->context.vm.asid,
        0x80000000u|s->context.vm.asid<<22|(u32)(s->context.vm.root.dma>>12));
    for(unsigned stage=0;stage<2 && !e;stage++){
        const struct drm_opengpu_native_program_view *p=stage?&plan->draw.fragment_program:&plan->draw.vertex_program;
        struct drm_opengpu_compute_uniform_view v={.handle=p->handle,.offset=p->offset,.bytes=p->bytes};
        fprintf(f,"%s{\"stage\":%u,\"entryOffset\":%u,\"privateVA\":%u,\"layout\":[",stage?",":"",stage,p->entry_offset,pc[stage]);
        for(unsigned i=0;i<5;i++)fprintf(f,"%s%u",i?",":"",plan->layout[stage][i]);
        fputs("],",f);e=source_plan_original(f,s,&v);if(e)break;
        const struct native_original *o=original_handle(s,p->handle);
        fputs(",\"words\":[",f);
        for(unsigned i=0;i<p->bytes/4;i++){u32 word;memcpy(&word,(const u8 *)o->gem.vaddr+p->offset+4*i,4);fprintf(f,"%s%u",i?",":"",word);}
        fputs("]}",f);
    }
    fputs("],\"uniforms\":[",f);unsigned uniforms=0;
    for(unsigned stage=0;stage<2 && !e;stage++)for(unsigned slot=0;slot<13 && !e;slot++){
        const struct drm_opengpu_native_uniform_stage *u=stage?&plan->draw.fragment_uniforms:&plan->draw.vertex_uniforms;
        const struct drm_opengpu_compute_uniform_view *v=slot?&u->blocks[slot-1]:&u->defaults;
        u32 map=test_read_va(s,roots[stage]+(slot>6?9:8)*4);
        unsigned binding=slot?((map>>(((slot-1)%6)*5))&31):31;
        u32 gub=slot?test_read_va(s,roots[stage]+10*4)+64*binding:test_read_va(s,roots[stage]+13*4);
        fprintf(f,"%s{\"stage\":%u,\"slot\":%u,\"globalBinding\":%u,\"rootVA\":%u,\"bindingVA\":%u,\"bindingIndex\":%u,\"privateVA\":%u,",
            uniforms++?",":"",stage,slot,slot?plan->bindings[stage][slot-1]:40+stage,roots[stage],gub,binding,test_read_va(s,gub+8*4));
        e=source_plan_original(f,s,v);fputs("}",f);
    }
    fputs("],\"resources\":[",f);
    for(unsigned i=0;i<plan->resource_count && !e;i++){
        const struct opengpu_native_frame_resource *p=&plan->resources[i];
        struct drm_opengpu_compute_uniform_view v={.handle=p->gem_handle,.offset=p->offset,.bytes=p->bytes};
        u64 physical,bytes;e=native_kernel_original_physical(s,v.handle,&physical,&bytes);if(e)break;
        fprintf(f,"%s{\"role\":%u,\"index\":%u,\"viewVA\":%u,",i?",":"",p->role,p->index,source_plan_va(owner,physical+v.offset,v.bytes));
        e=source_plan_original(f,s,&v);fputs("}",f);
    }
    const struct drm_opengpu_compute_uniform_view *extra[]={&plan->draw.vertex_scratch,&plan->draw.bindings[0],&plan->completion.view};
    const char *names[]={"vertexScratch","vertexInput","completion"};
    for(unsigned i=0;i<3 && !e;i++){
        u64 physical,bytes;const struct drm_opengpu_compute_uniform_view *v=extra[i];e=native_kernel_original_physical(s,v->handle,&physical,&bytes);if(e)break;
        fprintf(f,"%s\"%s\":{\"viewVA\":%u,",i?",":"],",names[i],source_plan_va(owner,physical+v->offset,v->bytes));
        e=source_plan_original(f,s,v);fputs("}",f);
    }
    fputs(",\"privateSegments\":[",f);unsigned count=0;
    for(const struct snapshot_segment *p=owner->segments;p;p=p->next)
        fprintf(f,"%s{\"va\":%llu,\"physical\":%llu,\"bytes\":%llu,\"allocationBytes\":%llu,\"executable\":%s}",count++?",":"",
            (unsigned long long)p->va,(unsigned long long)p->backing.dma,(unsigned long long)p->bytes,(unsigned long long)p->backing.size,p->executable?"true":"false");
    fputs("],\"resourceMaps\":[",f);count=0;
    for(const struct snapshot_resource *r=owner->resources;r;r=r->next)for(const struct snapshot_resource_map *m=r->maps;m;m=m->next)
        fprintf(f,"%s{\"va\":%llu,\"physical\":%llu,\"bytes\":%llu,\"writable\":%s}",count++?",":"",
            (unsigned long long)(r->reservation.va+m->offset),(unsigned long long)m->physical,(unsigned long long)m->bytes,m->writable?"true":"false");
    fputs("],\"pteEntries\":[",f);count=0;
    for(const struct snapshot_segment *p=owner->segments;p;p=p->next)source_plan_pte(f,s,(u32)p->va,p->bytes,&count);
    for(const struct snapshot_resource *r=owner->resources;r;r=r->next)for(const struct snapshot_resource_map *m=r->maps;m;m=m->next)
        source_plan_pte(f,s,(u32)(r->reservation.va+m->offset),m->bytes,&count);
    fputs("],\"pteRanges\":[",f);struct source_plan_pte_output ranges={.file=f};
    if(!e)e=fixture_mmu_visit_pte_pages(&s->gpu,source_plan_pte_range,&ranges);
    fputs("]}\n",f);if(ferror(f))e=-EIO;if(fclose(f) && !e)e=-EIO;if(e)remove(path);return e;
}
