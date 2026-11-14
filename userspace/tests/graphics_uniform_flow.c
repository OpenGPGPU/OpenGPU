/* Real compiler/state-tracker/lib and native-submit boundaries. OS primitives
 * are modeled; explicit active mode retains owners for the external real DUT. */
#define main unused_compute_callback_corpus
#define OPENGPU_CS_REAL_TGSI_DUMP
#define OPENGPU_CS_REAL_MESA_SURFACES
#define OPENGPU_CS_NATIVE_LAYOUT
#include "cs_callback_flow.c"
#undef main
#include "graphics_uniform_frontend.h"
#include "opengpu_native_uniform_emit.h"
#include "graphics_uniform_word_vm.h"
#include "opengpu_native_layout.h"
#include "opengpu_native_runtime.h"
#include "opengpu_native_graphics.h"
#include "opengpu_native_draw.h"
#include "tgsi/tgsi_parse.h"
#include "tgsi/tgsi_text.h"
#include "pipe/p_context.h"
#include "pipe/p_screen.h"
#include "util/u_inlines.h"
#include "native_graphics_uniform_source.h"
#ifdef OPENGPU_CS_NATIVE_KERNEL
#include "graphics_native_kernel.h"
static struct native_kernel_session *native_session;
static struct native_kernel_profile *native_probed_profile;
static const char *native_host_directory;
static int native_probed_parameter(unsigned param,uint64_t *value)
{return native_kernel_profile_query(native_probed_profile,param,value);}
static struct native_graphics_source_record *native_source_record;
static unsigned native_kernel_records;
static void *native_allocate_original(unsigned handle,uint64_t bytes)
{return native_kernel_original_allocate(native_session,handle,bytes);}
static int native_close_original(unsigned handle)
{return native_kernel_original_close(native_session,handle);}
static int native_original_references(unsigned handle)
{return native_kernel_original_refcount(native_session,handle);}
static int native_actual_transport(unsigned long request,void *argument)
{
    assert(request==DRM_IOCTL_OPENGPU_SUBMIT_NATIVE_BINNED && native_session && native_source_record);
    int ret=native_kernel_submit(native_session,argument,graphics_native_source_observer,native_source_record);
    if(ret){errno=-ret;return -1;}
    native_kernel_records++;return 0;
}
static void native_original_inputs(struct native_graphics_source_record *record,
    struct pipe_resource *const resources[36],const unsigned offsets[36],
    const struct drm_opengpu_native_uniform_stage uniforms[2])
{
    memset(record,0,sizeof(*record));
    for(unsigned stage=0;stage<2;stage++)for(unsigned source=0;source<13;source++) {
        unsigned binding=stage*24+(source?source-1:0);
        const struct drm_opengpu_compute_uniform_view *defaults=&uniforms[stage].defaults;
        unsigned handle=source?pipe_opengpu_resource_handle(opengpu_resource(resources[binding])->gpu):defaults->handle;
        record->windows[stage][source]=(struct native_graphics_source_window){.handle=handle,
            .allocation_bytes=bos[handle].size,.offset=source?offsets[binding]:defaults->offset,
            .bytes=source?16384u:4096u,.binding=source?binding:0};
    }
}
#endif
static uint32_t value(unsigned binding, unsigned word);
static void word_results(unsigned stage, const struct opengpu_native_uniform_program *native,
    const struct graphics_uniform_view views[13]);
static void word_oracle(void *program, unsigned stage, const struct opengpu_native_uniform_program *native,
    const struct pipe_opengpu_uniform_view actual[13]);

static uint64_t native_layout_word(unsigned capacity)
{
    return capacity | UINT64_C(16)<<32 | UINT64_C(16)<<37 | UINT64_C(16)<<42 |
        UINT64_C(4)<<47 | UINT64_C(6)<<50 | UINT64_C(30)<<53;
}
static void runtime_discovery(int fd)
{
    const uint32_t healthy[8]={0x474e4303,8,0x474e4a01,0x08000200,
        0xc2100800,0x08000800,0xe4908000,0x8813d902};
    struct opengpu_native_runtime runtime,saved;
    uint32_t raw[8],before[8];
    memset(&runtime,0xa5,sizeof(runtime));saved=runtime;
    memset(raw,0x53,sizeof(raw));memcpy(before,raw,sizeof(raw));
    unsigned queries=native_caps_queries;
    assert(opengpu_native_caps(fd,NULL)<0&&errno==EINVAL&&native_caps_queries==queries);
    assert(opengpu_native_runtime_query(fd,NULL)<0&&errno==EINVAL&&native_caps_queries==queries);
    uint64_t original=features;features&=~OPENGPU_DRIVER_FEATURE_NATIVE_BINNED;
    assert(opengpu_native_runtime_query(fd,&runtime)<0&&errno==ENOTSUP&&
        !memcmp(&runtime,&saved,sizeof(runtime))&&native_caps_queries==queries);
    features=original;
    for(unsigned i=0;i<8;i++)fixture_native_caps[i]=healthy[i];
    fixture_native_layout=native_layout_word(128);
    assert(!opengpu_native_runtime_query(fd,&runtime)&&runtime.shader.capacity==128&&
        !memcmp(runtime.frame.words,healthy,sizeof(healthy)));
    saved=runtime;
    for(unsigned word=0;word<8;word++) {
        fixture_native_caps[word]|=UINT64_C(1)<<32;
        assert(opengpu_native_caps(fd,raw)<0&&errno==EPROTO&&!memcmp(raw,before,sizeof(raw)));
        assert(opengpu_native_runtime_query(fd,&runtime)<0&&errno==EPROTO&&!memcmp(&runtime,&saved,sizeof(runtime)));
        fixture_native_caps[word]=healthy[word];
        for(unsigned e=0;e<2;e++) {
            native_caps_fail_word=word;native_caps_error=e?EINVAL:EIO;
            assert(opengpu_native_caps(fd,raw)<0&&errno==native_caps_error&&!memcmp(raw,before,sizeof(raw)));
            assert(opengpu_native_runtime_query(fd,&runtime)<0&&errno==native_caps_error&&
                !memcmp(&runtime,&saved,sizeof(runtime)));
        }
        native_caps_fail_word=-1;
    }
    for(unsigned word=0;word<8;word++) {
        fixture_native_caps[word]=0;
        assert(opengpu_native_runtime_query(fd,&runtime)<0&&!memcmp(&runtime,&saved,sizeof(runtime)));
        fixture_native_caps[word]=healthy[word];
    }
    /* Each overlapping slot field must agree, rather than allowing separately
     * valid GET8/GNC replies from different configurations. */
    const unsigned shifts[]={32,37,42,47};
    for(unsigned i=0;i<4;i++) {
        fixture_native_layout=native_layout_word(128)-(UINT64_C(1)<<shifts[i]);
        assert(opengpu_native_runtime_query(fd,&runtime)<0&&errno==EPROTO&&!memcmp(&runtime,&saved,sizeof(runtime)));
    }
    fixture_native_layout=native_layout_word(256);
    assert(opengpu_native_runtime_query(fd,&runtime)<0&&errno==ENOTSUP&&!memcmp(&runtime,&saved,sizeof(runtime)));
    fixture_native_layout=native_layout_word(128);
    fixture_native_caps[0]=OPENGPU_NATIVE_BINNED_HOST_PHYSICAL_MAGIC;
    assert(opengpu_native_runtime_query(fd,&runtime)<0&&errno==ENOTSUP&&!memcmp(&runtime,&saved,sizeof(runtime)));
    /* A real reduced runtime has independent frame and item capacities, and
     * payload-free GNC is legal. No profile-sized constants are inferred. */
    fixture_native_caps[0]=OPENGPU_NATIVE_BINNED_HOST_MAGIC;
    fixture_native_caps[4]=2048u|3u<<16|7u<<21|5u<<26|1u<<31;
    fixture_native_caps[6]=(healthy[6]&~OPENGPU_NATIVE_BINNED_HOST_COLORS_MASK)|2u<<21;
    fixture_native_caps[7]=2u|9u<<8;
    fixture_native_layout=17u|UINT64_C(3)<<32|UINT64_C(7)<<37|UINT64_C(5)<<42|
        UINT64_C(2)<<47|UINT64_C(6)<<50|UINT64_C(30)<<53;
    assert(!opengpu_native_runtime_query(fd,&runtime)&&runtime.shader.capacity==17&&
        runtime.shader.attributes==3&&runtime.shader.bindings==7&&runtime.shader.varyings==5&&runtime.shader.colors==2);
    for(unsigned i=0;i<8;i++)fixture_native_caps[i]=healthy[i];
    fixture_native_layout=native_layout_word(128);
    assert(!opengpu_native_runtime_query(fd,&runtime));
}
static void discovery(void)
{
    uint64_t original_features=features;
    struct opengpu_native_shader_layout layout,saved;
    memset(&layout,0xa5,sizeof(layout)); saved=layout;
    struct pipe_opengpu_screen *screen=pipe_opengpu_screen_create_fd(7);assert(screen);
    unsigned queries=native_layout_queries;
    const struct pipe_opengpu_uniform_view empty_views[2][13]={0};
    const unsigned empty_required[2][13]={0};
    struct drm_opengpu_native_uniform_stage records[2],saved_records[2];
    memset(records,0x5a,sizeof(records));memcpy(saved_records,records,sizeof(records));
    assert(pipe_opengpu_screen_native_layout(screen,&layout)<0&&errno==ENOTSUP);
    assert(pipe_opengpu_native_uniform_stages(screen,empty_views,empty_required,records)<0&&errno==ENOTSUP);
    assert(!memcmp(records,saved_records,sizeof(records)));
    assert(native_layout_queries==queries&&!memcmp(&layout,&saved,sizeof(layout)));
    pipe_opengpu_screen_destroy(screen);
    features|=OPENGPU_DRIVER_FEATURE_NATIVE_BINNED;
    screen=pipe_opengpu_screen_create_fd(7);assert(screen);
    fixture_native_layout=native_layout_word(128);
    assert(!pipe_opengpu_screen_native_layout(screen,&layout)&&layout.capacity==128&&layout.attributes==16&&
        layout.bindings==16&&layout.varyings==16&&layout.colors==4&&layout.vertex_versions==6&&layout.fragment_versions==30);
    saved=layout;
    const uint64_t malformed[]={0,native_layout_word(256),native_layout_word(0),
        native_layout_word(128)|(UINT64_C(1)<<63),native_layout_word(128)^(UINT64_C(1)<<51),
        native_layout_word(128)|(UINT64_C(1)<<58)};
    for(unsigned i=0;i<sizeof(malformed)/sizeof(malformed[0]);i++) {
        fixture_native_layout=malformed[i];
        assert(pipe_opengpu_screen_native_layout(screen,&layout)<0&&!memcmp(&layout,&saved,sizeof(layout)));
        assert(pipe_opengpu_native_uniform_stages(screen,empty_views,empty_required,records)<0&&!memcmp(records,saved_records,sizeof(records)));
    }
    for(unsigned i=0;i<2;i++) {
        native_layout_error=i?EINVAL:EIO;
        assert(pipe_opengpu_screen_native_layout(screen,&layout)<0&&errno==native_layout_error&&!memcmp(&layout,&saved,sizeof(layout)));
    }
    native_layout_error=0;fixture_native_layout=native_layout_word(128);
    assert(!pipe_opengpu_screen_native_layout(screen,&layout));
    runtime_discovery(pipe_opengpu_screen_fd(screen));
    pipe_opengpu_screen_destroy(screen);features=original_features;no_leaks();
}

