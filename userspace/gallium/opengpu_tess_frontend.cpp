/* Compiler-only linked ESSL320 VS/TCS/TES/(GS)/FS frontend.
 * Device capability reporting is deliberately separate from this fixture. */
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "main/mtypes.h"
#include "program/prog_parameter.h"
#include "state_tracker/st_context.h"
#include "state_tracker/st_atom.h"
#include "compiler/glsl/standalone.h"
#include "compiler/glsl/glsl_to_nir.h"
#include "compiler/glsl/gl_nir.h"
#include "compiler/glsl/gl_nir_linker.h"
#include "compiler/nir/nir.h"
extern "C" {
#include "nir/nir_to_tgsi.h"
}
#include "pipe/p_screen.h"
#include "tgsi/tgsi_dump.h"
#include "tgsi/tgsi_parse.h"
#include "tgsi/tgsi_from_mesa.h"
#include "tgsi/tgsi_ureg.h"
#include "opengpu_tess_frontend.h"
#include "opengpu_native_tess_uniform_emit.h"

static const gl_shader_stage stages[] = {MESA_SHADER_VERTEX, MESA_SHADER_TESS_CTRL,
    MESA_SHADER_TESS_EVAL, MESA_SHADER_GEOMETRY, MESA_SHADER_FRAGMENT};
static const unsigned gpu_stages[] = {0,4,5,3,1};
static const char *names[] = {"vertex", "control", "evaluation", "geometry", "fragment"};
static int stage_index(unsigned gpu) {
    switch(gpu) { case 0:return 0; case 4:return 1; case 5:return 2; case 3:return 3; case 1:return 4; default:return -1; }
}
static int param(pipe_screen *, pipe_cap) { return 0; }
static int shader_param(pipe_screen *, pipe_shader_type, pipe_shader_cap cap)
{
    switch (cap) {
    case PIPE_SHADER_CAP_INTEGERS: return 1;
    case PIPE_SHADER_CAP_MAX_TEMPS: return 5;
    case PIPE_SHADER_CAP_MAX_CONST_BUFFERS: return 13;
    case PIPE_SHADER_CAP_MAX_CONST_BUFFER0_SIZE: return 4096;
    default: return 0;
    }
}

void opengpu_tess_frontend_limits(gl_context *ctx)
{
    ctx->Const.MaxUniformBufferBindings = 72;
    ctx->Const.MaxCombinedUniformBlocks = 60;
    ctx->Const.MaxUniformBlockSize = 16384;
    ctx->Const.NativeIntegers = true;
    ctx->Const.MaxGeometryOutputVertices = 256;
    ctx->Const.MaxGeometryTotalOutputComponents = 1024;
    ctx->Const.MaxGeometryShaderInvocations = 32;
    ctx->Const.MaxTessGenLevel = 64; ctx->Const.MaxPatchVertices = 32;
    ctx->Const.MaxTessPatchComponents = 120; ctx->Const.MaxTessControlTotalOutputComponents = 2048;
    pipe_screen screen = {};
    screen.get_param = param; screen.get_shader_param = shader_param;
    for (auto stage : stages) {
        auto &c = ctx->Const.Program[stage];
        c.MaxUniformBlocks = 12;
        c.MaxInputComponents = 64; c.MaxOutputComponents = 64;
        c.MaxUniformComponents = 1024;
        c.MaxCombinedUniformComponents = 12u * 16384u / 4u + 1024u;
        ctx->Const.ShaderCompilerOptions[stage].NirOptions =
            static_cast<const nir_shader_compiler_options *>(nir_to_tgsi_get_compiler_options(
                &screen, PIPE_SHADER_IR_NIR, (pipe_shader_type)stage));
    }
}

struct graphics_program {
    gl_context ctx;
    standalone_options options;
    gl_shader_program *program;
    const tgsi_token *tokens[5];
    unsigned color_types[4];
    opengpu_native_tess_input_map evaluation_inputs,control_outputs;
};
static int type_size(const glsl_type *type, bool) { return glsl_count_vec4_slots(type, false, false); }

