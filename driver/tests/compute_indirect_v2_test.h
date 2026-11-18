/* SPDX-License-Identifier: MIT */
/* Included by the production-body fixture. Kernel primitives/MMIO replies
 * are modeled here; these checks do not execute a shader or qualify a DUT. */
#ifndef OPENGPU_COMPUTE_INDIRECT_V2_TEST_H
#define OPENGPU_COMPUTE_INDIRECT_V2_TEST_H

static void indirect_v2_snapshot(const struct opengpu_sched_job *job,
    const u32 counts[3], bool counts_kernarg)
{
    assert(job->kernel.indirect_enabled && job->kernel.indirect_base==0xb2000000u);
    assert(job->indirect_snapshot.size==job->kernel.indirect_bytes);
    assert(!memcmp(job->kernel.grid,counts,12));
    unsigned offset=job->kernel.indirect_offset;
    assert(!memcmp((u8 *)job->indirect_snapshot.cpu+offset,counts,12));
    for(unsigned i=0;i<job->indirect_snapshot.size;i++)
        if(i<offset || i>=offset+12)assert(!((u8 *)job->indirect_snapshot.cpu)[i]);
    if(counts_kernarg) {
        assert(job->kernel.kernarg==(u64)job->kernel.indirect_base+offset);
        if(job->kernel.uniform_enabled) {
            assert(job->kernel.uniform.private_base==job->kernel.kernarg);
            assert(job->kernel.uniform.private_limit==job->kernel.kernarg+12);
        }
    }
}

static void indirect_v2_cases(struct drm_device *drm,struct drm_file *file,
    struct opengpu_render_context *context,struct drm_opengpu_compute_counter_source_resources full)
{
    struct opengpu_device *g=drm->dev;struct opengpu_file *rf=file->driver_priv;
    struct opengpu_resource_binding *binding=context->bindings[0],*kernarg=context->bindings[1];
    struct drm_gem_object *shader=binding->object;
    void *original_code=shader->vaddr;
    u64 original_bytes=binding->size,original_object_bytes=shader->size;
    bool counter_transport=g->hw.compute_counter_transport,uniform_transport=g->hw.compute_uniform_transport;
    bool indirect_transport=g->hw.compute_indirect_transport;
    void *original_kernarg_map=kernarg->object->vaddr;
    void *guard_kernarg=malloc(kernarg->object->size);assert(guard_kernarg);
    memset(guard_kernarg,0x6b,kernarg->object->size);kernarg->object->vaddr=guard_kernarg;
    u32 code[4]={0x0000a503u,0x0040a583u,0x0080a603u,OPENGPU_SHADER_CEASE};
    assert(opengpu_compute_shader_validate_words_features(code,4,12,128,false,false));
    shader->vaddr=code;binding->size=shader->size=sizeof(code);
    struct dma_resv resv={0};u32 data[2048]={0};
    struct drm_gem_object source={.resv=&resv,.dma_addr=0x64000000,.size=sizeof(data),.refs=1,.vaddr=data};
    struct drm_opengpu_compute_indirect_resources base={
        .source={.handle=53,.offset=4092},.max_groups={65535,65535,65535},
        .version=OPENGPU_COMPUTE_INDIRECT_VERSION2,.v2={.flags=OPENGPU_COMPUTE_INDIRECT_KERNARG_COUNTS}};
    base.resources.version=OPENGPU_COMPUTE_COUNTER_SOURCE_VERSION;
    base.resources.resources.version=OPENGPU_COMPUTE_UNIFORM_VERSION;
    base.resources.resources.resources.launch=full.resources.resources.launch;
    memset(base.resources.resources.resources.launch.grid,0,12);
    rf->handles[53]=&source;indirect_wait_object=&source;indirect_copy_source=data+1023;
    g->hw.compute_indirect_transport=true;
    g->hw.compute_counter_transport=g->hw.compute_uniform_transport=false;

    /* The wait publishes the original producer's counts. A prior READ fence
     * does not delay the private copy; a second job gets a distinct copy. */
    struct dma_fence writer={.refs=1},reader={.refs=1};
    resv.prior_write=&writer;resv.prior_read=&reader;resv.producer=true;
    unsigned copies=indirect_copies;
    assert(!opengpu_compute_indirect_resources_ioctl(drm,&base,file));
    struct opengpu_sched_job *first=queued[queued_count-1];
    const u32 first_counts[3]={2,1,1},second_counts[3]={7,3,2};
    indirect_v2_snapshot(first,first_counts,true);
    assert(writer.signaled&&!reader.signaled&&indirect_copies==copies+1&&source.refs==2);
    assert(!first->kernel.counter_enabled&&!first->kernel.uniform_enabled&&first->object_count==2);
    memcpy(data+1023,second_counts,12);
    assert(!opengpu_compute_indirect_resources_ioctl(drm,&base,file));
    struct opengpu_sched_job *second=queued[queued_count-1];
    indirect_v2_snapshot(first,first_counts,true);indirect_v2_snapshot(second,second_counts,true);
    assert(first->indirect_snapshot.cpu!=second->indirect_snapshot.cpu&&source.refs==3);
    memset(data+1023,0x77,12);
    finish(g,first,0);finish(g,second,-EIO);
    assert(!buffers&&source.refs==1&&context->refs==1&&writer.refs==1&&reader.refs==1);
    resv.prior_write=resv.prior_read=NULL;

