/* Real Mesa standalone source compiler and real GLSL/NIR/TGSI passes.
 * This is a compiler-only context; it does not advertise a GLES device. */
#include <cassert>
#include <cstdio>
#include <cstdlib>

#include "main/mtypes.h"
#include "program/prog_parameter.h"
#include "state_tracker/st_context.h"
#include "state_tracker/st_atom.h"
#include "cs_glsl_frontend.h"
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

static int param(pipe_screen *, pipe_cap) { return 0; }
static int shader_param(pipe_screen *, pipe_shader_type, pipe_shader_cap cap)
{
    switch (cap) {
    case PIPE_SHADER_CAP_INTEGERS: return 1;
    case PIPE_SHADER_CAP_MAX_TEMPS: return 5;
    case PIPE_SHADER_CAP_MAX_CONST_BUFFERS: return 13;
    case PIPE_SHADER_CAP_MAX_CONST_BUFFER0_SIZE: return 4096;
    case PIPE_SHADER_CAP_MAX_SHADER_BUFFERS: return 4;
    default: return 0;
    }
}
static int type_size(const glsl_type *type, bool bindless)
{ return glsl_count_vec4_slots(type, false, bindless); }


void opengpu_frontend_compiler_limits(gl_context *ctx)
{
    /* Compiler-only profile, not a change to the device/API capability flags. */
    ctx->Const.MaxShaderStorageBufferBindings = 4;
    ctx->Const.MaxShaderStorageBlockSize = 128u * 1024u * 1024u;
    ctx->Const.MaxUniformBufferBindings = 36;
    ctx->Const.MaxCombinedUniformBlocks = 12;
    ctx->Const.MaxCombinedShaderStorageBlocks = 4;
    ctx->Const.MaxUniformBlockSize = 16384;
    ctx->Const.MaxComputeWorkGroupInvocations = 128;
    ctx->Const.MaxComputeSharedMemorySize = 16384;
    for (unsigned i = 0; i < 3; i++) ctx->Const.MaxComputeWorkGroupSize[i] = 128;
    ctx->Const.NativeIntegers = true;
    auto &c = ctx->Const.Program[MESA_SHADER_COMPUTE];
    c.MaxShaderStorageBlocks = 4;
    c.MaxUniformBlocks = 12;
    c.MaxUniformComponents = 1024;
    c.MaxCombinedUniformComponents = 12u * 16384u / 4u + 1024u;
    pipe_screen screen = {};
    screen.get_param = param; screen.get_shader_param = shader_param;
    ctx->Const.ShaderCompilerOptions[MESA_SHADER_COMPUTE].NirOptions =
        static_cast<const nir_shader_compiler_options *>(
            nir_to_tgsi_get_compiler_options(&screen, PIPE_SHADER_IR_NIR, PIPE_SHADER_COMPUTE));
}

struct frontend_program {
    gl_context ctx;
    standalone_options options;
    gl_shader_program *program;
    const tgsi_token *tokens;
};

