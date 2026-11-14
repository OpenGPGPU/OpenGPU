/* SPDX-License-Identifier: MIT */
/* Actual Mesa compiler-only ESSL fragment frontend; no advertised screen. */
#include <cstdio>
#include <cstdlib>
#include "main/mtypes.h"
#include "program/prog_parameter.h"
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
#include "fs_glsl_frontend.h"

static int param(pipe_screen *,pipe_cap) { return 0; }
static int shader_param(pipe_screen *,pipe_shader_type,pipe_shader_cap cap)
{
    switch(cap){
    case PIPE_SHADER_CAP_INTEGERS:return 1;
    case PIPE_SHADER_CAP_MAX_TEMPS:return 64;
    case PIPE_SHADER_CAP_MAX_CONST_BUFFERS:return 1;
    case PIPE_SHADER_CAP_MAX_CONST_BUFFER0_SIZE:return 4096;
    default:return 0;
    }
}
void opengpu_frontend_compiler_limits(gl_context *ctx)
{
    /* Private compiler limits only. Resource use is rejected below; this
     * enables original Mesa syntax/IR, not a GLES or Gallium device feature. */
    ctx->Const.NativeIntegers=true;
    ctx->Extensions.OES_sample_variables=true;
    ctx->Extensions.ARB_sample_shading=true;
    ctx->Const.MaxDrawBuffers=4;
    ctx->Const.MaxVarying=16;
    ctx->Const.Program[MESA_SHADER_FRAGMENT].MaxInputComponents=64;
    pipe_screen screen={};screen.get_param=param;screen.get_shader_param=shader_param;
    ctx->Const.ShaderCompilerOptions[MESA_SHADER_FRAGMENT].NirOptions=
        static_cast<const nir_shader_compiler_options *>(nir_to_tgsi_get_compiler_options(
            &screen,PIPE_SHADER_IR_NIR,PIPE_SHADER_FRAGMENT));
}
struct frontend_program {
    gl_context ctx;standalone_options options;gl_shader_program *program;
    const tgsi_token *tokens;
};
extern "C" void native_fs_frontend_destroy(void *handle)
{
    auto *f=static_cast<frontend_program *>(handle);if(!f)return;
    if(f->tokens)ureg_free_tokens(f->tokens);
    if(f->program)standalone_compiler_cleanup(f->program);
    delete f;
}
extern "C" void *native_fs_frontend_compile(const char *path,const char *dump_prefix)
{
    auto *f=new frontend_program{};
    f->options.glsl_version=300;f->options.do_link=1;f->options.just_log=1;
    char *filename=const_cast<char *>(path);
    f->program=standalone_compile_shader(&f->options,1,&filename,&f->ctx);
    auto *prog=f->program;
    if(!prog||prog->data->LinkStatus!=LINKING_SUCCESS||!prog->IsES||prog->data->Version!=310||
       !prog->_LinkedShaders[MESA_SHADER_FRAGMENT]){native_fs_frontend_destroy(f);return nullptr;}
    exec_list_make_empty(&prog->EmptyUniformLocations);
    auto *shader=prog->_LinkedShaders[MESA_SHADER_FRAGMENT];
    pipe_screen screen={};screen.get_param=param;screen.get_shader_param=shader_param;
    const auto *options=static_cast<const nir_shader_compiler_options *>(
        nir_to_tgsi_get_compiler_options(&screen,PIPE_SHADER_IR_NIR,PIPE_SHADER_FRAGMENT));
    shader->Program->Parameters=_mesa_new_parameter_list();
    nir_shader *nir=glsl_to_nir(&f->ctx.Const,prog,MESA_SHADER_FRAGMENT,options);
    if(!nir){native_fs_frontend_destroy(f);return nullptr;}
    shader->Program->nir=nir;
    NIR_PASS_V(nir,nir_lower_variable_initializers,nir_var_function_temp);
    NIR_PASS_V(nir,nir_lower_returns);
    NIR_PASS_V(nir,nir_split_var_copies);
    NIR_PASS_V(nir,nir_lower_var_copies);
    NIR_PASS_V(nir,nir_lower_vars_to_ssa);
    gl_nir_opts(nir);
    if(!gl_nir_link_glsl(&f->ctx.Const,&f->ctx.Extensions,f->ctx.API,prog)){
        native_fs_frontend_destroy(f);return nullptr;
    }
    /* No silent legacy constant bank, texture namespace or buffer lowering. */
    if(shader->Program->Parameters->NumParameterValues||shader->Program->sh.NumUniformBlocks||
       shader->Program->info.num_ssbos||shader->Program->info.num_images||
       shader->Program->SamplersUsed){native_fs_frontend_destroy(f);return nullptr;}
    nir_foreach_uniform_variable(var,nir){(void)var;native_fs_frontend_destroy(f);return nullptr;}
    NIR_PASS_V(nir,nir_lower_system_values);
    /* Match the actual state tracker's fragment IO assignment, preserving
     * semantic location (including explicitly located MRTs/varyings). */
    nir_assign_io_var_locations(nir,nir_var_shader_in,&nir->num_inputs,nir->info.stage);
    nir_assign_io_var_locations(nir,nir_var_shader_out,&nir->num_outputs,nir->info.stage);
    nir_validate_shader(nir,"actual native sample FS frontend");
    nir_shader_gather_info(nir,nir_shader_get_entrypoint(nir));
    shader->Program->info=nir->info;
    if(dump_prefix){
        char filename[4096];int n=snprintf(filename,sizeof(filename),"%s.nir",dump_prefix);
        if(n<0||(unsigned)n>=sizeof(filename))abort();
        FILE *out=fopen(filename,"wx");if(!out)abort();nir_print_shader(nir,out);if(fclose(out))abort();
    }
    f->tokens=static_cast<const tgsi_token *>(nir_to_tgsi(nir,&screen));
    shader->Program->nir=nullptr;
    if(!f->tokens){native_fs_frontend_destroy(f);return nullptr;}
    if(dump_prefix){
        char filename[4096];int n=snprintf(filename,sizeof(filename),"%s.tgsi.txt",dump_prefix);
        if(n<0||(unsigned)n>=sizeof(filename))abort();
        FILE *out=fopen(filename,"wx");if(!out)abort();tgsi_dump_to_file(f->tokens,0,out);if(fclose(out))abort();
    }
    return f;
}
extern "C" const void *native_fs_frontend_tokens(void *handle)
{ return static_cast<frontend_program *>(handle)->tokens; }
extern "C" unsigned native_fs_frontend_token_words(void *handle)
{ return tgsi_num_tokens(static_cast<frontend_program *>(handle)->tokens); }
