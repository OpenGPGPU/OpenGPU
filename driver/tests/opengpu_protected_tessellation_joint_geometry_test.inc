static struct opengpu_protected_tessellation_joint_geometry_source geometry_source(struct joint_fixture *f,
    struct opengpu_joint_geometry_plan gs[3],struct opengpu_protected_tessellation_joint_geometry_plan plans[3],
    struct opengpu_device *gpu,u32 domain)
{
    struct opengpu_protected_tessellation_joint_geometry_source s={0};
#define COPY(n) s.n=f->source.n
    COPY(job);COPY(frame);COPY(sequences);COPY(states);COPY(draws);COPY(sequences_bytes);COPY(states_bytes);COPY(draws_bytes);
    COPY(draw_plans);COPY(invocations);COPY(ranges);COPY(state_plans);COPY(resources);COPY(draw_count);COPY(draw_plan_count);
    COPY(invocation_count);COPY(range_count);COPY(plan_count);COPY(state_plan_count);COPY(resource_count);COPY(context);COPY(pass);COPY(generation);
#undef COPY
    memcpy(plans,f->source.plans,3*sizeof(*plans));s.plans=plans;s.geometry_plans=gs;s.geometry_plan_count=3;
    s.profile=(struct opengpu_protected_tessellation_joint_geometry_profile){.version=1,.domains=7,.compute_units=2,.lanes=16,.warps=8,.engines=4,
        .provider="two-cu-vga60-owner-v3-five-stage",.owner_resource_version=3,.payload_bytes=524288,.max_payload_bytes=69632,
        .payload_owners=5,.payload_ports=12,.image_producer_pins=34,.single_domain_total_pins=37,
        .available=true,.scalar_fpu=true,.advanced_blend=true,.owned_vertex_source=true,.patch_zero_initialized=true,.owned_patch_reads=true,
        .owned_tessellation_info=true,.geometry_inputs=true,.sample_inputs=true,.geometry_route=true,
        .geometry_uniforms=true,.joint_directory_route=true,.whole_owner_retirement=true};
    u32 *job=f->old.original.job;job[0]=0x474e5107u+domain;job[2]=3;job[36]=1;job[40]=3;job[41]=1024;job[42]=64+3*1024;
    f->old.original.request.scratch_stride=(domain==1?95u:92u)*128u*4;
    for(u32 i=0;i<4;i++) {
        struct opengpu_native_frame_gem *v=&f->old.original.request.scratch[i];
        opengpu_buffer_free(gpu,&graph_original[v->handle]);v->bytes=2*f->old.original.request.scratch_stride;
        assert(!opengpu_buffer_alloc(gpu,&graph_original[v->handle],(size_t)(v->bytes+64+4095)&~4095u));
    }
    assert(!opengpu_native_frame_encode(&f->old.original.request,&f->old.original.encoded));
    for(u32 i=0;i<2;i++)f->old.original.states[i*128+56]=domain==1?7:5;
    u32 code=graph_alloc(gpu,4096),blocks[13];
    const u32 instructions[]={0xffffffffu,7u<<25|5u<<7|0x0bu,33u<<25|5u<<20|0x0bu,35u<<25|0x0bu,OPENGPU_SHADER_CEASE};
    memcpy((u8 *)graph_original[code].cpu+64,instructions,sizeof(instructions));
    for(u32 j=0;j<13;j++) {
        blocks[j]=graph_alloc(gpu,j<12?16448:4160);
        for(u32 b=0;b<graph_original[blocks[j]].size;b++)((u8 *)graph_original[blocks[j]].cpu)[b]=source_byte(blocks[j],b,0);
    }
    for(u32 i=0;i<3;i++) {
        struct opengpu_joint_geometry_plan *g=&gs[i];memset(g,0,sizeof(*g));g->sequence_index=i;g->state_handle=f->old.sequences[i*512+40];
        g->command_id=3;g->gs_task_id_base=701;g->first_input_primitive_id=0;
        g->first_output_sequence=0x76543210fedcba98ULL;g->input_sequence=0x12345678abcdef12ULL;
        g->program=(struct drm_opengpu_native_program_view){.handle=code,.offset=64,.bytes=20,.entry_offset=4};
        g->uniforms.active_mask=0xfff;
        for(u32 j=0;j<12;j++)g->uniforms.blocks[j]=(struct drm_opengpu_compute_uniform_view){.handle=blocks[j],.offset=j&1?4:64,.bytes=16384};
        g->uniforms.defaults=(struct drm_opengpu_compute_uniform_view){.handle=blocks[12],.offset=4,.bytes=4096};
        u32 *n=g->gng;n[0]=0x474e4701u;n[1]=1024;n[2]=1;n[3]=7;n[4]=8;n[5]=9;n[6]=domain?3:2;
        n[7]=32;n[8]=1;n[9]=16;n[10]=8;n[11]=64;n[13]=16;n[14]=16;n[15]=1;
        for(u32 j=0;j<8;j++){n[32+j]=j==4?56:j;g->evaluation_components[j]=128+j;}
        for(u32 j=0;j<68;j++){n[96+2*j]=1;n[97+2*j]=j%64;}
        n[97+2*67]=63;
    }
    return s;
}
static void geometry_graph_oracle(struct opengpu_private_snapshot *o,struct opengpu_render_context *ctx,u32 id,u32 domain)
{
    const struct snapshot_segment *root=snapshot_segment_find(o,id);assert(root && root->mapped && !root->executable);
    struct opengpu_vm *vm=&ctx->vm;u32 va=(u32)root->va;
    assert(graph_read(vm,va,3)==0x474e5107u+domain && graph_read(vm,va+8,3)==3);
    u32 table=graph_read(vm,va+37*4,3);assert(graph_read(vm,table,3)==0x47474101u);
    assert(graph_read(vm,table+4,3)==64+3072 && graph_read(vm,table+16,3)==3);
    for(u32 i=0;i<3;i++) {
        u32 e=table+64+i*1024,gng=graph_read(vm,e+12*4,3);
        assert(graph_read(vm,e,3)==i && graph_read(vm,e+2*4,3)==domain);
        assert(graph_read(vm,e+10*4,3)==0xfedcba98u && graph_read(vm,e+11*4,3)==0x76543210u);
        assert(graph_read(vm,e+224*4,3)==0xabcdef12u && graph_read(vm,e+225*4,3)==0x12345678u);
        assert(graph_read(vm,gng+36*4,3)==56 && graph_read(vm,e+20*4,3)==132);
        assert(graph_read(vm,gng+231*4,3)==63); /* semantic67 uses GS raw output63, independent TESexports8 */
        u32 code=graph_read(vm,gng+17*4,3),pc=graph_read(vm,gng+16*4,3);
        assert(pc==code+4 && graph_read(vm,code,11)==0xffffffffu && graph_read(vm,code+16,11)==OPENGPU_SHADER_CEASE);
        u32 gur=graph_read(vm,gng+24*4,3);assert(graph_read(vm,gng+23*4,3)==2 && graph_read(vm,gur,3)==0x47555202u);
        assert(graph_read(vm,gur+24,3)==3 && graph_read(vm,gur+28,3)==0xfff);
        for(u32 j=80;j<180;j++)assert(!graph_read(vm,e+j*4,3));
        assert(!graph_read(vm,e+3*4,3));
    }
}
static const char *geometry_case="default";
static void geometry_reject(struct opengpu_device *gpu,struct opengpu_render_context *ctx,
    const struct opengpu_protected_tessellation_joint_geometry_source *s,int expected,bool no_copy)
{
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 out=0xcafef00d;assert(!IS_ERR(o));
    unsigned copies=graph_copies;int ret=opengpu_protected_tessellation_joint_geometry_build(o,s,&joint_callbacks,&out);
    if(ret!=expected)fprintf(stderr,"geometry negative %s expected%d got%d\n",geometry_case,expected,ret);
    assert(ret==expected && out==0xcafef00d);if(no_copy)assert(graph_copies==copies);
    assert(!opengpu_private_snapshot_retire(o));
}
static void geometry_negative_cases(struct opengpu_device *gpu,struct opengpu_render_context *ctx,
    struct opengpu_protected_tessellation_joint_geometry_source *s,struct opengpu_joint_geometry_plan gs[3])
{
    geometry_case="owner-version";s->profile.owner_resource_version=2;geometry_reject(gpu,ctx,s,-EOPNOTSUPP,true);s->profile.owner_resource_version=3;
    geometry_case="geometry-permission";
    bool permission=s->profile.geometry_inputs;s->profile.geometry_inputs=false;
    geometry_reject(gpu,ctx,s,-EOPNOTSUPP,true);s->profile.geometry_inputs=permission;
    geometry_case="provider-id";s->profile.provider[0]='x';geometry_reject(gpu,ctx,s,-EOPNOTSUPP,true);s->profile.provider[0]='t';
    u32 *job=(u32 *)s->job,magic=job[0];
    geometry_case="old-root";job[0]=0x474e5104u+(magic-0x474e5107u);
    geometry_reject(gpu,ctx,s,-EINVAL,true);job[0]=magic;
    geometry_case="root-version";job[2]=2;geometry_reject(gpu,ctx,s,-EINVAL,true);job[2]=3;
    geometry_case="geometry-base";job[37]=64;geometry_reject(gpu,ctx,s,-EINVAL,true);job[37]=0;
    geometry_case="duplicate-sequence";gs[1].sequence_index=0;geometry_reject(gpu,ctx,s,-EINVAL,true);gs[1].sequence_index=1;
    geometry_case="state-handle";gs[0].state_handle^=1;geometry_reject(gpu,ctx,s,-EINVAL,true);gs[0].state_handle^=1;
    geometry_case="export-bound";gs[0].evaluation_components[4]=128+8;geometry_reject(gpu,ctx,s,-EINVAL,true);gs[0].evaluation_components[4]=128+4;
    geometry_case="full32-map";gs[0].evaluation_components[4]=0x80000084u;geometry_reject(gpu,ctx,s,-EINVAL,true);gs[0].evaluation_components[4]=128+4;
    geometry_case="gng-source-conflict";gs[0].gng[36]=gs[0].gng[32];geometry_reject(gpu,ctx,s,-EINVAL,true);gs[0].gng[36]=56;
    geometry_case="command-id";gs[0].command_id=32;geometry_reject(gpu,ctx,s,-EINVAL,true);gs[0].command_id=3;
    geometry_case="gng-context";gs[0].gng[3]++;geometry_reject(gpu,ctx,s,-EINVAL,true);gs[0].gng[3]--;
    geometry_case="abi10";u32 abi=((u32 *)s->states)[56];((u32 *)s->states)[56]=10;
    geometry_reject(gpu,ctx,s,-EINVAL,true);((u32 *)s->states)[56]=abi;
    if(job[0]!=0x474e5108u) {
        u32 *frame=(u32 *)s->frame;
        u32 stride=frame[OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCHSTRIDE_WORD];
        assert(stride==47104u);
        geometry_case="abi5-prefix-understated";
        frame[OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCHSTRIDE_WORD]=46592u;
        geometry_reject(gpu,ctx,s,-EINVAL,true);
        frame[OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCHSTRIDE_WORD]=stride;
    }
    if(job[0]==0x474e5108u) {
        geometry_case="sample-inputs";s->profile.sample_inputs=false;geometry_reject(gpu,ctx,s,-EINVAL,true);s->profile.sample_inputs=true;
    }
    geometry_case="geometry-uniforms";s->profile.geometry_uniforms=false;geometry_reject(gpu,ctx,s,-EOPNOTSUPP,true);s->profile.geometry_uniforms=true;
    geometry_case="raster-range";gs[0].raster_discard=1;geometry_reject(gpu,ctx,s,-EINVAL,true);gs[0].raster_discard=0;
    assert(jg_geometry_same_source(&gs[0],&gs[1]));
    gs[1].raster_discard=1;assert(!jg_geometry_same_source(&gs[0],&gs[1]));gs[1].raster_discard=0;
    assert(jg_geometry_same_seeds(&gs[0],&gs[1]));
    gs[1].input_sequence^=1;assert(!jg_geometry_same_seeds(&gs[0],&gs[1]));gs[1].input_sequence^=1;
    geometry_case="GS-source-frame-writer-alias";
    struct drm_opengpu_native_program_view program=gs[0].program;
    struct opengpu_native_frame_resource *resources=(struct opengpu_native_frame_resource *)s->resources;
    u32 resource_index=0;
    while(resource_index<s->resource_count && !(resources[resource_index].access&OPENGPU_NATIVE_FRAME_RESOURCE_WRITE))resource_index++;
    assert(resource_index<s->resource_count);
    struct opengpu_native_frame_resource original=resources[resource_index];
    resources[resource_index].gem_handle=program.handle;resources[resource_index].offset=program.offset;resources[resource_index].bytes=program.bytes;
    geometry_reject(gpu,ctx,s,-EINVAL,true);resources[resource_index]=original;
    geometry_case="default";
}
static void geometry_prepared_negatives(struct opengpu_device *gpu,struct opengpu_render_context *ctx,
    const struct opengpu_protected_tessellation_joint_geometry_source *s)
{
    /* All real owner pages are prepared first, then the only remaining
     * allocation is the full 4096-byte attachment checker scratch. */
    struct opengpu_private_snapshot *failed=opengpu_private_snapshot_create(gpu,ctx);u32 failed_id;
    assert(!IS_ERR(failed));
    assert(!opengpu_protected_tessellation_joint_geometry_build(failed,s,&joint_callbacks,&failed_id));
    assert(!opengpu_private_snapshot_prepare(failed));
    private_alloc_fail_after=0;
    assert(opengpu_private_snapshot_check_prepared_joint_geometry(failed,failed_id)==-ENOMEM);
    private_alloc_fail_after=-1;
    assert(opengpu_private_snapshot_publish(failed)==-EINVAL);
    assert(!opengpu_private_snapshot_retire(failed));
    for(u32 test=0;test<6;test++) {
        struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 id;assert(!IS_ERR(o));
        assert(!opengpu_protected_tessellation_joint_geometry_build(o,s,&joint_callbacks,&id));
        assert(!opengpu_protected_tessellation_joint_geometry_prepare(o,id));
        const struct snapshot_segment *root=snapshot_segment_find(o,id);
        u32 table_address=opengpu_native_geometry_word_at(root->backing.cpu,37);
        const struct snapshot_segment *table=snapshot_joint_target(o,table_address);assert(table);
        u32 gng_address=opengpu_native_geometry_word_at((u8 *)table->backing.cpu+64,12);
        const struct snapshot_segment *gng=snapshot_joint_target(o,gng_address);assert(gng);
        if(test==0)word(table->backing.cpu,64,1); /* foreign sequence */
        if(test==1)word(gng->backing.cpu,3*4,8); /* foreign context */
        if(test==2)word(gng->backing.cpu,17*4,table_address); /* metadata as code */
        if(test==3)word(table->backing.cpu,64+166*4,4); /* nonzero reserved feedback word */
        if(test==4)word(table->backing.cpu,64+3*4,1); /* unsupported feedback flag */
        if(test==5) {
            const struct snapshot_segment *frame=snapshot_joint_target(o,opengpu_native_geometry_word_at(root->backing.cpu,21));
            u32 state_base=opengpu_native_geometry_word_at(frame->backing.cpu,292);
            const struct snapshot_segment *states=snapshot_joint_target(o,state_base);
            word(states->backing.cpu,56*4,10);
        }
        assert(opengpu_private_snapshot_check_prepared_joint_geometry(o,id)==-EINVAL);
        assert(opengpu_private_snapshot_publish(o)==-EINVAL);assert(!opengpu_private_snapshot_retire(o));
    }
}
static void geometry_command_oracle(struct opengpu_private_snapshot *o,
    struct opengpu_device *gpu,struct opengpu_render_context *ctx,u32 id,u32 domain)
{
    struct opengpu_private_snapshot_joint_request request,saved;
    struct opengpu_protected_tessellation_joint_geometry_host_command command,expected;
    struct opengpu_render_context foreign={.gpu=gpu,.id=ctx->id+1};
    const struct snapshot_segment *root=snapshot_segment_find(o,id);
    u32 magic;
    mutex_lock(&gpu->hw.submit_lock);
    memset(&request,0xa5,sizeof(request));
    assert(opengpu_private_snapshot_joint_request_locked(o,gpu,ctx,id,&request)==-EINVAL);
    assert(request.job_base==0xa5a5a5a5u);
    assert(!opengpu_private_snapshot_joint_geometry_request_locked(o,gpu,ctx,id,&request));
    saved=request;
    assert(request.domain==domain && request.context==ctx->id && request.pass==8 &&
        request.generation==9 && request.job_limit==request.job_base+512 &&
        request.job_allocation_limit==request.job_base+4096 &&
        request.owner_limit>request.owner_base && request.owner_limit<=(1ULL<<32));
    assert(root && !o->joint_root && !o->joint_request.job_base &&
        !o->joint_request.job_limit && !o->joint_request.job_allocation_limit);
    assert(opengpu_private_snapshot_joint_geometry_request_locked(o,gpu,&foreign,id,&request)==-EINVAL);
    assert(!memcmp(&request,&saved,sizeof(request)));
    memset(&command,0xa5,sizeof(command));
    assert(!opengpu_protected_tessellation_joint_geometry_command_locked(o,gpu,ctx,id,&command));
    expected=(struct opengpu_protected_tessellation_joint_geometry_host_command){
        .opcode=15,.source=request.job_base,.destination=request.owner_base,.bytes=512,
        .pattern=0x474a0200u|((u32)(request.owner_limit>>32))|(domain<<1),
        .width=(u32)request.owner_limit,.height=ctx->id,.source_stride=8,.destination_stride=9};
    assert(!memcmp(&command,&expected,sizeof(command)));
    expected=command;
    assert(opengpu_protected_tessellation_joint_geometry_command_locked(o,gpu,ctx,id+1,&command)==-EINVAL);
    assert(!memcmp(&command,&expected,sizeof(command)));
    assert(opengpu_protected_tessellation_joint_geometry_command_locked(o,gpu,&foreign,id,&command)==-EINVAL);
    assert(!memcmp(&command,&expected,sizeof(command)));
    magic=((const u32 *)root->backing.cpu)[0];
    ((u32 *)root->backing.cpu)[0]=0x474e5104u;
    assert(opengpu_private_snapshot_joint_geometry_request_locked(o,gpu,ctx,id,&request)==-EINVAL);
    assert(!memcmp(&request,&saved,sizeof(request)));
    assert(opengpu_protected_tessellation_joint_geometry_command_locked(o,gpu,ctx,id,&command)==-EINVAL);
    assert(!memcmp(&command,&expected,sizeof(command)));
    ((u32 *)root->backing.cpu)[0]=magic;
    o->joint_geometry_request.pass++;
    assert(opengpu_private_snapshot_joint_geometry_request_locked(o,gpu,ctx,id,&request)==-EINVAL);
    assert(!memcmp(&request,&saved,sizeof(request)));
    assert(opengpu_protected_tessellation_joint_geometry_command_locked(o,gpu,ctx,id,&command)==-EINVAL);
    assert(!memcmp(&command,&expected,sizeof(command)));
    o->joint_geometry_request.pass=saved.pass;
    o->joint_geometry_request.owner_limit=1ULL<<32;
    assert(!opengpu_protected_tessellation_joint_geometry_command_locked(o,gpu,ctx,id,&command));
    assert(command.pattern==(0x474a0200u|1u|(domain<<1)) && command.width==0);
    expected=command;
    o->joint_geometry_request.owner_limit=(1ULL<<32)+1;
    assert(opengpu_protected_tessellation_joint_geometry_command_locked(o,gpu,ctx,id,&command)==-EINVAL);
    assert(!memcmp(&command,&expected,sizeof(command)));
    o->joint_geometry_request.owner_limit=saved.owner_limit;
    expected=command;
    o->joint_geometry_request.domain=3;
    assert(opengpu_protected_tessellation_joint_geometry_command_locked(o,gpu,ctx,id,&command)==-EINVAL);
    assert(!memcmp(&command,&expected,sizeof(command)));
    o->joint_geometry_request.domain=saved.domain;
    o->joint_geometry_request.job_limit++;
    assert(opengpu_protected_tessellation_joint_geometry_command_locked(o,gpu,ctx,id,&command)==-EINVAL);
    assert(!memcmp(&command,&expected,sizeof(command)));
    o->joint_geometry_request.job_limit=saved.job_limit;
    mutex_unlock(&gpu->hw.submit_lock);
}