    /* Every existing resource domain remains independently usable. The last
     * case retains the complete original counter/uniform/resource graph. */
    const u32 counts[3]={3,2,2};
    for(unsigned mode=0;mode<9;mode++) {
        struct drm_opengpu_compute_indirect_resources request=base;
        bool has_counter=mode==6||mode==8,has_uniforms=mode>=4&&mode<=6;
        if(mode>=5) {
            request.resources=full;
            memset(request.resources.resources.resources.launch.grid,0,12);
            if(!has_counter)memset(&request.resources.source,0,sizeof(request.resources.source));
            if(!has_uniforms) {
                request.resources.resources.active_mask=0;
                memset(&request.resources.resources.defaults,0,sizeof(request.resources.resources.defaults));
                memset(request.resources.resources.blocks,0,sizeof(request.resources.resources.blocks));
            }
        } else {
            struct drm_opengpu_compute_counter_resources *out=&request.resources.resources.resources;
            const struct drm_opengpu_compute_counter_resources *in=&full.resources.resources;
            if(mode==1) { out->resource_flags=OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE;memcpy(out->bindings,in->bindings,sizeof(out->bindings)); }
            if(mode==2) { out->resource_flags=OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES;memcpy(out->images,in->images,sizeof(out->images)); }
            if(mode==3) { out->textures=in->textures;out->texture_count=in->texture_count; }
            if(mode==4) {
                request.resources.resources.active_mask=full.resources.active_mask;
                request.resources.resources.defaults=full.resources.defaults;
                memcpy(request.resources.resources.blocks,full.resources.blocks,sizeof(full.resources.blocks));
            }
        }
        g->hw.compute_counter_transport=has_counter;
        g->hw.compute_uniform_transport=has_uniforms;
        memcpy(data+1023,counts,12);copies=indirect_copies;
        assert(!opengpu_compute_indirect_resources_ioctl(drm,&request,file));
        struct opengpu_sched_job *job=queued[queued_count-1];
        indirect_v2_snapshot(job,counts,true);
        assert(indirect_copies==copies+1&&source.refs==2);
        assert(job->kernel.counter_enabled==has_counter&&job->kernel.uniform_enabled==has_uniforms);
        assert(job->kernel.storage_enabled==(mode==1||mode>=5));
        assert(job->kernel.image_enabled==(mode==2||mode>=5));
        assert(job->kernel.texture_enabled==(mode==3||mode>=5));
        for(unsigned i=0;i<job->object_count;i++)for(unsigned j=0;j<i;j++)assert(job->objects[i]!=job->objects[j]);
        if(mode>=5)assert(job->object_count==(has_uniforms?51u:38u)+(has_counter?1u:0u));
        finish(g,job,0);assert(!buffers&&source.refs==1&&context->refs==1);
    }
    g->hw.compute_counter_transport=g->hw.compute_uniform_transport=false;

    /* First/last single-page and crossing-page views preserve exact u32
     * counts and zero every byte outside the one immutable 12-byte copy. */
    const unsigned offsets[]={0,4084,4092,8180};
    for(unsigned i=0;i<ARRAY_SIZE(offsets);i++) {
        struct drm_opengpu_compute_indirect_resources request=base;request.source.offset=offsets[i];
        memcpy((u8 *)data+offsets[i],counts,12);indirect_copy_source=(u8 *)data+offsets[i];copies=indirect_copies;
        assert(!opengpu_compute_indirect_resources_ioctl(drm,&request,file));
        struct opengpu_sched_job *job=queued[queued_count-1];indirect_v2_snapshot(job,counts,true);
        assert(job->indirect_snapshot.size==(offsets[i]==4092?8192u:4096u)&&indirect_copies==copies+1);
        finish(g,job,0);assert(!buffers&&source.refs==1);
    }
    indirect_copy_source=data+1023;
    const u32 limits[][3]={{0,UINT32_MAX,UINT32_MAX},{65535,65535,65535}};
    for(unsigned i=0;i<ARRAY_SIZE(limits);i++) {
        memcpy(data+1023,limits[i],12);
        assert(!opengpu_compute_indirect_resources_ioctl(drm,&base,file));
        struct opengpu_sched_job *job=queued[queued_count-1];indirect_v2_snapshot(job,limits[i],true);
        finish(g,job,0);assert(!buffers&&source.refs==1);
    }
    memcpy(data+1023,counts,12);

