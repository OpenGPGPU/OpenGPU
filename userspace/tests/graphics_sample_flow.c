/* Private fixed112 ordinary original-Mesa bridge. Explicit CPU capture only.
 * Reuse actual callback/linked-source helpers without running the legacy entry. */
#define OPENGPU_GRAPHICS_UNIFORM_ENTRY unused_original_graphics_flow_main
#include "graphics_uniform_flow.c"
#undef OPENGPU_GRAPHICS_UNIFORM_ENTRY
#include "opengpu_native_sample_program.h"
#include "graphics_sample_kernel.h"
static struct opengpu_native_sample_runtime sample_cache;
/* Separate compiler observation, written before private-plan construction.
 * Uses the unchanged actual Mesa linked-reflection accessors. */
static int sample_compiler_reflection(void *linked,const char *path)
{
    FILE *f=fopen(path,"wx");if(!f)return -1;
    fputs("{\"schema\":\"opengpu-linked-graphics-reflection-v1\",\"stages\":[",f);
    for(unsigned stage=0;stage<2;stage++){
        fprintf(f,"%s{\"stage\":%u,\"defaultComponents\":%u,\"bindings\":[",stage?",":"",stage,graphics_uniform_components(linked,stage));
        for(unsigned block=0;block<graphics_uniform_blocks(linked,stage);block++)fprintf(f,"%s%u",block?",":"",graphics_uniform_binding(linked,stage,block));
        fputs("]}",f);
    }
    fputs("]}\n",f);int e=ferror(f)?-1:0;
    if(fclose(f))e=-1;
    if(e)remove(path);
    return e;
}