static void geometry_enqueue_oracle(struct opengpu_private_snapshot *o,
    struct opengpu_device *gpu,struct opengpu_render_context *ctx,u32 id,
    const struct opengpu_protected_tessellation_joint_geometry_profile *profile)
{
    struct opengpu_tessellation_joint_geometry_completion completion={.valid=true};
    struct dma_fence *fence=NULL;
    unsigned cached=invalidations,kicks=geometry_kicks;

    mutex_lock(&gpu->hw.submit_lock);
    assert(!opengpu_protected_tessellation_joint_geometry_command_locked(o,gpu,ctx,id,&geometry_expected_command));
    mutex_unlock(&gpu->hw.submit_lock);
    geometry_expected_report=&completion;
    gpu->hw.joint_geometry=(struct opengpu_protected_tessellation_joint_geometry_profile){0};
    assert(opengpu_hw_protected_tessellation_joint_geometry_async(gpu,o,ctx,id,&completion,&fence)==-EOPNOTSUPP);
    assert(!fence && invalidations==cached && geometry_kicks==kicks);
    gpu->hw.joint_geometry=*profile;
    geometry_busy=true;
    assert(opengpu_hw_protected_tessellation_joint_geometry_async(gpu,o,ctx,id,&completion,&fence)==-EBUSY);
    assert(!fence && invalidations==cached && geometry_kicks==kicks);
    geometry_busy=false;
    assert(opengpu_hw_protected_tessellation_joint_geometry_async(gpu,o,ctx,id+1,&completion,&fence)==-EINVAL);
    assert(!fence && invalidations==cached && geometry_kicks==kicks);
    invalidate_failure=(int)cached+1;
    assert(opengpu_hw_protected_tessellation_joint_geometry_async(gpu,o,ctx,id,&completion,&fence)==-EIO);
    assert(!fence && invalidations==cached+1 && geometry_kicks==kicks);
    invalidate_failure=0;
    geometry_kick_fail=true;
    assert(opengpu_hw_protected_tessellation_joint_geometry_async(gpu,o,ctx,id,&completion,&fence)==-ENOMEM);
    assert(!fence && geometry_kicks==kicks+1);
    geometry_kick_fail=false;
    assert(!opengpu_hw_protected_tessellation_joint_geometry_async(gpu,o,ctx,id,&completion,&fence));
    assert(fence==(struct dma_fence *)&geometry_fence_token && geometry_kicks==kicks+2);
    assert(!completion.valid && !completion.status && !gpu->hw.submit_lock.held);
}

