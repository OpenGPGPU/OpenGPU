/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_TEST_NATIVE_FRAGMENT_REFLECTION_V2_H
#define OPENGPU_TEST_NATIVE_FRAGMENT_REFLECTION_V2_H
#include "opengpu_native_fragment_v2.h"
#include "compiler/glsl/ir.h"
#include <cstring>
/* Read original AST-to-HIR variables, whose data.used is maintained by Mesa
 * for static language checks. These attached shader IRs are distinct from the
 * optimized linked NIR. Copy facts now; never retain an IR pointer. */
static bool native_fragment_reflect_v2(gl_shader_program *program,
    opengpu_native_fragment_reflection_v2 *out)
{
    if(!program||!out||!program->IsES||program->data->Version!=320||
       !program->_LinkedShaders[MESA_SHADER_FRAGMENT]||program->_LinkedShaders[MESA_SHADER_GEOMETRY])return false;
    opengpu_native_fragment_reflection_v2 r={};r.version=2;r.essl_version=320;
    bool seen=false;
    for(unsigned i=0;i<program->NumShaders;i++){
        auto *shader=program->Shaders[i];if(shader->Stage!=MESA_SHADER_FRAGMENT)continue;
        seen=true;
        foreach_in_list(ir_instruction,inst,shader->ir){
            auto *v=inst->as_variable();if(!v||!v->data.used)continue;
            if(v->data.mode==ir_var_shader_in&&v->data.sample)r.static_inputs|=OPENGPU_FRAGMENT_SAMPLE_QUALIFIER;
            const struct {const char *name;unsigned flag;} names[]={
                {"gl_SampleID",OPENGPU_FRAGMENT_SAMPLE_ID},{"gl_SamplePosition",OPENGPU_FRAGMENT_SAMPLE_POSITION},
                {"gl_SampleMaskIn",OPENGPU_FRAGMENT_SAMPLE_MASK_IN},{"gl_FragCoord",OPENGPU_FRAGMENT_FRAG_COORD},
                {"gl_PrimitiveID",OPENGPU_FRAGMENT_PRIMITIVE_ID},{"gl_Layer",OPENGPU_FRAGMENT_LAYER_ZERO},
                {"gl_HelperInvocation",OPENGPU_FRAGMENT_HELPER}};
            for(auto &n:names)if(!strcmp(v->name,n.name))r.static_inputs|=n.flag;
        }
    }
    if(!seen)return false;
    *out=r;return true;
}
#endif
