/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stddef.h>
#include <string.h>
#include <stdio.h>
#include "../opengpu_drm.h"
#include "../opengpu_native_layout.h"
#include "../opengpu_native_frame_abi.h"
_Static_assert(sizeof(struct drm_opengpu_native_uniform_stage)==216,"13 stage views");
_Static_assert(offsetof(struct drm_opengpu_native_uniform_stage,blocks)==8,"blocks");
_Static_assert(offsetof(struct drm_opengpu_native_uniform_stage,defaults)==200,"defaults");
_Static_assert(sizeof(struct drm_opengpu_native_program_view)==24,"program view");
_Static_assert(offsetof(struct drm_opengpu_native_program_view,entry_offset)==16,"entry");
_Static_assert(sizeof(struct drm_opengpu_native_draw_plan)==792,"complete per draw");
_Static_assert(offsetof(struct drm_opengpu_native_draw_plan,vertex_uniforms)==56,"VS uniforms");
_Static_assert(offsetof(struct drm_opengpu_native_draw_plan,fragment_uniforms)==272,"FS uniforms");
_Static_assert(offsetof(struct drm_opengpu_native_draw_plan,bindings)==488,"all 16 inputs");
_Static_assert(offsetof(struct drm_opengpu_native_draw_plan,vertex_scratch)==776,"scratch");
_Static_assert(sizeof(struct drm_opengpu_submit_native_binned)==128,"native submit");
_Static_assert(offsetof(struct drm_opengpu_submit_native_binned,job)==24,"job");
_Static_assert(offsetof(struct drm_opengpu_submit_native_binned,plans)==72,"plans");
_Static_assert(offsetof(struct drm_opengpu_submit_native_binned,fence_seqno)==88,"fence");
_Static_assert(offsetof(struct drm_opengpu_submit_native_binned,resources)==96,"resources");
_Static_assert(offsetof(struct drm_opengpu_submit_native_binned,reserved)==112,"reserved");
_Static_assert(OPENGPU_PARAM_NATIVE_LAYOUT==8,"independent native query");
_Static_assert(OPENGPU_DRIVER_FEATURE_NATIVE_BINNED==(1ull<<40),"independent feature");

int main(void)
{
    const unsigned long long base=(16ull<<32)|(16ull<<37)|(16ull<<42)|(4ull<<47)|(6ull<<50)|(30ull<<53);
    const unsigned int capacities[]={1,16,128,255,65536,0xffffffffu};
    struct opengpu_native_shader_layout decoded, saved;
    for(unsigned i=0;i<sizeof(capacities)/sizeof(*capacities);++i){
        assert(opengpu_native_layout_decode(base|capacities[i],&decoded));
        assert(decoded.capacity==capacities[i] && decoded.attributes==16 && decoded.bindings==16);
        assert(decoded.varyings==16 && decoded.colors==4 && decoded.vertex_versions==6 && decoded.fragment_versions==30);
    }
    memset(&decoded,0x5a,sizeof(decoded));saved=decoded;
    assert(!opengpu_native_layout_decode(0,&decoded));
    assert(!opengpu_native_layout_decode(base,&decoded));
    for(unsigned bit=59;bit<64;++bit)
        assert(!opengpu_native_layout_decode(base|128|(1ull<<bit),&decoded));
    for(unsigned bits=0;bits<8;++bits)if(bits!=6)
        assert(!opengpu_native_layout_decode((base&~(7ull<<50))|128|((unsigned long long)bits<<50),&decoded));
    for(unsigned bits=0;bits<64;++bits)if(bits!=30)
        assert(!opengpu_native_layout_decode((base&~(63ull<<53))|128|((unsigned long long)bits<<53),&decoded));
    for(unsigned shift=32;shift<=42;shift+=5)
        for(unsigned n=0;n<32;++n)if(!n || n>16)
            assert(!opengpu_native_layout_decode((base&~(31ull<<shift))|128|((unsigned long long)n<<shift),&decoded));
    for(unsigned n=0;n<8;++n)if(!n || n>4)
        assert(!opengpu_native_layout_decode((base&~(7ull<<47))|128|((unsigned long long)n<<47),&decoded));
    assert(!memcmp(&decoded,&saved,sizeof(decoded)));
    assert(!opengpu_native_layout_decode(base|128,0));
    puts("native submit layout and strict physical capacity decoding: PASS (no submission)");
    return 0;
}
