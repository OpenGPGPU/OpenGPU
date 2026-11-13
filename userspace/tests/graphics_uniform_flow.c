/* Real linked compiler/state-tracker/lib boundaries with modeled OS/GEM.
 * Production GNJ submission and native host execution are separate. */
#define main unused_compute_callback_corpus
#define OPENGPU_CS_REAL_TGSI_DUMP
#define OPENGPU_CS_REAL_MESA_SURFACES
#include "cs_callback_flow.c"
#undef main
#include "graphics_uniform_frontend.h"
#include "opengpu_native_uniform_emit.h"
#include "graphics_uniform_word_vm.h"
#include "tgsi/tgsi_parse.h"
#include "tgsi/tgsi_text.h"
#include "pipe/p_context.h"
#include "pipe/p_screen.h"
#include "util/u_inlines.h"
static uint32_t value(unsigned binding, unsigned word);
static void word_oracle(void *program, unsigned stage, const struct opengpu_native_uniform_program *native,
    const struct pipe_opengpu_uniform_view actual[13]);

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
        const struct opengpu_native_uniform_layout layout = {.stage=stage,.capacity=128,.attributes=1,.varyings=1,.colors=1};
        assert(!opengpu_native_uniform_emit(graphics_uniform_tokens(program,stage),&layout,&native[stage]));
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
    screen->destroy(screen); no_leaks();
}

static void reject(const struct tgsi_token *tokens, const struct opengpu_native_uniform_layout *layout)
{
    struct opengpu_native_uniform_program out, sentinel;
    memset(&out, 0xa5, sizeof(out)); sentinel = out;
    assert(opengpu_native_uniform_emit(tokens, layout, &out) < 0);
    assert(!memcmp(&out, &sentinel, sizeof(out)));
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
    struct opengpu_native_uniform_layout layout = {.stage=0,.capacity=128,.attributes=1,.varyings=1,.colors=1};
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
static uint32_t value(unsigned binding,unsigned word)
{return 0x71005013u^(binding*0x10203u)^(word*0x10001u);}
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
int main(int argc, char **argv)
{
    char vertex[4096], fragment[4096], prefix[4096];
    assert(argc == 2);
    assert(snprintf(vertex, sizeof(vertex), "%s/full.vert", argv[1]) > 0);
    assert(snprintf(fragment, sizeof(fragment), "%s/full.frag", argv[1]) > 0);
    assert(snprintf(prefix, sizeof(prefix), "%s/full", argv[1]) > 0);
    void *program = graphics_uniform_compile(vertex, fragment, prefix); assert(program);
    tracker_bindings(program);
    alias_and_alu();
    for (unsigned stage = 0; stage < 2; stage++) {
        struct opengpu_native_uniform_layout layout = {.stage=stage,.capacity=128,.attributes=1,.varyings=1,.colors=1};
        struct opengpu_native_uniform_program native;
        assert(!opengpu_native_uniform_emit(graphics_uniform_tokens(program, stage), &layout, &native));
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