extern "C" void *cs_frontend_compile(const char *path, const char *dump_prefix)
{
    auto *f = new frontend_program{};
    auto &ctx = f->ctx;
    f->options.glsl_version = 300; /* ESSL selector; the source is real #310. */
    f->options.do_link = 1;
    f->options.just_log = 1;
    char *filename = const_cast<char *>(path);
    f->program = standalone_compile_shader(&f->options, 1, &filename, &ctx);
    gl_shader_program *prog = f->program;
    if (!prog || prog->data->LinkStatus != LINKING_SUCCESS) {
        if (prog) standalone_compiler_cleanup(prog);
        delete f;
        return nullptr;
    }
    assert(prog->IsES && prog->data->Version == 310);
    exec_list_make_empty(&prog->EmptyUniformLocations);
    gl_linked_shader *shader = prog->_LinkedShaders[MESA_SHADER_COMPUTE];
    assert(shader && shader->Program);
    pipe_screen screen = {};
    screen.get_param = param; screen.get_shader_param = shader_param;
    const auto *nir_options = static_cast<const nir_shader_compiler_options *>(
        nir_to_tgsi_get_compiler_options(&screen, PIPE_SHADER_IR_NIR, PIPE_SHADER_COMPUTE));
    shader->Program->Parameters = _mesa_new_parameter_list();
    nir_shader *nir = glsl_to_nir(&ctx.Const, prog, MESA_SHADER_COMPUTE, nir_options);
    assert(nir); shader->Program->nir = nir;
    NIR_PASS_V(nir, nir_lower_variable_initializers, nir_var_function_temp);
    NIR_PASS_V(nir, nir_lower_returns);
    NIR_PASS_V(nir, nir_split_var_copies);
    NIR_PASS_V(nir, nir_lower_var_copies);
    NIR_PASS_V(nir, nir_lower_vars_to_ssa);
    gl_nir_opts(nir);
    if (!gl_nir_link_glsl(&ctx.Const, &ctx.Extensions, ctx.API, prog)) {
        fprintf(stderr, "NIR link: %s\n", prog->data->InfoLog);
        cs_frontend_destroy(f); return nullptr;
    }
    /* Match the unpacked state tracker mapping by actual parameter metadata.
     * Complex built-in/state/opaque uniforms are outside this uint fixture. */
    auto *params = shader->Program->Parameters;
    nir_foreach_uniform_variable(var, nir) {
        int location = -1;
        for (unsigned i = 0; i < params->NumParameters; i++)
            if (var->data.location >= 0 &&
                params->Parameters[i].MainUniformStorageIndex == (unsigned)var->data.location) {
                location = i; break;
            }
        assert(location >= 0 && !var->state_slots);
        var->data.driver_location = location;
    }
    NIR_PASS_V(nir, gl_nir_lower_buffers, prog);
    NIR_PASS_V(nir, nir_lower_system_values);
    NIR_PASS_V(nir, nir_lower_compute_system_values, nullptr);
    NIR_PASS_V(nir, nir_lower_io, nir_var_uniform, type_size, (nir_lower_io_options)0);
    nir->num_uniforms = (params->NumParameterValues + 3) / 4;
    if (nir->options->lower_uniforms_to_ubo)
        NIR_PASS_V(nir, nir_lower_uniforms_to_ubo, false, false);
    nir_validate_shader(nir, "actual GLSL frontend pre-TGSI");
    nir_shader_gather_info(nir, nir_shader_get_entrypoint(nir));
    shader->Program->info = nir->info;
    if (dump_prefix) {
        char filename[4096];
        int n = snprintf(filename, sizeof(filename), "%s.nir", dump_prefix);
        assert(n > 0 && (size_t)n < sizeof(filename));
        FILE *out = fopen(filename, "wx"); assert(out);
        nir_print_shader(nir, out); assert(!fclose(out));
        n = snprintf(filename, sizeof(filename), "%s.reflection", dump_prefix);
        assert(n > 0 && (size_t)n < sizeof(filename));
        out = fopen(filename, "wx"); assert(out);
        fprintf(out, "ESSL=%u\ndefault.components=%u\nUBOs=%u\n",
            prog->data->Version, params->NumParameterValues, shader->Program->sh.NumUniformBlocks);
        for (unsigned i = 0; i < shader->Program->sh.NumUniformBlocks; i++)
            fprintf(out, "UBO.%u.binding=%u\nUBO.%u.bytes=%u\n", i,
                shader->Program->sh.UniformBlocks[i]->Binding, i,
                shader->Program->sh.UniformBlocks[i]->UniformBufferSize);
        assert(!fclose(out));
    }
    f->tokens = static_cast<const tgsi_token *>(nir_to_tgsi(nir, &screen));
    shader->Program->nir = nullptr; /* nir_to_tgsi consumed the actual NIR. */
    assert(f->tokens);
    if (dump_prefix) {
        char filename[4096];
        int n = snprintf(filename, sizeof(filename), "%s.tgsi", dump_prefix);
        assert(n > 0 && (size_t)n < sizeof(filename));
        FILE *out = fopen(filename, "wx"); assert(out);
        unsigned count = tgsi_num_tokens(f->tokens);
        assert(fwrite(f->tokens, sizeof(tgsi_token), count, out) == count && !fclose(out));
        n = snprintf(filename, sizeof(filename), "%s.tgsi.txt", dump_prefix);
        assert(n > 0 && (size_t)n < sizeof(filename));
        out = fopen(filename, "wx"); assert(out);
        tgsi_dump_to_file(f->tokens, 0, out); assert(!fclose(out));
    }
    printf("Real Mesa ESSL310 frontend: %s, %u TGSI tokens, %u default components, %u UBOs\n",
        path, tgsi_num_tokens(f->tokens), params->NumParameterValues, shader->Program->sh.NumUniformBlocks);
    return f;
}

