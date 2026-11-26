/* Actual owner/MMU BUILD graph only. Literal CEASE/emit programs are NOT
 * shader results, VS output, patch bytes or GPU-execution evidence. */
#include "../opengpu_tessellation_joint_host.h"
#define OPENGPU_PROTECTED_TESS_TEST_MAIN joint_old_protected_regression_main
#include "../opengpu_protected_tessellation_test.c"
#undef OPENGPU_PROTECTED_TESS_TEST_MAIN
#include "../opengpu_protected_tessellation_joint.h"
#include "../opengpu_tessellation_vertex_source.c"
#include "../opengpu_tessellation_vertex_plan.c"
#include "../opengpu_tessellation_vertex_source_abi.h"
struct geometry_joint_layout { u32 vertex_stride, old_uniform, task_id; };
#define GEOMETRY_LAYOUT(P) {P##_TEMPLATE_STRIDE_WORD, \
    P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_UNIFORMRESOURCES_WORD, \
    P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_TASKID_WORD}
static const struct geometry_joint_layout geometry_joint_layouts[]={
    GEOMETRY_LAYOUT(OPENGPU_NATIVE_TESSELLATION_SEQUENCE),
    GEOMETRY_LAYOUT(OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE),
    GEOMETRY_LAYOUT(OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE),
};
#undef GEOMETRY_LAYOUT

struct joint_fixture {
    struct pt_records old;
    u32 draws[3][512];
    struct drm_opengpu_native_draw_plan draw_plans[3];
    struct opengpu_tessellation_vertex_outer_invocation_v2 invocations[3];
    struct opengpu_tessellation_vertex_outer_range_v2 ranges[3];
    struct opengpu_protected_tessellation_joint_plan plans[3];
    struct opengpu_protected_tessellation_joint_source source;
};
static struct joint_fixture *joint_mutate;
static int joint_resolve(void *context,u32 handle,struct opengpu_native_frame_backing *out)
{
    if(!graph_producers_complete)return -EAGAIN;
    int ret=graph_resolve(context,handle,out);
    if(joint_mutate){memset(joint_mutate,0xa7,sizeof(*joint_mutate));joint_mutate=NULL;}
    return ret;
}
static const struct opengpu_native_job_copy joint_callbacks={pt_test_copy,joint_resolve,graph_original};
static void joint_init(struct joint_fixture *f,struct opengpu_device *gpu,u32 domain)
{
    memset(f,0,sizeof(*f));pt_init(&f->old,gpu,domain);
    const struct geometry_joint_layout *l=&geometry_joint_layouts[domain];
    /* Full ABI9 writable/input prefix, not the old ABI1 88-word prefix. */
    f->old.original.request.scratch_stride=94*128*4;
    for(u32 i=0;i<4;i++) {
        struct opengpu_native_frame_gem *v=&f->old.original.request.scratch[i];
        opengpu_buffer_free(gpu,&graph_original[v->handle]);
        v->bytes=(u64)2*f->old.original.request.scratch_stride;
        assert(!opengpu_buffer_alloc(gpu,&graph_original[v->handle],(size_t)(v->bytes+64+4095)&~4095u));
    }
    for(u32 i=0;i<4;i++)f->old.original.request.attachments[i].format=35; /* RGBA32F, same 16-byte capacity */
    assert(!opengpu_native_frame_encode(&f->old.original.request,&f->old.original.encoded));
    memcpy(f->draw_plans,f->old.original.plans,sizeof(f->draw_plans));
    for(u32 i=0;i<3;i++) {
        memcpy(f->draws[i],f->old.original.draws+(size_t)i*GRAPH_STRIDE/4,2048);
        f->draws[i][311]=4;f->draws[i][325]=3;f->draws[i][326]=1;
        f->draws[i][343]=f->draws[i][344]=0xffffffffu;
        u32 *d=f->old.sequences+i*512;
        d[0]=0x474e5407+domain;d[2]=7+domain;d[16]=6;d[17]=0;d[31]=2;
        d[l->vertex_stride]=0;d[l->old_uniform]=0;
        for(u32 w=237+(domain!=0);w<=482+(domain!=0);w++)d[w]=0;
        struct opengpu_tessellation_vertex_outer_range_v2 *r=&f->ranges[i];
        r->sequence_index=i;r->invocation_index=i;r->original_draw_index=i;
        r->flags=OPENGPU_VERTEX_OUTER_FIRST|OPENGPU_VERTEX_OUTER_LAST;r->task_id_base=100+10*i;r->patch_count=6;
        r->slice=(struct opengpu_tessellation_vertex_slice){1,0,2,0,3};
        r->layout=(struct opengpu_tessellation_patch_layout){2,4,2,8,0};
        for(u32 j=0;j<4;j++)r->components[j]=(struct opengpu_tessellation_vertex_component){0,j};
        d[l->task_id]=r->task_id_base;
        f->invocations[i]=(struct opengpu_tessellation_vertex_outer_invocation_v2){.invocation_id=(1ULL<<32)+i,
            .original_draw_index=i,.input_patch_vertices=2};
        /* Plans remain permuted independently of sequence/catalog indices. */
        const struct opengpu_protected_tessellation_plan *p=&f->old.plans[i];
        f->plans[i]=(struct opengpu_protected_tessellation_joint_plan){.sequence_index=p->sequence_index,
            .state_handle=p->state_handle,.control_program=p->control_program,.evaluation_program=p->evaluation_program,
            .control_uniforms=p->control_uniforms,.evaluation_uniforms=p->evaluation_uniforms};
    }
    for(u32 i=0;i<2;i++){
        f->old.original.states[i*128+56]=domain==1?9:8;
        f->old.original.states[i*128+39]=2;
        f->old.original.states[i*128+93]=f->old.original.states[i*128+94]=1;
    }
    u32 *job=f->old.original.job;
    job[0]=0x474e5104+domain;job[2]=2;job[27]=2;job[31]=3;job[32]=256;job[33]=64;job[34]=3;job[35]=3;
    f->source=(struct opengpu_protected_tessellation_joint_source){.job=job,.frame=&f->old.original.encoded.frame,
        .sequences=f->old.sequences,.sequences_bytes=sizeof(f->old.sequences),
        .states=f->old.original.states,.states_bytes=sizeof(f->old.original.states),
        .draws=f->draws,.draws_bytes=sizeof(f->draws),.draw_plans=f->draw_plans,.draw_count=3,.draw_plan_count=3,
        .invocations=f->invocations,.invocation_count=3,.ranges=f->ranges,.range_count=3,
        .plans=f->plans,.plan_count=3,.state_plans=f->old.states,.state_plan_count=2,
        .resources=f->old.original.encoded.resources,.resource_count=27,.context=7,.pass=8,.generation=9,
        .profile={.version=1,.domains=7,.compute_units=2,.lanes=16,.warps=8,.engines=4,.available=true,
            .scalar_fpu=true,.owned_vertex_source=true,.patch_zero_initialized=true,.owned_patch_reads=true,
            .owned_tessellation_info=true,.primitive_inputs=true,.sample_inputs=true,.joint_directory_route=true,.whole_owner_retirement=true}};
}

#ifndef OPENGPU_JOINT_GEOMETRY_SOURCE
#define OPENGPU_JOINT_GEOMETRY_SOURCE "../opengpu_protected_tessellation_joint_geometry.c"
#endif
#include OPENGPU_JOINT_GEOMETRY_SOURCE
#include "opengpu_protected_tessellation_joint_geometry_test.inc"
