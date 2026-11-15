/* SPDX-License-Identifier: MIT */
/* Actual userspace/winsys and Gallium sources; only OS/GEM primitives modeled.
 * This test never launches a shader, supplies CU results or claims GPU proof. */
#define main unused_cs_callback_main
#define OPENGPU_CS_REAL_TGSI_DUMP
#define OPENGPU_CS_REAL_MESA_SURFACES
#include "cs_callback_flow.c"
#undef main
#include "opengpu_native_tess_constants.h"
#include "opengpu_native_tess_context.h"
#include "opengpu_native_tess_program.h"
#include "opengpu_tess_frontend.h"
#include "tgsi/tgsi_parse.h"
void tessellation_resource_test(void *frontend)
{
    struct pipe_screen *screen=opengpu_drm_screen_create(7,NULL);assert(screen);
    struct pipe_context *pipe=screen->context_create(screen,NULL,0);assert(pipe);
    assert(!pipe->create_tcs_state&&!pipe->create_tes_state);
    assert(!screen->get_shader_param(screen,PIPE_SHADER_TESS_CTRL,PIPE_SHADER_CAP_MAX_CONST_BUFFERS));
    struct opengpu_native_tess_context *context=opengpu_native_tess_context_create(pipe,128);assert(context);
    struct opengpu_native_tess_constants *cache=opengpu_native_tess_context_constants(context);
    struct pipe_context *sink=opengpu_native_tess_context_pipe(context);
    sink->set_patch_vertices(sink,2);
    float outer[4]={2,3,4,5},inner[2]={6,7};unsigned vertices=0,levels[6]={0};
    sink->set_tess_state(sink,outer,inner);memset(outer,0,sizeof(outer));
    assert(!opengpu_native_tess_context_state(context,&vertices,levels)&&vertices==2&&levels[0]==0x40000000&&levels[5]==0x40e00000);
    struct pipe_resource *resources[72]={0};unsigned offsets[72]={0},sizes[72]={0};
    unsigned blocks[2]={opengpu_tess_frontend_blocks(frontend,4),opengpu_tess_frontend_blocks(frontend,5)};
    assert((blocks[0]==1&&blocks[1]==1)||(blocks[0]==12&&blocks[1]==12));
    unsigned total=blocks[0]+blocks[1],allocation=total*16448+64;
    sparse_next=1; /* existing exact-size OS backing, not the old64KiB mock */
    struct pipe_resource *original=buffer(screen,allocation);assert(original);
    unsigned first_binding=opengpu_tess_frontend_binding(frontend,4,0);
    for(unsigned s=0,window=0;s<2;s++)for(unsigned i=0;i<blocks[s];i++,window++){
        unsigned binding=opengpu_tess_frontend_binding(frontend,s+4,i);
        assert(binding<72&&!resources[binding]);
        pipe_resource_reference(&resources[binding],original);
        offsets[binding]=4+window*16448;sizes[binding]=16384;
    }
    unsigned handle=pipe_opengpu_resource_handle(opengpu_resource(original)->gpu);
    uint32_t *data=(void *)bos[handle].data;
    for(unsigned i=0;i<allocation/4;i++)data[i]=0x12345678u+i*0x10203u;
    pipe_resource_reference(&original,NULL);
    assert(!opengpu_tess_frontend_ubos(frontend,sink,resources,offsets,sizes));
    uint32_t defaults[1024];for(unsigned i=0;i<1024;i++)defaults[i]=0x76543210u-i;
    for(unsigned s=4;s<=5;s++)assert(!opengpu_tess_frontend_defaults(frontend,sink,s,defaults,1024));
    memset(defaults,0xee,sizeof(defaults)); /* caller memory may immediately change */
    struct pipe_shader_state shader={.type=PIPE_SHADER_IR_TGSI};
    shader.tokens=opengpu_tess_frontend_tokens(frontend,4);void *control=sink->create_tcs_state(sink,&shader);assert(control);
    shader.tokens=opengpu_tess_frontend_tokens(frontend,5);void *evaluation=sink->create_tes_state(sink,&shader);assert(evaluation);
    assert(!opengpu_native_tess_context_program(context)); /* stages must be bound */
    sink->bind_tcs_state(sink,control);sink->bind_tes_state(sink,evaluation);
    sink->delete_tcs_state(sink,control);sink->delete_tes_state(sink,evaluation); /* bound refs survive deletion */
    struct opengpu_native_tess_program_pair *program=opengpu_native_tess_context_program(context);assert(program);
    struct opengpu_native_tess_program_sources source,before;
    assert(!opengpu_native_tess_program_sources(program,cache,&source));before=source;
    assert(source.uniforms[0].active_mask==((1u<<blocks[0])-1)&&source.uniforms[1].active_mask==((1u<<blocks[1])-1));
    for(unsigned s=0;s<2;s++){
        for(unsigned i=0;i<blocks[s];i++){
            unsigned binding=opengpu_tess_frontend_binding(frontend,s+4,i);
            assert(source.uniforms[s].blocks[i].handle==handle);
            assert(source.uniforms[s].blocks[i].offset==offsets[binding]&&source.uniforms[s].blocks[i].bytes==16384);
        }
        unsigned d=source.uniforms[s].defaults.handle;assert(d&&d!=handle&&source.uniforms[s].defaults.bytes==4096);
        for(unsigned i=0;i<1024;i++)assert(((uint32_t *)bos[d].data)[i]==0x76543210u-i);
        unsigned code=source.code[s].handle;
        assert(!memcmp(bos[code].data,source.shaders[s].code,source.code[s].bytes));
    }
    assert(source.uniforms[0].defaults.handle!=source.uniforms[1].defaults.handle);
    /* View rebinding keeps the old caller resource reference alive until its
     * exact stage is rebound. Same GEM/different view must never collapse. */
    struct pipe_constant_buffer cb={.buffer=resources[first_binding],.buffer_offset=68,.buffer_size=16384};
    assert(!opengpu_native_tess_constant_set(cache,4,1,0,&cb));
    assert(!opengpu_native_tess_program_sources(program,cache,&source));
    assert(source.uniforms[0].blocks[0].offset==68&&source.uniforms[1].blocks[0].offset==offsets[opengpu_tess_frontend_binding(frontend,5,0)]);
    for(unsigned i=0;i<72;i++)pipe_resource_reference(&resources[i],NULL);
    assert(bos[handle].handle);assert(!opengpu_native_tess_program_sources(program,cache,&source));
    /* Each invalid callback poisons only its own private adapter. Neither
     * another stage nor an already captured program/view may be changed. */
    for(unsigned kind=0;kind<7;kind++){
        struct opengpu_native_tess_context *bad=opengpu_native_tess_context_create(pipe,128);assert(bad);
        struct pipe_context *bp=opengpu_native_tess_context_pipe(bad);
        struct opengpu_native_tess_constants *bc=opengpu_native_tess_context_constants(bad);
        struct drm_opengpu_native_uniform_stage sentinels[2],saved[2];
        unsigned needed[2][13]={{0}};
        memset(sentinels,0xa5,sizeof(sentinels));memcpy(saved,sentinels,sizeof(saved));
        struct pipe_constant_buffer wrong={.user_buffer=defaults,.buffer_size=4096};
        if(kind==0)assert(opengpu_native_tess_constant_set(bc,1,0,0,&wrong));
        if(kind==1)assert(opengpu_native_tess_constant_set(bc,4,13,0,&wrong));
        if(kind==2)assert(opengpu_native_tess_constant_set(bc,4,1,0,&wrong)); /* user UBO */
        if(kind==3){wrong.buffer_size=4100;assert(opengpu_native_tess_constant_set(bc,4,0,0,&wrong));}
        if(kind==4)bp->set_patch_vertices(bp,33);
        if(kind==5)bp->bind_tcs_state(bp,(void *)(uintptr_t)1); /* foreign handle */
        if(kind==6){
            shader.tokens=opengpu_tess_frontend_tokens(frontend,5);
            void *ts=bp->create_tes_state(bp,&shader);assert(ts);
            bp->bind_tcs_state(bp,ts);bp->delete_tes_state(bp,ts);
        }
        if(kind<4){assert(opengpu_native_tess_constant_sources(bc,needed,sentinels));assert(!memcmp(sentinels,saved,sizeof(saved)));}
        else {unsigned v=0xa5a5a5a5,lv[6];memset(lv,0xa5,sizeof(lv));assert(opengpu_native_tess_context_state(bad,&v,lv));assert(v==0xa5a5a5a5&&lv[0]==0xa5a5a5a5);}
        assert(!opengpu_native_tess_context_program(bad));
        opengpu_native_tess_context_destroy(bad);
    }
    before=source;cb.buffer=NULL;cb.user_buffer=defaults;cb.buffer_size=4100;
    assert(opengpu_native_tess_constant_set(cache,5,0,0,&cb));
    assert(opengpu_native_tess_program_sources(program,cache,&source));assert(!memcmp(&before,&source,sizeof(source)));
    sink->bind_tcs_state(sink,NULL);sink->bind_tes_state(sink,NULL);
    opengpu_native_tess_context_destroy(context);opengpu_native_tess_program_destroy(program);
    pipe->destroy(pipe);screen->destroy(screen);
    for(unsigned i=1;i<next_bo;i++)assert(!bos[i].handle&&!bos[i].jobs&&!bos[i].maps);
    puts("Actual Mesa TCS/TES callbacks, original GEM views/private code/defaults CPU PASS; GPU not executed");
}

