/* Real original GEM -> VS RX/GUR1/GND/GVS1 -> actual private owner/MMU.
 * CEASE exercises ownership/validation only, never a CPU shader oracle. */
#define OPENGPU_NATIVE_JOB_SNAPSHOT_TEST_MAIN gvs_native_regression_main
#include "opengpu_native_job_snapshot_test.c"
#undef OPENGPU_NATIVE_JOB_SNAPSHOT_TEST_MAIN
#include "../opengpu_tessellation_uniform_snapshot.c"
#ifndef OPENGPU_GVS_SOURCE
#define OPENGPU_GVS_SOURCE "../opengpu_tessellation_vertex_source.c"
#endif
#include OPENGPU_GVS_SOURCE

static struct opengpu_tessellation_vertex_source *gvs_mutate_source;
static bool gvs_mutated;
static int gvs_test_resolve(void *opaque,u32 handle,struct opengpu_native_frame_backing *out)
{
    if(gvs_mutate_source && !gvs_mutated) {
        gvs_mutated=true;
        gvs_mutate_source->sequence_index=0xffffffffu;
        gvs_mutate_source->layout.input_components=0;
        gvs_mutate_source->components[0].kind=3;
        gvs_mutate_source->plan.program.handle=0;
        gvs_mutate_source->plan.uniforms.active_mask=0;
        memset((void *)gvs_mutate_source->draw,0,2048);
    }
    return graph_resolve(opaque,handle,out);
}
static const struct opengpu_native_job_copy gvs_callbacks={graph_copy,gvs_test_resolve,graph_original};
static struct opengpu_tessellation_vertex_source gvs_source(struct graph_records *g)
{
    struct opengpu_tessellation_vertex_source s={0};
    const struct drm_opengpu_native_draw_plan *p=&g->plans[1];
    u32 *d=g->draws;
    d[311]=4;d[325]=3;d[326]=1;d[343]=d[344]=0xffffffffu;
    s.draw=d;s.draw_bytes=2048;s.context=7;s.pass=8;s.generation=9;
    s.sequence_index=11;s.draw_index=0;s.task_id_base=100;s.patch_count=4;
    s.slice=(struct opengpu_tessellation_vertex_slice){1,0,2,1,2};
    s.layout=(struct opengpu_tessellation_patch_layout){2,64,32,60,120};
    for(u32 i=0;i<64;i++)s.components[i]=(struct opengpu_tessellation_vertex_component){i<4?0:1,i<4?i:i-4};
    s.plan.program=p->vertex_program;s.plan.uniforms=p->vertex_uniforms;
    memcpy(s.plan.bindings,p->bindings,sizeof(s.plan.bindings));s.plan.scratch=p->vertex_scratch;
    s.profile=(struct opengpu_tessellation_vertex_source_profile){1,2,16,8,true,true,true};
    return s;
}
static void gvs_reject(struct opengpu_device *gpu,struct opengpu_render_context *ctx,
    const struct opengpu_tessellation_vertex_source *s,int error)
{
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);
    u32 out=0x5a5a5a5a;
    assert(!IS_ERR(o));
    int actual=opengpu_tessellation_vertex_source_append(o,s,&gvs_callbacks,&out);
    if(actual!=error)fprintf(stderr,"GVS reject case %u expected %d got %d\n",graph_rejection_case,error,actual);
    assert(actual==error && out==0x5a5a5a5a);
    assert(!opengpu_private_snapshot_retire(o));graph_retired(gpu,ctx);
}
static void gvs_record_mutants(u32 words[128])
{
    assert(opengpu_tessellation_vertex_source_canonical(words,512));
    for(u32 n=0;n<128;n++) {
        u32 saved=words[n],width=opengpu_gvs_word_rules[n]&127u;
        if(width<32) {
            words[n]=saved^(width?1u<<width:1u);
            assert(!opengpu_tessellation_vertex_source_canonical(words,512));
            words[n]=saved;
        }
    }
    const u32 targets[]={0,1,2,3,4,5,6,7,8,9,10,26,29,32,40,41,42,43,44,45};
    for(u32 i=0;i<sizeof(targets)/sizeof(targets[0]);i++) {
        u32 n=targets[i],old=words[n];words[n]=0xffffffffu;
        assert(!opengpu_tessellation_vertex_source_canonical(words,512));words[n]=old;
    }
    assert(!opengpu_tessellation_vertex_source_canonical(words,511));
    /* Canonical exclusive endpoints can equal exactly 4GiB. */
    u32 saved[128];memcpy(saved,words,sizeof(saved));
    words[24]=0xfffff800;words[25]=0;words[26]=1;
    words[27]=0xfffffff8;words[28]=0;words[29]=1;words[30]=0xfffffffc;
    assert(opengpu_tessellation_vertex_source_canonical(words,512));
    words[29]=2;assert(!opengpu_tessellation_vertex_source_canonical(words,512));
    memcpy(words,saved,sizeof(saved));
    for(u32 domain=0;domain<3;domain++) {
        u32 sequence[512]={0};
        sequence[0]=0x474e5407+domain;sequence[1]=2048;sequence[2]=7+domain;
        sequence[509]=0xfffffe00;sequence[510]=0;sequence[511]=1;
        assert(opengpu_tessellation_vertex_sequence_canonical(sequence,2048,domain));
        for(u32 other=0;other<3;other++)if(other!=domain)assert(!opengpu_tessellation_vertex_sequence_canonical(sequence,2048,other));
        sequence[0]-=3;sequence[2]-=3;
        assert(!opengpu_tessellation_vertex_sequence_canonical(sequence,2048,domain));
        sequence[0]+=3;sequence[2]+=3;
        sequence[511]=2;assert(!opengpu_tessellation_vertex_sequence_canonical(sequence,2048,domain));sequence[511]=1;
        sequence[509]++;assert(!opengpu_tessellation_vertex_sequence_canonical(sequence,2048,domain));sequence[509]--;
        for(u32 n=17;n<=23;n++) {
            sequence[n]=1;assert(!opengpu_tessellation_vertex_sequence_canonical(sequence,2048,domain));sequence[n]=0;
        }
        for(u32 n=domain==0?237u:238u;n<=(domain==0?482u:483u);n++) {
            sequence[n]=1;assert(!opengpu_tessellation_vertex_sequence_canonical(sequence,2048,domain));sequence[n]=0;
        }
        sequence[domain==0?491u:492u]=1;assert(!opengpu_tessellation_vertex_sequence_canonical(sequence,2048,domain));
        sequence[domain==0?491u:492u]=0;
        const unsigned char *rules=domain==0?opengpu_gvs_isoline_word_rules:domain==1?opengpu_gvs_triangle_word_rules:opengpu_gvs_quad_word_rules;
        for(u32 n=3;n<509;n++)if(!(rules[n]&127u)) {
            sequence[n]=1;assert(!opengpu_tessellation_vertex_sequence_canonical(sequence,2048,domain));sequence[n]=0;
        }
        /* Inherited wide fields must not truncate to a valid low word. */
        for(u32 n=3;n<509;n++)if((rules[n]&127u)<32 && rules[n] && !(rules[n]&128u)) {
            sequence[n]=1u<<(rules[n]&127u);
            assert(!opengpu_tessellation_vertex_sequence_canonical(sequence,2048,domain));sequence[n]=0;
        }
    }
}
static void gvs_oracle(struct opengpu_private_snapshot *owner,u32 segment,
    const struct opengpu_tessellation_vertex_source *s,u32 revision)
{
    struct opengpu_vm *vm=&owner->context->vm;
    u32 root,actual[128],expected[128]={0};
    assert(!opengpu_private_snapshot_address(owner,segment,&root));
    u32 draw=graph_read(vm,root+24*4,3),code=graph_read(vm,root+27*4,3),entry=graph_read(vm,root+30*4,3);
    const u32 header[]={0x47565301,512,1,16,16,16,64,32,16,8,2};memcpy(expected,header,sizeof(header));
    expected[16]=7;expected[17]=8;expected[18]=9;expected[19]=11;expected[20]=s->draw_index;
    expected[21]=s->task_id_base;expected[22]=s->patch_count;
    expected[24]=draw;expected[25]=draw+2048;expected[27]=code;expected[28]=code+s->plan.program.bytes;expected[30]=entry;
    expected[32]=1;expected[33]=s->slice.first_ordinal;expected[34]=s->slice.patches_per_instance;
    expected[35]=s->slice.first_instance;expected[36]=s->slice.instances;
    expected[40]=s->layout.input_vertices;expected[41]=s->layout.input_components;
    expected[42]=s->layout.output_vertices;expected[43]=s->layout.output_components;expected[44]=s->layout.patch_components;
    expected[45]=((const u32 *)s->draw)[346];
    for(u32 i=0;i<s->layout.input_components;i++)expected[64+i]=s->components[i].kind|(s->components[i].component<<2);
    for(u32 i=0;i<128;i++) {actual[i]=graph_read(vm,root+i*4,3);assert(actual[i]==expected[i]);}
    gvs_record_mutants(actual);
    assert(entry==code+s->plan.program.entry_offset && graph_read(vm,code,11)==0x93000000u+revision && graph_read(vm,entry,11)==0x30500073u);
    assert(!(graph_translate(vm,code,11)&4095u));
    graph_uniform_oracle(vm,graph_read(vm,draw+507*4,3),0,revision);
    for(u32 b=0;b<16;b++) {
        const struct input_words *w=&binding_words[b];
        u32 base=graph_read(vm,draw+w->base*4,3);
        assert(graph_translate(vm,base,3)==graph_original[s->plan.bindings[b].handle].dma+s->plan.bindings[b].offset);
        assert(graph_read(vm,draw+w->limit*4,3)==base+s->plan.bindings[b].bytes);
    }
    assert(graph_translate(vm,graph_read(vm,draw+339*4,3),7)==graph_original[s->plan.scratch.handle].dma+s->plan.scratch.offset);
    for(u32 w=0;w<512;w++) {
        bool patched=w==326 || (w>=336 && w<=342) || w==506 || w==507;
        for(u32 b=0;b<16;b++)if(w>=binding_words[b].allocation_base && w<=binding_words[b].limit+1)patched=true;
        if(!patched)assert(graph_read(vm,draw+w*4,3)==((const u32 *)s->draw)[w]);
    }
    assert(!graph_read(vm,draw+326*4,3) && graph_read(vm,draw+506*4,3)==1);
}
static void gvs_publish_one(struct opengpu_device *gpu,struct opengpu_render_context *ctx,
    const struct opengpu_tessellation_vertex_source *s)
{
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 out;
    assert(!IS_ERR(o));
    int ret=opengpu_tessellation_vertex_source_append(o,s,&gvs_callbacks,&out);
    if(ret)fprintf(stderr,"GVS positive ABI%u components%u code%llu scratch%llu error%d\n",((const u32 *)s->draw)[346],s->layout.input_components,
        (unsigned long long)s->plan.program.offset,(unsigned long long)s->plan.scratch.bytes,ret);
    assert(!ret);
    assert(!opengpu_private_snapshot_prepare(o));assert(!opengpu_private_snapshot_publish(o));gvs_oracle(o,out,s,0);
    assert(!opengpu_private_snapshot_retire(o));graph_retired(gpu,ctx);
}
int main(void)
{
    struct opengpu_device gpu={0};struct opengpu_render_context ctx={.gpu=&gpu,.references=1,.id=7};
    struct graph_records g;
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu));assert(!opengpu_mmu_vm_create(&gpu,&ctx.vm));copy_gpu=&gpu;graph_init(&g,&gpu);
    struct opengpu_tessellation_vertex_source source=gvs_source(&g),s;
    u32 saved_draw[512];memcpy(saved_draw,g.draws,sizeof(saved_draw));
    unsigned copies,resolves;
    {
        struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&ctx);
        struct opengpu_tessellation_uniform_roots tess;
        struct drm_opengpu_native_uniform_stage stages[2]={g.plans[1].vertex_uniforms,g.plans[1].fragment_uniforms};
        u32 out;
        graph_copies=graph_resolves=0;
        assert(!opengpu_tessellation_vertex_source_append(o,&source,&gvs_callbacks,&out));
        copies=graph_copies;resolves=graph_resolves;assert(copies==14 && resolves==31);
        /* The same real owner also carries all 24 TCS/TES blocks. This does
         * not append/admit a GNT or claim a linked executable whole graph. */
        assert(!opengpu_tessellation_uniform_snapshot_append(o,stages,7,8,9,graph_copy,graph_original,&tess));
        graph_original_contents(1);
        assert(!opengpu_private_snapshot_prepare(o));assert(!opengpu_private_snapshot_publish(o));
        gvs_oracle(o,out,&source,0);
        u32 aggregate;assert(!opengpu_private_snapshot_address(o,tess.segment,&aggregate));
        for(u32 stage=0;stage<2;stage++) {
            u32 root=aggregate+tess.root_offset[stage];
            assert(graph_read(&ctx.vm,root,3)==0x47555203 && graph_read(&ctx.vm,root+24,3)==stage+4);
            u32 table=graph_read(&ctx.vm,root+40,3);
            for(u32 b=0;b<12;b++) {
                u32 record=table+(stage*12+b)*64;
                assert(graph_read(&ctx.vm,record,3)==0x47554203 && graph_read(&ctx.vm,record+28,3)==(1u<<(stage+4)));
                u32 base=graph_read(&ctx.vm,record+32,3);
                for(u32 k=0;k<16384;k+=4)assert(graph_read(&ctx.vm,base+k,3)==source_word(graph_ubo[stage*12+b],(b&1?4:64)+k,0));
            }
        }
        assert(!memcmp(g.draws,saved_draw,sizeof(saved_draw)));
        assert(!opengpu_private_snapshot_retire(o));graph_retired(&gpu,&ctx);graph_original_contents(0);
    }
    for(u32 kind=0;kind<43;kind++) {
        s=source;memcpy(g.draws,saved_draw,sizeof(saved_draw));graph_rejection_case=kind;
        switch(kind) {
        case 0:s.profile.version=2;break;case 1:s.profile.compute_units=1;break;
        case 2:s.profile.lanes=8;break;case 3:s.profile.warps=4;break;case 4:s.profile.patch_zero_initialized=false;break;
        case 5:s.context=8;break;case 6:s.draw_bytes=2047;break;case 7:s.layout.input_vertices=0;break;
        case 8:s.layout.input_vertices=33;break;case 9:s.layout.input_components=65;break;
        case 10:s.layout.output_vertices=33;break;case 11:s.layout.output_components=65;break;
        case 12:s.layout.patch_components=121;break;case 13:s.layout.output_components=61;break;
        case 14:s.slice.version=2;break;case 15:s.patch_count=3;break;case 16:s.task_id_base=0xfffffffe;break;
        case 17:s.slice.first_ordinal=1;break;case 18:s.slice.first_instance=2;break;
        case 19:s.slice.patches_per_instance=0xffffffff;s.slice.instances=0xffffffff;break;
        case 20:s.components[0].kind=3;break;case 21:s.components[0].component=4;break;
        case 22:s.components[63].component=64;break;case 23:s.components[0].kind=2;break;
        case 24:g.draws[343]&=~1u;break;case 25:g.draws[345]=14;break;
        case 26:g.draws[342]=4;break;case 27:g.draws[507]=4;break;case 28:g.draws[506]=1;break;
        case 29:g.draws[314]=8;break;case 30:g.draws[315]=8;break;case 31:g.draws[316]=9;break;
        case 32:g.draws[0]=0x474e4402;break;case 33:g.draws[8]=1;break;
        case 34:s.plan.program.pad=1;break;case 35:s.plan.program.entry_offset=8;break;
        case 36:s.plan.program.offset=~(u64)0-3;break;case 37:s.plan.uniforms.active_mask|=1u<<12;break;
        case 38:s.plan.bindings[0].offset=4096;break;case 39:s.plan.scratch.bytes--;break;
        case 40:s.layout.input_components=63;break;case 41:g.draws[326]=2;break;
        case 42:s.plan.bindings[0].handle=0;break;
        }
        gvs_reject(&gpu,&ctx,&s,-EINVAL);
    }
    memcpy(g.draws,saved_draw,sizeof(saved_draw));
    for(u32 kind=0;kind<7;kind++) {
        s=source;
        if(kind==0)s.plan.indirect=s.plan.bindings[0];
        if(kind==1)s.plan.sampler=s.plan.bindings[0];
        if(kind==2)s.plan.texture_root=s.plan.bindings[0];
        if(kind==3)g.draws[499]=1;
        if(kind==4)g.draws[496]=1;
        if(kind==5)g.draws[480]=1;
        if(kind==6)g.draws[498]=1;
        gvs_reject(&gpu,&ctx,&s,-EOPNOTSUPP);memcpy(g.draws,saved_draw,sizeof(saved_draw));
    }
    for(u32 field=0;field<12;field++) {
        u32 word=field<8?328+field:480+field-8;
        g.draws[word]=1;gvs_reject(&gpu,&ctx,&source,-EOPNOTSUPP);
        memcpy(g.draws,saved_draw,sizeof(saved_draw));
    }
    s=source;s.profile.available=false;graph_copies=graph_resolves=0;
    gvs_reject(&gpu,&ctx,&s,-EOPNOTSUPP);assert(!graph_copies && !graph_resolves);
    for(u32 fail=1;fail<=copies;fail++) {graph_copy_failure=fail;graph_copies=graph_resolves=0;gvs_reject(&gpu,&ctx,&source,-EIO);}
    graph_copy_failure=0;
    for(u32 fail=1;fail<=resolves;fail++) {graph_resolve_failure=fail;graph_copies=graph_resolves=0;gvs_reject(&gpu,&ctx,&source,-EIO);}
    graph_resolve_failure=0;
    /* An alternate handle with the same physical allocation is not isolation. */
    {
        u32 alias=++graph_handles;graph_original[alias]=graph_original[graph_vs[0]];
        s=source;s.plan.uniforms.blocks[0]=(struct drm_opengpu_compute_uniform_view){.handle=alias,.offset=64,.bytes=16384};
        gvs_reject(&gpu,&ctx,&s,-EINVAL);
        s=source;s.plan.bindings[15]=(struct drm_opengpu_compute_uniform_view){.handle=alias,.offset=64,.bytes=512};
        gvs_reject(&gpu,&ctx,&s,-EINVAL);
        s=source;s.plan.program=(struct drm_opengpu_native_program_view){.handle=alias,.offset=64,.bytes=4};
        word(graph_original[alias].cpu,64,0x30500073);
        gvs_reject(&gpu,&ctx,&s,-EINVAL);
        /* Read-only source outside the writable logical view remains legal
         * even in the same physical GEM. The boundary is a whole 64B line. */
        s=source;s.plan.program=(struct drm_opengpu_native_program_view){.handle=alias,.offset=64+GRAPH_VS_BYTES,.bytes=8,.entry_offset=4};
        word(graph_original[alias].cpu,64+GRAPH_VS_BYTES,0x93000000);
        word(graph_original[alias].cpu,68+GRAPH_VS_BYTES,0x30500073);
        gvs_publish_one(&gpu,&ctx,&s);
        s.plan.program.offset-=4;gvs_reject(&gpu,&ctx,&s,-EINVAL);
        memset(&graph_original[alias],0,sizeof(graph_original[alias]));graph_handles--;graph_original_contents(0);
    }
    /* Immutable kernel plan and GND are captured before the first callback. */
    {
        struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&ctx);u32 out;
        s=source;gvs_mutate_source=&s;gvs_mutated=false;
        assert(!opengpu_tessellation_vertex_source_append(o,&s,&gvs_callbacks,&out));
        gvs_mutate_source=NULL;assert(gvs_mutated);memcpy(g.draws,saved_draw,sizeof(saved_draw));
        assert(!opengpu_private_snapshot_prepare(o));assert(!opengpu_private_snapshot_publish(o));gvs_oracle(o,out,&source,0);
        assert(!opengpu_private_snapshot_retire(o));graph_retired(&gpu,&ctx);
    }
    /* Zero-work ranges are canonical zero; capacities/layout stay full. */
    s=source;s.patch_count=0;s.slice=(struct opengpu_tessellation_vertex_slice){.version=1};
    {
        struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&ctx);u32 out;
        assert(!opengpu_tessellation_vertex_source_append(o,&s,&gvs_callbacks,&out));
        assert(!opengpu_private_snapshot_prepare(o));assert(!opengpu_private_snapshot_publish(o));gvs_oracle(o,out,&s,0);
        assert(!opengpu_private_snapshot_retire(o));graph_retired(&gpu,&ctx);
    }
    s.slice.first_ordinal=1;gvs_reject(&gpu,&ctx,&s,-EINVAL);
    /* Zero components, high generic component and point-size VS2 are distinct
     * legal layouts. Inactive maps must remain zero, not silently truncated. */
    s=source;s.layout.input_components=0;memset(s.components,0,sizeof(s.components));gvs_publish_one(&gpu,&ctx,&s);
    s.layout.input_components=1;s.components[0]=(struct opengpu_tessellation_vertex_component){1,63};gvs_publish_one(&gpu,&ctx,&s);
    g.draws[344]&=~(1u<<31);gvs_reject(&gpu,&ctx,&s,-EINVAL);memcpy(g.draws,saved_draw,sizeof(saved_draw));
    s.components[0]=(struct opengpu_tessellation_vertex_component){2,0};g.draws[346]=2;
    opengpu_buffer_free(&gpu,&graph_original[graph_vs[0]]);
    assert(!opengpu_buffer_alloc(&gpu,&graph_original[graph_vs[0]],73728));graph_original_contents(0);
    s.plan.scratch.bytes+=128*4;gvs_publish_one(&gpu,&ctx,&s);
    s.components[0].component=1;gvs_reject(&gpu,&ctx,&s,-EINVAL);memcpy(g.draws,saved_draw,sizeof(saved_draw));
    for(int allocation=0;;allocation++) {
        struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&ctx);u32 out=0x5a5a5a5a;
        assert(allocation<1024 && !IS_ERR(o));private_alloc_fail_after=allocation;
        int ret=opengpu_tessellation_vertex_source_append(o,&source,&gvs_callbacks,&out);
        private_alloc_fail_after=-1;
        if(ret)assert(ret==-ENOMEM && out==0x5a5a5a5a);
        assert(!opengpu_private_snapshot_retire(o));graph_retired(&gpu,&ctx);
        if(!ret)break;
    }
    for(u32 h=1;h<=graph_handles;h++)opengpu_buffer_free(&gpu,&graph_original[h]);
    opengpu_mmu_vm_destroy(&gpu,&ctx.vm);opengpu_mmu_fini(&gpu);assert(ctx.references==1 && !live_allocations && !fixture_buffers);
    puts("GVS1 original VS/RX/GUR1 + GUR3 owner graph: PASS (CPU only)");
    return 0;
}