extern "C" void *opengpu_tess_frontend_compile(const char *const files[5], const char *prefix)
{
    if(!files || !files[0] || !files[1] || !files[2] || !files[4])return nullptr;
    auto *f = new graphics_program{};
    f->options.glsl_version = 300;
    f->options.do_link = 1; f->options.just_log = 1;
    char *filenames[5]; unsigned count=0;
    for(unsigned i=0;i<5;i++)if(files[i])filenames[count++]=const_cast<char *>(files[i]);
    f->program = standalone_compile_shader(&f->options, count, filenames, &f->ctx);
    if (!f->program || f->program->data->LinkStatus != LINKING_SUCCESS) {
        if(f->program && f->program->data)fprintf(stderr,"ESSL tess link: %s\n",f->program->data->InfoLog);
        fflush(stdout);
        opengpu_tess_frontend_destroy(f); return nullptr;
    }
    auto *program = f->program;
    assert(program->IsES && program->data->Version == 320);
    exec_list_make_empty(&program->EmptyUniformLocations);
    pipe_screen screen = {};
    screen.get_param = param; screen.get_shader_param = shader_param;
    for (auto stage : stages) {
        auto *shader = program->_LinkedShaders[stage]; if(!shader)continue; assert(shader->Program);
        shader->Program->Parameters = _mesa_new_parameter_list();
        const auto *options = f->ctx.Const.ShaderCompilerOptions[stage].NirOptions;
        nir_shader *nir = glsl_to_nir(&f->ctx.Const, program, stage, options); assert(nir);
        shader->Program->nir = nir;
        NIR_PASS_V(nir, nir_lower_variable_initializers, nir_var_function_temp);
        NIR_PASS_V(nir, nir_lower_returns);
        NIR_PASS_V(nir, nir_split_var_copies);
        NIR_PASS_V(nir, nir_lower_var_copies);
        NIR_PASS_V(nir, nir_lower_vars_to_ssa);
        gl_nir_opts(nir);
    }
    if (!gl_nir_link_glsl(&f->ctx.Const, &f->ctx.Extensions, f->ctx.API, program)) {
        opengpu_tess_frontend_destroy(f); return nullptr;
    }
    for (unsigned i = 0; i < 5; i++) {
        auto stage = stages[i]; auto *shader = program->_LinkedShaders[stage]; if(!shader)continue;
        auto *params = shader->Program->Parameters; auto *nir = shader->Program->nir;
        nir_foreach_uniform_variable(var, nir) {
            int location = -1;
            for (unsigned p = 0; p < params->NumParameters; p++)
                if (var->data.location >= 0 && params->Parameters[p].MainUniformStorageIndex == (unsigned)var->data.location) {
                    location = p; break;
                }
            assert(location >= 0 && !var->state_slots); var->data.driver_location = location;
        }
        /* Mesa NIR requires compact scalar arrays (tessellation levels) to
         * have constant offsets before lower_io. Mode0 lowers compact arrays
         * only; per-vertex invocation indexing remains real TGSI dimensions. */
        NIR_PASS_V(nir, nir_lower_indirect_derefs, (nir_variable_mode)0, UINT32_MAX);
        NIR_PASS_V(nir, gl_nir_lower_buffers, program);
        NIR_PASS_V(nir, nir_lower_system_values);
        NIR_PASS_V(nir, nir_lower_io, nir_var_uniform, type_size, (nir_lower_io_options)0);
        nir->num_uniforms = (params->NumParameterValues + 3) / 4;
        if (nir->options->lower_uniforms_to_ubo)
            NIR_PASS_V(nir, nir_lower_uniforms_to_ubo, false, false);
        if (stage != MESA_SHADER_VERTEX)
            nir_assign_io_var_locations(nir, nir_var_shader_in, &nir->num_inputs, stage);
        nir_assign_io_var_locations(nir, nir_var_shader_out, &nir->num_outputs, stage);
        if(stage!=MESA_SHADER_FRAGMENT) {
            unsigned next=i+1; while(next<5 && !program->_LinkedShaders[stages[next]])next++;
            nir->info.next_stage=stages[next];
        }
        if(stage==MESA_SHADER_TESS_EVAL||stage==MESA_SHADER_TESS_CTRL){
            auto &map=stage==MESA_SHADER_TESS_EVAL?f->evaluation_inputs:f->control_outputs;
            auto mode=stage==MESA_SHADER_TESS_EVAL?nir_var_shader_in:nir_var_shader_out;
            map.version=1;
            nir_foreach_variable_with_modes(var,nir,mode){
                /* PrimitiveID is a language system value, never an owned patch
                 * component or a CPU-selected patch word. */
                if(var->data.location==VARYING_SLOT_PRIMITIVE_ID)continue;
                if(map.count==64 || var->data.location_frac){map.version=0;break;}
                auto *type=var->type;bool per_vertex=nir_is_arrayed_io(var,stage);
                if(per_vertex)type=glsl_get_array_element(type);
                auto &span=map.spans[map.count++];
                span.first=var->data.driver_location;span.slots=glsl_count_attribute_slots(type,false);
                span.per_vertex=per_vertex;span.compact=var->data.compact;
                unsigned location=var->data.location;
                if(location>=VARYING_SLOT_VAR0 && location<VARYING_SLOT_PATCH0){
                    span.semantic=TGSI_SEMANTIC_GENERIC;span.index=location-VARYING_SLOT_VAR0;
                }else tgsi_get_gl_varying_semantic((gl_varying_slot)location,true,&span.semantic,&span.index);
                unsigned components=span.compact?glsl_get_length(type):glsl_get_vector_elements(glsl_without_array_or_matrix(type));
                if(!components||components>4){map.version=0;break;}
                span.mask=(1u<<components)-1;
            }
        }
        nir_validate_shader(nir, "actual linked graphics uniform frontend");
        nir_shader_gather_info(nir, nir_shader_get_entrypoint(nir)); shader->Program->info = nir->info;
        if (stage == MESA_SHADER_FRAGMENT) nir_foreach_shader_out_variable(var, nir) {
            assert(var->data.driver_location < 4 && glsl_get_components(var->type) <= 4);
            auto type = glsl_get_base_type(var->type);
            assert(type == GLSL_TYPE_FLOAT || type == GLSL_TYPE_UINT || type == GLSL_TYPE_INT);
            f->color_types[var->data.driver_location] = type == GLSL_TYPE_UINT ? 1 : type == GLSL_TYPE_INT ? 2 : 0;
        }
        f->tokens[i] = static_cast<const tgsi_token *>(nir_to_tgsi(nir, &screen));
        shader->Program->nir = nullptr; assert(f->tokens[i]);
        if (prefix) {
            char filename[4096]; int n = snprintf(filename, sizeof(filename), "%s.%s.tgsi.txt", prefix, names[i]);
            assert(n > 0 && (size_t)n < sizeof(filename));
            FILE *out = fopen(filename, "wx"); assert(out); tgsi_dump_to_file(f->tokens[i], 0, out); assert(!fclose(out));
        }
        printf("ACTUAL_LINKED_TESSELLATION_UNIFORMS stage=%u blocks=%u defaultComponents=%u tokens=%u\n",
            gpu_stages[i], shader->Program->sh.NumUniformBlocks, params->NumParameterValues, tgsi_num_tokens(f->tokens[i]));
    }
    return f;
}
static gl_program *linked(void *p,unsigned gpu)
{
    int i=stage_index(gpu); if(!p||i<0)return nullptr;
    auto *sh=static_cast<graphics_program *>(p)->program->_LinkedShaders[stages[i]];
    return sh?sh->Program:nullptr;
}
extern "C" const void *opengpu_tess_frontend_tokens(void *p,unsigned gpu)
{ int i=stage_index(gpu); return linked(p,gpu)?static_cast<graphics_program *>(p)->tokens[i]:nullptr; }
extern "C" unsigned opengpu_tess_frontend_blocks(void *p,unsigned gpu)
{ auto *sh=linked(p,gpu);return sh?sh->sh.NumUniformBlocks:0; }
extern "C" unsigned opengpu_tess_frontend_binding(void *p,unsigned gpu,unsigned block)
{ auto *sh=linked(p,gpu);return sh&&block<sh->sh.NumUniformBlocks?sh->sh.UniformBlocks[block]->Binding:~0u; }
extern "C" unsigned opengpu_tess_frontend_components(void *p,unsigned gpu)
{ auto *sh=linked(p,gpu);return sh?sh->Parameters->NumParameterValues:0; }
extern "C" int opengpu_tess_frontend_input_map(void *p,opengpu_native_tess_input_map *out)
{if(!p||!out||static_cast<graphics_program *>(p)->evaluation_inputs.version!=1)return -1;*out=static_cast<graphics_program *>(p)->evaluation_inputs;return 0;}
extern "C" int opengpu_tess_frontend_control_output_map(void *p,opengpu_native_tess_input_map *out)
{if(!p||!out||static_cast<graphics_program *>(p)->control_outputs.version!=1)return -1;*out=static_cast<graphics_program *>(p)->control_outputs;return 0;}
extern "C" int opengpu_tess_frontend_ubos(void *p,pipe_context *pipe,
    pipe_resource *const resources[72],const unsigned offsets[72],const unsigned sizes[72])
{
    if(!p||!pipe||!pipe->set_constant_buffer||!resources||!offsets||!sizes)return -1;
    auto *f=static_cast<graphics_program *>(p); gl_buffer_object objects[72]={};
    struct st_context st={};st.ctx=&f->ctx;st.pipe=pipe;f->ctx._Shader=&f->ctx.Shader;
    for(unsigned i=0;i<72;i++) {
        objects[i].buffer=resources[i]; auto &b=f->ctx.UniformBufferBindings[i];
        b.BufferObject=&objects[i];b.Offset=offsets[i];b.Size=sizes[i];b.AutomaticSize=false;
    }
    for(auto stage:stages)if(f->program->_LinkedShaders[stage])
        f->ctx._Shader->CurrentProgram[stage]=f->program->_LinkedShaders[stage]->Program;
    st_bind_vs_ubos(&st);st_bind_tcs_ubos(&st);st_bind_tes_ubos(&st);
    if(f->program->_LinkedShaders[MESA_SHADER_GEOMETRY])st_bind_gs_ubos(&st);
    st_bind_fs_ubos(&st);
    for(unsigned i=0;i<72;i++)f->ctx.UniformBufferBindings[i].BufferObject=nullptr;
    return 0;
}
extern "C" int opengpu_tess_frontend_defaults(void *p,pipe_context *pipe,unsigned gpu,
    const unsigned *values,unsigned components)
{
    auto *sh=linked(p,gpu);
    if(!sh||!pipe||!pipe->set_constant_buffer||!values||components!=sh->Parameters->NumParameterValues||components>1024)return -1;
    memcpy(sh->Parameters->ParameterValues,values,components*4);
    pipe_constant_buffer cb={};cb.user_buffer=sh->Parameters->ParameterValues;cb.buffer_size=components*4;
    pipe->set_constant_buffer(pipe,(pipe_shader_type)stages[stage_index(gpu)],0,false,&cb);
    memset(sh->Parameters->ParameterValues,0xee,components*4);
    return 0;
}
extern "C" void opengpu_tess_frontend_destroy(void *p)
{
    auto *f=static_cast<graphics_program *>(p);if(!f)return;
    for(auto *t:f->tokens)if(t)ureg_free_tokens(t);
    if(f->program)standalone_compiler_cleanup(f->program);
    delete f;
}
