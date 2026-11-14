/* Actual independent validator -> unchanged private RX owner/MMU. */
#define OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN tess_program_old_owner
#include "opengpu_private_snapshot_test.c"
#undef OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN
#ifndef OPENGPU_TESS_VALIDATOR_SOURCE
#define OPENGPU_TESS_VALIDATOR_SOURCE "../opengpu_tessellation_shader_validator.c"
#endif
#include OPENGPU_TESS_VALIDATOR_SOURCE
#include "../opengpu_tessellation_program_snapshot.c"

static u32 ti(u32 rd,u32 rs,int immediate)
{ return ((u32)immediate&4095)<<20|rs<<15|rd<<7|0x13; }
static u32 tv(u32 fn,u32 form,u32 rd,u32 a,u32 b,bool vm)
{ return fn<<26|(u32)vm<<25|b<<20|a<<15|form<<12|rd<<7|0x57; }
static u32 tp(u32 fn,u32 rd,u32 a,u32 b,bool vm)
{ return fn<<26|(u32)vm<<25|b<<20|a<<15|rd<<7|0x2b; }
static u32 tg(u32 fn,u32 rd,u32 a,u32 b)
{ return fn<<25|b<<20|a<<15|rd<<7|0xb; }
static u32 tb(u32 opcode,u32 function,u32 a,u32 b,int bytes)
{ u32 n=(u32)bytes;return (n>>12&1)<<31|(n>>5&63)<<25|b<<20|a<<15|function<<12|(n>>1&15)<<8|(n>>11&1)<<7|opcode; }
static struct opengpu_tessellation_program_profile policy(u32 stage)
{
    struct opengpu_tessellation_program_profile p={.version=1,.stage=stage,.lanes=16,.warps=8,
        .output_components=64,.available=true,.owned_uniforms=true,.scalar_fpu=true};
    if(stage==4){p.input_vertices=32;p.input_components=64;p.output_vertices=32;p.output_components=60;p.patch_components=120;}
    else p.patch_words=61;
    return p;
}
#define VALID(a,p) assert(opengpu_tessellation_shader_validate_words(a,sizeof(a)/4,&p))
#define INVALID(a,p) assert(!opengpu_tessellation_shader_validate_words(a,sizeof(a)/4,&p))
static void program_tests(void)
{
    struct opengpu_tessellation_program_profile c=policy(4),e=policy(5),bad;
    u32 control[]={0x0104f057,tv(23,3,2,0,0,true),tv(23,3,3,7,0,true),
        tp(21,4,1,2,true),tp(20,0,4,0,true),tp(22,0,1,3,true),0xb,OPENGPU_SHADER_CEASE};
    /* input component0 -> every invocation's output tess level is NOT legal;
     * use an ordinary output component for per-vertex publication. */
    control[2]=tv(23,3,3,1,0,true);VALID(control,c);INVALID(control,e);
    bad=c;bad.owned_uniforms=false;VALID(control,bad);
    u32 simple[]={OPENGPU_SHADER_CEASE};VALID(simple,c);INVALID(simple,e);
    for(unsigned kind=0;kind<13;kind++){
        bad=c;
        switch(kind){case 0:bad.stage=0;break;case 1:bad.stage=1;break;case 2:bad.stage=3;break;case 3:bad.stage=6;break;
        case 4:bad.version=0;break;case 5:bad.version=2;break;case 6:bad.available=false;break;
        case 7:bad.lanes=3;break;case 8:bad.warps=1;break;case 9:bad.output_vertices=33;break;
        case 10:bad.patch_components=121;break;case 11:bad.patch_words=1;break;case 12:bad.input_components=65;break;}
        INVALID(simple,bad);
    }
    /* The physical patch RAM bounds the coupled layout, not just each field. */
    bad=c;bad.output_components=64;bad.patch_components=0;VALID(simple,bad); /* 2048 */
    bad.patch_components=1;INVALID(simple,bad); /* 2049 */
    bad.output_components=60;bad.patch_components=120;VALID(simple,bad); /* 2040 */
    bad.output_components=61;INVALID(simple,bad); /* 2072 */
    bad.patch_components=96;VALID(simple,bad); /* 2048 with nonzero patch data */
    bad.patch_components=97;INVALID(simple,bad); /* 2049 */
    bad.output_components=64;bad.patch_components=120;INVALID(simple,bad); /* 2168 */
    bad.output_components=60;bad.patch_components=120;bad.input_vertices=33;INVALID(simple,bad);
    bad=c;bad.input_components=65;INVALID(simple,bad);
    u32 load[]={ti(10,0,31),ti(11,0,0),tg(7,12,10,11),0x0104f057,tv(23,3,2,0,0,true),tv(23,3,3,0,0,true),tp(7,4,2,3,true),OPENGPU_SHADER_CEASE};
    VALID(load,c);bad=c;bad.owned_uniforms=false;INVALID(load,bad);
    u32 no_vl[]={tp(7,4,1,1,true),OPENGPU_SHADER_CEASE};INVALID(no_vl,c);
    u32 undefined[]={ti(10,31,0),OPENGPU_SHADER_CEASE};INVALID(undefined,c);
    u32 raw[]={0x0000a503,OPENGPU_SHADER_CEASE};INVALID(raw,c);raw[0]=0x00a0a023;INVALID(raw,c);
    for(unsigned opcode=0;opcode<128;opcode++)if(opcode==0x03 || opcode==0x23 || opcode==0x07 || opcode==0x27 || opcode==0x2f || opcode==0x67){raw[0]=opcode;INVALID(raw,c);}
    raw[0]=tg(34,0,0,0);INVALID(raw,e);raw[0]=tg(38,10,0,0);INVALID(raw,e);
    u32 params[]={0x0104f057,tv(23,3,2,0,0,true),tv(23,3,3,1,0,true),tp(22,0,2,3,true),OPENGPU_SHADER_CEASE};INVALID(params,c);
    params[3]=tp(20,0,2,0,true);VALID(params,c);
    u32 masked[]={0x0104f057,tv(23,3,0,31,0,true),tv(23,3,2,0,0,true),tv(23,3,3,1,0,true),
        tp(20,0,2,0,false),tp(22,0,2,3,false),OPENGPU_SHADER_CEASE};VALID(masked,c);
    masked[5]=tp(22,0,2,3,true);INVALID(masked,c);masked[5]=tp(22,0,2,3,false);
    masked[1]=tv(23,3,5,0,0,true);INVALID(masked,c);masked[1]=tv(23,3,0,31,0,true);
    u32 changed_mask[8];memcpy(changed_mask,masked,5*4);changed_mask[5]=tv(23,3,0,0,0,true);changed_mask[6]=masked[5];changed_mask[7]=masked[6];INVALID(changed_mask,c);
    u32 missed_scope[]={0x0104f057,tb(0x63,0,8,0,12),tp(20,0,1,0,true),0x00000013,
        tv(23,3,3,1,0,true),tp(22,0,1,3,true),OPENGPU_SHADER_CEASE};INVALID(missed_scope,c);
    u32 malformed_patch[8];memcpy(malformed_patch,control,sizeof(control));
    malformed_patch[2]=tv(23,3,3,0,0,true);INVALID(malformed_patch,c); /* store to input */
    malformed_patch[2]=tv(23,3,3,7,0,true);malformed_patch[5]=tp(22,0,1,3,true); /* dynamic vertex checked at runtime */
    u32 malformed_vertex[]={0x0104f057,ti(10,0,64),tv(23,4,2,10,0,true),tv(23,3,3,1,0,true),tp(21,4,2,3,true),OPENGPU_SHADER_CEASE};INVALID(malformed_vertex,c);
    u32 levels[]={0x0104f057,tv(23,3,2,0,0,true),ti(10,0,47),tv(23,4,3,10,0,true),
        tp(20,0,2,0,true),tp(22,0,2,3,true),OPENGPU_SHADER_CEASE};
    bad=c;bad.patch_components=0;VALID(levels,bad); /* level5 independent of ordinary patch capacity */
    levels[2]=ti(10,0,5);INVALID(levels,bad); /* missing perPatch is an actual hardware error */
    levels[2]=ti(10,0,55);INVALID(levels,bad);levels[2]=ti(10,0,3);INVALID(levels,bad);
    levels[2]=ti(10,0,47);levels[4]=tp(20,0,2,1,true);INVALID(levels,bad);levels[4]=tp(20,1,2,0,true);INVALID(levels,bad);
    levels[4]=tp(20,0,2,0,true);levels[5]=tp(22,1,2,3,true);INVALID(levels,bad);
    u32 vl[]={ti(9,0,32),0x0104f057,OPENGPU_SHADER_CEASE};INVALID(vl,c);vl[0]=ti(9,0,128);VALID(vl,c);
    vl[0]=ti(9,0,128);vl[1]=0x0102f057;INVALID(vl,c); /* localSize is not physical capacity */
    u32 short_vl[]={0xc100f057,OPENGPU_SHADER_CEASE};INVALID(short_vl,c);
    u32 branch[]={tb(0x63,0,8,0,12),ti(10,0,1),tb(0x63,0,0,0,8),ti(10,0,2),ti(11,10,0),OPENGPU_SHADER_CEASE};VALID(branch,c);
    branch[3]=0x00000013;INVALID(branch,c);branch[3]=ti(10,0,2);
    branch[0]=tb(0x63,0,8,0,-4);INVALID(branch,c);branch[0]=tb(0x63,0,8,0,24);INVALID(branch,c);
    u32 early[]={tb(0x63,0,8,0,8),OPENGPU_SHADER_CEASE,0xb,OPENGPU_SHADER_CEASE};INVALID(early,c);
    u32 skipped[]={tb(0x63,0,0,0,8),0xffffffff,OPENGPU_SHADER_CEASE};VALID(skipped,c);
    u32 simt[]={0x0104f057,tb(0x5b,3,0,0,24),tb(0x5b,0,1,1,12),tv(23,3,5,0,0,true),OPENGPU_SHADER_SIMT_JOIN,
        tv(23,3,5,0,0,true),OPENGPU_SHADER_SIMT_JOIN,OPENGPU_SHADER_SIMT_JOIN,tv(23,3,3,1,0,true),
        tp(20,0,5,0,true),tp(22,0,1,3,true),0xb,OPENGPU_SHADER_CEASE};
    VALID(simt,c);simt[5]=0x13;INVALID(simt,c);simt[5]=tv(23,3,5,0,0,true);
    simt[3]=0xb;INVALID(simt,c);simt[3]=tv(23,3,5,0,0,true);simt[4]=0x13;INVALID(simt,c);
    /* Real RF is shared across arms: an entry snapshot cannot restore x10. */
    u32 clobber[]={0x0104f057,ti(10,0,0),tb(0x5b,3,0,0,24),tb(0x5b,0,1,1,12),ti(10,0,1),
        OPENGPU_SHADER_SIMT_JOIN,tv(23,4,3,10,0,true),OPENGPU_SHADER_SIMT_JOIN,OPENGPU_SHADER_SIMT_JOIN,OPENGPU_SHADER_CEASE};
    INVALID(clobber,c);
    /* Other scalar registers cannot be mutated in either arm. */
    clobber[1]=ti(11,0,0);clobber[4]=ti(11,0,1);clobber[6]=tv(23,4,3,11,0,true);INVALID(clobber,c);
    u32 rematerialize[]={0x0104f057,ti(10,0,0),tb(0x5b,3,0,0,28),tb(0x5b,0,1,1,12),ti(10,0,1),
        OPENGPU_SHADER_SIMT_JOIN,ti(10,0,2),tv(23,4,3,10,0,true),OPENGPU_SHADER_SIMT_JOIN,OPENGPU_SHADER_SIMT_JOIN,OPENGPU_SHADER_CEASE};
    VALID(rematerialize,c);rematerialize[6]=ti(10,10,1);INVALID(rematerialize,c);rematerialize[6]=ti(10,0,2);
    /* Shared scalar FP cannot be restored or clobbered through lane snapshots. */
    rematerialize[6]=0xf0050553;INVALID(rematerialize,c);rematerialize[6]=ti(10,0,2);
    u32 nested_scratch[]={0x0104f057,ti(10,0,0),tb(0x5b,3,0,0,56),tb(0x5b,0,1,1,40),
        tb(0x5b,3,0,0,24),tb(0x5b,0,1,1,12),ti(10,0,1),OPENGPU_SHADER_SIMT_JOIN,ti(10,0,2),
        OPENGPU_SHADER_SIMT_JOIN,OPENGPU_SHADER_SIMT_JOIN,ti(10,10,1),OPENGPU_SHADER_SIMT_JOIN,
        ti(10,0,0),tv(23,4,3,10,0,true),OPENGPU_SHADER_SIMT_JOIN,OPENGPU_SHADER_SIMT_JOIN,OPENGPU_SHADER_CEASE};
    INVALID(nested_scratch,c);nested_scratch[11]=ti(10,0,3);VALID(nested_scratch,c);
    u32 fp_scope[]={0x0104f057,tb(0x5b,3,0,0,24),tb(0x5b,0,1,1,12),0xf0000553,OPENGPU_SHADER_SIMT_JOIN,
        0x13,OPENGPU_SHADER_SIMT_JOIN,OPENGPU_SHADER_SIMT_JOIN,OPENGPU_SHADER_CEASE};INVALID(fp_scope,c);
    fp_scope[3]=tv(0,1,3,1,1,true);VALID(fp_scope,c); /* lane-private FP32 remains supported */
    /* A common continuation has to recapture parameters; neither arm carries
     * a warp-global parameters/mask proof across a JOIN. */
    u32 shared_params[]={0x0104f057,tv(23,3,3,1,0,true),tb(0x5b,3,0,0,24),tb(0x5b,0,1,1,12),tp(20,0,1,0,true),
        OPENGPU_SHADER_SIMT_JOIN,tp(20,0,1,0,true),OPENGPU_SHADER_SIMT_JOIN,OPENGPU_SHADER_SIMT_JOIN,tp(22,0,1,3,true),OPENGPU_SHADER_CEASE};
    INVALID(shared_params,c);
    u32 inherited_mask[]={0x0104f057,tv(23,3,0,31,0,true),tv(23,3,3,1,0,true),tb(0x5b,3,0,0,24),tb(0x5b,0,1,1,12),
        tp(20,0,1,0,false),OPENGPU_SHADER_SIMT_JOIN,tp(20,0,1,0,false),OPENGPU_SHADER_SIMT_JOIN,OPENGPU_SHADER_SIMT_JOIN,OPENGPU_SHADER_CEASE};
    INVALID(inherited_mask,c);
    u32 tes[]={ti(10,0,0),ti(11,0,0),tg(32,12,10,11),0x0104f057,tv(23,4,3,12,0,true),
        tv(16,2,13,0,3,true),ti(10,0,0),tg(33,0,10,13),tg(35,0,0,0),tg(36,0,0,0),OPENGPU_SHADER_CEASE};
    VALID(tes,e);INVALID(tes,c);
    tes[1]=ti(11,0,63);VALID(tes,e);bad=e;bad.patch_words=60;INVALID(tes,bad);tes[1]=ti(11,0,64);INVALID(tes,e);tes[1]=ti(11,0,0);
    tes[0]=ti(10,0,1);INVALID(tes,e);tes[0]=ti(10,0,0);
    tes[8]=tg(34,0,0,0);INVALID(tes,e);tes[8]=tg(35,0,0,0);
    tes[9]=tg(35,0,0,0);INVALID(tes,e);tes[9]=0x13;INVALID(tes,e);tes[9]=tg(36,0,0,0);
    tes[6]=ti(10,0,64);INVALID(tes,e);tes[6]=ti(10,0,0);
    u32 bypass[]={tb(0x63,0,8,0,8),tg(35,0,0,0),tg(36,0,0,0),OPENGPU_SHADER_CEASE};INVALID(bypass,e);
    u32 unknown_fp[]={0x00000553,tg(35,0,0,0),tg(36,0,0,0),OPENGPU_SHADER_CEASE};INVALID(unknown_fp,e);
    u32 fp[]={ti(10,0,0),0xf0050553,0xe00505d3,OPENGPU_SHADER_CEASE};VALID(fp,c);bad=c;bad.scalar_fpu=false;INVALID(fp,bad);
    u32 crosslane[]={0x0104f057,tv(12,3,3,0,1,true),OPENGPU_SHADER_CEASE};INVALID(crosslane,c);
    crosslane[1]=tv(14,3,3,0,1,true);INVALID(crosslane,c);
    crosslane[1]=tv(0,2,3,1,1,true);INVALID(crosslane,c);
    u32 masked_undefined[]={0x0104f057,tv(23,3,0,31,0,true),tv(0,0,3,1,1,false),OPENGPU_SHADER_CEASE};INVALID(masked_undefined,c);
    u32 unreachable[]={OPENGPU_SHADER_CEASE,0xffffffff,0x00a0a023};VALID(unreachable,c);
    u32 no_end[]={0x13};INVALID(no_end,c);
    u32 capacity[512];for(unsigned i=0;i<511;i++)capacity[i]=ti(10,0,(int)i);capacity[511]=OPENGPU_SHADER_CEASE;VALID(capacity,c);
    assert(!opengpu_tessellation_shader_validate_words(capacity,513,&c));
}
static u8 tc_code[4096];static unsigned tc_code_copies;static bool tc_code_ready,tc_code_retained;static int tc_code_error;
static struct drm_opengpu_native_program_view *tc_view_change;
static struct opengpu_tessellation_program_profile *tc_policy_change;
static int program_copy(void *opaque,const struct drm_opengpu_compute_uniform_view *v,void *out)
{
    assert(opaque==tc_code);if(!tc_code_ready)return -EAGAIN;if(!tc_code_retained || v->handle!=71)return -ENOENT;
    if(v->offset>sizeof(tc_code) || v->bytes>sizeof(tc_code)-v->offset)return -ERANGE;
    tc_code_copies++;if(tc_code_error)return tc_code_error;memcpy(out,tc_code+v->offset,v->bytes);
    if(tc_view_change){memset(tc_view_change,0xa7,sizeof(*tc_view_change));tc_view_change=NULL;}
    if(tc_policy_change){memset(tc_policy_change,0xa7,sizeof(*tc_policy_change));tc_policy_change=NULL;}
    return 0;
}
static void code_init(void)
{
    memset(tc_code,0x5a,sizeof(tc_code));tc_code_copies=0;tc_code_ready=tc_code_retained=true;tc_code_error=0;
    tc_view_change=NULL;tc_policy_change=NULL;
    /* Poisoned prefix is outside the actual entry; original bytes stay exact. */
    for(unsigned i=64;i<80;i+=4)word(tc_code,i,0xffffffff);
    word(tc_code,80,0x0104f057);word(tc_code,84,OPENGPU_SHADER_CEASE);
}
static void snapshot_tests(void)
{
    struct opengpu_device gpu={0};struct opengpu_render_context ctx={.gpu=&gpu,.references=1,.id=7};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu,&ctx.vm));copy_gpu=&gpu;
    struct drm_opengpu_native_program_view view={.handle=71,.offset=64,.bytes=24,.entry_offset=16};
    struct opengpu_tessellation_program_profile p=policy(4);struct opengpu_native_program_snapshot out,saved;
    for(unsigned fault=0;fault<12;fault++){
        code_init();struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(o));
        struct drm_opengpu_native_program_view bad=view;struct opengpu_tessellation_program_profile q=p;
        int error=-EINVAL;
        switch(fault){case 0:bad.entry_offset=0;break;case 1:bad.entry_offset=24;break;case 2:bad.bytes=2052;break;
        case 3:bad.offset=~(u64)3;break;case 4:bad.pad=1;break;case 5:q.available=false;break;
        case 6:tc_code_ready=false;error=-EAGAIN;break;case 7:tc_code_retained=false;error=-ENOENT;break;
        case 8:tc_code_error=-EIO;error=-EIO;break;case 9:private_alloc_fail_after=0;error=-ENOMEM;break;
        case 10:word(tc_code,80,tb(0x63,0,8,0,-4));break;
        case 11:q.output_vertices=32;q.output_components=64;q.patch_components=120;break;}
        out=(struct opengpu_native_program_snapshot){0x5a,0x27};saved=out;
        assert(opengpu_tessellation_program_snapshot_append(o,&bad,&q,program_copy,tc_code,&out)==error);
        private_alloc_fail_after=-1;assert(!memcmp(&out,&saved,sizeof(out)) && !o->segments);
        assert(!opengpu_private_snapshot_retire(o));
    }
    unsigned reached=0;
    for(int allocation=0;allocation<20;allocation++){
        code_init();struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(o));
        out=(struct opengpu_native_program_snapshot){0x5a,0x27};saved=out;private_alloc_fail_after=allocation;
        int ret=opengpu_tessellation_program_snapshot_append(o,&view,&p,program_copy,tc_code,&out);private_alloc_fail_after=-1;
        if(ret)assert(ret==-ENOMEM || ret==-EINVAL);else reached=allocation;
        if(ret)assert(!memcmp(&out,&saved,sizeof(out)) && !o->segments);
        assert(!opengpu_private_snapshot_retire(o));if(!ret)break;
    }assert(reached>=4 && reached<20);
    code_init();u8 expected[24];memcpy(expected,tc_code+64,sizeof(expected));
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(o));
    tc_view_change=&view;tc_policy_change=&p;
    assert(!opengpu_tessellation_program_snapshot_append(o,&view,&p,program_copy,tc_code,&out));
    assert(tc_code_copies==1 && out.entry_offset==16);memset(tc_code,0x99,sizeof(tc_code));tc_code_retained=false;
    assert(!memcmp(o->segments->staging,expected,sizeof(expected)));
    assert(!opengpu_private_snapshot_prepare(o) && !opengpu_private_snapshot_publish(o));
    struct snapshot_segment *segment=o->segments;assert(segment->executable && segment->lease.executable);
    assert(!memcmp(segment->backing.cpu,expected,sizeof(expected)));
    const struct opengpu_buffer *l1=vm_live_l1(&ctx.vm,segment->va/MMU_SUPERPAGE_SIZE);assert(l1);
    assert((((u32 *)l1->cpu)[(segment->va%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE]&15)==(MMU_PTE_V|MMU_PTE_R|MMU_PTE_X));
    struct opengpu_vm attacker={0};assert(!opengpu_mmu_vm_create(&gpu,&attacker));
    assert(opengpu_mmu_vm_map(&gpu,&attacker,0x50000000,segment->backing.dma,4096,2)==-EACCES);
    int allocations=live_allocations;idle_error=-ETIMEDOUT;
    assert(opengpu_private_snapshot_retire(o)==-ETIMEDOUT && ctx.references==2 && live_allocations==allocations);
    idle_error=0;gpu.hw.wedged=false;asid_flush_failures=1;
    assert(opengpu_private_snapshot_retire(o)==-EIO && ctx.references==2 && live_allocations==allocations);
    gpu.hw.wedged=false;assert(!opengpu_private_snapshot_retire(o));
    code_init();view=(struct drm_opengpu_native_program_view){.handle=71,.offset=64,.bytes=24,.entry_offset=16};p=policy(4);
    o=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(o));
    assert(!opengpu_tessellation_program_snapshot_append(o,&view,&p,program_copy,tc_code,&out));
    assert(!opengpu_private_snapshot_prepare(o) && !opengpu_private_snapshot_publish(o) && !opengpu_private_snapshot_retire(o));
    opengpu_mmu_vm_destroy(&gpu,&attacker);opengpu_mmu_vm_destroy(&gpu,&ctx.vm);opengpu_mmu_fini(&gpu);
    assert(ctx.references==1 && !live_allocations && !fixture_buffers);
}
/* Optional independent producer byte input. No shader interpreter or expected
 * shader result is involved: this follows the production validator/RX path. */
