/* Actual GVS/RX/GUR1/inputs -> private owner/MMU. No shader/GPU emulation. */
#define OPENGPU_NATIVE_JOB_SNAPSHOT_TEST_MAIN outer_native_regression_main
#include "opengpu_native_job_snapshot_test.c"
#undef OPENGPU_NATIVE_JOB_SNAPSHOT_TEST_MAIN
#include "../opengpu_tessellation_uniform_snapshot.c"
#include "../opengpu_tessellation_vertex_source.c"
#ifndef OPENGPU_VERTEX_OUTER_SOURCE
#define OPENGPU_VERTEX_OUTER_SOURCE "../opengpu_tessellation_vertex_plan.c"
#endif
#include OPENGPU_VERTEX_OUTER_SOURCE

struct outer_fixture {
    u32 draws[2][512];
    struct drm_opengpu_native_draw_plan plans[2];
    struct opengpu_tessellation_vertex_outer_invocation_v2 inv[3];
    struct opengpu_tessellation_vertex_outer_range_v2 ranges[5];
    struct opengpu_tessellation_vertex_outer_source_v2 source;
    struct opengpu_native_job_copy callbacks;
};
static struct outer_fixture *outer_mutate;
static bool outer_mutated;
static int outer_resolve_fixture(void *opaque,u32 handle,struct opengpu_native_frame_backing *out)
{
    if(outer_mutate && !outer_mutated) {
        outer_mutated=true;
        memset(outer_mutate->draws,0,sizeof(outer_mutate->draws));
        memset(outer_mutate->plans,0,sizeof(outer_mutate->plans));
        memset(outer_mutate->inv,0,sizeof(outer_mutate->inv));
        memset(outer_mutate->ranges,0,sizeof(outer_mutate->ranges));
        memset(&outer_mutate->source,0,sizeof(outer_mutate->source));
        memset(&outer_mutate->callbacks,0,sizeof(outer_mutate->callbacks));
    }
    return graph_resolve(opaque,handle,out);
}
static void outer_fixture_init(struct outer_fixture *f,const struct graph_records *g)
{
    memset(f,0,sizeof(*f));
    for(u32 d=0;d<2;d++) {
        memcpy(f->draws[d],g->draws+(size_t)d*GRAPH_STRIDE/4,2048);
        f->draws[d][311]=d?5:4;f->draws[d][325]=d?2:3;
        f->draws[d][326]=1;f->draws[d][343]=f->draws[d][344]=0xffffffffu;
        for(u32 i=0;i<GRAPH_DRAWS;i++)if(g->plans[i].draw_index==d)f->plans[1-d]=g->plans[i];
    }
    /* Original indexed input, base vertex, instance and all16 divisors survive.
     * Raw indices are NOT CPU decoded. GPU execution remains unqualified. */
    f->draws[1][317]=2;f->draws[1][312]=1;f->draws[1][181]=0xfffffffdu;
    f->draws[1][180]=7;
    for(u32 b=0;b<16;b++)f->draws[1][binding_words[b].divisor]=b+1;
    f->plans[0].indices=(struct drm_opengpu_compute_uniform_view){.handle=graph_inputs[0],.offset=66,.bytes=64};
    f->inv[0]=(struct opengpu_tessellation_vertex_outer_invocation_v2){.invocation_id=0x100000001ULL,.original_draw_index=1,.input_patch_vertices=2};
    f->inv[1]=(struct opengpu_tessellation_vertex_outer_invocation_v2){.invocation_id=0,.original_draw_index=0,.input_patch_vertices=2};
    f->inv[2]=(struct opengpu_tessellation_vertex_outer_invocation_v2){.invocation_id=0x200000001ULL,.original_draw_index=1,.input_patch_vertices=2};
    /* Independently fixed API invocation catalog order[1,0,1]. First invocation
     * continues twice; the same catalog1 later starts a DISTINCT invocation. */
    const u32 iv[]={0,0,0,1,2},draw[]={1,1,1,0,1},ord[]={0,1,2,0,0};
    const u32 flags[]={1,4,6,3,3},first[]={0,2,0,0,0},per[]={1,1,2,2,2};
    const u32 inst[]={0,0,1,0,0},instances[]={1,1,1,3,2};
    for(u32 i=0;i<5;i++) {
        struct opengpu_tessellation_vertex_outer_range_v2 *r=&f->ranges[4-i];
        r->sequence_index=i;r->invocation_index=iv[i];r->original_draw_index=draw[i];
        r->range_ordinal=ord[i];r->flags=flags[i];r->task_id_base=100+10*i;
        r->patch_count=per[i]*instances[i];
        r->slice=(struct opengpu_tessellation_vertex_slice){1,first[i],per[i],inst[i],instances[i]};
        r->layout=(struct opengpu_tessellation_patch_layout){2,64,32,60,120};
        for(u32 c=0;c<64;c++)r->components[c]=(struct opengpu_tessellation_vertex_component){c<4?0:1,c<4?c:c-4};
    }
    f->source=(struct opengpu_tessellation_vertex_outer_source_v2){.version=2,.context=7,.pass=8,.generation=9,
        .draws=f->draws,.draws_bytes=sizeof(f->draws),.plans=f->plans,.draw_count=2,.plan_count=2,
        .invocations=f->inv,.ranges=f->ranges,.invocation_count=3,.range_count=5,
        .profile={1,2,16,8,true,true,true}};
    f->callbacks=(struct opengpu_native_job_copy){graph_copy,outer_resolve_fixture,graph_original};
}
static struct opengpu_tessellation_vertex_outer_range_v2 *outer_range(struct outer_fixture *f,u32 sequence)
{return &f->ranges[4-sequence];}
static const struct drm_opengpu_native_draw_plan *outer_plan(const struct outer_fixture *f,u32 draw)
{
    for(u32 i=0;i<2;i++)if(f->plans[i].draw_index==draw)return &f->plans[i];
    assert(!"missing independent original plan");return NULL;
}
static void outer_view_oracle(struct opengpu_vm *vm,u32 draw,const struct input_words *w,
    const struct drm_opengpu_compute_uniform_view *v,u32 permission)
{
    u32 base=graph_read(vm,draw+w->base*4,3),allocation=graph_read(vm,draw+w->allocation_base*4,3);
    u64 limit=graph_read(vm,draw+w->limit*4,3)|((u64)graph_read(vm,draw+(w->limit+1)*4,3)<<32);
    u64 end=graph_read(vm,draw+w->allocation_limit*4,3)|((u64)graph_read(vm,draw+(w->allocation_limit+1)*4,3)<<32);
    assert(limit==(u64)base+v->bytes && allocation==(base&~63u) && end==((limit+63)&~63ULL));
    assert(graph_translate(vm,base,permission)==graph_original[v->handle].dma+v->offset);
}
static void outer_root_oracle(struct opengpu_vm *vm,u32 root,u32 sequence,const struct outer_fixture *f)
{
    const struct opengpu_tessellation_vertex_outer_range_v2 *r=&f->ranges[4-sequence];
    const struct drm_opengpu_native_draw_plan *p=outer_plan(f,r->original_draw_index);
    u32 words[128],expected[128]={0},draw=graph_read(vm,root+96,3),code=graph_read(vm,root+108,3);
    const u32 header[]={0x47565301,512,1,16,16,16,64,32,16,8,2};memcpy(expected,header,sizeof(header));
    expected[16]=7;expected[17]=8;expected[18]=9;expected[19]=sequence;expected[20]=r->original_draw_index;
    expected[21]=r->task_id_base;expected[22]=r->patch_count;
    expected[24]=draw;expected[25]=draw+2048;expected[27]=code;expected[28]=code+p->vertex_program.bytes;
    expected[30]=code+p->vertex_program.entry_offset;expected[32]=1;
    expected[33]=r->slice.first_ordinal;expected[34]=r->slice.patches_per_instance;
    expected[35]=r->slice.first_instance;expected[36]=r->slice.instances;
    expected[40]=2;expected[41]=64;expected[42]=32;expected[43]=60;expected[44]=120;expected[45]=1;
    for(u32 c=0;c<64;c++)expected[64+c]=c<4?c<<2:1|((c-4)<<2);
    for(u32 w=0;w<128;w++){words[w]=graph_read(vm,root+w*4,3);assert(words[w]==expected[w]);}
    assert(opengpu_tessellation_vertex_source_canonical(words,512));
    assert(graph_read(vm,code,11)==0x93000000u && graph_read(vm,expected[30],11)==0x30500073u);
    graph_uniform_oracle(vm,graph_read(vm,draw+507*4,3),0,0);
    for(u32 b=0;b<16;b++)outer_view_oracle(vm,draw,&binding_words[b],&p->bindings[b],3);
    outer_view_oracle(vm,draw,&scratch_words,&p->vertex_scratch,7);
    if(p->indices.handle)outer_view_oracle(vm,draw,&index_words,&p->indices,3);
    /* Every original descriptor word except actual typed relocations and the
     * deliberate PATCHES restart canonicalization is identical. */
    for(u32 w=0;w<512;w++) {
        bool relocated=w==342 || w==507;
        const struct input_words *views[18];
        for(u32 b=0;b<16;b++){views[b]=&binding_words[b];}
        views[16]=&scratch_words;views[17]=&index_words;
        for(u32 b=0;b<18;b++)if(b!=17 || p->indices.handle) {
            const struct input_words *v=views[b];
            relocated|=w==v->base || w==v->limit || w==v->limit+1 || w==v->allocation_base ||
                w==v->allocation_limit || w==v->allocation_limit+1;
        }
        if(!relocated)assert(graph_read(vm,draw+w*4,3)==(w==326?0:w==506?1:f->draws[r->original_draw_index][w]));
    }
}
static void outer_map_oracle(struct opengpu_private_snapshot *o,u32 segment,const struct outer_fixture *f)
{
    u32 base,words[96];assert(!opengpu_private_snapshot_address(o,segment,&base));
    struct opengpu_vm *vm=&o->context->vm;
    const u32 header[]={0x47564d02,384,2,64,2,3,5,7,8,9,16,16,16,8,2,0};
    const u32 entries[5][12]={
        {0,1,1,1,1,0,0,1,0,1,100,1},
        {1,1,1,1,4,1,0,1,1,1,110,1},
        {2,1,1,1,6,2,1,1,0,2,120,2},
        {3,0,0,0,3,0,0,3,0,2,130,6},
        {4,1,1,2,3,0,0,2,0,2,140,4}};
    for(u32 i=0;i<96;i++)words[i]=graph_read(vm,base+i*4,3);
    for(u32 i=0;i<16;i++)assert(words[i]==header[i]);
    for(u32 n=0;n<5;n++) {
        u32 *r=words+16+n*16;
        for(u32 w=0;w<12;w++)assert(r[w]==entries[n][w]);
        assert(r[13]==r[12]+512 && !r[14] && !r[15]);
        outer_root_oracle(vm,r[12],n,f);
    }
    assert(opengpu_tessellation_vertex_outer_map_canonical(words,sizeof(words)));
    assert(!opengpu_tessellation_vertex_outer_map_source_canonical(words,sizeof(words)));
    /* Canonical syntax checks are independent from the original-source proof. */
    u32 source[96];memcpy(source,words,sizeof(source));
    for(u32 n=0;n<5;n++)source[16+n*16+12]=source[16+n*16+13]=source[16+n*16+14]=0;
    assert(opengpu_tessellation_vertex_outer_map_source_canonical(source,sizeof(source)));
    assert(!opengpu_tessellation_vertex_outer_map_canonical(source,sizeof(source)));
    for(u32 i=0;i<96;i++) {
        u32 saved=source[i];source[i]^=0x80000000u;
        /* Full-width identity/task fields are legal values, not reserved bits. */
        if(i==0 || i==1 || i==2 || i==3 || i==5 || i==6 || (i>=10 && i<16) ||
            (i>=16 && ((i-16)%16==0 || (i-16)%16==1 || (i-16)%16==4 ||
             (i-16)%16==5 || (i-16)%16>=12)))
            assert(!opengpu_tessellation_vertex_outer_map_source_canonical(source,sizeof(source)));
        source[i]=saved;
    }
    words[28]=0xfffffe00;words[29]=0;words[30]=1;
    assert(opengpu_tessellation_vertex_outer_map_canonical(words,sizeof(words)));
    words[30]=2;assert(!opengpu_tessellation_vertex_outer_map_canonical(words,sizeof(words)));
    words[30]=1;assert(opengpu_tessellation_vertex_outer_map_canonical(words,sizeof(words)));
    words[16+16+1]=0;
    assert(!opengpu_tessellation_vertex_outer_map_canonical(words,sizeof(words)));
    source[16+16+1]=0; /* a continuation cannot switch the original catalog */
    assert(!opengpu_tessellation_vertex_outer_map_source_canonical(source,sizeof(source)));
}
static unsigned outer_case;
static void outer_reject(struct opengpu_device *gpu,struct opengpu_render_context *ctx,
    struct outer_fixture *f,int expected)
{
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 out=0x5a5a5a5a;
    assert(!IS_ERR(o));
    int got=opengpu_tessellation_vertex_outer_append(o,&f->source,&f->callbacks,&out);
    if(got!=expected)fprintf(stderr,"outer reject case %u expected %d got %d\n",outer_case,expected,got);
    assert(got==expected && out==0x5a5a5a5a);
    assert(!opengpu_private_snapshot_retire(o));graph_retired(gpu,ctx);outer_case++;
}
static void outer_publish(struct opengpu_device *gpu,struct opengpu_render_context *ctx,
    struct outer_fixture *f,const struct outer_fixture *expected)
{
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 out;
    assert(!IS_ERR(o));assert(!opengpu_tessellation_vertex_outer_append(o,&f->source,&f->callbacks,&out));
    assert(!opengpu_private_snapshot_prepare(o));assert(!opengpu_private_snapshot_publish(o));
    outer_map_oracle(o,out,expected);
    assert(!opengpu_private_snapshot_retire(o));graph_retired(gpu,ctx);
}
static void outer_empty(struct opengpu_device *gpu,struct opengpu_render_context *ctx,const struct graph_records *g)
{
    struct outer_fixture f;outer_fixture_init(&f,g);
    f.source.invocation_count=f.source.range_count=1;f.source.ranges=outer_range(&f,0);
    f.draws[1][311]=1; /* fewer original vertices than one input patch */
    struct opengpu_tessellation_vertex_outer_range_v2 *r=outer_range(&f,0);
    r->flags=3;r->patch_count=0;r->slice=(struct opengpu_tessellation_vertex_slice){.version=1};
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 segment,base;
    assert(!IS_ERR(o));assert(!opengpu_tessellation_vertex_outer_append(o,&f.source,&f.callbacks,&segment));
    assert(!opengpu_private_snapshot_prepare(o));assert(!opengpu_private_snapshot_publish(o));
    assert(!opengpu_private_snapshot_address(o,segment,&base));
    u32 map[32];for(u32 i=0;i<32;i++)map[i]=graph_read(&ctx->vm,base+i*4,3);
    const u32 header[]={0x47564d02,128,2,64,2,1,1,7,8,9,16,16,16,8,2,0};
    for(u32 i=0;i<16;i++)assert(map[i]==header[i]);
    const u32 entry[]={0,1,1,1,3,0,0,0,0,0,100,0};
    for(u32 i=0;i<12;i++)assert(map[16+i]==entry[i]);
    assert(opengpu_tessellation_vertex_outer_map_canonical(map,sizeof(map)));
    assert(graph_read(&ctx->vm,map[28]+22*4,3)==0); /* actual GVS count0 */
    assert(!opengpu_private_snapshot_retire(o));graph_retired(gpu,ctx);
    /* A zero range cannot hide an otherwise nonempty invocation. */
    f.draws[1][311]=5;outer_reject(gpu,ctx,&f,-EINVAL);
}
static void outer_unused_catalog(struct opengpu_device *gpu,struct opengpu_render_context *ctx,const struct graph_records *g)
{
    struct outer_fixture f;outer_fixture_init(&f,g);
    f.source.invocation_count=1;f.source.range_count=3;f.source.ranges=&f.ranges[2];
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);u32 segment,base;
    assert(!IS_ERR(o));assert(!opengpu_tessellation_vertex_outer_append(o,&f.source,&f.callbacks,&segment));
    assert(!opengpu_private_snapshot_prepare(o));assert(!opengpu_private_snapshot_publish(o));
    assert(!opengpu_private_snapshot_address(o,segment,&base));
    u32 words[64];for(u32 w=0;w<64;w++)words[w]=graph_read(&ctx->vm,base+w*4,3);
    const u32 header[]={0x47564d02,256,2,64,2,1,3,7,8,9,16,16,16,8,2,0};
    for(u32 w=0;w<16;w++)assert(words[w]==header[w]);
    assert(opengpu_tessellation_vertex_outer_map_canonical(words,sizeof(words)));
    for(u32 n=0;n<3;n++) {
        assert(words[16+n*16]==n && words[17+n*16]==1);
        outer_root_oracle(&ctx->vm,words[28+n*16],n,&f);
    }
    assert(!opengpu_private_snapshot_retire(o));graph_retired(gpu,ctx);
    f.draws[0][328]=1;outer_reject(gpu,ctx,&f,-EOPNOTSUPP);
    f.draws[0][328]=0;f.draws[0][15]=1;outer_reject(gpu,ctx,&f,-EINVAL);
}
int main(void)
{
    struct opengpu_device gpu={0};struct opengpu_render_context ctx={.gpu=&gpu,.references=1,.id=7};
    struct graph_records graph;struct outer_fixture f,expected;
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;assert(!opengpu_mmu_init(&gpu));
    assert(!opengpu_mmu_vm_create(&gpu,&ctx.vm));copy_gpu=&gpu;graph_init(&graph,&gpu);
    outer_fixture_init(&f,&graph);expected=f;
    graph_copies=graph_resolves=0;outer_publish(&gpu,&ctx,&f,&expected);
    unsigned copies=graph_copies,resolves=graph_resolves;assert(copies && resolves);
    /* First actual callback destroys ALL caller containers, including the
     * callback function table and source pointers/counts for later ranges. */
    outer_fixture_init(&f,&graph);outer_mutate=&f;outer_mutated=false;
    outer_publish(&gpu,&ctx,&f,&expected);assert(outer_mutated);outer_mutate=NULL;
    outer_empty(&gpu,&ctx,&graph);
    outer_unused_catalog(&gpu,&ctx,&graph);
    for(u32 field=0;field<27;field++) {
        outer_fixture_init(&f,&graph);
        switch(field) {
        case 0:f.source.version=1;break;
        case 1:f.source.reserved[1]=1;break;
        case 2:f.source.draws_bytes--;break;
        case 3:f.source.plan_count--;break;
        case 4:f.plans[0].draw_index=0;break;
        case 5:f.plans[0].draw_index=2;break;
        case 6:f.inv[2].invocation_id=f.inv[0].invocation_id;break;
        case 7:f.inv[0].reserved[0]=1;break;
        case 8:f.inv[0].original_draw_index=2;break;
        case 9:outer_range(&f,1)->sequence_index=0;break;
        case 10:outer_range(&f,1)->range_ordinal=0;break;
        case 11:outer_range(&f,1)->invocation_index=1;break;
        case 12:outer_range(&f,1)->original_draw_index=0;break;
        case 13:outer_range(&f,1)->flags=3;break;
        case 14:outer_range(&f,0)->reserved=1;break;
        case 15:outer_range(&f,0)->slice.first_ordinal=1;break;
        case 16:outer_range(&f,1)->slice.first_ordinal=0;break;
        case 17:outer_range(&f,0)->slice.instances=2;outer_range(&f,0)->patch_count=2;break;
        case 18:outer_range(&f,3)->task_id_base=0xfffffffe;break;
        case 19:outer_range(&f,4)->layout.input_vertices=1;break;
        case 20:f.draws[0][315]=10;break;
        case 21:f.plans[0].vertex_program.pad=1;break;
        case 22:f.source.range_count=0xffffffffu;break;
        case 23:f.source.invocation_count=6;break;
        case 24:outer_range(&f,4)->flags=0x80000003u;break;
        case 25:f.inv[2].input_patch_vertices=33;break;
        case 26:f.plans[0].fragment_uniforms.active_mask|=0x1000;break;
        }
        graph_copies=graph_resolves=0;outer_reject(&gpu,&ctx,&f,-EINVAL);
        assert(!graph_copies && !graph_resolves);
    }
    /* A skipped FIRST patch must not be legitimized by reaching the final
     * original ordinal. This one-range recipe isolates exact cover. */
    outer_fixture_init(&f,&graph);f.source.invocation_count=f.source.range_count=1;
    f.source.ranges=outer_range(&f,0);f.draws[1][325]=1;
    outer_range(&f,0)->slice.first_ordinal=2;outer_range(&f,0)->flags=3;
    outer_reject(&gpu,&ctx,&f,-EINVAL);
    /* Unused catalog0 is still canonical and unsupported control fails closed. */
    outer_fixture_init(&f,&graph);f.source.invocation_count=1;f.source.range_count=3;
    outer_range(&f,0)->sequence_index=0;outer_range(&f,1)->sequence_index=1;outer_range(&f,2)->sequence_index=2;
    f.source.ranges=&f.ranges[2];f.plans[1].indirect=f.plans[1].bindings[0];
    outer_reject(&gpu,&ctx,&f,-EOPNOTSUPP);
    outer_fixture_init(&f,&graph);f.source.profile.available=false;
    graph_copies=graph_resolves=0;outer_reject(&gpu,&ctx,&f,-EOPNOTSUPP);assert(!graph_copies && !graph_resolves);
    for(unsigned fail=1;fail<=copies;fail++) {
        outer_fixture_init(&f,&graph);graph_copies=graph_resolves=0;graph_copy_failure=fail;
        outer_reject(&gpu,&ctx,&f,-EIO);
    }
    graph_copy_failure=0;
    for(unsigned fail=1;fail<=resolves;fail++) {
        outer_fixture_init(&f,&graph);graph_copies=graph_resolves=0;graph_resolve_failure=fail;
        outer_reject(&gpu,&ctx,&f,-EIO);
    }
    graph_resolve_failure=0;
    /* All failures, including partial real append, retain a single owner for
     * actual MMU retirement. Never reuse an unsuccessful owner. */
    for(int allocation=0;;allocation++) {
        struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&ctx);u32 out=0x5a5a5a5a;
        assert(!IS_ERR(o) && allocation<4096);outer_fixture_init(&f,&graph);private_alloc_fail_after=allocation;
        int ret=opengpu_tessellation_vertex_outer_append(o,&f.source,&f.callbacks,&out);
        private_alloc_fail_after=-1;
        if(ret)assert(ret==-ENOMEM && out==0x5a5a5a5a);
        assert(!opengpu_private_snapshot_retire(o));graph_retired(&gpu,&ctx);
        if(!ret)break;
    }
    for(u32 h=1;h<=graph_handles;h++)opengpu_buffer_free(&gpu,&graph_original[h]);
    opengpu_mmu_vm_destroy(&gpu,&ctx.vm);opengpu_mmu_fini(&gpu);
    assert(ctx.references==1 && !live_allocations && !fixture_buffers);
    puts("GVM2 independent catalog/invocation/range -> actual GVS/RX/GUR1/owner/MMU: PASS (CPU only)");
    return 0;
}
