/* SPDX-License-Identifier: MIT */
/* Actual Gallium/winsys and native compiler; only OS/GEM primitives modeled.
 * No producer kick, shader VM, GPU result, protected grant or new API caps. */
#define main unused_cs_callback_main
#define OPENGPU_CS_REAL_TGSI_DUMP
#define OPENGPU_CS_REAL_MESA_SURFACES
#include "cs_callback_flow.c"
#undef main
#include "opengpu_native_graphics_v2.h"
#include "opengpu_native_draw_v2.h"
#include "graphics_uniform_frontend.h"
#include "native_graphics_v2_profile.inc"
static struct opengpu_native_graphics_program_v2 *compile_v2(struct pipe_context *pipe,void *frontend)
{
    struct opengpu_native_fragment_reflection_v2 r;assert(!graphics_uniform_reflection_v2(frontend,&r));
    return opengpu_native_graphics_program_create_v2(pipe,graphics_uniform_tokens(frontend,0),
        graphics_uniform_token_words(frontend,0),graphics_uniform_tokens(frontend,1),
        graphics_uniform_token_words(frontend,1),&r);
}
static void source_buffers(struct pipe_context *pipe,void *frontend,struct pipe_resource *original,
    struct pipe_resource *resources[36],unsigned offsets[36],unsigned sizes[36])
{
    for(unsigned stage=0,w=0;stage<2;stage++)for(unsigned i=0;i<graphics_uniform_blocks(frontend,stage);i++,w++){
        unsigned binding=graphics_uniform_binding(frontend,stage,i);assert(binding<36&&!resources[binding]);
        pipe_resource_reference(&resources[binding],original);offsets[binding]=4+w*16448;sizes[binding]=16384;
    }
    graphics_uniform_ubos(frontend,pipe,resources,offsets,sizes);
    uint32_t defaults[1024];for(unsigned i=0;i<1024;i++)defaults[i]=0x76543210u-i;
    for(unsigned stage=0;stage<2;stage++){
        unsigned components=graphics_uniform_components(frontend,stage);assert(components<=1024);
        if(components)graphics_uniform_defaults(frontend,pipe,stage,defaults,components);
    }
    memset(defaults,0xee,sizeof(defaults));
}
static void record(struct pipe_context *pipe,const struct opengpu_native_graphics_source_v2 *source,
    const struct opengpu_native_graphics_source_description_v2 *d,const char *output)
{
    sparse_next=1;struct pipe_resource *input=buffer(pipe->screen,256);assert(input);
    struct pipe_opengpu_screen *gpu=opengpu_screen_gpu(pipe->screen);
    uint64_t needed=(7+4*d->vertex_layout.attributes+4*d->vertex_layout.varyings)*512;
    struct pipe_opengpu_resource *scratch=pipe_opengpu_resource_create(gpu,needed);assert(scratch);
    struct opengpu_native_draw_request_v2 r={.context=pipe,.source=source,.pass=11,.generation=13,
        .draw_index=3,.state_handle=1,.state_count=2,.token=41,.width=4,.height=4,.topology=4,.count=3,
        .instances=2,.base_instance=7,.vertex_scratch={scratch,0,needed}};
    r.attributes[0]=(struct opengpu_native_draw_attribute){.enabled=1,.components=4};
    r.bindings[0]=(struct opengpu_native_draw_binding){.view={opengpu_resource(input)->gpu,4,48},.stride=16};
    for(unsigned mode=0;mode<3;mode++){
        r.sample_mode=mode;struct opengpu_native_draw_encoding_v2 e,original;
        assert(!opengpu_native_draw_encode_v2(&r,&e));original=e;
        const struct opengpu_native_fragment_metadata_v2 *m=&d->fragment.metadata;
        assert(e.state.words[56]==m->abi_version&&e.fragment_prefix_bytes==m->prefix_bytes);
        assert(e.state.words[29]==m->writes_depth&&e.state.words[124]==(uint32_t)m->centroid_mask&&
            e.state.words[125]==(uint32_t)(m->centroid_mask>>32));
        assert(e.draw.words[OPENGPU_NATIVE_BINNED_DRAW_DRAW_VERTEX_GEOMETRY_FLATMASK_WORD]==(uint32_t)m->flat_mask);
        assert(!memcmp(&e.plan.draw.vertex_uniforms,&d->uniforms[0],sizeof(d->uniforms[0]))&&
            !memcmp(&e.plan.draw.fragment_uniforms,&d->uniforms[1],sizeof(d->uniforms[1])));
        struct drm_opengpu_native_layered_draw_plan ordinary={.draw=e.plan.draw};assert(!memcmp(&ordinary,&e.plan,sizeof(ordinary)));
        /* Complete canonical/high-bit checks are performed independently from
         * published JSON schemas by the Python consumer of these actual bytes. */
        if(!mode){char path[4096];FILE *f;
            assert(snprintf(path,sizeof(path),"%s.gnd",output)>0);f=fopen(path,"wb");assert(f);assert(fwrite(&e.draw,1,2048,f)==2048);assert(!fclose(f));
            assert(snprintf(path,sizeof(path),"%s.gfs",output)>0);f=fopen(path,"wb");assert(f);assert(fwrite(&e.state,1,512,f)==512);assert(!fclose(f));
            assert(snprintf(path,sizeof(path),"%s.ognsf",output)>0);assert(!opengpu_native_fragment_write_v2(path,&d->fragment));
        }
        /* All these fail without touching a prior good encoding. */
        for(unsigned bad=0;bad<9;bad++){
            struct opengpu_native_draw_request_v2 wrong=r;
            if(bad==0)wrong.source=NULL;
            if(bad==1)wrong.state_handle=2;
            if(bad==2)wrong.width=UINT32_MAX;
            if(bad==3)wrong.sample_mode=3;
            if(bad==4)wrong.first_vertex=UINT32_MAX;
            if(bad==5)wrong.bindings[0].view.bytes=47;
            if(bad==6)wrong.vertex_scratch.bytes=needed-64;
            if(bad==7)wrong.vertex_scratch.offset=4;
            if(bad==8)wrong.attributes[0].binding=16;
            int rejected=opengpu_native_draw_encode_v2(&wrong,&e);
            if(!rejected)fprintf(stderr,"unexpected draw acceptance case=%u mode=%u\n",bad,mode);
            assert(rejected);assert(!memcmp(&e,&original,sizeof(e)));
        }
    }
    pipe_opengpu_resource_destroy(gpu,scratch);pipe_resource_reference(&input,NULL);
}
void native_graphics_v2_test(void *frontend,unsigned full,const char *output)
{
    struct pipe_screen *screen=opengpu_drm_screen_create(7,NULL);assert(screen);
    struct pipe_context *pipe=screen->context_create(screen,NULL,0);assert(pipe);
    assert(!screen->get_param(screen,PIPE_CAP_COMPUTE));
    struct opengpu_native_sample_runtime runtime;
    assert(!opengpu_native_sample_runtime_decode(cpu_profile,117,&runtime));
    /* Compilation cannot silently bootstrap a public or old context. */
    assert(!compile_v2(pipe,frontend));assert(!opengpu_native_sample_context_bootstrap(pipe,&runtime));
    assert(!opengpu_native_sample_program_create(pipe,graphics_uniform_tokens(frontend,0),graphics_uniform_tokens(frontend,1)));
    struct opengpu_native_fragment_reflection_v2 reflected;assert(!graphics_uniform_reflection_v2(frontend,&reflected));
    unsigned vn=graphics_uniform_token_words(frontend,0),fn=graphics_uniform_token_words(frontend,1);
    for(unsigned bad=0;bad<4;bad++){
        struct opengpu_native_fragment_reflection_v2 wrong=reflected;
        if(bad==2)wrong.version=1;
        if(bad==3)wrong.linked_geometry=1;
        assert(!opengpu_native_graphics_program_create_v2(pipe,graphics_uniform_tokens(frontend,0),vn-(bad==0),
            graphics_uniform_tokens(frontend,1),fn-(bad==1),&wrong));
    }
    struct opengpu_native_graphics_program_v2 *p=compile_v2(pipe,frontend);assert(p);
    struct pipe_context *other=screen->context_create(screen,NULL,0);assert(other);
    assert(!opengpu_native_sample_context_bootstrap(other,&runtime));
    assert(!opengpu_native_graphics_source_create_v2(other,p));other->destroy(other);
    unsigned allocation=24*16448+64;sparse_next=1;
    struct pipe_resource *original=buffer(screen,allocation);assert(original);
    unsigned handle=pipe_opengpu_resource_handle(opengpu_resource(original)->gpu);
    uint32_t *data=(void *)bos[handle].data;
    for(unsigned i=0;i<allocation/4;i++)data[i]=0x12345678u+i*0x10203u;
    struct pipe_resource *resources[36]={0};unsigned offsets[36]={0},sizes[36]={0};
    source_buffers(pipe,frontend,original,resources,offsets,sizes);
    struct opengpu_native_graphics_source_v2 *lease=opengpu_native_graphics_source_create_v2(pipe,p);assert(lease);
    struct opengpu_native_graphics_source_description_v2 d,saved;
    assert(!opengpu_native_graphics_source_describe_v2(lease,&d));saved=d;
    assert(d.vertex_layout.varyings==d.fragment.metadata.varying_slots);
    for(unsigned stage=0,w=0;stage<2;stage++){
        unsigned blocks=graphics_uniform_blocks(frontend,stage);if(full)assert(blocks==12);
        for(unsigned i=0;i<blocks;i++,w++){
            unsigned binding=graphics_uniform_binding(frontend,stage,i);
            assert(d.uniforms[stage].blocks[i].handle==handle&&d.uniforms[stage].blocks[i].offset==offsets[binding]&&
                d.uniforms[stage].blocks[i].bytes==16384);
            if(full)assert((stage?d.fragment.metadata.constant_bytes[i+1]:d.vertex.constant_bytes[i+1])==16384);
        }
        unsigned code=d.programs[stage].handle;
        assert(!memcmp(bos[code].data,stage?(void *)d.fragment.code:(void *)d.vertex.code,d.programs[stage].bytes));
        if(full){unsigned h=d.uniforms[stage].defaults.handle;assert(h&&h!=handle&&d.uniforms[stage].defaults.bytes==4096);
            for(unsigned i=0;i<1024;i++)assert(((uint32_t *)bos[h].data)[i]==0x76543210u-i);
        }
    }
    /* A returned metadata copy has no authority over the opaque program. */
    memset(&d.fragment.metadata,0xff,sizeof(d.fragment.metadata));
    assert(!opengpu_native_graphics_source_describe_v2(lease,&d)&&!memcmp(&saved,&d,sizeof(d)));
    /* A real positive rebind captures a distinct window of the same GEM;
     * it must neither retarget the old lease nor collapse the new view. */
    if(full){
        unsigned binding=graphics_uniform_binding(frontend,0,0);
        struct pipe_constant_buffer cb={.buffer=resources[binding],.buffer_offset=offsets[binding]+64,.buffer_size=16384};
        pipe->set_constant_buffer(pipe,PIPE_SHADER_VERTEX,1,false,&cb);
        struct opengpu_native_graphics_source_v2 *second=opengpu_native_graphics_source_create_v2(pipe,p);assert(second);
        struct opengpu_native_graphics_source_description_v2 changed;
        assert(!opengpu_native_graphics_source_describe_v2(second,&changed));
        assert(changed.uniforms[0].blocks[0].offset==offsets[binding]+64);
        assert(!opengpu_native_graphics_source_describe_v2(lease,&d)&&!memcmp(&d,&saved,sizeof(d)));
        opengpu_native_graphics_source_destroy_v2(second);
    }
    struct opengpu_native_graphics_source_description_v2 sentinel,untouched;
    memset(&sentinel,0xa5,sizeof(sentinel));untouched=sentinel;
    assert(opengpu_native_graphics_source_describe_v2(NULL,&sentinel)&&!memcmp(&sentinel,&untouched,sizeof(sentinel)));
    /* Caller program release and all rebinds are legal while a lease lives. */
    opengpu_native_graphics_program_destroy_v2(p);
    for(unsigned stage=0;stage<2;stage++)for(unsigned slot=0;slot<13;slot++)
        pipe->set_constant_buffer(pipe,stage?PIPE_SHADER_FRAGMENT:PIPE_SHADER_VERTEX,slot,false,NULL);
    for(unsigned i=0;i<36;i++)pipe_resource_reference(&resources[i],NULL);
    pipe_resource_reference(&original,NULL);
    if(full){assert(bos[handle].handle);assert(bos[d.uniforms[0].defaults.handle].handle&&bos[d.uniforms[1].defaults.handle].handle);}
    record(pipe,lease,&d,output);
    unsigned codes[2]={d.programs[0].handle,d.programs[1].handle};
    opengpu_native_graphics_source_destroy_v2(lease);
    assert(!bos[codes[0]].handle&&!bos[codes[1]].handle&&!bos[handle].handle);
    if(full)assert(!bos[d.uniforms[0].defaults.handle].handle&&!bos[d.uniforms[1].defaults.handle].handle);
    /* Exact code-allocation rollback: both first and second GEM create/map. */
    for(unsigned k=0;k<3;k++){
        if(k<2){fail_request=DRM_IOCTL_OPENGPU_CREATE_BUFFER;fail_nth=k+1;fail_seen=0;}else map_error=1;
        assert(!compile_v2(pipe,frontend));fail_request=fail_nth=fail_seen=map_error=0;
    }
    p=compile_v2(pipe,frontend);assert(p);
    if(full){
        sparse_next=1;
        source_buffers(pipe,frontend,(original=buffer(screen,allocation)),resources,offsets,sizes);
        unsigned slot=graphics_uniform_binding(frontend,0,0),refs=resources[slot]->reference.count;
        struct pipe_constant_buffer cb={.buffer=resources[slot],.buffer_offset=offsets[slot],.buffer_size=16380};
        pipe->set_constant_buffer(pipe,PIPE_SHADER_VERTEX,1,false,&cb);
        assert(!opengpu_native_graphics_source_create_v2(pipe,p));assert(resources[slot]->reference.count==refs);
        for(unsigned i=0;i<36;i++)pipe_resource_reference(&resources[i],NULL);
        pipe_resource_reference(&original,NULL);
    }
    opengpu_native_graphics_program_destroy_v2(p);pipe->destroy(pipe);screen->destroy(screen);
    for(unsigned i=1;i<next_bo;i++)assert(!bos[i].handle&&!bos[i].jobs&&!bos[i].maps);
    puts("NATIVE_GRAPHICS_V2_CPU lifecycle/source lease/canonical draw PASS; GPU=false submit=false");
}

