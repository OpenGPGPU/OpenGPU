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
static u32 gs_addi(unsigned rd,unsigned rs1,int value)
{ return ((u32)value&4095u)<<20|rs1<<15|rd<<7|0x13u; }
static u32 gs_branch(unsigned condition,unsigned a,unsigned b,int bytes)
{
    u32 n=(u32)bytes&8191u;
    return (n>>12)<<31|((n>>11)&1u)<<7|((n>>5)&63u)<<25|
        ((n>>1)&15u)<<8|b<<20|a<<15|condition<<12|0x63u;
}
/* The same 19-word physical GS fixture used by the native viewport test.
 * Each invocation reads a position, then rewrites all four staged components
 * for every emit; the worker clears staged output after each accepted emit. */
static void gs_emit_loop(u32 *words,unsigned count)
{
    words[0]=gs_addi(2,0,0);
    for(unsigned c=0;c<4;c++){
        words[1+c*2]=gs_addi(10+c,0,(int)c);
        words[2+c*2]=gs_op(32,14+c,2,10+c);
        words[10+c]=gs_op(33,0,10+c,14+c);
    }
    words[9]=gs_addi(20,0,(int)count);
    words[14]=gs_op(35,0,0,0);words[15]=gs_addi(20,20,-1);
    words[16]=gs_branch(1,20,0,-24);words[17]=gs_op(36,0,0,0);
    words[18]=OPENGPU_SHADER_CEASE;
}
static void test_gs_emit_loops(void)
{
    u32 words[32],saved[32];
    for(unsigned count=1;count<=256;count++){
        gs_emit_loop(words,count);assert(gs_validate(words,19,false));
    }
    memcpy(saved,words,19*sizeof(*words));
    assert(!opengpu_compute_shader_validate_words_fpu(words,19,4096,128,true));
    assert(!opengpu_native_shader_validate_words(words,19,16*128*4,128,0,1,1,1,0,true,true,false));
    assert(!opengpu_native_shader_validate_words(words,19,16*128*4,128,1,1,0,1,1,true,true,false));
    assert(!opengpu_geometry_shader_validate_words(words,19,1,true,false,false));
    const struct {unsigned index;u32 instruction;} rejected[]={
        {0,gs_addi(1,0,0)}, /* reserved protected-ABI register */
        {9,gs_addi(20,0,0)},{9,gs_addi(20,0,257)},{9,gs_addi(20,0,-1)},
        {9,gs_op(32,20,2,10)}, /* runtime input is not a loop bound */
        {10,gs_op(33,21,10,14)}, /* body must preserve all integer registers */
        {10,gs_addi(21,0,7)},{10,gs_op(32,21,2,10)},
        {14,gs_op(35,5,0,0)},
        {15,gs_addi(20,20,0)},{15,gs_addi(20,20,1)},{15,gs_addi(20,20,-2)},
        {16,gs_branch(0,20,0,-24)},{16,gs_branch(1,20,2,-24)},
        {16,gs_branch(1,20,0,0)},{16,gs_branch(1,20,0,-22)},
        {16,gs_branch(1,20,0,-28)}, /* includes counter initializer */
        {16,gs_branch(1,20,0,-4)}, /* no output body */
        {16,gs_branch(1,20,0,-68)}, /* before original entry */
    };
    for(unsigned k=0;k<sizeof(rejected)/sizeof(rejected[0]);k++){
        memcpy(words,saved,19*sizeof(*words));words[rejected[k].index]=rejected[k].instruction;
        assert(!gs_validate(words,19,false));
    }
    /* A path entering at BNE skips x22 initialization. Its known counter
     * agrees at the join, so only the independent live-input proof rejects it. */
    const u32 skipped[]={gs_addi(20,0,255),gs_branch(1,2,0,24),gs_addi(22,0,7),
        gs_addi(20,0,256),gs_op(33,0,0,22),gs_op(35,0,0,0),gs_addi(20,20,-1),
        gs_branch(1,20,0,-12),OPENGPU_SHADER_CEASE};
    assert(!gs_validate(skipped,9,false));
    /* A still-pending alternate exit must not borrow this loop's proof. */
    memcpy(words,saved,19*sizeof(*words));words[0]=gs_branch(1,2,0,68);
    assert(!gs_validate(words,19,false));
    /* Actual fallthrough counter=0 makes a following negative initializer
     * unbounded. Retaining the first-visit value 255 would incorrectly admit it. */
    memcpy(words,saved,19*sizeof(*words));words[17]=gs_addi(21,20,-254);
    words[18]=gs_op(35,0,0,0);words[19]=gs_addi(21,21,-1);
    words[20]=gs_branch(1,21,0,-8);words[21]=OPENGPU_SHADER_CEASE;
    assert(!gs_validate(words,22,false));
}
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
    for(unsigned i=v.bytes;i<((v.bytes+63u)&~63u);i+=4){u32 padding;
        assert(!opengpu_private_snapshot_read32(o,out.segment,i,&padding) && !padding);
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
    test_gs_emit_loops();
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
    /* Freeze the original bounded GS loop into private RX storage, then
     * mutate the producer. An excessive bound must fail before publication. */
    gs_emit_loop(words,256);
    for(unsigned i=0;i<19;i++)gs_code_word(i,words[i]);
    view.bytes=76;gs_program_accept(&gpu,&ctx,view,p);
    gs_emit_loop(words,257);
    for(unsigned i=0;i<19;i++)gs_code_word(i,words[i]);
    o=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(o));
    gs_program_reject(o,view,p,-EINVAL);
    assert(!opengpu_private_snapshot_retire(o));
    opengpu_mmu_vm_destroy(&gpu,&ctx.vm);opengpu_mmu_fini(&gpu);
    assert(ctx.references==1 && !live_allocations && !fixture_buffers);
    puts("opt-in scalar GS validator/private RX owner: strict legacy isolation, source suffix/bytes, resource rejection, allocation failures/reuse PASS (CPU only)");
    return 0;
}