    /* Optional mode flags never widen shader argument authority. The same
     * fourth-word read is legal only with the explicitly original kernarg. */
    code[2]=0x00c0a603u;
    unsigned before=queued_count;
    assert(opengpu_compute_indirect_resources_ioctl(drm,&base,file)==-EINVAL);
    assert(queued_count==before&&!buffers&&source.refs==1);
    struct drm_opengpu_compute_indirect_resources original_args=base;original_args.v2.flags=0;
    assert(!opengpu_compute_indirect_resources_ioctl(drm,&original_args,file));
    struct opengpu_sched_job *job=queued[queued_count-1];
    assert(job->kernel.kernarg==kernarg->va+original_args.resources.resources.resources.launch.kernarg_offset);
    indirect_v2_snapshot(job,counts,false);finish(g,job,0);code[2]=0x0080a603u;

    before=queued_count;
    for(unsigned bad=0;bad<13;bad++) {
        struct drm_opengpu_compute_indirect_resources request=base;
        switch(bad) {
        case 0:request.version=3;break;
        case 1:request.v2.flags=2;break;
        case 2:request.v2.reserved=1;break;
        case 3:request.version=1;break; /* v1 rejects the new flag. */
        case 4:request.version=1;request.v2.flags=0;break; /* v1 requires its counter. */
        case 5:request.resources.source.offset=4;break;
        case 6:request.resources.source.handle=51;break;
        case 7:request.resources.source.count=1;break;
        case 8:request.resources.pad[1]=1;break;
        case 9:request.resources.resources.resources.resource_flags=OPENGPU_COMPUTE_UNIFORM_RESOURCE_COUNTERS;break;
        case 10:request.resources.resources.defaults.offset=4;break;
        case 11:request.source.offset=8184;break;
        case 12:request.resources.resources.resources.launch.kernarg_offset=kernarg->size-4;break;
        }
        assert(opengpu_compute_indirect_resources_ioctl(drm,&request,file)<0);
        assert(queued_count==before&&!buffers&&source.refs==1&&!resv.locked&&!rf->lock);
    }
    data[1023]=65536;
    assert(opengpu_compute_indirect_resources_ioctl(drm,&base,file)==-ERANGE);
    assert(queued_count==before&&!buffers&&source.refs==1);
    struct drm_opengpu_compute_indirect_resources overflow=base;
    for(unsigned i=0;i<3;i++)overflow.max_groups[i]=data[1023+i]=UINT32_MAX;
    assert(opengpu_compute_indirect_resources_ioctl(drm,&overflow,file)==-ERANGE);
    assert(queued_count==before&&!buffers&&source.refs==1);
    memcpy(data+1023,counts,12);writer.error=-EIO;writer.signaled=true;resv.prior_write=&writer;copies=indirect_copies;
    assert(opengpu_compute_indirect_resources_ioctl(drm,&base,file)==-EIO);
    assert(queued_count==before&&indirect_copies==copies&&!buffers&&source.refs==1);resv.prior_write=NULL;
    for(unsigned n=1;n<=2;n++) {
        fail_allocation=allocation+n;
        assert(opengpu_compute_indirect_resources_ioctl(drm,&base,file)==-ENOMEM);
        fail_allocation=0;assert(queued_count==before&&!buffers&&source.refs==1);
    }
    assert(!opengpu_compute_indirect_resources_ioctl(drm,&base,file));
    job=queued[queued_count-1];snapshot_map_error=-EACCES;
    assert(opengpu_job_run(job)==ERR_PTR(-EACCES));snapshot_map_error=0;
    opengpu_sched_free_job(&job->base);assert(!buffers&&source.refs==1);
    assert(!opengpu_compute_indirect_resources_ioctl(drm,&base,file));
    finish(g,queued[queued_count-1],0);assert(!buffers&&source.refs==1);
    for(unsigned i=0;i<kernarg->object->size;i++)assert(((u8 *)guard_kernarg)[i]==0x6b);
    kernarg->object->vaddr=original_kernarg_map;free(guard_kernarg);
    release_fence(&resv.last);rf->handles[53]=NULL;indirect_wait_object=NULL;indirect_copy_source=NULL;
    g->hw.compute_counter_transport=counter_transport;g->hw.compute_uniform_transport=uniform_transport;
    g->hw.compute_indirect_transport=indirect_transport;
    shader->vaddr=original_code;binding->size=original_bytes;shader->size=original_object_bytes;
    puts("indirect v2: counter-free/all-resource original admission, one fenced copy for GCI1+12B shader kernarg, immutable queued/zero/max/boundary/RO cleanup/error/reuse PASS (mock kernel/MMIO; no GPU execution)");
}
#endif