/* This boundary fixture calls Mesa's real state-tracker binders. The callback
 * checks transferred references; it is not the production GNJ submission. */
struct stage_capture {
    struct pipe_context pipe;
    struct pipe_constant_buffer cb[2][13];
    uint32_t defaults[2][1024];
    unsigned calls[2];
};
static void capture_constant(struct pipe_context *pipe, enum pipe_shader_type shader,
    unsigned slot, bool owned, const struct pipe_constant_buffer *cb)
{
    struct stage_capture *capture = (void *)pipe;
    assert((shader == PIPE_SHADER_VERTEX || shader == PIPE_SHADER_FRAGMENT) && slot < 13 && cb);
    unsigned stage = shader == PIPE_SHADER_FRAGMENT;
    if (!slot) {
        assert(!owned && !cb->buffer && cb->user_buffer && !cb->buffer_offset && cb->buffer_size == 4096);
        memcpy(capture->defaults[stage], cb->user_buffer, 4096);
    } else {
        assert(owned && !cb->user_buffer);
        pipe_resource_reference(&capture->cb[stage][slot].buffer, NULL);
        capture->cb[stage][slot] = *cb;
    }
    capture->calls[stage]++;
}
static void tracker_bindings(void *program)
{
    struct stage_capture capture = {0}; capture.pipe.set_constant_buffer = capture_constant;
    uint64_t original_features=features;features|=OPENGPU_DRIVER_FEATURE_NATIVE_BINNED;
    fixture_native_layout=native_layout_word(128);
    struct pipe_screen *screen = opengpu_drm_screen_create(7,NULL); assert(screen);
    struct pipe_resource *objects[37], *resources[36];
    unsigned offsets[36], sizes[36];
    uint32_t defaults[1024];
    for (unsigned i = 0; i < 37; i++) {
        objects[i] = buffer(screen,20480); assert(objects[i]);
        uint32_t *data = pipe_opengpu_resource_map(opengpu_resource(objects[i])->gpu); assert(data);
        for (unsigned word = 0; word < 20480/4; word++) data[word] = 0xd35ea5edu;
    }
    for (unsigned i = 0; i < 36; i++) {
        resources[i] = objects[i]; offsets[i] = 256u * (1u + i % 8); sizes[i] = 16384;
    }
    graphics_uniform_ubos(program, &capture.pipe, resources, offsets, sizes);
    for (unsigned stage = 0; stage < 2; stage++) {
        assert(capture.calls[stage] == 12);
        for (unsigned slot = 1; slot < 13; slot++) {
            unsigned binding = graphics_uniform_binding(program, stage, slot - 1);
            const struct pipe_constant_buffer *cb = &capture.cb[stage][slot];
            assert(cb->buffer == resources[binding] && cb->buffer_offset == offsets[binding] && cb->buffer_size == 16384);
        }
        for (unsigned word = 0; word < 1024; word++) defaults[word] = value(40 + stage, word);
        graphics_uniform_defaults(program, &capture.pipe, stage, defaults, 1024);
        memset(defaults, 0x77, sizeof(defaults));
        for (unsigned word = 0; word < 1024; word++) assert(capture.defaults[stage][word] == value(40 + stage, word));
    }
    for (unsigned i = 0; i < 36; i++) assert(objects[i]->reference.count == (i < 12 || i >= 24 ? 2 : 1));
    struct pipe_resource *default_objects[2];
    struct pipe_opengpu_uniform_view views[2][13] = {0};
    unsigned required[2][13];
    struct opengpu_native_uniform_program native[2];
    for (unsigned stage = 0; stage < 2; stage++) {
        struct opengpu_native_uniform_layout layout;
        assert(!opengpu_native_uniform_compile(graphics_uniform_tokens(program,stage),stage,128,&layout,&native[stage]));
        assert(layout.capacity==128 && layout.stage==stage && layout.attributes==(stage?0u:1u) &&
            layout.varyings==1 && layout.colors==(stage?1u:0u));
        assert(opengpu_native_shader_validate_words(native[stage].code,native[stage].words,
            (stage?16u:15u)*128*4,128,stage,1,stage?0u:1u,1,stage?1u:0u,true,true,false));
        memcpy(required[stage],native[stage].constant_bytes,sizeof(required[stage]));
        default_objects[stage] = buffer(screen,4096); assert(default_objects[stage]);
        void *default_data = pipe_opengpu_resource_map(opengpu_resource(default_objects[stage])->gpu); assert(default_data);
        memcpy(default_data,capture.defaults[stage],4096);
        views[stage][0] = (struct pipe_opengpu_uniform_view){opengpu_resource(default_objects[stage])->gpu,0,4096};
        for (unsigned slot = 1; slot < 13; slot++) {
            const struct pipe_constant_buffer *cb = &capture.cb[stage][slot];
            views[stage][slot] = (struct pipe_opengpu_uniform_view){opengpu_resource(cb->buffer)->gpu,cb->buffer_offset,cb->buffer_size};
        }
    }
    struct pipe_opengpu_native_uniform_plan plan, saved;
    struct pipe_opengpu_screen *gpu_screen = opengpu_screen_gpu(screen);
    assert(!pipe_opengpu_native_uniform_plan(gpu_screen,views,required,&plan));
    assert(plan.count == 24 && plan.active_mask[0] == 4095 && plan.active_mask[1] == 4095);
    struct drm_opengpu_native_uniform_stage stages[2],original_stages[2];
    assert(!pipe_opengpu_native_uniform_stages(gpu_screen,views,required,stages));
    for(unsigned stage=0;stage<2;stage++) {
        assert(stages[stage].active_mask==4095&&!stages[stage].pad);
        assert(stages[stage].defaults.handle==pipe_opengpu_resource_handle(views[stage][0].resource)&&
            stages[stage].defaults.offset==views[stage][0].offset&&stages[stage].defaults.bytes==4096);
        for(unsigned slot=0;slot<12;slot++)assert(stages[stage].blocks[slot].handle==pipe_opengpu_resource_handle(views[stage][slot+1].resource)&&
            stages[stage].blocks[slot].offset==views[stage][slot+1].offset&&stages[stage].blocks[slot].bytes==16384);
    }
    memcpy(original_stages,stages,sizeof(stages));
    for (unsigned stage = 0; stage < 2; stage++) {
        for (unsigned slot = 0; slot < 12; slot++) {
            unsigned index = plan.mapping[stage][slot];
            assert(index == stage*12+slot && plan.stage_mask[index] == 1u<<stage);
            assert(plan.bindings[index].resource == views[stage][slot+1].resource);
        }
        word_oracle(program,stage,&native[stage],views[stage]);
        for (unsigned slot = 1; slot < 13; slot++) {
            const struct pipe_constant_buffer *cb = &capture.cb[stage][slot];
            const uint32_t *data = pipe_opengpu_resource_map(opengpu_resource(cb->buffer)->gpu);
            for (unsigned word = 0; word < 20480/4; word++)
                if (word < cb->buffer_offset/4 || word >= (cb->buffer_offset+cb->buffer_size)/4)
                    assert(data[word] == 0xd35ea5edu);
        }
    }
    /* All active malformed inputs fail before output publication. */
    saved = plan;
    struct pipe_opengpu_uniform_view original = views[1][12];
    const struct pipe_opengpu_uniform_view malformed[] = {
        {NULL,0,0}, {original.resource,original.offset+2,original.bytes},
        {original.resource,original.offset,original.bytes-4},
        {original.resource,UINT64_MAX-255,original.bytes},
        {(struct pipe_opengpu_resource *)(uintptr_t)1,0,16384}
    };
    for (unsigned i=0;i<sizeof(malformed)/sizeof(malformed[0]);i++) {
        views[1][12] = malformed[i];
        assert(pipe_opengpu_native_uniform_plan(gpu_screen,views,required,&plan)<0 && !memcmp(&plan,&saved,sizeof(plan)));
        assert(pipe_opengpu_native_uniform_stages(gpu_screen,views,required,stages)<0&&!memcmp(stages,original_stages,sizeof(stages)));
    }
    struct pipe_opengpu_screen *foreign_screen = pipe_opengpu_screen_create_fd(7); assert(foreign_screen);
    struct pipe_opengpu_resource *foreign = pipe_opengpu_resource_create(foreign_screen,16384); assert(foreign);
    views[1][12] = (struct pipe_opengpu_uniform_view){foreign,0,16384};
    assert(pipe_opengpu_native_uniform_plan(gpu_screen,views,required,&plan)<0 && !memcmp(&plan,&saved,sizeof(plan)));
    pipe_opengpu_resource_destroy(foreign_screen,foreign); pipe_opengpu_screen_destroy(foreign_screen);
    struct pipe_opengpu_resource *image = pipe_opengpu_resource_create_linear(gpu_screen,1,1); assert(image);
    views[1][12] = (struct pipe_opengpu_uniform_view){image,0,16384};
    assert(pipe_opengpu_native_uniform_plan(gpu_screen,views,required,&plan)<0 && !memcmp(&plan,&saved,sizeof(plan)));
    pipe_opengpu_resource_destroy(gpu_screen,image);
    views[1][12] = original; views[1][12].offset = 4096;
    assert(!pipe_opengpu_native_uniform_plan(gpu_screen,views,required,&plan)); /* exact BO endpoint */
    saved = plan; views[1][12].offset += 256;
    assert(pipe_opengpu_native_uniform_plan(gpu_screen,views,required,&plan)<0 && !memcmp(&plan,&saved,sizeof(plan)));
    views[1][12] = original;
    views[1][12].offset += 4;
    assert(!pipe_opengpu_native_uniform_plan(gpu_screen,views,required,&plan));
    views[1][12] = original; views[1][12].offset += 64;
    assert(!pipe_opengpu_native_uniform_plan(gpu_screen,views,required,&plan));
    views[1][12] = original;
    /* Exact same view can be reused across stages; different subranges keep
     * distinct slots. Defaults never consume or alias an aggregate UBO slot. */
    views[1][12] = views[0][1];
    assert(!pipe_opengpu_native_uniform_plan(gpu_screen,views,required,&plan));
    assert(plan.count == 23 && plan.mapping[1][11] == 0 && plan.stage_mask[0] == 3);
    views[1][12].offset += 256;
    assert(!pipe_opengpu_native_uniform_plan(gpu_screen,views,required,&plan) && plan.count == 24);
    required[1][12] = 0; views[1][12] = malformed[4];
    assert(!pipe_opengpu_native_uniform_plan(gpu_screen,views,required,&plan));
    assert(plan.count == 23 && plan.active_mask[1] == 2047);
    assert(!pipe_opengpu_native_uniform_stages(gpu_screen,views,required,stages));
    const struct drm_opengpu_compute_uniform_view zero={0};
    assert(stages[1].active_mask==2047&&!memcmp(&stages[1].blocks[11],&zero,sizeof(zero)));
    for (unsigned stage = 0; stage < 2; stage++) pipe_resource_reference(&default_objects[stage],NULL);
    /* Highest global binding rebind and inactive binding do not change the
     * program-local slot or accidentally acquire the unused global resource. */
    resources[35] = objects[36]; resources[12] = objects[36]; offsets[35] = 4096;
    graphics_uniform_ubos(program, &capture.pipe, resources, offsets, sizes);
    assert(objects[35]->reference.count == 1 && objects[36]->reference.count == 2);
    unsigned last_slot = 0;
    for (unsigned slot = 1; slot < 13; slot++) if (graphics_uniform_binding(program, 1, slot - 1) == 35) last_slot = slot;
    assert(last_slot && capture.cb[1][last_slot].buffer == objects[36]);
    assert(capture.cb[1][last_slot].buffer_offset == 4096 && capture.cb[1][last_slot].buffer_size == 16384);
    resources[35] = NULL;
    graphics_uniform_ubos(program, &capture.pipe, resources, offsets, sizes);
    assert(!capture.cb[1][last_slot].buffer && !capture.cb[1][last_slot].buffer_size && !capture.cb[1][last_slot].buffer_offset);
    assert(objects[36]->reference.count == 1);
    /* Mesa clamps an explicit view to its original allocation's tail. The
     * eventual GNJ admission must reject this under-sized active 16KiB view. */
    resources[35] = objects[35]; offsets[35] = 20224;
    graphics_uniform_ubos(program, &capture.pipe, resources, offsets, sizes);
    assert(capture.cb[1][last_slot].buffer_size == 256);
    for (unsigned stage = 0; stage < 2; stage++) for (unsigned slot = 1; slot < 13; slot++)
        pipe_resource_reference(&capture.cb[stage][slot].buffer, NULL);
    for (unsigned i = 0; i < 37; i++) {
        assert(objects[i]->reference.count == 1); pipe_resource_reference(&objects[i], NULL);
    }
    screen->destroy(screen);features=original_features;no_leaks();
}