static void producer_programs(const char *directory,const char **metadata)
{
    struct opengpu_device gpu={0};struct opengpu_render_context ctx={.gpu=&gpu,.references=1,.id=7};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu,&ctx.vm));copy_gpu=&gpu;
    for(unsigned stage=4;stage<=5;stage++){
        char path[4096];assert(snprintf(path,sizeof(path),"%s/native-%s.words",directory,stage==4?"tcs":"tes")<(int)sizeof(path));
        FILE *input=fopen(path,"rb");assert(input);code_init();
        size_t bytes=fread(tc_code+4,1,2048,input);assert(bytes && !(bytes&3) && !ferror(input) && fgetc(input)==EOF);assert(!fclose(input));
        word(tc_code,0,0xffffffff); /* Proves nonzero entry and exact opaque prefix. */
        struct opengpu_tessellation_program_profile p=policy(stage);
        if(stage==4){p.input_vertices=(u32)strtoul(metadata[0],NULL,10);p.input_components=(u32)strtoul(metadata[1],NULL,10);
            p.output_vertices=(u32)strtoul(metadata[2],NULL,10);p.output_components=(u32)strtoul(metadata[3],NULL,10);p.patch_components=(u32)strtoul(metadata[4],NULL,10);}
        else {p.patch_words=(u32)strtoul(metadata[5],NULL,10);p.output_components=(u32)strtoul(metadata[6],NULL,10);}
        struct drm_opengpu_native_program_view view={.handle=71,.bytes=bytes+4,.entry_offset=4};
        struct opengpu_native_program_snapshot output;struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(owner));
        int result=opengpu_tessellation_program_snapshot_append(owner,&view,&p,program_copy,tc_code,&output);
        if(result)fprintf(stderr,"actual producer stage%u rejected: %d (%zu words)\n",stage,result,bytes/4);
        assert(!result && tc_code_copies==1 && output.entry_offset==4);
        assert(!memcmp(owner->segments->staging,tc_code,bytes+4));
        assert(!opengpu_private_snapshot_prepare(owner) && !opengpu_private_snapshot_publish(owner));
        assert(!memcmp(owner->segments->backing.cpu,tc_code,bytes+4));
        assert(!opengpu_private_snapshot_retire(owner));printf("actual producer stage%u: %zu words validator/RX PASS (CPU only)\n",stage,bytes/4);
    }
    opengpu_mmu_vm_destroy(&gpu,&ctx.vm);opengpu_mmu_fini(&gpu);assert(ctx.references==1 && !live_allocations && !fixture_buffers);
}
int main(int argc,const char **argv)
{
    assert(argc==1 || argc==9);program_tests();snapshot_tests();
    if(argc==9)producer_programs(argv[1],argv+2);
    puts("TCS/TES version1 validator + exact RX snapshot: definedness/branch/patch/mask/barrier/single-point/entry/lease tests PASS (CPU only; no protected submit)");return 0;
}