static struct opengpu_joint_geometry_plan *geometry_mutate_plans;
static struct opengpu_protected_tessellation_joint_geometry_source *geometry_mutate_source;
static int geometry_mutating_resolve(void *context,u32 handle,struct opengpu_native_frame_backing *out)
{
    int ret=joint_resolve(context,handle,out);
    if(geometry_mutate_plans) {
        memset(geometry_mutate_plans,0xa7,3*sizeof(*geometry_mutate_plans));geometry_mutate_plans=NULL;
        memset(geometry_mutate_source,0xa7,sizeof(*geometry_mutate_source));geometry_mutate_source=NULL;
    }
    return ret;
}

int main(void)
{
    for(u32 domain=0;domain<3;domain++) {
        struct opengpu_device gpu={0};struct opengpu_render_context ctx={.gpu=&gpu,.references=1,.id=7};
        gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu,&ctx.vm));copy_gpu=&gpu;
        struct joint_fixture *f=calloc(1,sizeof(*f));assert(f);joint_init(f,&gpu,domain);
        struct opengpu_joint_geometry_plan gs[3];struct opengpu_protected_tessellation_joint_geometry_plan plans[3];
        struct opengpu_protected_tessellation_joint_geometry_source s=geometry_source(f,gs,plans,&gpu,domain);
        struct opengpu_protected_tessellation_joint_geometry_profile profile=s.profile;
        geometry_negative_cases(&gpu,&ctx,&s,gs);
        if(!domain)geometry_prepared_negatives(&gpu,&ctx,&s);
        struct opengpu_native_job_copy callbacks=joint_callbacks;
        if(domain==2){geometry_mutate_plans=gs;geometry_mutate_source=&s;callbacks.resolve=geometry_mutating_resolve;}
        struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&ctx);u32 out=0x5aa5;
        assert(!IS_ERR(o));int ret=opengpu_protected_tessellation_joint_geometry_build(o,&s,&callbacks,&out);
        if(ret) {
            fprintf(stderr,"geometry build domain%u ret%d\n",domain,ret);
            assert(!ret);
        }
        ret=opengpu_protected_tessellation_joint_geometry_prepare(o,out);
        if(ret) {
            fprintf(stderr,"geometry prepare domain%u ret%d\n",domain,ret);
            assert(!ret);
        }
        assert(!opengpu_private_snapshot_publish(o));geometry_command_oracle(o,&gpu,&ctx,out,domain);
        geometry_enqueue_oracle(o,&gpu,&ctx,out,&profile);
        geometry_graph_oracle(o,&ctx,out,domain);
        assert(!opengpu_private_snapshot_retire(o));pt_free_originals(&gpu);free(f);
        opengpu_mmu_vm_destroy(&gpu,&ctx.vm);opengpu_mmu_fini(&gpu);assert(!fixture_buffers && !live_allocations);
    }
    puts("new GNQ7/8/9 geometry BUILD/prepare/private MMU graph PASS; CPU only");return 0;
}