static void reject(const struct tgsi_token *tokens, const struct opengpu_native_uniform_layout *layout)
{
    struct opengpu_native_uniform_program out, sentinel;
    memset(&out, 0xa5, sizeof(out)); sentinel = out;
    assert(opengpu_native_uniform_emit(tokens, layout, &out) < 0);
    assert(!memcmp(&out, &sentinel, sizeof(out)));
    struct opengpu_native_uniform_layout reflected, saved;
    memset(&reflected,0x5a,sizeof(reflected)); saved=reflected;
    assert(opengpu_native_uniform_compile(tokens,layout->stage,layout->capacity,&reflected,&out)<0);
    assert(!memcmp(&out,&sentinel,sizeof(out)) && !memcmp(&reflected,&saved,sizeof(saved)));
}
static void admission(const struct tgsi_token *tokens, const struct opengpu_native_uniform_layout *layout)
{
    unsigned count = tgsi_num_tokens(tokens), first = 0, multiply = 0, end = 0;
    struct tgsi_token *copy = calloc(count + 1000, sizeof(*copy)); assert(copy);
    for (unsigned pos = 2; pos < count;) {
        const struct tgsi_token *t = tokens + pos;
        unsigned n = t->NrTokens;
        if (t->Type == TGSI_TOKEN_TYPE_INSTRUCTION) {
            struct tgsi_instruction i; memcpy(&i, t, 4); n++;
            if (!first) first = pos;
            if (i.Opcode == TGSI_OPCODE_UMUL) multiply = pos;
            if (i.Opcode == TGSI_OPCODE_END) end = pos;
        }
        assert(n); pos += n;
    }
    assert(first && multiply && end == count - 1);
    memcpy(copy, tokens, count * 4);
    ((struct tgsi_processor *)(copy + 1))->Processor = layout->stage ? PIPE_SHADER_VERTEX : PIPE_SHADER_FRAGMENT;
    reject(copy, layout);
    memcpy(copy, tokens, count * 4);
    ((struct tgsi_instruction *)(copy + first))->Opcode = TGSI_OPCODE_ADD;
    reject(copy, layout);
    memcpy(copy, tokens, count * 4);
    ((struct tgsi_instruction *)(copy + first))->NrTokens = 1;
    reject(copy, layout);
    /* Actual first instruction reads constants; both range and modifier gates
     * must reject without publishing even a native prologue. */
    memcpy(copy, tokens, count * 4);
    struct tgsi_src_register *s = (void *)(copy + first + 2);
    assert(s->File == TGSI_FILE_CONSTANT && s->Dimension);
    s->Negate = 1; reject(copy, layout);
    memcpy(copy, tokens, count * 4);
    ((struct tgsi_dimension *)(copy + first + 3))->Index = 13;
    reject(copy, layout);
    for (unsigned pos = 2; pos < count;) {
        const struct tgsi_token *t = tokens + pos; unsigned n = t->NrTokens;
        if (t->Type == TGSI_TOKEN_TYPE_DECLARATION) {
            const struct tgsi_declaration *d = (const void *)t;
            if (d->File == TGSI_FILE_CONSTANT) {
                memcpy(copy, tokens, count * 4);
                ((struct tgsi_declaration_dimension *)(copy + pos + 2))->Index2D = 13;
                reject(copy, layout);
                if (!((const struct tgsi_declaration_dimension *)(t + 2))->Index2D) {
                    memcpy(copy, tokens, count * 4);
                    ((struct tgsi_declaration_range *)(copy + pos + 1))->Last = 256;
                    reject(copy, layout);
                }
            }
        } else if (t->Type == TGSI_TOKEN_TYPE_INSTRUCTION) {
            const struct tgsi_instruction *i = (const void *)t; n++;
            if (!layout->stage && i->NumDstRegs) {
                const struct tgsi_dst_register *d = (const void *)(t + 1);
                if (d->File == TGSI_FILE_OUTPUT && !d->Index) {
                    memcpy(copy, tokens, count * 4);
                    ((struct tgsi_dst_register *)(copy + pos + 1))->WriteMask = 1;
                    reject(copy, layout);
                }
            }
        }
        pos += n;
    }
    memcpy(copy, tokens, count * 4);
    s = (void *)(copy + first + 2); s->File = TGSI_FILE_TEMPORARY; s->Dimension = 0;
    ((struct tgsi_instruction *)(copy + first))->NrTokens--;
    /* Remove the dimension token as well to leave an otherwise valid packet. */
    memmove(copy + first + 3, copy + first + 4, (count - first - 4) * 4);
    ((struct tgsi_header *)copy)->BodySize--;
    reject(copy, layout);
    /* Repeating a real supported instruction exceeds the unchanged 512-word
     * native ceiling, while the TGSI packet stream remains well formed. */
    unsigned n = ((const struct tgsi_instruction *)(tokens + multiply))->NrTokens + 1;
    assert(n < 10);
    memcpy(copy, tokens, end * 4);
    for (unsigned k = 0; k < 100; k++) memcpy(copy + end + k * n, tokens + multiply, n * 4);
    copy[end + 100 * n] = tokens[end];
    ((struct tgsi_header *)copy)->BodySize += 100 * n;
    reject(copy, layout);
    free(copy);
}
static void alias_and_alu(void)
{
    /* Native lowering boundary regression, separate from the real linked
     * frontend above. Include read-before-write aliases and repeated swizzles. */
    const char *text = "VERT\nPROPERTY NEXT_SHADER FRAG\n"
        "DCL IN[0]\nDCL OUT[0], POSITION\nDCL OUT[1], GENERIC[0]\n"
        "DCL CONST[0][0..255]\nDCL TEMP[0]\nIMM[0] UINT32 {2147483649,4294967295,3,32}\n"
        "MOV TEMP[0], CONST[0][0]\nMOV TEMP[0], TEMP[0].wzyx\n"
        "MOV TEMP[0].xy, TEMP[0].yxzw\nXOR TEMP[0].x, TEMP[0].xxxx, IMM[0].xxxx\n"
        "UADD TEMP[0].y, TEMP[0].yyyy, IMM[0].yyyy\nUMUL TEMP[0].z, TEMP[0].zzzz, IMM[0].zzzz\n"
        "SHL TEMP[0].w, TEMP[0].wwww, IMM[0].wwww\nUSHR TEMP[0].x, TEMP[0].xxxx, IMM[0].zzzz\n"
        "AND TEMP[0].y, TEMP[0].yyyy, IMM[0].xxxx\nOR TEMP[0].z, TEMP[0].zzzz, IMM[0].xxxx\n"
        "MOV OUT[0], IN[0]\nMOV OUT[1], TEMP[0].zyxw\nEND\n";
    struct tgsi_token tokens[512]; assert(tgsi_text_translate(text, tokens, 512));
    struct opengpu_native_uniform_layout layout = {.stage=0,.capacity=128,.attributes=1,.varyings=1,.colors=0};
    struct opengpu_native_uniform_program native; assert(!opengpu_native_uniform_emit(tokens, &layout, &native));
    uint32_t constants[] = {0x81112223u,0xf1234567u,0x76543210u,0x10293847u};
    struct graphics_uniform_view views[13] = {{constants,16}};
    uint32_t payload[15*128]; for (unsigned i = 0; i < 15*128; i++) payload[i] = 0xcafebabeu;
    assert(graphics_uniform_word_vm(native.code,native.words,payload,15*128,128,3,views)==4);
    uint32_t z=(constants[1]*3u)|0x80000001u, x=(constants[2]^0x80000001u)>>3;
    uint32_t expected[]={z,(constants[3]-1u)&0x80000001u,x,constants[0]};
    for (unsigned c=0;c<4;c++) for (unsigned i=0;i<128;i++)
        assert(payload[(11+c)*128+i] == (i<3?expected[c]:0xcafebabeu));
}
static void interface_boundaries(void)
{
    const char *sources[] = {
        "VERT\nDCL IN[15]\nDCL OUT[0], POSITION\nDCL OUT[1], GENERIC[15]\n"
        "MOV OUT[0], IN[15]\nMOV OUT[1], IN[15].wzyx\nEND\n",
        "FRAG\nDCL IN[0], GENERIC[15], CONSTANT\nDCL OUT[0], COLOR[3]\n"
        "MOV OUT[0], IN[0].wzyx\nEND\n"
    };
    const struct graphics_uniform_view none[13] = {0};
    for (unsigned stage=0;stage<2;stage++) {
        struct tgsi_token tokens[512]; assert(tgsi_text_translate(sources[stage],tokens,512));
        struct opengpu_native_uniform_layout layout;
        struct opengpu_native_uniform_program native;
        assert(!opengpu_native_uniform_compile(tokens,stage,128,&layout,&native));
        assert(layout.attributes==(stage?0u:16u) && layout.varyings==16 && layout.colors==(stage?4u:0u));
        assert(stage?native.color_mask==8:native.varying_mask==(UINT64_C(15)<<60));
        unsigned arrays=stage?88:135;
        assert(opengpu_native_shader_validate_words(native.code,native.words,arrays*128*4,128,stage,1,
            layout.attributes,layout.varyings,layout.colors,true,false,false));
        uint32_t *payload=malloc(arrays*128*4),*before=malloc(arrays*128*4);assert(payload&&before);
        for(unsigned i=0;i<arrays*128;i++)payload[i]=0x77331155u;
        for(unsigned c=0;c<4;c++)for(unsigned lane=0;lane<128;lane++)
            payload[((stage?66:60)+c)*128+lane]=0x3f000000u^(c*0x10703u)^(lane*31u);
        memcpy(before,payload,arrays*128*4);
        assert(!graphics_uniform_word_vm(native.code,native.words,payload,arrays*128,128,3,none));
        for(unsigned array=0;array<arrays;array++)for(unsigned lane=0;lane<128;lane++) {
            uint32_t want=before[array*128+lane];
            if(lane<3) {
                if(!stage&&array>=67&&array<71)want=before[(60+array-67)*128+lane];
                if(!stage&&array>=131&&array<135)want=before[(63-(array-131))*128+lane];
                if(stage&&array>=82&&array<86)want=before[(69-(array-82))*128+lane];
                if(stage&&array==87)want=33;
            }
            assert(payload[array*128+lane]==want);
        }
        free(before);free(payload);
    }
    /* Zero attributes/varyings are valid runtime layouts. They do not narrow
     * the hardware's maximum interface or require dummy user bindings. */
    const char *empty[] = {
        "VERT\nDCL OUT[0], POSITION\nIMM[0] FLT32 {0,0,0,1}\nMOV OUT[0], IMM[0]\nEND\n",
        "FRAG\nDCL OUT[0], COLOR[0]\nIMM[0] UINT32 {1,2,3,4}\nMOV OUT[0], IMM[0]\nEND\n"
    };
    for(unsigned stage=0;stage<2;stage++) {
        struct tgsi_token tokens[512];assert(tgsi_text_translate(empty[stage],tokens,512));
        struct opengpu_native_uniform_layout layout;
        struct opengpu_native_uniform_program native;
        assert(!opengpu_native_uniform_compile(tokens,stage,128,&layout,&native));
        assert(!layout.attributes&&!layout.varyings&&layout.colors==stage);
        assert(opengpu_native_shader_validate_words(native.code,native.words,(stage?12u:7u)*128*4,128,
            stage,1,layout.attributes,layout.varyings,layout.colors,true,false,false));
    }
}
static uint32_t value(unsigned binding,unsigned word)
{return (0x71005013u+binding*0x10203u+word*0x9e3779b9u)^((binding+13u)<<(word%23));}
/* Source language order and actual original-GEM bytes, independent of native
 * slot remapping and instructions. Avoid endpoint patterns that cancel their
 * binding identity or make an empty zero-cleared render pass as correct. */
