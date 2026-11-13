/* Real Mesa standalone source compiler and real GLSL/NIR/TGSI passes.
 * This is a compiler-only context; it does not advertise a GLES device. */
#include <cassert>
#include <cstdio>
#include <cstdlib>

#include "main/mtypes.h"
#include "program/prog_parameter.h"
#include "state_tracker/st_context.h"
#include "state_tracker/st_atom.h"
extern "C" {
#include "state_tracker/st_sampler_view.h"
#include "state_tracker/st_texture.h"
}
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

static bool buffer_profile;
static bool texture_namespace_profile;
static bool atomic_counter_profile;

/* The namespace fixture admits buffer textures only. Unused general-image
 * allocation/sampling must fail, rather than silently acquire fake views. */
extern "C" GLboolean __wrap_st_finalize_texture(gl_context *, pipe_context *, gl_texture_object *, unsigned)
{ abort(); }
extern "C" pipe_sampler_view *__wrap_st_get_texture_sampler_view_from_stobj(struct st_context *,
    gl_texture_object *, const gl_sampler_object *, bool, bool, bool)
{ abort(); }
static int param(pipe_screen *, pipe_cap) { return 0; }
static int shader_param(pipe_screen *, pipe_shader_type, pipe_shader_cap cap)
{
    switch (cap) {
    case PIPE_SHADER_CAP_INTEGERS: return 1;
    case PIPE_SHADER_CAP_MAX_TEMPS: return 5;
    case PIPE_SHADER_CAP_MAX_CONST_BUFFERS: return 13;
    case PIPE_SHADER_CAP_MAX_CONST_BUFFER0_SIZE: return 4096;
    case PIPE_SHADER_CAP_MAX_SHADER_BUFFERS: return 4;
    case PIPE_SHADER_CAP_MAX_TEXTURE_SAMPLERS: return buffer_profile ? 32 : 0;
    case PIPE_SHADER_CAP_MAX_SHADER_IMAGES: return buffer_profile ? 4 : 0;
    default: return 0;
    }
}
static int type_size(const glsl_type *type, bool bindless)
{ return glsl_count_vec4_slots(type, false, bindless); }

/* Match state_tracker/st_glsl_to_nir.cpp's real compute shared layout. */
static void shared_type_info(const glsl_type *type, unsigned *size, unsigned *align)
{
    assert(glsl_type_is_vector_or_scalar(type));
    unsigned component = glsl_type_is_boolean(type) ? 4 : glsl_get_bit_size(type) / 8;
    unsigned length = glsl_get_vector_elements(type);
    *size = component * length; *align = component * (length == 3 ? 4 : length);
}


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
    if (atomic_counter_profile) {
        ctx->Extensions.ARB_shader_atomic_counters = true;
        ctx->Const.MaxAtomicBufferBindings = 1;
        ctx->Const.MaxAtomicBufferSize = 32;
        ctx->Const.MaxCombinedAtomicBuffers = 1;
        ctx->Const.MaxCombinedAtomicCounters = 8;
        c.MaxAtomicBuffers = 1;
        c.MaxAtomicCounters = 8;
    }
    if (buffer_profile) {
        ctx->Extensions.OES_texture_buffer = true;
        ctx->Const.MaxCombinedTextureImageUnits = texture_namespace_profile ? 48 : 32;
        ctx->Const.MaxTextureBufferSize = 1u << 27;
        ctx->Const.TextureBufferOffsetAlignment = 1;
        ctx->Const.MaxImageUnits = ctx->Const.MaxCombinedImageUniforms = 4;
        ctx->Const.MaxCombinedShaderOutputResources = 8;
        c.MaxTextureImageUnits = 32; c.MaxImageUniforms = 4;
    }
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
    unsigned shared_bytes;
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
        if (buffer_profile && var->type->contains_opaque()) continue;
        int location = -1;
        for (unsigned i = 0; i < params->NumParameters; i++)
            if (var->data.location >= 0 &&
                params->Parameters[i].MainUniformStorageIndex == (unsigned)var->data.location) {
                location = i; break;
            }
        assert(location >= 0 && !var->state_slots);
        var->data.driver_location = location;
    }
    NIR_PASS_V(nir, nir_lower_vars_to_explicit_types, nir_var_mem_shared, shared_type_info);
    NIR_PASS_V(nir, nir_lower_explicit_io, nir_var_mem_shared, nir_address_format_32bit_offset);
    NIR_PASS_V(nir, gl_nir_lower_buffers, prog);
    NIR_PASS_V(nir, nir_lower_system_values);
    NIR_PASS_V(nir, nir_lower_compute_system_values, nullptr);
    NIR_PASS_V(nir, nir_lower_io, nir_var_uniform, type_size, (nir_lower_io_options)0);
    nir->num_uniforms = (params->NumParameterValues + 3) / 4;
    if (nir->options->lower_uniforms_to_ubo)
        NIR_PASS_V(nir, nir_lower_uniforms_to_ubo, false, false);
    if (buffer_profile) {
        if (atomic_counter_profile)
            NIR_PASS_V(nir, gl_nir_lower_atomics, prog, true);
        NIR_PASS_V(nir, gl_nir_lower_samplers, prog);
        /* Match st_nir_assign_uniform_locations for nonbindless images before
         * gl_nir_lower_images converts derefs to program-local unit indices. */
        unsigned image = 0;
        nir_foreach_image_variable(var, nir) {
            var->data.driver_location = image;
            image += glsl_type_get_image_count(var->type);
        }
        NIR_PASS_V(nir, gl_nir_lower_images, false);
    }
    nir_validate_shader(nir, "actual GLSL frontend pre-TGSI");
    nir_shader_gather_info(nir, nir_shader_get_entrypoint(nir));
    shader->Program->info = nir->info;
    f->shared_bytes = nir->info.shared_size;
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
        if (f->shared_bytes) fprintf(out, "shared.bytes=%u\n", f->shared_bytes);
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