extern "C" const void *cs_frontend_tokens(void *handle)
{ return static_cast<frontend_program *>(handle)->tokens; }

extern "C" unsigned cs_frontend_default_components(void *handle)
{
    auto *f = static_cast<frontend_program *>(handle);
    return f->program->_LinkedShaders[MESA_SHADER_COMPUTE]->Program->Parameters->NumParameterValues;
}

extern "C" unsigned cs_frontend_storage_binding(void *handle, unsigned native_slot)
{
    auto *f = static_cast<frontend_program *>(handle);
    auto *program = f->program->_LinkedShaders[MESA_SHADER_COMPUTE]->Program;
    assert(native_slot < program->info.num_ssbos);
    return program->sh.ShaderStorageBlocks[native_slot]->Binding;
}

extern "C" void cs_frontend_defaults(void *handle, pipe_context *pipe,
    const uint32_t *values, unsigned components)
{
    auto *f = static_cast<frontend_program *>(handle);
    auto *params = f->program->_LinkedShaders[MESA_SHADER_COMPUTE]->Program->Parameters;
    assert(components == params->NumParameterValues && components <= 1024);
    memcpy(params->ParameterValues, values, components * 4);
    pipe_constant_buffer cb = {};
    cb.user_buffer = params->ParameterValues; cb.buffer_size = components * 4;
    pipe->set_constant_buffer(pipe, PIPE_SHADER_COMPUTE, 0, false, &cb);
    /* Mesa may immediately reuse these values. The callback must own a copy. */
    memset(params->ParameterValues, 0xee, components * 4);
}

extern "C" void cs_frontend_ubos(void *handle, pipe_context *pipe,
    pipe_resource *const resources[36], const unsigned offsets[36], const unsigned sizes[36])
{
    auto *f = static_cast<frontend_program *>(handle);
    gl_buffer_object objects[36] = {};
    struct st_context st = {};
    st.ctx = &f->ctx; st.pipe = pipe;
    /* The standalone compiler initializes no GL pipeline state. Use the
     * context's own pipeline object, as normal context initialization does. */
    f->ctx._Shader = &f->ctx.Shader;
    for (unsigned i = 0; i < 36; i++) {
        objects[i].buffer = resources[i];
        auto &binding = f->ctx.UniformBufferBindings[i];
        binding.BufferObject = &objects[i]; binding.Offset = offsets[i];
        binding.Size = sizes[i]; binding.AutomaticSize = false;
    }
    f->ctx._Shader->CurrentProgram[MESA_SHADER_COMPUTE] =
        f->program->_LinkedShaders[MESA_SHADER_COMPUTE]->Program;
    /* Actual Mesa function maps global binding35 to program-local CB1+i,
     * acquires the real resource reference and transfers its ownership. */
    st_bind_cs_ubos(&st);
    for (unsigned i = 0; i < 36; i++) f->ctx.UniformBufferBindings[i].BufferObject = nullptr;
}

extern "C" void cs_frontend_storage(void *handle, pipe_context *pipe,
    pipe_resource *const resources[4])
{
    auto *f = static_cast<frontend_program *>(handle);
    gl_buffer_object objects[4] = {};
    struct st_context st = {};
    st.ctx = &f->ctx; st.pipe = pipe;
    f->ctx._Shader = &f->ctx.Shader;
    auto *program = f->program->_LinkedShaders[MESA_SHADER_COMPUTE]->Program;
    assert(program->info.num_ssbos == 4);
    f->ctx._Shader->CurrentProgram[MESA_SHADER_COMPUTE] = program;
    for (unsigned i = 0; i < 4; i++) {
        objects[i].buffer = resources[i];
        auto &binding = f->ctx.ShaderStorageBufferBindings[i];
        binding.BufferObject = &objects[i]; binding.Offset = 0;
        binding.Size = resources[i]->width0; binding.AutomaticSize = false;
    }
    st_bind_cs_ssbos(&st);
    for (unsigned i = 0; i < 4; i++) f->ctx.ShaderStorageBufferBindings[i].BufferObject = nullptr;
}

extern "C" void cs_frontend_destroy(void *handle)
{
    auto *f = static_cast<frontend_program *>(handle);
    if (f->tokens) tgsi_free_tokens(f->tokens);
    if (f->program) standalone_compiler_cleanup(f->program);
    delete f;
}