static uint32_t original_color(struct pipe_resource *const resources[36],const unsigned offsets[36],
    const struct drm_opengpu_native_uniform_stage records[2])
{
    uint32_t sum[2];
    for(unsigned stage=0;stage<2;stage++) {
        const struct drm_opengpu_compute_uniform_view *view=&records[stage].defaults;
        const uint32_t *defaults=(const uint32_t *)(bos[view->handle].data+view->offset);
        sum[stage]=defaults[0]^defaults[1023];
        for(unsigned block=0;block<12;block++) {
            unsigned binding=stage*24+block;
            const uint32_t *data=(const uint32_t *)((const uint8_t *)
                pipe_opengpu_resource_map(opengpu_resource(resources[binding])->gpu)+offsets[binding]);
            sum[stage]=(sum[stage]*33u)^data[0]^data[4095];
        }
    }
    return sum[0]^sum[1];
}
static void original_source_sensitivity(struct pipe_resource *resources[36],unsigned offsets[36],
    const struct drm_opengpu_native_uniform_stage records[2])
{
    uint32_t expected=original_color(resources,offsets,records);assert(expected);
    for(unsigned stage=0;stage<2;stage++)for(unsigned source=0;source<13;source++) {
        uint32_t *data;
        unsigned last=source?4095:1023;
        if(!source)data=(uint32_t *)(bos[records[stage].defaults.handle].data+records[stage].defaults.offset);
        else {
            unsigned binding=stage*24+source-1;
            data=(uint32_t *)((uint8_t *)pipe_opengpu_resource_map(opengpu_resource(resources[binding])->gpu)+offsets[binding]);
        }
        data[0]^=1;assert(original_color(resources,offsets,records)!=expected);data[0]^=1;
        data[last]^=0x40000000u;assert(original_color(resources,offsets,records)!=expected);data[last]^=0x40000000u;
    }
    struct pipe_resource *resource=resources[0];resources[0]=resources[1];resources[1]=resource;
    unsigned offset=offsets[0];offsets[0]=offsets[1];offsets[1]=offset;
    assert(original_color(resources,offsets,records)!=expected);
    resource=resources[0];resources[0]=resources[1];resources[1]=resource;
    offset=offsets[0];offsets[0]=offsets[1];offsets[1]=offset;
    assert(original_color(resources,offsets,records)==expected);
}
static void word_oracle(void *program,unsigned stage,const struct opengpu_native_uniform_program *native,
    const struct pipe_opengpu_uniform_view actual[13])
{
    struct graphics_uniform_view views[13]={{0}};
    for(unsigned slot=0;slot<13;slot++) {
        unsigned binding=slot?graphics_uniform_binding(program,stage,slot-1):40+stage;
        uint32_t *data=(uint32_t *)((uint8_t *)pipe_opengpu_resource_map(actual[slot].resource)+actual[slot].offset);
        assert(data); views[slot]=(struct graphics_uniform_view){data,(unsigned)actual[slot].bytes};
        for(unsigned word=0;word<views[slot].bytes/4;word++)
            if(slot)data[word]=value(binding,word);else assert(data[word]==value(binding,word));
    }
    word_results(stage,native,views);
}
static void word_results(unsigned stage,const struct opengpu_native_uniform_program *native,
    const struct graphics_uniform_view views[13])
{
    uint32_t sum=value(40+stage,0)^value(40+stage,1023);
    /* Source order, independent of Mesa's shuffled logical block allocation. */
    for(unsigned block=0;block<12;block++)sum=(sum*33u)^value(stage*24+block,0)^value(stage*24+block,4095);
    for(unsigned trial=0;trial<4;trial++) {
        unsigned counts[]={1,3,17,128},count=counts[trial],arrays=stage?16:15;
        uint32_t payload[16*128],original[16*128];
        for(unsigned i=0;i<16*128;i++)payload[i]=0x5a5a5a5au;
        for(unsigned i=0;i<128;i++)for(unsigned c=0;c<4;c++)
            payload[(stage?6+c:c)*128+i]=0x3f800000u^(i*31u+c*0x10203u);
        memcpy(original,payload,sizeof(payload));
        assert(graphics_uniform_word_vm(native->code,native->words,payload,arrays*128,128,count,views)==26u*((count+15)/16));
        for(unsigned array=0;array<16;array++)for(unsigned i=0;i<128;i++) {
            uint32_t want=original[array*128+i];
            if(i<count) {
                if(!stage&&array>=7&&array<11)want=original[(array-7)*128+i];
                else if(!stage&&array>=11&&array<15)want=sum;
                else if(stage&&array>=10&&array<14)want=original[(array-4)*128+i]^sum;
                else if(stage&&array==15)want=5; /* emit + color0 mask */
            }
            assert(payload[array*128+i]==want);
        }
    }
}
static const struct opengpu_native_submit_sources *native_transport_source;
static unsigned native_transport_calls, native_transport_fail;
/* Exact original code-GEM inputs consumed by the explicit program-owner
 * qualification. These are temporary inputs, removed with its private build. */
