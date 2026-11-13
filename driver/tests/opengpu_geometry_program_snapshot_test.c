#define OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN gs_program_owner_regression_main
#include "opengpu_private_snapshot_test.c"
#undef OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN
#include "../opengpu_geometry_program_snapshot.c"

static u8 gs_code[4096];
static unsigned gs_code_copies;
static int gs_code_error;
static bool gs_code_producer;
static u32 gs_op(unsigned function,unsigned rd,unsigned a,unsigned b)
{ return function<<25|b<<20|a<<15|rd<<7|0x0bu; }
static void gs_code_word(unsigned i,u32 v)
{ for(unsigned b=0;b<4;b++)gs_code[64+i*4+b]=(u8)(v>>(8*b)); }
static int gs_code_copy(void *opaque,const struct drm_opengpu_compute_uniform_view *v,void *out)
{
    assert(opaque==gs_code && gs_code_producer && v->handle==17 && v->offset+v->bytes<=sizeof(gs_code));
    ++gs_code_copies;if(gs_code_error)return gs_code_error;
    memcpy(out,gs_code+v->offset,v->bytes);return 0;
}
static bool gs_validate(const u32 *words,unsigned count,bool uniforms)
{ return opengpu_geometry_shader_validate_words(words,count,1,true,uniforms,true); }
static void gs_program_reject(struct opengpu_private_snapshot *o,struct drm_opengpu_native_program_view v,
    struct opengpu_geometry_program_profile p,int error)
{
    struct opengpu_native_program_snapshot out={0x12345678,0xabcddcba},saved=out;
    void *before=o->segments;
    assert(opengpu_geometry_program_snapshot_append(o,&v,&p,gs_code_copy,gs_code,&out)==error);
    assert(!memcmp(&out,&saved,sizeof(out)) && o->segments==before);
}
static void gs_program_accept(struct opengpu_device *gpu,struct opengpu_render_context *ctx,
    struct drm_opengpu_native_program_view v,struct opengpu_geometry_program_profile p)
{
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);assert(!IS_ERR(o));
    struct opengpu_native_program_snapshot out;u8 expected[2048];memcpy(expected,gs_code+v.offset,v.bytes);
    assert(!opengpu_geometry_program_snapshot_append(o,&v,&p,gs_code_copy,gs_code,&out));
    assert(out.entry_offset==v.entry_offset);memset(gs_code+v.offset,0x95,v.bytes);
    for(unsigned i=0;i<v.bytes;i+=4){u32 observed,w=0;
        assert(!opengpu_private_snapshot_read32(o,out.segment,i,&observed));
        for(unsigned b=0;b<4;b++)w|=(u32)expected[i+b]<<(8*b);
        assert(observed==w);
    }
    assert(!opengpu_private_snapshot_prepare(o) && !opengpu_private_snapshot_publish(o));
    u32 base;assert(!opengpu_private_snapshot_address(o,out.segment,&base));
    const struct opengpu_buffer *l1=vm_live_l1(&ctx->vm,base/MMU_SUPERPAGE_SIZE);assert(l1);
    assert((((u32 *)l1->cpu)[base%MMU_SUPERPAGE_SIZE/MMU_PAGE_SIZE]&15)==(MMU_PTE_V|MMU_PTE_R|MMU_PTE_X));
    assert(!opengpu_private_snapshot_retire(o));
}
int main(void)
{
    u32 words[513];
    for(unsigned function=32;function<=38;function++){
        words[0]=gs_op(function,5,0,0);words[1]=OPENGPU_SHADER_CEASE;
        assert(gs_validate(words,2,false));
        assert(!opengpu_geometry_shader_validate_words(words,2,1,true,false,false));
        assert(!opengpu_geometry_shader_validate_words(words,2,2,true,false,true));
        assert(!opengpu_native_shader_validate_words(words,2,16*128*4,128,0,1,1,1,0,true,true,false));
        assert(!opengpu_native_shader_validate_words(words,2,16*128*4,128,1,1,0,1,1,true,true,false));
        assert(!opengpu_compute_shader_validate_words_fpu(words,2,4096,128,true));
        words[0]|=1u<<12;assert(!gs_validate(words,2,false));
        words[0]=gs_op(function,1,0,0);assert(!gs_validate(words,2,false));
        words[0]=gs_op(function,5,31,0);assert(!gs_validate(words,2,false));
        words[0]=gs_op(function,5,0,31);assert(!gs_validate(words,2,false));
    }
    words[0]=gs_op(7,5,0,0);words[1]=gs_op(33,0,0,5);words[2]=OPENGPU_SHADER_CEASE;
    assert(gs_validate(words,3,true) && !gs_validate(words,3,false));
    const u32 forbidden[]={0x0000a283u,0x0050a023u,0x0000a287u,0x0050a027u,
        0x02006007u,0x02006027u,0x01007557u,0x0000002bu,0x0000005bu,0x0000000bu,
        0x00000073u,0x00000067u,0x0000002fu};
    for(unsigned i=0;i<sizeof(forbidden)/sizeof(forbidden[0]);i++){
        words[0]=forbidden[i];words[1]=OPENGPU_SHADER_CEASE;assert(!gs_validate(words,2,true));
    }
    words[0]=gs_op(31,5,0,0);words[1]=OPENGPU_SHADER_CEASE;assert(!gs_validate(words,2,true));
    words[0]=gs_op(39,5,0,0);assert(!gs_validate(words,2,true));
    words[0]=0x00000063u;assert(!gs_validate(words,2,true)); /* backward/self branch */
    words[0]=0x00000463u;assert(!gs_validate(words,2,true)); /* branch past suffix */
    words[0]=0x0000f537u;words[1]=0x00050293u;words[2]=gs_op(33,0,0,5);words[3]=OPENGPU_SHADER_CEASE;
    assert(gs_validate(words,4,false));
    words[0]=0xf0028053u; /* fmv.w.x f0,x5; x5 is launch-defined */
    words[1]=0xe0000353u; /* fmv.x.w x6,f0 */
    words[2]=gs_op(33,0,0,6);words[3]=OPENGPU_SHADER_CEASE;
    assert(gs_validate(words,4,false));
    assert(!opengpu_geometry_shader_validate_words(words,4,1,false,false,true));
    for(unsigned i=0;i<512;i++)words[i]=i==511?OPENGPU_SHADER_CEASE:0x00000013u;
    assert(gs_validate(words,512,false) && !gs_validate(words,513,false));
    assert(!gs_validate(words,0,false));
    struct opengpu_device gpu={0};struct opengpu_render_context ctx={.gpu=&gpu,.references=1,.id=7};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu,&ctx.vm));copy_gpu=&gpu;
    gs_code_producer=true;
    struct drm_opengpu_native_program_view view={.offset=64,.handle=17,.bytes=8};
    struct opengpu_geometry_program_profile p={.version=1,.geometry_enabled=true,.scalar_fpu=true};
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(o));
    struct drm_opengpu_native_program_view bad=view;struct opengpu_geometry_program_profile q=p;
    q.geometry_enabled=false;gs_program_reject(o,view,q,-EINVAL);q=p;q.version=0;gs_program_reject(o,view,q,-EINVAL);
    bad.handle=0;gs_program_reject(o,bad,p,-EINVAL);bad=view;bad.pad=1;gs_program_reject(o,bad,p,-EINVAL);
    bad=view;bad.offset=2;gs_program_reject(o,bad,p,-EINVAL);bad=view;bad.bytes=2052;gs_program_reject(o,bad,p,-EINVAL);
    bad=view;bad.entry_offset=8;gs_program_reject(o,bad,p,-EINVAL);bad=view;bad.entry_offset=2;gs_program_reject(o,bad,p,-EINVAL);
    bad=view;bad.offset=~(u64)3;gs_program_reject(o,bad,p,-EINVAL);assert(!gs_code_copies);
    gs_code_word(0,gs_op(35,0,0,0));gs_code_word(1,OPENGPU_SHADER_CEASE);
    gs_code_error=-EIO;gs_program_reject(o,view,p,-EIO);gs_code_error=0;
    for(int fail=0;fail<3;fail++){
        private_alloc_fail_after=fail;gs_program_reject(o,view,p,-ENOMEM);private_alloc_fail_after=-1;
    }
    gs_code_word(0,gs_op(7,5,0,0));gs_program_reject(o,view,p,-EINVAL);
    assert(!opengpu_private_snapshot_retire(o));
    q=p;q.owned_uniforms=true;gs_program_accept(&gpu,&ctx,view,q);
    gs_code_word(0,0xffffffffu);gs_code_word(1,gs_op(35,0,0,0));gs_code_word(2,OPENGPU_SHADER_CEASE);
    view.bytes=12;view.entry_offset=4;gs_program_accept(&gpu,&ctx,view,p);
    gs_code_word(0,0xffffffffu);gs_code_word(1,0xfe000ee3u);gs_code_word(2,OPENGPU_SHADER_CEASE);
    o=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(o));gs_program_reject(o,view,p,-EINVAL);
    assert(!opengpu_private_snapshot_retire(o));
    for(unsigned i=0;i<512;i++)gs_code_word(i,i==511?OPENGPU_SHADER_CEASE:0x00000013u);
    view.bytes=2048;view.entry_offset=0;gs_program_accept(&gpu,&ctx,view,p);
    opengpu_mmu_vm_destroy(&gpu,&ctx.vm);opengpu_mmu_fini(&gpu);
    assert(ctx.references==1 && !live_allocations && !fixture_buffers);
    puts("opt-in scalar GS validator/private RX owner: strict legacy isolation, source suffix/bytes, resource rejection, allocation failures/reuse PASS (CPU only)");
    return 0;
}
