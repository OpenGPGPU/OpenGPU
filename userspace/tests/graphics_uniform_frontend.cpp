/* Compiler-only limits for actual linked ESSL vertex/fragment shaders.
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
#include "tgsi/tgsi_ureg.h"
#include "graphics_uniform_frontend.h"

static const gl_shader_stage stages[] = {MESA_SHADER_VERTEX, MESA_SHADER_FRAGMENT};
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

void opengpu_frontend_compiler_limits(gl_context *ctx)
{
    ctx->Const.MaxUniformBufferBindings = 36;
    ctx->Const.MaxCombinedUniformBlocks = 24;
    ctx->Const.MaxUniformBlockSize = 16384;
    ctx->Const.NativeIntegers = true;
    pipe_screen screen = {};
    screen.get_param = param; screen.get_shader_param = shader_param;
    for (auto stage : stages) {
        auto &c = ctx->Const.Program[stage];
        c.MaxUniformBlocks = 12;
        c.MaxUniformComponents = 1024;
        c.MaxCombinedUniformComponents = 12u * 16384u / 4u + 1024u;
        ctx->Const.ShaderCompilerOptions[stage].NirOptions =
            static_cast<const nir_shader_compiler_options *>(nir_to_tgsi_get_compiler_options(
                &screen, PIPE_SHADER_IR_NIR, stage == MESA_SHADER_VERTEX ? PIPE_SHADER_VERTEX : PIPE_SHADER_FRAGMENT));
    }
}

struct graphics_program {
    gl_context ctx;
    standalone_options options;
    gl_shader_program *program;
    const tgsi_token *tokens[2];
};
static int type_size(const glsl_type *type, bool) { return glsl_count_vec4_slots(type, false, false); }

extern "C" void *graphics_uniform_compile(const char *vertex, const char *fragment, const char *prefix)
{
    auto *f = new graphics_program{};
    f->options.glsl_version = 300;
    f->options.do_link = 1; f->options.just_log = 1;
    char *filenames[] = {const_cast<char *>(vertex), const_cast<char *>(fragment)};
    f->program = standalone_compile_shader(&f->options, 2, filenames, &f->ctx);
    if (!f->program || f->program->data->LinkStatus != LINKING_SUCCESS) {
        graphics_uniform_destroy(f); return nullptr;
    }
    auto *program = f->program;
    assert(program->IsES && program->data->Version == 310);
    exec_list_make_empty(&program->EmptyUniformLocations);
    pipe_screen screen = {};
    screen.get_param = param; screen.get_shader_param = shader_param;
    for (auto stage : stages) {
        auto *shader = program->_LinkedShaders[stage]; assert(shader && shader->Program);
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
        graphics_uniform_destroy(f); return nullptr;
    }
    for (unsigned i = 0; i < 2; i++) {
        auto stage = stages[i]; auto *shader = program->_LinkedShaders[stage];
        auto *params = shader->Program->Parameters; auto *nir = shader->Program->nir;
        nir_foreach_uniform_variable(var, nir) {
            int location = -1;
            for (unsigned p = 0; p < params->NumParameters; p++)
                if (var->data.location >= 0 && params->Parameters[p].MainUniformStorageIndex == (unsigned)var->data.location) {
                    location = p; break;
                }
            assert(location >= 0 && !var->state_slots); var->data.driver_location = location;
        }
        NIR_PASS_V(nir, gl_nir_lower_buffers, program);
        NIR_PASS_V(nir, nir_lower_system_values);
        NIR_PASS_V(nir, nir_lower_io, nir_var_uniform, type_size, (nir_lower_io_options)0);
        nir->num_uniforms = (params->NumParameterValues + 3) / 4;
        if (nir->options->lower_uniforms_to_ubo)
            NIR_PASS_V(nir, nir_lower_uniforms_to_ubo, false, false);
        if (stage != MESA_SHADER_VERTEX)
            nir_assign_io_var_locations(nir, nir_var_shader_in, &nir->num_inputs, stage);
        nir_assign_io_var_locations(nir, nir_var_shader_out, &nir->num_outputs, stage);
        if (stage == MESA_SHADER_VERTEX) nir->info.next_stage = MESA_SHADER_FRAGMENT;
        nir_validate_shader(nir, "actual linked graphics uniform frontend");
        nir_shader_gather_info(nir, nir_shader_get_entrypoint(nir)); shader->Program->info = nir->info;
        f->tokens[i] = static_cast<const tgsi_token *>(nir_to_tgsi(nir, &screen));
        shader->Program->nir = nullptr; assert(f->tokens[i]);
        if (prefix) {
            char filename[4096]; int n = snprintf(filename, sizeof(filename), "%s.%s.tgsi.txt", prefix, i ? "fragment" : "vertex");
            assert(n > 0 && (size_t)n < sizeof(filename));
            FILE *out = fopen(filename, "wx"); assert(out); tgsi_dump_to_file(f->tokens[i], 0, out); assert(!fclose(out));
        }
        printf("ACTUAL_LINKED_GRAPHICS_UNIFORMS stage=%u blocks=%u defaultComponents=%u tokens=%u\n",
            i, shader->Program->sh.NumUniformBlocks, params->NumParameterValues, tgsi_num_tokens(f->tokens[i]));
    }
    return f;
}
extern "C" const void *graphics_uniform_tokens(void *p, unsigned stage)
{ assert(stage < 2); return static_cast<graphics_program *>(p)->tokens[stage]; }
extern "C" unsigned graphics_uniform_blocks(void *p, unsigned stage)
{ assert(stage < 2); return static_cast<graphics_program *>(p)->program->_LinkedShaders[stages[stage]]->Program->sh.NumUniformBlocks; }
extern "C" unsigned graphics_uniform_binding(void *p, unsigned stage, unsigned block)
{
    assert(block < graphics_uniform_blocks(p, stage));
    return static_cast<graphics_program *>(p)->program->_LinkedShaders[stages[stage]]->Program->sh.UniformBlocks[block]->Binding;
}
extern "C" unsigned graphics_uniform_components(void *p, unsigned stage)
{ assert(stage < 2); return static_cast<graphics_program *>(p)->program->_LinkedShaders[stages[stage]]->Program->Parameters->NumParameterValues; }
extern "C" void graphics_uniform_ubos(void *p, pipe_context *pipe,
    pipe_resource *const resources[36], const unsigned offsets[36], const unsigned sizes[36])
{
    auto *f = static_cast<graphics_program *>(p);
    gl_buffer_object objects[36] = {};
    struct st_context st = {}; st.ctx = &f->ctx; st.pipe = pipe;
    f->ctx._Shader = &f->ctx.Shader;
    for (unsigned i = 0; i < 36; i++) {
        objects[i].buffer = resources[i];
        auto &binding = f->ctx.UniformBufferBindings[i];
        binding.BufferObject = &objects[i]; binding.Offset = offsets[i];
        binding.Size = sizes[i]; binding.AutomaticSize = false;
    }
    for (auto stage : stages)
        f->ctx._Shader->CurrentProgram[stage] = f->program->_LinkedShaders[stage]->Program;
    /* These real state-tracker functions acquire and transfer each exact
     * source reference, using linked block order rather than global bindings. */
    st_bind_vs_ubos(&st); st_bind_fs_ubos(&st);
    for (unsigned i = 0; i < 36; i++) f->ctx.UniformBufferBindings[i].BufferObject = nullptr;
}
extern "C" void graphics_uniform_defaults(void *p, pipe_context *pipe, unsigned stage,
    const unsigned *values, unsigned components)
{
    assert(stage < 2 && components == graphics_uniform_components(p, stage));
    auto *f = static_cast<graphics_program *>(p);
    auto *params = f->program->_LinkedShaders[stages[stage]]->Program->Parameters;
    memcpy(params->ParameterValues, values, components * 4);
    pipe_constant_buffer cb = {}; cb.user_buffer = params->ParameterValues; cb.buffer_size = components * 4;
    pipe->set_constant_buffer(pipe, stage ? PIPE_SHADER_FRAGMENT : PIPE_SHADER_VERTEX, 0, false, &cb);
    memset(params->ParameterValues, 0xee, components * 4);
}
extern "C" void graphics_uniform_destroy(void *p)
{
    auto *f = static_cast<graphics_program *>(p);
    if (!f) return;
    for (auto *tokens : f->tokens) if (tokens) ureg_free_tokens(tokens);
    if (f->program) standalone_compiler_cleanup(f->program);
    delete f;
}