static void sample_metadata(struct pipe_context *pipe,const struct opengpu_native_graphics_sources *sources,
    struct pipe_resource *input,unsigned color_type,const char *directory,const char *prefix,
    struct native_graphics_source_record *record,const unsigned reflection[2][12])
{
    struct pipe_opengpu_screen *screen=opengpu_screen_gpu(pipe->screen);unsigned context;
    struct opengpu_native_sample_runtime actual;assert(!opengpu_native_sample_context_runtime(pipe,&actual,&context));
    assert(!memcmp(actual.observed,sample_cache.observed,sizeof(actual.observed)));
    struct pipe_opengpu_resource *scratch=pipe_opengpu_resource_create(screen,15*128*4);assert(scratch);
    struct opengpu_native_draw_request request={.context=pipe,.sources=sources,.pass=11,.generation=13,
        .state_count=1,.token=0x12345678,.width=4,.height=4,.topology=4,.count=3,.instances=1,
        .vertex_scratch={scratch,0,15*128*4}};
    request.attributes[0]=(struct opengpu_native_draw_attribute){.enabled=1,.components=4};
    request.bindings[0]=(struct opengpu_native_draw_binding){.view={opengpu_resource(input)->gpu,4,48},.stride=16};
    request.color_types[0]=color_type;
    struct opengpu_native_draw_encoding encoded,before;assert(!opengpu_native_sample_draw_encode(&request,&encoded));before=encoded;
    assert(!memcmp(&encoded.plan.vertex_uniforms,&sources->uniforms[0],sizeof(sources->uniforms[0])) &&
        !memcmp(&encoded.plan.fragment_uniforms,&sources->uniforms[1],sizeof(sources->uniforms[1])));
    struct drm_opengpu_native_layered_draw_plan plan;assert(!opengpu_native_sample_ordinary_plan(&actual,&encoded.plan,&plan));
    const unsigned allocation_bytes[8]={384,65536,4096,4096,8192,8192,8192,8192};
    struct pipe_opengpu_resource *objects[9];struct opengpu_native_frame_gem windows[8];
    for(unsigned i=0;i<9;i++){
        unsigned bytes=i==8?16384:allocation_bytes[i];objects[i]=pipe_opengpu_resource_create(screen,bytes);assert(objects[i]);
        void *map=pipe_opengpu_resource_map(objects[i]);assert(map);memset(map,i?0:0x5a,bytes);
        if(i<8)windows[i]=(struct opengpu_native_frame_gem){.handle=pipe_opengpu_resource_handle(objects[i]),.bytes=bytes};
    }
    struct opengpu_native_sample_frame_request frame_request={.runtime=&actual,.source_pitch={256},.frame={
        .caps=&actual.limits.frame,.width=4,.height=4,.samples=1,.color_mask=1,.context=context,.pass=11,.generation=13,
        .state_count=1,.scratch_stride=8192,.primitives=windows[1],.directory=windows[2]}};
    frame_request.frame.attachments[0]=(struct opengpu_native_frame_attachment){.format=26,.load_op=1,.channel_mask=15,
        .image={.gem={.handle=windows[0].handle,.offset=64,.bytes=256},.row_pitch=64,.store=1}};
    frame_request.frame.pool[0]=windows[3];for(unsigned i=0;i<4;i++)frame_request.frame.scratch[i]=windows[4+i];
    struct opengpu_native_frame_encoding frame;assert(!opengpu_native_sample_frame_encode(&frame_request,&frame));
    struct opengpu_native_sample_job_encoding job;
    const struct drm_opengpu_compute_uniform_view completion={.handle=pipe_opengpu_resource_handle(objects[8]),.bytes=16384};
    assert(!opengpu_native_sample_job_encode(&actual,context,11,13,1,&completion,&job));
    const float triangle[12]={-1,-1,0,1,3,-1,0,1,-1,3,0,1};
    memcpy((uint8_t *)pipe_opengpu_resource_map(opengpu_resource(input)->gpu)+4,triangle,sizeof(triangle));
    const void *records[]={&job.job,&encoded.draw,&encoded.state,&frame.frame};
    const unsigned sizes[]={512,2048,512,2048};const char *names[]={"gnj","gnd","gfs","gnf"};
    for(unsigned i=0;i<4;i++){char path[4096];assert(snprintf(path,sizeof(path),"%s/%s.%s",directory,prefix,names[i])>0);
        FILE *f=fopen(path,"wx");assert(f && fwrite(records[i],1,sizes[i],f)==sizes[i] && !fclose(f));}
    unsigned sync;assert(!opengpu_sync_create(pipe_opengpu_screen_fd(screen),&sync));
    struct opengpu_native_sample_submit_sources submission={.runtime=&actual,.job=&job,.frame=&frame,
        .draws=&encoded.draw,.states=&encoded.state,.plans=&plan,.draw_count=1,.state_count=1,.out_syncobj=sync};
    uint64_t sequence=0x53535353;native_source_record=record;resource_ioctl=native_actual_transport;
    struct opengpu_native_sample_job_encoding bad=job;bad.job.words[127]=1;submission.job=&bad;
    assert(opengpu_native_sample_graphics_submit(pipe,&submission,&sequence)<0 && sequence==0x53535353);
    submission.job=&job;
    record->width=record->height=4;record->color_handle=windows[0].handle;record->color_offset=64;record->color_bytes=256;record->row_pitch=64;
    assert(!opengpu_native_sample_graphics_submit(pipe,&submission,&sequence) && sequence);
    assert(!graphics_sample_private_code(native_session,native_kernel_records-1,sources->programs));
    struct graphics_sample_source_plan proof={.draw=plan.draw,.completion=job.completion,.resource_count=frame.resource_count};
    memcpy(proof.resources,frame.resources,frame.resource_count*sizeof(frame.resources[0]));
    memcpy(proof.bindings,reflection,sizeof(proof.bindings));
    for(unsigned stage=0;stage<2;stage++){
        const struct opengpu_native_uniform_layout *l=&sources->layout[stage];
        unsigned layout[5]={l->stage,l->capacity,l->attributes,l->varyings,l->colors};
        memcpy(proof.layout[stage],layout,sizeof(layout));
    }
    char proof_path[4096];assert(snprintf(proof_path,sizeof(proof_path),"%s/record-%u.source-plan.json",directory,native_kernel_records-1)>0);
    assert(!graphics_sample_source_plan_write(native_session,native_kernel_records-1,proof_path,&proof));
    resource_ioctl=NULL;native_source_record=NULL;
    assert(!opengpu_sync_destroy(pipe_opengpu_screen_fd(screen),sync));
    request.attributes[1].enabled=1;
    assert(opengpu_native_sample_draw_encode(&request,&encoded)<0 && !memcmp(&encoded,&before,sizeof(before)));
    for(unsigned i=0;i<9;i++)pipe_opengpu_resource_destroy(screen,objects[i]);
    pipe_opengpu_resource_destroy(screen,scratch);
}
int main(int argc,char **argv)
{
    assert(argc==3); /* sources, explicit OGNP0002 profile; no live/controller CLI */
    assert(!getenv("OPENGPU_NATIVE_HOST_DIRECTORY") && !getenv("OPENGPU_NATIVE_HOST_PROFILE"));
    assert(!graphics_sample_profile_read(argv[2],&sample_cache));
    char vertex[4096],fragment[4096],prefix[4096];
    assert(snprintf(vertex,sizeof(vertex),"%s/full.vert",argv[1])>0);
    assert(snprintf(fragment,sizeof(fragment),"%s/full.frag",argv[1])>0);
    assert(snprintf(prefix,sizeof(prefix),"%s/full",argv[1])>0);
    void *linked=graphics_uniform_compile(vertex,fragment,prefix);assert(linked && graphics_uniform_color_type(linked,0)==1);
    unsigned reflection[2][12];
    for(unsigned stage=0;stage<2;stage++)for(unsigned slot=0;slot<12;slot++)reflection[stage][slot]=graphics_uniform_binding(linked,stage,slot);
    char reflection_path[4096];assert(snprintf(reflection_path,sizeof(reflection_path),"%s/compiler-reflection.json",argv[1])>0);
    assert(!sample_compiler_reflection(linked,reflection_path));
    uint64_t oldfeatures=features;features&=~OPENGPU_DRIVER_FEATURE_NATIVE_BINNED;
    struct pipe_screen *screen=opengpu_drm_screen_create(7,NULL);assert(screen);
    struct pipe_context *pipe=screen->context_create(screen,NULL,0);assert(pipe);
    assert(!opengpu_native_graphics_context_id(pipe));
    struct opengpu_native_runtime legacy;assert(opengpu_native_runtime_query(pipe_opengpu_screen_fd(opengpu_screen_gpu(screen)),&legacy)<0 && errno==ENOTSUP);
    assert(!opengpu_native_sample_context_bootstrap(pipe,&sample_cache));
    assert(opengpu_native_sample_context_bootstrap(pipe,&sample_cache)<0 && errno==EBUSY);
    struct opengpu_native_sample_runtime runtime;unsigned context;
    assert(!opengpu_native_sample_context_runtime(pipe,&runtime,&context) && context);
    assert(!opengpu_native_graphics_context_id(pipe)); /* private bootstrap never authorizes old route */
    native_session=graphics_sample_session_create(context,&runtime);assert(native_session);
    native_original_allocate=native_allocate_original;native_original_close=native_close_original;native_original_refcount=native_original_references;
    struct pipe_resource *resources[36];unsigned offsets[36],sizes[36];
    for(unsigned binding=0;binding<36;binding++){
        resources[binding]=buffer(screen,20480);assert(resources[binding]);offsets[binding]=4+64*(binding%16);sizes[binding]=16384;
        uint32_t *data=pipe_opengpu_resource_map(opengpu_resource(resources[binding])->gpu);assert(data);
        for(unsigned word=0;word<5120;word++)data[word]=0x3eadbeef;
        for(unsigned word=0;word<4096;word++)data[offsets[binding]/4+word]=value(binding,word);
    }
    graphics_uniform_ubos(linked,pipe,resources,offsets,sizes);
    uint32_t defaults[1024];
    for(unsigned stage=0;stage<2;stage++){
        for(unsigned word=0;word<1024;word++)defaults[word]=value(40+stage,word);
        graphics_uniform_defaults(linked,pipe,stage,defaults,1024);memset(defaults,0x77,sizeof(defaults));
    }
    assert(!opengpu_native_graphics_program_create(pipe,graphics_uniform_tokens(linked,0),graphics_uniform_tokens(linked,1)));
    struct opengpu_native_graphics_program *program=opengpu_native_sample_program_create(pipe,
        graphics_uniform_tokens(linked,0),graphics_uniform_tokens(linked,1));assert(program);
    struct opengpu_native_graphics_sources sources,first;
    struct native_graphics_source_record observations[2];
    for(unsigned index=0;index<2;index++){
        if(index){
            /* Rebind a true original UBO and default, retaining first-owner snapshots. */
            struct pipe_resource *old=resources[35];resources[35]=buffer(screen,20480);assert(resources[35]);offsets[35]=68;
            uint32_t *data=(uint32_t *)((uint8_t *)pipe_opengpu_resource_map(opengpu_resource(resources[35])->gpu)+68);
            for(unsigned word=0;word<4096;word++)data[word]=value(35,word);
            data[0]^=0x12345678;
            graphics_uniform_ubos(linked,pipe,resources,offsets,sizes);pipe_resource_reference(&old,NULL);
            for(unsigned word=0;word<1024;word++)defaults[word]=value(40,word);
            defaults[0]^=0xfedc1234;
            graphics_uniform_defaults(linked,pipe,0,defaults,1024);memset(defaults,0x77,sizeof(defaults));
        }
        assert(!opengpu_native_sample_program_sources(pipe,program,&sources));
        assert(sources.programs[0].bytes==308*4 && sources.programs[1].bytes==301*4);
        if(!index)first=sources;else assert(!memcmp(first.programs,sources.programs,sizeof(first.programs)));
        for(unsigned stage=0;stage<2;stage++){
            assert(sources.uniforms[stage].active_mask==4095 && sources.uniforms[stage].defaults.bytes==4096);
            for(unsigned slot=0;slot<12;slot++)assert(sources.uniforms[stage].blocks[slot].bytes==16384);
        }
        native_original_inputs(&observations[index],resources,offsets,sources.uniforms);
        sample_metadata(pipe,&sources,resources[12],graphics_uniform_color_type(linked,0),argv[1],index?"native-next":"native",&observations[index],reflection);
        if(!index)native_program_inputs(&sources,"real",argv[1]);
    }
    assert(native_kernel_records==2);
    unsigned codes[2]={sources.programs[0].handle,sources.programs[1].handle};
    opengpu_native_graphics_program_destroy(program);pipe->destroy(pipe);
    for(unsigned binding=0;binding<36;binding++)pipe_resource_reference(&resources[binding],NULL);
    screen->destroy(screen);features=oldfeatures;
    assert(native_original_references(codes[0])==2 && native_original_references(codes[1])==2);
    assert(!graphics_sample_cpu_capture(native_session,argv[1],observations));
    for(unsigned h=1;h<next_bo;h++)if(bos[h].native_owned)maybe_free(h);
    assert(!native_kernel_session_destroy(native_session));native_session=NULL;
    native_original_allocate=NULL;native_original_close=NULL;native_original_refcount=NULL;
    graphics_uniform_destroy(linked);no_leaks();
    puts("ACTUAL_SAMPLE_GRAPHICS_CPU_CAPTURE_PASS records=2 originalMesaABI1=true originalGUR=true completionBytes=16384 GPU=false hardwareKick=modeled");
    return 0;
}