/* Explicit private language compiler path; old public callbacks stay absent.
 * The original GEM views and code/default copies remain real winsys objects. */
void tessellation_language_resource_test(void *frontend,const struct opengpu_native_tess_layout layout[2],
    const struct opengpu_native_tess_input_map maps[2])
{
    struct pipe_screen *screen=opengpu_drm_screen_create(7,NULL);assert(screen);
    struct pipe_context *pipe=screen->context_create(screen,NULL,0);assert(pipe);
    assert(!pipe->create_tcs_state&&!pipe->create_tes_state);
    struct opengpu_native_tess_constants *cache=opengpu_native_tess_constants_create(pipe);assert(cache);
    struct pipe_context *sink=opengpu_native_tess_constants_pipe(cache);
    struct pipe_resource *resources[72]={0};unsigned offsets[72]={0},sizes[72]={0};
    unsigned blocks[2]={opengpu_tess_frontend_blocks(frontend,4),opengpu_tess_frontend_blocks(frontend,5)};
    assert((blocks[0]==1&&blocks[1]==1)||(blocks[0]==12&&blocks[1]==12));
    unsigned allocation=(blocks[0]+blocks[1])*16448+64;sparse_next=1;
    struct pipe_resource *original=buffer(screen,allocation);assert(original);
    unsigned handle=pipe_opengpu_resource_handle(opengpu_resource(original)->gpu);
    for(unsigned s=0,w=0;s<2;s++)for(unsigned i=0;i<blocks[s];i++,w++){
        unsigned binding=opengpu_tess_frontend_binding(frontend,s+4,i);assert(binding<72&&!resources[binding]);
        pipe_resource_reference(resources+binding,original);offsets[binding]=4+w*16448;sizes[binding]=16384;
    }
    pipe_resource_reference(&original,NULL);
    assert(!opengpu_tess_frontend_ubos(frontend,sink,resources,offsets,sizes));
    unsigned defaults[1024];for(unsigned i=0;i<1024;i++)defaults[i]=0x19283746u+i;
    for(unsigned stage=4;stage<=5;stage++)assert(!opengpu_tess_frontend_defaults(frontend,sink,stage,defaults,1024));
    memset(defaults,0xee,sizeof(defaults));
    const void *tokens[2]={opengpu_tess_frontend_tokens(frontend,4),opengpu_tess_frontend_tokens(frontend,5)};
    unsigned words[2]={tgsi_num_tokens(tokens[0]),tgsi_num_tokens(tokens[1])};
    assert(!opengpu_native_tess_program_create(pipe,tokens,words,layout));
    struct opengpu_native_tess_program_pair *pair=opengpu_native_tess_language_program_create(pipe,tokens,words,layout,maps);assert(pair);
    struct opengpu_native_tess_program_sources source,saved;
    assert(!opengpu_native_tess_program_sources(pair,cache,&source));saved=source;
    for(unsigned s=0;s<2;s++){
        assert(source.shaders[s].layout.version==3&&source.shaders[s].layout.owned_tessellation_info==1);
        assert(source.uniforms[s].active_mask==((1u<<blocks[s])-1));
        assert(source.code[s].bytes==source.shaders[s].words*4&&source.code[s].offset==0);
        assert(!memcmp(bos[source.code[s].handle].data,source.shaders[s].code,source.code[s].bytes));
        for(unsigned i=0;i<blocks[s];i++){
            unsigned b=opengpu_tess_frontend_binding(frontend,s+4,i);
            assert(source.uniforms[s].blocks[i].handle==handle&&source.uniforms[s].blocks[i].offset==offsets[b]&&source.uniforms[s].blocks[i].bytes==16384);
        }
        unsigned d=source.uniforms[s].defaults.handle;assert(d&&d!=handle&&source.uniforms[s].defaults.bytes==4096);
        for(unsigned i=0;i<1024;i++)assert(((unsigned *)bos[d].data)[i]==0x19283746u+i);
    }
    for(unsigned i=0;i<72;i++)pipe_resource_reference(resources+i,NULL);
    assert(bos[handle].handle);assert(!opengpu_native_tess_program_sources(pair,cache,&source));assert(!memcmp(&saved,&source,sizeof(source)));
    struct opengpu_native_tess_layout bad[2]={layout[0],layout[1]};bad[1].tes_patch_layout.input_vertices++;
    assert(!opengpu_native_tess_language_program_create(pipe,tokens,words,bad,maps));
    opengpu_native_tess_program_destroy(pair);opengpu_native_tess_constants_destroy(cache);
    pipe->destroy(pipe);screen->destroy(screen);
    for(unsigned i=1;i<next_bo;i++)assert(!bos[i].handle&&!bos[i].jobs&&!bos[i].maps);
    puts("Language v3 original code/default/UBO GEM source copies and reference retirement CPU PASS");
}