int reference_uniform_compile(const void *,unsigned,unsigned,struct opengpu_native_uniform_layout *,struct opengpu_native_uniform_program *);
void native_graphics_v2_legacy_test(void *frontend)
{
    struct pipe_screen *screen=opengpu_drm_screen_create(7,NULL);assert(screen);
    struct pipe_context *pipe=screen->context_create(screen,NULL,0);assert(pipe);
    struct opengpu_native_sample_runtime runtime;assert(!opengpu_native_sample_runtime_decode(cpu_profile,117,&runtime));
    assert(!opengpu_native_sample_context_bootstrap(pipe,&runtime));
    struct opengpu_native_graphics_program *p=opengpu_native_sample_program_create(pipe,
        graphics_uniform_tokens(frontend,0),graphics_uniform_tokens(frontend,1));assert(p);
    struct pipe_resource *resources[36]={0};unsigned offsets[36]={0},sizes[36]={0};
    sparse_next=1;struct pipe_resource *original=buffer(screen,24*16448+64);assert(original);
    source_buffers(pipe,frontend,original,resources,offsets,sizes);
    struct opengpu_native_graphics_sources source;assert(!opengpu_native_sample_program_sources(pipe,p,&source));
    for(unsigned stage=0;stage<2;stage++){
        struct opengpu_native_uniform_layout layout;struct opengpu_native_uniform_program reference;
        assert(!reference_uniform_compile(graphics_uniform_tokens(frontend,stage),stage,128,&layout,&reference));
        assert(reference.words==(stage?301:308));
        assert(source.programs[stage].bytes==reference.words*4&&!memcmp(&layout,&source.layout[stage],sizeof(layout)));
        assert(!memcmp(bos[source.programs[stage].handle].data,reference.code,reference.words*4));
    }
    struct opengpu_native_fragment_reflection_v2 reflection;
    assert(graphics_uniform_reflection_v2(frontend,&reflection)); /* ESSL310 is not v2 reflection */
    opengpu_native_graphics_program_destroy(p);
    for(unsigned i=0;i<36;i++)pipe_resource_reference(&resources[i],NULL);
    pipe_resource_reference(&original,NULL);pipe->destroy(pipe);screen->destroy(screen);
    for(unsigned i=1;i<next_bo;i++)assert(!bos[i].handle&&!bos[i].maps&&!bos[i].jobs);
    puts("ORIGINAL_NATIVE_ABI1 exact308/301 old-byte/reference isolation PASS");
}
