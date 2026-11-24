/* SPDX-License-Identifier: MIT */
/* Same original linked Mesa program and actual winsys GEM identities through
 * the protected joint BUILD. No file-handle import, CPU patch or ISA execution. */
#define OPENGPU_CS_NATIVE_LAYOUT
#define native_joint_test original_joint_lifecycle_test
#include "native_joint_resources.c"
#undef native_joint_test
#include "opengpu_native_joint_build_source.h"
#include "native_joint_build_bridge.h"
extern void native_joint_build_linkage_test(void);
static struct native_joint_build_session *build_session;
static void *build_allocate(unsigned handle,uint64_t bytes)
{return native_joint_build_original_allocate(build_session,handle,bytes);}
static int build_close(unsigned handle)
{return native_joint_build_original_close(build_session,handle);}
static int build_references(unsigned handle)
{return native_joint_build_original_references(build_session,handle);}
static struct opengpu_native_joint_view extra_view(struct pipe_screen *screen,unsigned bytes,unsigned offset,
    struct pipe_resource **refs,unsigned *count)
{
    assert(*count<32);struct pipe_resource *r=buffer(screen,bytes+offset);assert(r);refs[(*count)++]=r;
    void *p=pipe_opengpu_resource_map(opengpu_resource(r)->gpu);assert(p);memset(p,0x5a,bytes+offset);
    return (struct opengpu_native_joint_view){r,offset,bytes};
}
void native_joint_test(struct opengpu_native_joint_link *link,const char *output,unsigned full,unsigned vs_slot,unsigned fs_slot)
{
    native_joint_build_linkage_test();
    /* Preserve every original compiler/52-reference/token/old-byte assertion. */
    original_joint_lifecycle_test(link,output,full,vs_slot,fs_slot);
    struct pipe_screen *screen=opengpu_drm_screen_create(7,NULL);assert(screen);
    struct pipe_context *pipe=screen->context_create(screen,NULL,0);assert(pipe);
    struct opengpu_native_sample_runtime runtime;assert(!opengpu_native_sample_runtime_decode(cpu_profile,117,&runtime));
    assert(!opengpu_native_sample_context_bootstrap(pipe,&runtime));
    unsigned context;assert(!opengpu_native_sample_context_runtime(pipe,&runtime,&context));
    build_session=native_joint_build_session_create(context);
    native_original_allocate=build_allocate;native_original_close=build_close;native_original_refcount=build_references;
    struct opengpu_native_joint_program *program=opengpu_native_joint_program_create(pipe,link,2);assert(program);
    struct opengpu_native_joint_constants *constants=opengpu_native_joint_constants_create(pipe);assert(constants);
    struct pipe_context *sink=opengpu_native_joint_constants_pipe(constants);
    sparse_next=1;struct pipe_resource *original=buffer(screen,72*16448+256);assert(original);
    unsigned handle=pipe_opengpu_resource_handle(opengpu_resource(original)->gpu);
    for(unsigned i=0;i<(72*16448+256)/4;i++)((uint32_t *)bos[handle].data)[i]=0x13579bdfu+i*33u;
    struct pipe_resource *resources[72]={0};unsigned offsets[72]={0},sizes[72]={0};
    for(unsigned s=0;s<4;s++)for(unsigned b=0;b<opengpu_native_joint_blocks(link,joint_stages[s]);b++){
        unsigned binding=opengpu_native_joint_binding(link,joint_stages[s],b);assert(binding<72&&!resources[binding]);
        pipe_resource_reference(resources+binding,original);offsets[binding]=4+binding*16448;sizes[binding]=16384;
    }
    assert(!opengpu_native_joint_bind_ubos(link,sink,resources,offsets,sizes));
    uint32_t defaults[1024];for(unsigned s=0;s<4;s++){
        for(unsigned i=0;i<1024;i++)defaults[i]=0x76543210u-i-s*4096;
        unsigned count=opengpu_native_joint_defaults(link,joint_stages[s]);
        if(count)assert(!opengpu_native_joint_bind_defaults(link,sink,joint_stages[s],defaults,count));
    }
    memset(defaults,0xee,sizeof(defaults));
    struct opengpu_native_joint_source *lease=opengpu_native_joint_source_create(program,constants);assert(lease);
    struct opengpu_native_joint_description pd;assert(!opengpu_native_joint_source_describe(lease,&pd));
    struct pipe_resource *extra[32]={0};unsigned ne=0;
    struct opengpu_native_joint_view input=extra_view(screen,512,4,extra,&ne);
    struct opengpu_native_joint_catalog_draw draws[2]={0};
    unsigned vs_bytes=(7+4*pd.vertex_layout.attributes+4*pd.vertex_layout.varyings)*512;
    for(unsigned i=0;i<2;i++){
        draws[i]=(struct opengpu_native_joint_catalog_draw){.count=4,.instances=2,.first_vertex=1,.base_instance=7,.token=41+i,
            .scratch=extra_view(screen,vs_bytes,64,extra,&ne)};
        draws[i].attributes[0]=(struct opengpu_native_draw_attribute){.enabled=1,.components=4};
        draws[i].bindings[0]=(struct opengpu_native_joint_binding){.view=input,.stride=16};
    }
    /* Highest original binding remains a real source view, not a capacity
     * label. Exercise it in the actual second draw/owner graph. */
    draws[1].bindings[15]=draws[1].bindings[0];memset(draws[1].bindings,0,sizeof(draws[1].bindings[0]));
    draws[1].attributes[0].binding=15;
    draws[1].indices=extra_view(screen,16,2,extra,&ne);draws[1].indexed=1;draws[1].index_format=2;draws[1].first_index=1;draws[1].base_vertex=-1;
    const uint16_t original_indices[8]={1,2,3,4,5,6,7,8};
    memcpy((unsigned char *)pipe_opengpu_resource_map(opengpu_resource(draws[1].indices.resource)->gpu)+2,original_indices,sizeof(original_indices));
    struct opengpu_native_joint_invocation inv[2]={{(1ULL<<32)+3,0,2},{(1ULL<<32)+9,1,2}};
    struct opengpu_native_joint_range_request ranges[4];
    for(unsigned i=0;i<4;i++)ranges[i]=(struct opengpu_native_joint_range_request){.sequence_index=i,.invocation_index=i/2,
        .draw_index=i/2,.range_ordinal=i%2,.flags=i%2?6:1,.task_id_base=100+i*8,.patch_count=2,
        .patches_per_instance=2,.first_instance=i%2,.instances=1};
    unsigned samples=pd.fragment.metadata.requires_sample_owner?4:1;
    struct opengpu_native_joint_build_request request={.context=pipe,.source=lease,.pass=11,.generation=13,.state_count=1,
        .draws=draws,.draw_count=2,.invocations=inv,.invocation_count=2,.ranges=ranges,.range_count=4,
        .frame={.width=4,.height=4,.samples=samples,.color_mask=1,.tile_log2=3}};
    request.frame.attachments[0]=(struct opengpu_native_joint_attachment){.format=pd.fragment.metadata.color_types[0]==1?26:35,
        .load_op=1,.channel_mask=15,.clear={0x3e800000,0x3e800000,0x3e800000,0x3e800000},
        .image={.view=extra_view(screen,4*4*samples*16,64,extra,&ne),.row_pitch=4*samples*16,.store=1}};
    request.frame.primitives=extra_view(screen,65536,64,extra,&ne);
    request.frame.directory=extra_view(screen,4096,64,extra,&ne);
    request.frame.pool[0]=extra_view(screen,65536,0,extra,&ne);
    for(unsigned e=0;e<4;e++)request.frame.scratch[e]=extra_view(screen,pd.fragment.metadata.prefix_bytes,64,extra,&ne);
    /* Rejection must unwind partial real resource holds without touching the
     * retained original source, including errors reached after frame holds. */
    /* All sixteen nonuniformly packed binding records use published literal
     * offsets. A scalar stride formula would corrupt binding2 and higher. */
    const unsigned stride_words[16]={189,198,206,214,222,230,238,246,254,262,270,278,286,294,302,310};
    for(unsigned b=0;b<16;b++){
        struct opengpu_native_joint_catalog_draw probe[2];memcpy(probe,draws,sizeof(probe));
        memset(probe[0].bindings,0,sizeof(probe[0].bindings));
        probe[0].attributes[0].binding=b;probe[0].bindings[b]=(struct opengpu_native_joint_binding){.view=input,.stride=16};
        struct opengpu_native_joint_build_request q=request;q.draws=probe;
        struct opengpu_native_joint_build_source *v=opengpu_native_joint_build_source_create(&q);assert(v);
        struct opengpu_native_joint_build_description got;assert(!opengpu_native_joint_build_source_describe(v,&got));
        for(unsigned n=0;n<16;n++)assert(got.draws[stride_words[n]]==(n==b?16:0));
        opengpu_native_joint_build_source_destroy(v);
    }
    unsigned original_refs=original->reference.count,input_refs=extra[0]->reference.count;
    for(unsigned bad=0;bad<13;bad++){
        struct opengpu_native_joint_build_request q=request;
        struct opengpu_native_joint_catalog_draw wrong_draws[2];memcpy(wrong_draws,draws,sizeof(draws));q.draws=wrong_draws;
        struct opengpu_native_joint_range_request wrong_ranges[4];memcpy(wrong_ranges,ranges,sizeof(ranges));q.ranges=wrong_ranges;
        struct opengpu_native_joint_invocation wrong_inv[2];memcpy(wrong_inv,inv,sizeof(inv));q.invocations=wrong_inv;
        switch(bad){
        case 0:q.draw_count=UINT32_MAX;break;
        case 1:q.range_count=UINT32_MAX;break;
        case 2:wrong_draws[0].indexed=1;wrong_draws[0].index_format=0;break;
        case 3:wrong_draws[0].scratch.bytes--;break;
        case 4:wrong_draws[0].attributes[0].format=25;break;
        case 5:wrong_draws[0].bindings[0].view.offset=UINT64_MAX;break;
        case 6:wrong_ranges[0].first_instance=2;break;
        case 7:wrong_ranges[1].range_ordinal=0;break;
        case 8:wrong_ranges[0].task_id_base=UINT32_MAX;break;
        case 9:wrong_inv[1].invocation_id=wrong_inv[0].invocation_id;break;
        case 10:q.frame.width=UINT32_MAX;break;
        case 11:q.frame.scratch[0].bytes--;break;
        case 12:q.invocation_count=UINT32_MAX;break;
        }
        assert(!opengpu_native_joint_build_source_create(&q));
        assert(original->reference.count==original_refs&&extra[0]->reference.count==input_refs);
    }
    struct opengpu_native_joint_build_source *bundle=opengpu_native_joint_build_source_create(&request);assert(bundle);
    struct opengpu_native_joint_build_description d;assert(!opengpu_native_joint_build_source_describe(bundle,&d));
    uint64_t sequence=0xfeed;
    assert(opengpu_native_joint_build_source_submit(bundle,0,0,&sequence)==-1 && errno==ENOTSUP && sequence==0xfeed);
    struct opengpu_native_joint_build_request zero_request=request;
    struct opengpu_native_joint_catalog_draw zero_draws[2];memcpy(zero_draws,draws,sizeof(zero_draws));
    zero_draws[0].count=0;zero_draws[1].instances=0;zero_request.draws=zero_draws;
    struct opengpu_native_joint_range_request zero_ranges[2]={0};
    for(unsigned i=0;i<2;i++)zero_ranges[i]=(struct opengpu_native_joint_range_request){.sequence_index=i,.invocation_index=i,.draw_index=i,.flags=3,.task_id_base=200+i};
    zero_request.ranges=zero_ranges;zero_request.range_count=2;
    struct opengpu_native_joint_build_source *zero=opengpu_native_joint_build_source_create(&zero_request);assert(zero);
    struct opengpu_native_joint_build_description zero_d;assert(!opengpu_native_joint_build_source_describe(zero,&zero_d));
    struct opengpu_native_joint_build_request empty_request=request;
    empty_request.draw_count=empty_request.invocation_count=empty_request.range_count=empty_request.state_count=0;
    empty_request.draws=NULL;empty_request.invocations=NULL;empty_request.ranges=NULL;
    struct opengpu_native_joint_build_source *empty=opengpu_native_joint_build_source_create(&empty_request);assert(empty);
    struct opengpu_native_joint_build_description empty_d;assert(!opengpu_native_joint_build_source_describe(empty,&empty_d));
    unsigned expected_domain=pd.tess[1].layout.domain==PIPE_PRIM_LINES?0:pd.tess[1].layout.domain==PIPE_PRIM_TRIANGLES?1:2;
    assert(d.domain==expected_domain);
    assert(d.draw_count==2&&d.range_count==4&&d.frame->frame.words[0]==0x474e4601&&d.frame->frame.words[2]==1);
    assert(!memcmp(&d.draw_plans[0].vertex_program,&pd.code[0],sizeof(pd.code[0])));
    assert(!memcmp(&d.plans[0].control_uniforms,&pd.uniforms[1],sizeof(pd.uniforms[1])));
    /* Rebind to different same-GEM windows/default copies. The bundle keeps
     * the earlier 52 exact views, not these latest mutable cache contents. */
    for(unsigned st=0;st<4;st++){
        for(unsigned i=0;i<1024;i++)defaults[i]=0xabcdef00u+i+st;
        unsigned count=opengpu_native_joint_defaults(link,joint_stages[st]);
        if(count)assert(!opengpu_native_joint_bind_defaults(link,sink,joint_stages[st],defaults,count));
        unsigned blocks=opengpu_native_joint_blocks(link,joint_stages[st]);
        if(blocks){unsigned binding=opengpu_native_joint_binding(link,joint_stages[st],0);
            struct pipe_constant_buffer cb={.buffer=resources[binding],.buffer_offset=offsets[binding]+64,.buffer_size=16384};
            sink->set_constant_buffer(sink,joint_stages[st],1,false,&cb);}
    }
    memset(defaults,0xbb,sizeof(defaults));
    /* Immutable originals survive complete caller/cache/program release. */
    opengpu_native_joint_source_destroy(lease);opengpu_native_joint_program_destroy(program);
    opengpu_native_joint_constants_destroy(constants);
    for(unsigned b=0;b<72;b++)pipe_resource_reference(resources+b,NULL);
    pipe_resource_reference(&original,NULL);for(unsigned i=0;i<ne;i++)pipe_resource_reference(extra+i,NULL);
    memset(draws,0xa7,sizeof(draws));memset(inv,0xa7,sizeof(inv));memset(ranges,0xa7,sizeof(ranges));memset(&request,0xa7,sizeof(request));
    for(unsigned st=0;st<4;st++)assert(bos[pd.code[st].handle].handle);
    assert(bos[d.draw_plans[0].bindings[0].handle].handle);
    assert(!native_joint_build_prove(build_session,&d,&pd));
    assert(!native_joint_build_prove(build_session,&zero_d,&pd));
    opengpu_native_joint_build_source_destroy(zero);
    assert(!native_joint_build_prove(build_session,&empty_d,&pd));
    opengpu_native_joint_build_source_destroy(empty);
    opengpu_native_joint_build_source_destroy(bundle);
    pipe->destroy(pipe);screen->destroy(screen);
    native_original_allocate=NULL;native_original_close=NULL;native_original_refcount=NULL;
    native_joint_build_session_destroy(build_session);build_session=NULL;
}