static void native_program_inputs(const struct opengpu_native_graphics_sources *sources,
    const char *name,const char *directory)
{
    for(unsigned stage=0;stage<2;stage++) {
        const struct drm_opengpu_native_program_view *view=&sources->programs[stage];
        char path[4096];assert(snprintf(path,sizeof(path),"%s/native-%s-%s.words",directory,name,stage?"fs":"vs")>0);
        FILE *stream=fopen(path,"wx");assert(stream);
        assert(fwrite(bos[view->handle].data+view->offset,1,view->bytes,stream)==view->bytes&&!fclose(stream));
    }
}
/* Transport assembly only. This hook will be replaced by the actual kernel
 * handler bridge for protected-owner execution qualification; it is not a
 * kernel admission or hardware success oracle. */
static int native_transport(unsigned long request,void *arg)
{
    const struct opengpu_native_submit_sources *source=native_transport_source;
    struct drm_opengpu_submit_native_binned *command=arg;
    assert(request==DRM_IOCTL_OPENGPU_SUBMIT_NATIVE_BINNED&&source);
    assert(command->version==1&&!command->flags&&!command->pad&&!command->fence_seqno&&
        !command->reserved[0]&&!command->reserved[1]&&command->out_syncobj==source->out_syncobj&&
        command->in_syncobj==source->in_syncobj&&command->job==(uintptr_t)source->job&&
        command->frame==(uintptr_t)&source->frame->frame&&command->draws==(uintptr_t)source->draws&&
        command->draws_bytes==2048&&command->states==(uintptr_t)source->states&&command->states_bytes==512&&
        command->plans==(uintptr_t)source->plans&&command->plan_count==1&&command->plan_stride==792&&
        command->resources==(uintptr_t)source->frame->resources&&command->resource_count==8&&command->resource_stride==32&&
        command->context_id==source->job->words[OPENGPU_NATIVE_BINNED_JOB_JOB_IDENTITY_CONTEXT_WORD]);
    command->fence_seqno=0xf00d12345678ull;native_transport_calls++;
    if(native_transport_fail){errno=EIO;return -1;}
    return 0;
}
static void native_metadata(struct pipe_context *pipe, const struct opengpu_native_graphics_sources *sources,
    struct pipe_resource *input, unsigned color_type, const char *directory,const char *prefix,
    struct native_graphics_source_record *observation)
{
    struct pipe_opengpu_screen *screen=opengpu_screen_gpu(pipe->screen);
    struct pipe_opengpu_resource *scratch=pipe_opengpu_resource_create(screen,15*128*4);assert(scratch);
    struct opengpu_native_draw_request request={.context=pipe,.sources=sources,.pass=11,.generation=13,
        .state_count=1,.token=0x12345678,.width=4,.height=4,.topology=4,.count=3,.instances=1,
        .vertex_scratch={scratch,0,15*128*4}};
    request.attributes[0]=(struct opengpu_native_draw_attribute){.enabled=1,.components=4};
    request.bindings[0]=(struct opengpu_native_draw_binding){.view={opengpu_resource(input)->gpu,4,48},.stride=16};
    request.color_types[0]=color_type;
    struct opengpu_native_draw_encoding encoded,saved;
    assert(!opengpu_native_draw_encode(&request,&encoded));saved=encoded;
    assert(encoded.plan.vertex_program.handle==sources->programs[0].handle&&
        encoded.plan.fragment_program.handle==sources->programs[1].handle&&
        !memcmp(&encoded.plan.vertex_uniforms,&sources->uniforms[0],216)&&
        !memcmp(&encoded.plan.fragment_uniforms,&sources->uniforms[1],216)&&
        encoded.plan.bindings[0].handle==pipe_opengpu_resource_handle(opengpu_resource(input)->gpu)&&
        encoded.plan.bindings[0].offset==4&&encoded.plan.bindings[0].bytes==48&&
        encoded.plan.vertex_scratch.handle==pipe_opengpu_resource_handle(scratch)&&
        encoded.plan.vertex_scratch.bytes==15*128*4);
    const struct drm_opengpu_compute_uniform_view zero={0};
    for(unsigned i=1;i<16;i++)assert(!memcmp(&encoded.plan.bindings[i],&zero,sizeof(zero)));
    assert(!memcmp(&encoded.plan.indices,&zero,sizeof(zero))&&!memcmp(&encoded.plan.indirect,&zero,sizeof(zero)));
    struct opengpu_native_binned_job job;
    assert(!opengpu_native_job_encode(opengpu_native_graphics_context_id(pipe),11,13,1,&job));
    const unsigned allocation_bytes[8]={384,65536,4096,4096,8192,8192,8192,8192};
    struct pipe_opengpu_resource *frame_objects[8];
    struct opengpu_native_frame_gem windows[8];
    for(unsigned i=0;i<8;i++) {
        frame_objects[i]=pipe_opengpu_resource_create(screen,allocation_bytes[i]);assert(frame_objects[i]);
        void *mapping=pipe_opengpu_resource_map(frame_objects[i]);assert(mapping);
        memset(mapping,i?0:0x5a,allocation_bytes[i]);
        windows[i]=(struct opengpu_native_frame_gem){.handle=pipe_opengpu_resource_handle(frame_objects[i]),.bytes=allocation_bytes[i]};
    }
    struct opengpu_native_frame_request frame_request={.caps=&sources->runtime.frame,.width=4,.height=4,
        .samples=1,.color_mask=1,.context=opengpu_native_graphics_context_id(pipe),.pass=11,.generation=13,
        .state_count=1,.scratch_stride=8192,.primitives=windows[1],.directory=windows[2]};
    frame_request.attachments[0]=(struct opengpu_native_frame_attachment){.format=26,.load_op=1,.channel_mask=15,
        .image={.gem={.handle=windows[0].handle,.offset=64,.bytes=256},.row_pitch=64,.store=1}};
    frame_request.pool[0]=windows[3];
    for(unsigned i=0;i<4;i++)frame_request.scratch[i]=windows[4+i];
    struct opengpu_native_frame_encoding frame;
    assert(!opengpu_native_frame_encode(&frame_request,&frame)&&frame.resource_count==8);
    const float triangle[12]={-1,-1,0,1,3,-1,0,1,-1,3,0,1};
    memcpy((uint8_t *)pipe_opengpu_resource_map(opengpu_resource(input)->gpu)+4,triangle,sizeof(triangle));
    const void *records[]={&job,&encoded.draw,&encoded.state,&frame.frame};
    const unsigned sizes[]={sizeof(job),sizeof(encoded.draw),sizeof(encoded.state),sizeof(frame.frame)};
    const char *names[]={"gnj","gnd","gfs","gnf"};
    for(unsigned i=0;i<4;i++) {
        char path[4096];assert(snprintf(path,sizeof(path),"%s/%s.%s",directory,prefix,names[i])>0);
        FILE *stream=fopen(path,"wx");assert(stream&&fwrite(records[i],1,sizes[i],stream)==sizes[i]&&!fclose(stream));
    }
    unsigned sync;assert(!opengpu_sync_create(pipe_opengpu_screen_fd(screen),&sync));
    struct opengpu_native_submit_sources submission={.runtime=&sources->runtime,.job=&job,.frame=&frame,
        .draws=&encoded.draw,.states=&encoded.state,.plans=&encoded.plan,.draw_count=1,.state_count=1,.out_syncobj=sync};
    uint64_t sequence=0x53a75c1d23ull;
    native_transport_source=&submission;resource_ioctl=native_transport;
    unsigned calls=native_transport_calls;
    assert(opengpu_native_submit(pipe_opengpu_screen_fd(screen),NULL,&sequence)<0&&errno==EINVAL&&
        sequence==0x53a75c1d23ull&&native_transport_calls==calls);
    if(!native_parameter_query) {
        uint64_t previous_features=features;features&=~OPENGPU_DRIVER_FEATURE_NATIVE_BINNED;
        assert(opengpu_native_graphics_submit(pipe,&submission,&sequence)<0&&errno==ENOTSUP&&
            sequence==0x53a75c1d23ull&&native_transport_calls==calls);
        features=previous_features;
    }
    submission.draw_count=2;
    assert(opengpu_native_graphics_submit(pipe,&submission,&sequence)<0&&errno==EINVAL&&
        sequence==0x53a75c1d23ull&&native_transport_calls==calls);
    submission.draw_count=UINT32_MAX;
    assert(opengpu_native_graphics_submit(pipe,&submission,&sequence)<0&&errno==EINVAL&&
        sequence==0x53a75c1d23ull&&native_transport_calls==calls);
    submission.draw_count=1;submission.state_count=UINT32_MAX;
    assert(opengpu_native_graphics_submit(pipe,&submission,&sequence)<0&&errno==EINVAL&&
        sequence==0x53a75c1d23ull&&native_transport_calls==calls);
    submission.state_count=1;
    submission.draw_count=1;native_transport_fail=1;
    assert(opengpu_native_graphics_submit(pipe,&submission,&sequence)<0&&errno==EIO&&
        sequence==0x53a75c1d23ull&&native_transport_calls==calls+1);
    native_transport_fail=0;
    assert(!opengpu_native_graphics_submit(pipe,&submission,&sequence)&&sequence==0xf00d12345678ull&&
        native_transport_calls==calls+2);
#ifdef OPENGPU_CS_NATIVE_KERNEL
    assert(observation && native_session);
    observation->width=observation->height=4;observation->color_handle=windows[0].handle;
    observation->color_offset=64;observation->color_bytes=256;observation->row_pitch=64;
    native_source_record=observation;resource_ioctl=native_actual_transport;
    sequence=0;
    assert(!opengpu_native_graphics_submit(pipe,&submission,&sequence)&&sequence);
    char path[4096];assert(snprintf(path,sizeof(path),"%s/%s.source.json",directory,prefix)>0);
    FILE *stream=fopen(path,"wx");assert(stream);
    assert(!graphics_native_source_write(native_session,stream,observation)&&!fclose(stream));
    resource_ioctl=NULL;native_source_record=NULL;
#else
    (void)observation;
#endif
    resource_ioctl=NULL;native_transport_source=NULL;
    assert(!opengpu_sync_destroy(pipe_opengpu_screen_fd(screen),sync));
    request.attributes[1].enabled=1;
    assert(opengpu_native_draw_encode(&request,&encoded)<0&&!memcmp(&encoded,&saved,sizeof(encoded)));
    request.attributes[1].enabled=0;request.vertex_scratch.bytes-=64;
    assert(opengpu_native_draw_encode(&request,&encoded)<0&&!memcmp(&encoded,&saved,sizeof(encoded)));
    request.vertex_scratch.bytes+=64;request.color_types[0]=3;
    assert(opengpu_native_draw_encode(&request,&encoded)<0&&!memcmp(&encoded,&saved,sizeof(encoded)));
    request.color_types[0]=color_type;request.bindings[0].view.offset=20480;
    assert(opengpu_native_draw_encode(&request,&encoded)<0&&!memcmp(&encoded,&saved,sizeof(encoded)));
    request.bindings[0].view.offset=4;
    request.bindings[0].stride=15;
    assert(opengpu_native_draw_encode(&request,&encoded)<0&&!memcmp(&encoded,&saved,sizeof(encoded)));
    request.bindings[0].stride=16;request.count=4;
    assert(opengpu_native_draw_encode(&request,&encoded)<0&&!memcmp(&encoded,&saved,sizeof(encoded)));
    request.count=3;request.attributes[0].format=20;request.attributes[0].components=3;
    assert(opengpu_native_draw_encode(&request,&encoded)<0&&!memcmp(&encoded,&saved,sizeof(encoded)));
    request.attributes[0].format=0;request.attributes[0].components=4;
    struct pipe_opengpu_uniform_view valid=request.bindings[0].view;
    request.bindings[0].view.resource=(void *)(uintptr_t)1;
    assert(opengpu_native_draw_encode(&request,&encoded)<0&&!memcmp(&encoded,&saved,sizeof(encoded)));
    request.bindings[0].view=valid;
    assert(!opengpu_native_draw_encode(&request,&encoded));
    for(unsigned i=0;i<8;i++)pipe_opengpu_resource_destroy(screen,frame_objects[i]);
    pipe_opengpu_resource_destroy(screen,scratch);
}
static void native_capacity_metadata(struct pipe_context *pipe, struct pipe_resource *input,const char *directory)
{
    struct tgsi_token vs[256],fs[256];
    assert(tgsi_text_translate("VERT\nDCL IN[15]\nDCL OUT[0], POSITION\nDCL OUT[1], GENERIC[15]\nMOV OUT[0], IN[15]\nMOV OUT[1], IN[15]\nEND\n",vs,256));
    assert(tgsi_text_translate("FRAG\nDCL IN[0], GENERIC[15], CONSTANT\nDCL OUT[0], COLOR[3]\nMOV OUT[0], IN[0]\nEND\n",fs,256));
    struct opengpu_native_graphics_program *program=opengpu_native_graphics_program_create(pipe,vs,fs);assert(program);
    struct opengpu_native_graphics_sources sources;
    assert(!opengpu_native_graphics_program_sources(pipe,program,&sources)&&
        sources.layout[0].attributes==16&&sources.layout[0].varyings==16&&sources.layout[1].varyings==16&&
        sources.layout[1].colors==4&&sources.varying_mask[0]==(UINT64_C(15)<<60)&&
        sources.varying_mask[1]==(UINT64_C(15)<<60)&&sources.color_mask==8);
    struct pipe_opengpu_screen *screen=opengpu_screen_gpu(pipe->screen);
    sparse_next=1;
    struct pipe_opengpu_resource *scratch=pipe_opengpu_resource_create(screen,135*128*4+128);assert(scratch);
    memset(pipe_opengpu_resource_map(scratch),0x5a,135*128*4+128);
    struct opengpu_native_draw_request request={.context=pipe,.sources=&sources,.state_count=1,
        .width=4,.height=4,.topology=4,.count=3,.instances=1,.vertex_scratch={scratch,64,135*128*4}};
    request.attributes[15]=(struct opengpu_native_draw_attribute){.enabled=1,.binding=15,.components=4};
    request.bindings[15]=(struct opengpu_native_draw_binding){.view={opengpu_resource(input)->gpu,4,48},.stride=16};
    request.color_types[3]=1;
    struct opengpu_native_draw_encoding encoded;
    assert(!opengpu_native_draw_encode(&request,&encoded));
    native_program_inputs(&sources,"full",directory);
    assert(encoded.draw.words[OPENGPU_NATIVE_BINNED_DRAW_DRAW_VERTEX_GEOMETRY_VERTEX_ATTRIBUTESLOTS_WORD]==16&&
        encoded.draw.words[OPENGPU_NATIVE_BINNED_DRAW_DRAW_VERTEX_GEOMETRY_VERTEX_VARYINGSLOTS_WORD]==16&&
        encoded.draw.words[OPENGPU_NATIVE_BINNED_DRAW_DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_ATTRIBUTES15_ENABLED_WORD]==1&&
        encoded.plan.bindings[15].handle==pipe_opengpu_resource_handle(opengpu_resource(input)->gpu)&&
        encoded.state.words[OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_COLORSLOTS_WORD]==4&&
        encoded.state.words[OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_COLORMASK_WORD]==8&&
        encoded.state.words[OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_COLORTYPES3_WORD]==1);
    const struct graphics_uniform_view uniforms[13]={0};
    for(unsigned stage=0;stage<2;stage++) {
        unsigned arrays=stage?88:135,bytes=arrays*128*4;
        uint32_t *payload=malloc(bytes),*original=malloc(bytes);assert(payload&&original);
        for(unsigned i=0;i<arrays*128;i++)payload[i]=original[i]=0x91c352a7u^i;
        const struct drm_opengpu_native_program_view *code=&sources.programs[stage];
        const uint32_t *words=(const uint32_t *)bos[code->handle].data;
        assert(opengpu_native_shader_validate_words(words,code->bytes/4,bytes,128,stage,1,stage?0:16,16,
            stage?4:0,false,true,false));
        assert(!graphics_uniform_word_vm(words,code->bytes/4,payload,arrays*128,128,17,uniforms));
        for(unsigned array=0;array<arrays;array++)for(unsigned item=0;item<128;item++) {
            uint32_t want=original[array*128+item];
            if(item<17) {
                if(!stage&&array>=67&&array<71)want=original[(array-7)*128+item];
                if(!stage&&array>=131)want=original[(array-71)*128+item];
                if(stage&&array>=82&&array<86)want=original[(array-16)*128+item];
                if(stage&&array==87)want=33;
            }
            assert(payload[array*128+item]==want);
        }
        free(payload);free(original);
    }
    pipe_opengpu_resource_destroy(screen,scratch);opengpu_native_graphics_program_destroy(program);
}
static void production_constants(void *program, const char *directory)
{
    uint64_t original_features=features;
#ifdef OPENGPU_CS_NATIVE_KERNEL
    const char *profile_path=getenv("OPENGPU_NATIVE_HOST_PROFILE");
    native_host_directory=getenv("OPENGPU_NATIVE_HOST_DIRECTORY");
    assert(!!profile_path==!!native_host_directory);
#ifndef OPENGPU_NATIVE_MMIO
    assert(!profile_path && !native_host_directory);
#endif
    if(profile_path) {
        native_probed_profile=graphics_native_profile_read(profile_path);assert(native_probed_profile);
        native_parameter_query=native_probed_parameter;
    }
#endif
    if(!native_parameter_query) {
        features|=OPENGPU_DRIVER_FEATURE_NATIVE_BINNED;
        fixture_native_layout=native_layout_word(128);
    }
    struct pipe_screen *screen=opengpu_drm_screen_create(7,NULL);assert(screen);
    struct pipe_context *pipe=screen->context_create(screen,NULL,0);assert(pipe);
#ifdef OPENGPU_CS_NATIVE_KERNEL
    native_session=native_probed_profile?
        native_kernel_session_create_profile(opengpu_native_graphics_context_id(pipe),native_probed_profile):
        native_kernel_session_create(opengpu_native_graphics_context_id(pipe));
    assert(native_session);
    native_kernel_records=0;
    native_original_allocate=native_allocate_original;native_original_close=native_close_original;
    native_original_refcount=native_original_references;
    struct native_graphics_source_record observations[2];
#endif
    struct opengpu_native_shader_layout physical;
    assert(!pipe_opengpu_screen_native_layout(opengpu_screen_gpu(screen),&physical)&&physical.capacity==128);
    struct pipe_resource *resources[36];unsigned offsets[36],sizes[36];
    for(unsigned binding=0;binding<36;binding++) {
        resources[binding]=buffer(screen,20480);assert(resources[binding]);
        offsets[binding]=4+64*(binding%16);sizes[binding]=16384;
        uint32_t *data=pipe_opengpu_resource_map(opengpu_resource(resources[binding])->gpu);assert(data);
        for(unsigned word=0;word<20480/4;word++)data[word]=0x3eadbeefu;
        for(unsigned word=0;word<4096;word++)data[offsets[binding]/4+word]=value(binding,word);
    }
    graphics_uniform_ubos(program,pipe,resources,offsets,sizes);
    uint32_t defaults[1024];unsigned required[2][13];
    struct opengpu_native_uniform_program native[2];
    for(unsigned stage=0;stage<2;stage++) {
        struct opengpu_native_uniform_layout layout;
        assert(!opengpu_native_uniform_compile(graphics_uniform_tokens(program,stage),stage,
            physical.capacity,&layout,&native[stage]));
        memcpy(required[stage],native[stage].constant_bytes,sizeof(required[stage]));
        for(unsigned word=0;word<1024;word++)defaults[word]=value(40+stage,word);
        graphics_uniform_defaults(program,pipe,stage,defaults,1024);
        memset(defaults,0x77,sizeof(defaults));
    }
    struct drm_opengpu_native_uniform_stage records[2],saved[2];
    assert(!opengpu_native_graphics_uniform_sources(pipe,required,records));
    original_source_sensitivity(resources,offsets,records);
    uint32_t original_pixel=original_color(resources,offsets,records);assert(original_pixel);
    for(unsigned nth=1;nth<=2;nth++) {
        unsigned first=next_bo;
        fail_request=DRM_IOCTL_OPENGPU_CREATE_BUFFER;fail_nth=nth;fail_seen=0;
        assert(!opengpu_native_graphics_program_create(pipe,graphics_uniform_tokens(program,0),graphics_uniform_tokens(program,1))&&errno==ENOMEM);
        fail_request=fail_nth=fail_seen=0;
        for(unsigned handle=first;handle<next_bo;handle++)assert(!bos[handle].data&&!bos[handle].handle&&!bos[handle].maps);
    }
    struct tgsi_token absent_varying[128];
    assert(tgsi_text_translate("VERT\nDCL IN[0]\nDCL OUT[0], POSITION\nMOV OUT[0], IN[0]\nEND\n",absent_varying,128));
    unsigned first=next_bo;
    assert(!opengpu_native_graphics_program_create(pipe,absent_varying,graphics_uniform_tokens(program,1))&&errno==EINVAL&&next_bo==first);
    struct tgsi_token extra_vs[256],small_fs[256];
    assert(tgsi_text_translate("VERT\nDCL IN[0]\nDCL OUT[0], POSITION\nDCL OUT[1], GENERIC[0]\nDCL OUT[2], GENERIC[1]\nMOV OUT[0], IN[0]\nMOV OUT[1], IN[0]\nMOV OUT[2], IN[0]\nEND\n",extra_vs,256));
    assert(tgsi_text_translate("FRAG\nDCL IN[0], GENERIC[0], CONSTANT\nDCL OUT[0], COLOR[0]\nMOV OUT[0], IN[0]\nEND\n",small_fs,256));
    struct opengpu_native_graphics_program *extra=opengpu_native_graphics_program_create(pipe,extra_vs,small_fs);assert(extra);
    struct opengpu_native_graphics_sources extra_sources;
    assert(!opengpu_native_graphics_program_sources(pipe,extra,&extra_sources)&&
        extra_sources.layout[0].varyings==2&&extra_sources.layout[1].varyings==2&&
        extra_sources.varying_mask[0]==255&&extra_sources.varying_mask[1]==15);
    uint32_t payload[20*128],original_payload[20*128];
    for(unsigned i=0;i<20*128;i++)payload[i]=original_payload[i]=0xc350a91du^i;
    const struct graphics_uniform_view unused_uniforms[13]={0};
    const struct drm_opengpu_native_program_view *extra_code=&extra_sources.programs[1];
    assert(!graphics_uniform_word_vm((const uint32_t *)bos[extra_code->handle].data,extra_code->bytes/4,
        payload,20*128,128,1,unused_uniforms));
    for(unsigned array=0;array<20;array++)for(unsigned item=0;item<128;item++) {
        uint32_t expected=original_payload[array*128+item];
        if(!item&&array>=14&&array<18)expected=original_payload[(array-8)*128];
        if(!item&&array==19)expected=5;
        assert(payload[array*128+item]==expected);
    }
    opengpu_native_graphics_program_destroy(extra);
    struct opengpu_native_graphics_program *compiled=opengpu_native_graphics_program_create(pipe,
        graphics_uniform_tokens(program,0),graphics_uniform_tokens(program,1));assert(compiled);
    struct opengpu_native_graphics_sources sources,original_sources;
    assert(!opengpu_native_graphics_program_sources(pipe,compiled,&sources));original_sources=sources;
    assert(opengpu_native_graphics_program_sources(NULL,compiled,&sources)<0&&errno==EINVAL&&
        !memcmp(&sources,&original_sources,sizeof(sources)));
    assert(!memcmp(sources.uniforms,records,sizeof(records))&&sources.runtime.shader.capacity==128&&
        sources.varying_mask[0]==15&&sources.varying_mask[1]==15&&sources.color_mask==1);
    for(unsigned stage=0;stage<2;stage++) {
        const struct drm_opengpu_native_program_view *view=&sources.programs[stage];
        assert(view->handle&&bos[view->handle].data&&!view->offset&&!view->entry_offset&&!view->pad&&
            view->bytes==native[stage].words*4&&!memcmp(bos[view->handle].data,native[stage].code,view->bytes));
    }
#ifdef OPENGPU_CS_NATIVE_KERNEL
    native_original_inputs(&observations[0],resources,offsets,records);
    native_metadata(pipe,&sources,resources[12],graphics_uniform_color_type(program,0),directory,"native",&observations[0]);
#else
    native_metadata(pipe,&sources,resources[12],graphics_uniform_color_type(program,0),directory,"native",NULL);
#endif
    native_program_inputs(&sources,"real",directory);
    native_capacity_metadata(pipe,resources[12],directory);
    fixture_native_caps[5]=0;
    if(native_parameter_query) {
        /* The validated DUT profile is immutable; CPU fixture variables cannot
         * replace any of its discovery words. */
        assert(!opengpu_native_graphics_program_sources(pipe,compiled,&sources)&&
            !memcmp(&sources,&original_sources,sizeof(sources)));
    } else {
        assert(opengpu_native_graphics_program_sources(pipe,compiled,&sources)<0&&
            !memcmp(&sources,&original_sources,sizeof(sources)));
        assert(!opengpu_native_graphics_program_create(pipe,graphics_uniform_tokens(program,0),graphics_uniform_tokens(program,1)));
    }
    fixture_native_caps[5]=0x08000800;
    for(unsigned binding=0;binding<36;binding++)assert(resources[binding]->reference.count==(binding<12||binding>=24?2:1));
    for(unsigned stage=0;stage<2;stage++) {
        struct graphics_uniform_view views[13];
        assert(records[stage].active_mask==4095&&!records[stage].pad);
        for(unsigned slot=0;slot<13;slot++) {
            const struct drm_opengpu_compute_uniform_view *view=slot?&records[stage].blocks[slot-1]:&records[stage].defaults;
            assert(view->handle&&view->handle<N&&bos[view->handle].data&&
                view->offset<=bos[view->handle].size&&view->bytes<=bos[view->handle].size-view->offset);
            if(slot) {
                unsigned binding=graphics_uniform_binding(program,stage,slot-1);
                assert(view->handle==pipe_opengpu_resource_handle(opengpu_resource(resources[binding])->gpu)&&
                    view->offset==offsets[binding]&&view->bytes==16384);
            } else assert(!view->offset&&view->bytes==4096);
            views[slot]=(struct graphics_uniform_view){(const uint32_t *)(bos[view->handle].data+view->offset),view->bytes};
            unsigned binding=slot?graphics_uniform_binding(program,stage,slot-1):40+stage;
            for(unsigned word=0;word<view->bytes/4;word++)assert(views[slot].words[word]==value(binding,word));
        }
        word_results(stage,&native[stage],views);
    }
    memcpy(saved,records,sizeof(records));
    unsigned last_slot=0;
    for(unsigned slot=1;slot<13;slot++)if(graphics_uniform_binding(program,1,slot-1)==35)last_slot=slot;
    assert(last_slot);
    int32_t references=resources[35]->reference.count;
    struct pipe_resource *owned=NULL;pipe_resource_reference(&owned,resources[35]);
    struct pipe_constant_buffer bad={.buffer=owned,.buffer_offset=20224,.buffer_size=16384};
    pipe->set_constant_buffer(pipe,PIPE_SHADER_FRAGMENT,last_slot,true,&bad);
    assert(resources[35]->reference.count==references);
    assert(opengpu_native_graphics_uniform_sources(pipe,required,records)<0&&!memcmp(records,saved,sizeof(records)));
    assert(opengpu_native_graphics_program_sources(pipe,compiled,&sources)<0&&
        !memcmp(&sources,&original_sources,sizeof(sources)));
    graphics_uniform_ubos(program,pipe,resources,offsets,sizes);
    assert(!opengpu_native_graphics_uniform_sources(pipe,required,records));
    /* Rebinding the highest global source preserves the true linked slot and
     * transfers the old reference without touching another stage's views. */
    struct pipe_resource *old=resources[35];resources[35]=buffer(screen,20480);assert(resources[35]);
    offsets[35]=68;
    uint32_t *new_block=(uint32_t *)((uint8_t *)pipe_opengpu_resource_map(opengpu_resource(resources[35])->gpu)+offsets[35]);
    for(unsigned word=0;word<4096;word++)new_block[word]=value(35,word);
    new_block[0]^=0x12345678u;
    graphics_uniform_ubos(program,pipe,resources,offsets,sizes);
    assert(old->reference.count==1&&resources[35]->reference.count==2);
    assert(!opengpu_native_graphics_uniform_sources(pipe,required,records));
    assert(records[1].blocks[last_slot-1].handle==pipe_opengpu_resource_handle(opengpu_resource(resources[35])->gpu)&&
        records[1].blocks[last_slot-1].offset==68);
    assert(!opengpu_native_graphics_program_sources(pipe,compiled,&sources)&&
        sources.uniforms[1].blocks[last_slot-1].handle==records[1].blocks[last_slot-1].handle&&
        sources.uniforms[1].blocks[last_slot-1].offset==68&&
        !memcmp(sources.programs,original_sources.programs,sizeof(sources.programs)));
    uint32_t rebound_pixel=original_color(resources,offsets,records);assert(rebound_pixel&&rebound_pixel!=original_pixel);
    pipe_resource_reference(&old,NULL);
    unsigned old_default=records[0].defaults.handle;
    for(unsigned word=0;word<1024;word++)defaults[word]=value(40,word);
    defaults[0]^=0xfedc1234u;
    graphics_uniform_defaults(program,pipe,0,defaults,1024);memset(defaults,0x77,sizeof(defaults));
    assert(!opengpu_native_graphics_uniform_sources(pipe,required,records));
    assert(records[0].defaults.handle!=old_default);
#ifdef OPENGPU_CS_NATIVE_KERNEL
    assert(bos[old_default].data&&native_original_references(old_default)==1);
#else
    assert(!bos[old_default].data);
#endif
    const uint32_t *new_default=(const uint32_t *)bos[records[0].defaults.handle].data;
    for(unsigned word=0;word<1024;word++)assert(new_default[word]==(value(40,word)^(word?0:0xfedc1234u)));
    uint32_t new_pixel=original_color(resources,offsets,records);
    assert(new_pixel&&new_pixel!=original_pixel&&new_pixel!=rebound_pixel);
    assert(!opengpu_native_graphics_program_sources(pipe,compiled,&sources)&&
        sources.uniforms[0].defaults.handle==records[0].defaults.handle);
#ifdef OPENGPU_CS_NATIVE_KERNEL
    native_original_inputs(&observations[1],resources,offsets,records);
    native_metadata(pipe,&sources,resources[12],graphics_uniform_color_type(program,0),directory,"native-next",&observations[1]);
    assert(native_kernel_records==2);
#endif
    unsigned code_handles[2]={sources.programs[0].handle,sources.programs[1].handle};
    opengpu_native_graphics_program_destroy(compiled);
#ifdef OPENGPU_CS_NATIVE_KERNEL
    assert(native_original_references(code_handles[0])==2 && native_original_references(code_handles[1])==2);
#else
    assert(!bos[code_handles[0]].data&&!bos[code_handles[1]].data);
#endif
    pipe->destroy(pipe);
    for(unsigned binding=0;binding<36;binding++) {
        assert(resources[binding]->reference.count==1);pipe_resource_reference(&resources[binding],NULL);
    }
    screen->destroy(screen);features=original_features;
#ifdef OPENGPU_CS_NATIVE_KERNEL
#ifdef OPENGPU_NATIVE_MMIO
    if(native_host_directory) {
        assert(!graphics_native_host_execute(native_session,native_host_directory,observations));
        assert(!native_original_references(old_default));
        assert(!native_original_references(code_handles[0]) && !native_original_references(code_handles[1]));
    } else
#endif
    {
        /* Explicit CPU-only component mode; never authorizes a host/DUT result. */
        assert(!native_kernel_run(native_session,0));assert(!native_kernel_retire(native_session,0,0));
        assert(!native_original_references(old_default));
        assert(native_original_references(code_handles[0])==1 && native_original_references(code_handles[1])==1);
        assert(!native_kernel_run(native_session,0));assert(!native_kernel_retire(native_session,0,0));
    }
    for(unsigned handle=1;handle<next_bo;handle++)if(bos[handle].native_owned)maybe_free(handle);
    assert(!native_kernel_session_destroy(native_session));native_session=NULL;
    native_original_allocate=NULL;native_original_close=NULL;native_original_refcount=NULL;
    native_parameter_query=NULL;
    native_kernel_profile_destroy(native_probed_profile);native_probed_profile=NULL;
    if(native_host_directory)puts("ACTUAL_GRAPHICS_NATIVE_OWNER_RETIRED records=2 observed=true");
    else puts("ACTUAL_GRAPHICS_NATIVE_HANDLER_PASS realMesa=true realIoctl=true preparedOwners=2 sourceObserver=true sameOriginalGEM=true GPU=false hardwareKick=modeled");
#endif
    no_leaks();
}
int main(int argc, char **argv)
{
    char vertex[4096], fragment[4096], prefix[4096];
    assert(argc == 2);
    assert(snprintf(vertex, sizeof(vertex), "%s/full.vert", argv[1]) > 0);
    assert(snprintf(fragment, sizeof(fragment), "%s/full.frag", argv[1]) > 0);
    assert(snprintf(prefix, sizeof(prefix), "%s/full", argv[1]) > 0);
    void *program = graphics_uniform_compile(vertex, fragment, prefix); assert(program);
    assert(graphics_uniform_color_type(program,0)==1);
    discovery();
    tracker_bindings(program);
    production_constants(program,argv[1]);
    alias_and_alu();
    interface_boundaries();
    for (unsigned stage = 0; stage < 2; stage++) {
        struct opengpu_native_uniform_layout layout;
        struct opengpu_native_uniform_program native;
        assert(!opengpu_native_uniform_compile(graphics_uniform_tokens(program, stage),stage,128,&layout,&native));
        admission(graphics_uniform_tokens(program, stage), &layout);
        assert(native.words && native.words <= 512);
        for (unsigned c=0;c<13;c++)assert(native.constant_bytes[c] == (c ? 16384u : 4096u));
        unsigned uniform_loads=0;
        for(unsigned i=0;i<native.words;i++)uniform_loads += (native.code[i]&127u)==11u && (native.code[i]>>25)==7u;
        assert(uniform_loads==26 && (stage ? native.color_mask==1 : native.varying_mask==15));
        printf("NATIVE_GRAPHICS_UNIFORM_PROGRAM stage=%u words=%u uniformLoads=%u\n",stage,native.words,uniform_loads);
        assert(graphics_uniform_blocks(program, stage) == 12);
        assert(graphics_uniform_components(program, stage) == 1024);
        unsigned seen = 0;
        for (unsigned block = 0; block < 12; block++) {
            unsigned binding = graphics_uniform_binding(program, stage, block);
            assert(binding >= (stage ? 24u : 0u) && binding < (stage ? 36u : 12u));
            seen |= 1u << (binding - (stage ? 24u : 0u));
        }
        assert(seen == 4095);
    }
    graphics_uniform_destroy(program);
    puts("ACTUAL_LINKED_GRAPHICS_COMPILER_PASS stages=2 blocks=24 globalBindings=36 defaultsPerStage=1024 actualStageTracker=true exactNativeWords=true tailCounts=1,3,17,128 strictReject=true noGPU=true");
    return 0;
}