extern "C" void *cs_frontend_compile_buffers(const char *path, const char *dump_prefix)
{
    assert(!buffer_profile); buffer_profile = true;
    void *result = cs_frontend_compile(path, dump_prefix);
    buffer_profile = false;
    return result;
}

extern "C" void *cs_frontend_compile_texture_namespace(const char *path, const char *dump_prefix)
{
    assert(!texture_namespace_profile); texture_namespace_profile = true;
    void *result = cs_frontend_compile_buffers(path, dump_prefix);
    texture_namespace_profile = false;
    return result;
}

extern "C" void *cs_frontend_compile_atomic_counters(const char *path, const char *dump_prefix)
{
    assert(!atomic_counter_profile); atomic_counter_profile = true;
    void *result = cs_frontend_compile_buffers(path, dump_prefix);
    atomic_counter_profile = false;
    return result;
}

extern "C" unsigned cs_frontend_sampler_binding(void *handle, unsigned native_slot)
{
    auto *f = static_cast<frontend_program *>(handle);
    auto *program = f->program->_LinkedShaders[MESA_SHADER_COMPUTE]->Program;
    assert(native_slot < 32 && (program->SamplersUsed & (1u << native_slot)));
    return program->SamplerUnits[native_slot];
}

extern "C" unsigned cs_frontend_textures(void *handle, pipe_context *pipe,
    pipe_resource *const resources[48], const unsigned offsets[48], const unsigned sizes[48],
    unsigned previously_bound, unsigned rgba_unit)
{
    auto *f = static_cast<frontend_program *>(handle);
    gl_buffer_object buffers[48] = {};
    gl_texture_object textures[48] = {};
    struct st_context st = {};
    st.ctx = &f->ctx; st.pipe = pipe; st.screen = pipe->screen;
    assert(previously_bound <= 32);
    st.state.num_sampler_views[PIPE_SHADER_COMPUTE] = previously_bound;
    f->ctx.ComputeProgram._Current = f->program->_LinkedShaders[MESA_SHADER_COMPUTE]->Program;
    for (unsigned i = 0; i < 48; i++) {
        buffers[i].buffer = resources[i];
        auto &texture = textures[i];
        texture.Target = GL_TEXTURE_BUFFER;
        texture.BufferObject = &buffers[i];
        texture.BufferOffset = offsets[i]; texture.BufferSize = sizes[i];
        texture._BufferObjectFormat = i == rgba_unit ? MESA_FORMAT_RGBA_UINT32 : MESA_FORMAT_R_UINT32;
        simple_mtx_init(&texture.validate_mutex, mtx_plain);
        texture.sampler_views = static_cast<st_sampler_views *>(calloc(1,
            sizeof(st_sampler_views) + sizeof(st_sampler_view)));
        assert(texture.sampler_views); texture.sampler_views->max = 1;
        f->ctx.Texture.Unit[i]._Current = &texture;
    }
    /* Actual Mesa traverses SamplerUnits (global0..47) and creates/binds the
     * program-local views; this fixture does not implement the remapping. */
    st_update_compute_textures(&st);
    unsigned bound = st.state.num_sampler_views[PIPE_SHADER_COMPUTE];
    for (unsigned i = 0; i < 48; i++) {
        f->ctx.Texture.Unit[i]._Current = nullptr;
        st_delete_texture_sampler_views(&st, &textures[i]);
        simple_mtx_destroy(&textures[i].validate_mutex);
    }
    f->ctx.ComputeProgram._Current = nullptr;
    return bound;
}

extern "C" void cs_frontend_counters(void *handle, pipe_context *pipe,
    pipe_resource *resource, unsigned offset, unsigned bytes)
{
    auto *f = static_cast<frontend_program *>(handle);
    gl_buffer_object object = {};
    object.buffer = resource;
    auto &binding = f->ctx.AtomicBufferBindings[0];
    binding.BufferObject = &object; binding.Offset = offset;
    binding.Size = bytes; binding.AutomaticSize = false;
    struct st_context st = {};
    st.ctx = &f->ctx; st.pipe = pipe; st.has_hw_atomics = true;
    /* Actual Mesa maps its independent counter binding to the real Gallium
     * callback; it does not bind or rewrite an SSBO slot for this fixture. */
    st_bind_hw_atomic_buffers(&st);
    binding.BufferObject = nullptr;
}

extern "C" const void *cs_frontend_tokens(void *handle)
{ return static_cast<frontend_program *>(handle)->tokens; }

extern "C" unsigned cs_frontend_default_components(void *handle)
{
    auto *f = static_cast<frontend_program *>(handle);
    return f->program->_LinkedShaders[MESA_SHADER_COMPUTE]->Program->Parameters->NumParameterValues;
}

extern "C" unsigned cs_frontend_shared_bytes(void *handle)
{ return static_cast<frontend_program *>(handle)->shared_bytes; }

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
    assert(program->info.num_ssbos && program->info.num_ssbos <= 4);
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
