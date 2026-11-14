/* Real encoder/private owner/MMU; only OS/GEM-copy boundary primitives modeled. */
#define OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN tess_old_owner_regression
#include "opengpu_private_snapshot_test.c"
#undef OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN
#ifndef OPENGPU_TESS_UNIFORM_SNAPSHOT_SOURCE
#define OPENGPU_TESS_UNIFORM_SNAPSHOT_SOURCE "../opengpu_tessellation_uniform_snapshot.c"
#endif
#include OPENGPU_TESS_UNIFORM_SNAPSHOT_SOURCE

static u8 tc_sources[27][16896];
static unsigned tc_copies,tc_fail_copy;
static bool tc_ready,tc_retained[27];
static struct drm_opengpu_native_uniform_stage *tc_mutate_plan;
static u8 tc_pattern(unsigned handle,unsigned offset)
{ return (u8)((handle*29u)^(offset*13u)^(offset>>8)); }
static void tc_init(void)
{
    for(unsigned h=1;h<=26;h++){
        tc_retained[h]=true;
        for(unsigned i=0;i<sizeof(tc_sources[0]);i++)tc_sources[h][i]=tc_pattern(h,i);
    }
    tc_copies=0;tc_fail_copy=0;tc_ready=true;tc_mutate_plan=NULL;
}
static int tc_copy(void *opaque,const struct drm_opengpu_compute_uniform_view *v,void *out)
{
    assert(opaque==tc_sources);
    /* These are caller preconditions, not GEM lookup/fence authority in the
     * encoder. The future ioctl must enforce them with actual reservations. */
    if(!tc_ready)return -EAGAIN;
    if(v->handle<1 || v->handle>26 || !tc_retained[v->handle])return -ENOENT;
    if(v->offset>sizeof(tc_sources[0]) || v->bytes>sizeof(tc_sources[0])-v->offset)return -ERANGE;
    ++tc_copies;if(tc_fail_copy==tc_copies)return -EIO;
    memcpy(out,tc_sources[v->handle]+v->offset,v->bytes);
    if(tc_mutate_plan){memset(tc_mutate_plan,0xa7,2*sizeof(*tc_mutate_plan));tc_mutate_plan=NULL;}
    return 0;
}
static void tc_stages(struct drm_opengpu_native_uniform_stage s[2])
{
    memset(s,0,2*sizeof(*s));
    for(unsigned stage=0;stage<2;stage++){
        s[stage].active_mask=0xfff;
        for(unsigned b=0;b<12;b++)s[stage].blocks[b]=(struct drm_opengpu_compute_uniform_view){
            .handle=1+12*stage+b,.offset=b%3==0?4:b%3==1?64:256,.bytes=16384};
        s[stage].defaults=(struct drm_opengpu_compute_uniform_view){.handle=25+stage,.offset=4,.bytes=4096};
    }
}
static u32 tc_le(const u8 *p)
{ return (u32)p[0]|(u32)p[1]<<8|(u32)p[2]<<16|(u32)p[3]<<24; }
static void tc_put(u8 *p,unsigned word_index,u32 value)
{ for(unsigned b=0;b<4;b++)p[word_index*4+b]=(u8)(value>>(b*8)); }
static bool tc_same(const struct drm_opengpu_compute_uniform_view *a,const struct drm_opengpu_compute_uniform_view *b)
{ return a->handle==b->handle && a->offset==b->offset && a->bytes==b->bytes; }
/* Independent literal ABI oracle. Walk source mappings, check all addresses
 * against disjoint aligned private payload intervals, then compare EVERY byte
 * including inactive roots/table slots/defaults/line padding. No encoder macros. */
static bool tc_oracle(const u8 *raw,size_t bytes,u32 va,
    const struct drm_opengpu_native_uniform_stage s[2])
{
    struct drm_opengpu_compute_uniform_view unique[24]={0};
    unsigned maps[2][12]={{0}},masks[24]={0},count=0;
    u8 *expected=calloc(1,bytes),*used=calloc(1,bytes);
    assert(expected && used);bool ok=bytes>=1792 && !(bytes&63);
    for(unsigned stage=0;stage<2;stage++)for(unsigned b=0;b<12;b++)if(s[stage].active_mask&(1u<<b)){
        unsigned slot;for(slot=0;slot<count && !tc_same(&unique[slot],&s[stage].blocks[b]);slot++);
        assert(slot<24);if(slot==count)unique[count++]=s[stage].blocks[b];
        maps[stage][b]=slot;masks[slot]|=1u<<(stage+4);
    }
    for(unsigned stage=0;stage<2;stage++){
        if(!s[stage].active_mask && !s[stage].defaults.bytes)continue;
        u8 *root=expected+stage*64;
        tc_put(root,0,0x47555203);tc_put(root,1,64);tc_put(root,2,3);
        tc_put(root,3,7);tc_put(root,4,8);tc_put(root,5,9);
        tc_put(root,6,stage+4);tc_put(root,7,s[stage].active_mask);
        u32 lo=0,hi=0;
        for(unsigned b=0;b<12;b++)if(b<6)lo|=maps[stage][b]<<(5*b);else hi|=maps[stage][b]<<(5*(b-6));
        tc_put(root,8,lo);tc_put(root,9,hi);
        if(s[stage].active_mask){tc_put(root,10,va+128);tc_put(root,11,va+1664);}
        if(s[stage].defaults.bytes)tc_put(root,13,va+1664+stage*64);
        tc_put(root,14,!!s[stage].defaults.bytes);
    }
    for(unsigned slot=0;ok && slot<26;slot++){
        const bool def=slot>=24;
        const struct drm_opengpu_compute_uniform_view *v=def?&s[slot-24].defaults:slot<count?&unique[slot]:NULL;
        if(!v || !v->bytes)continue;
        unsigned record=128+slot*64;
        u32 base=tc_le(raw+record+32),limit=tc_le(raw+record+36);
        u64 off=(u64)base-va;
        if(base<va || (base&255) || limit<(u64)base || (u64)limit-base!=v->bytes || off<1792 || off+v->bytes>bytes){ok=false;break;}
        unsigned rounded=(v->bytes+63)&~63u;
        if(off+rounded>bytes){ok=false;break;}
        for(unsigned i=0;i<rounded;i++)if(used[off+i])ok=false;else used[off+i]=1;
        u8 *binding=expected+record;
        tc_put(binding,0,0x47554203);tc_put(binding,1,64);tc_put(binding,2,3);
        tc_put(binding,3,7);tc_put(binding,4,8);tc_put(binding,5,9);
        tc_put(binding,6,def?31:slot);tc_put(binding,7,def?1u<<(slot-24+4):masks[slot]);
        tc_put(binding,8,base);tc_put(binding,9,limit);tc_put(binding,11,base);
        tc_put(binding,12,base+rounded);tc_put(binding,14,1);
        for(unsigned i=0;i<v->bytes;i++)expected[off+i]=tc_pattern(v->handle,v->offset+i);
    }
    if(ok)ok=!memcmp(expected,raw,bytes);
    free(expected);free(used);return ok;
}
static void tc_reject(struct opengpu_private_snapshot *o,
    const struct drm_opengpu_native_uniform_stage s[2],int error)
{
    struct opengpu_tessellation_uniform_roots out,saved;
    memset(&out,0x5a,sizeof(out));saved=out;
    assert(opengpu_tessellation_uniform_snapshot_append(o,s,7,8,9,tc_copy,tc_sources,&out)==error);
    assert(!memcmp(&out,&saved,sizeof(out)) && !o->segments);
}
static struct opengpu_private_snapshot *tc_build(struct opengpu_device *gpu,
    struct opengpu_render_context *ctx,struct drm_opengpu_native_uniform_stage s[2],
    unsigned copies,bool mutate,struct opengpu_tessellation_uniform_roots *out)
{
    struct drm_opengpu_native_uniform_stage captured[2];memcpy(captured,s,sizeof(captured));
    tc_init();struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);assert(!IS_ERR(o));
    if(mutate)tc_mutate_plan=s;
    assert(!opengpu_tessellation_uniform_snapshot_append(o,s,7,8,9,tc_copy,tc_sources,out));
    assert(tc_copies==copies);
    for(unsigned stage=0;stage<2;stage++){
        bool present=captured[stage].active_mask || captured[stage].defaults.bytes;
        assert(out->present[stage]==present && out->root_offset[stage]==(present?stage*64:0));
    }
    assert(ctx->references==2 && (!copies || (o->segments && !o->segments->backing.cpu && o->segments->staging)));
    /* Mutate all original data AFTER its stable copy, before DMA sealing. */
    memset(tc_sources,0x77,sizeof(tc_sources));memset(tc_retained,0,sizeof(tc_retained));
    if(copies){
        assert(!opengpu_private_snapshot_prepare(o) && !opengpu_private_snapshot_publish(o));
        u32 va;assert(!opengpu_private_snapshot_address(o,out->segment,&va));
        struct snapshot_segment *seg=o->segments;
        assert(seg && !seg->next && seg->id==out->segment && !seg->executable && !seg->lease.executable);
        assert(tc_oracle(seg->backing.cpu,seg->bytes,va,captured));
        for(u64 a=va;a<(u64)va+seg->bytes;a+=4096){
            const struct opengpu_buffer *l1=vm_live_l1(&ctx->vm,a/MMU_SUPERPAGE_SIZE);assert(l1);
            u32 leaf=((u32 *)l1->cpu)[(a%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE];
            assert((leaf&15)==(MMU_PTE_V|MMU_PTE_R));
        }
        /* Every metadata word, every data boundary and padding byte matters.
         * This catches wrong-stage/version/limit33/reserved/map/mask encoders. */
        u8 *raw=seg->backing.cpu;
        for(unsigned w=0;w<448;w++){
            raw[w*4]^=1;assert(!tc_oracle(raw,seg->bytes,va,captured));raw[w*4]^=1;
        }
        raw[seg->bytes-1]^=1;assert(!tc_oracle(raw,seg->bytes,va,captured));raw[seg->bytes-1]^=1;
    }else assert(!o->segments);
    return o;
}
static void tc_accept(struct opengpu_device *gpu,struct opengpu_render_context *ctx,
    struct drm_opengpu_native_uniform_stage s[2],unsigned copies,bool mutate)
{
    struct opengpu_tessellation_uniform_roots out;
    struct opengpu_private_snapshot *o=tc_build(gpu,ctx,s,copies,mutate,&out);
    assert(!opengpu_private_snapshot_retire(o));assert(ctx->references==1);
}
int main(void)
{
    /* Existing GUR1/MMU/lease tests execute without changing their helper. */
    assert(!tess_old_owner_regression());
    struct opengpu_device gpu={0};struct opengpu_render_context ctx={.gpu=&gpu,.references=1,.id=7};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu,&ctx.vm));copy_gpu=&gpu;
    struct drm_opengpu_native_uniform_stage s[2],bad[2];tc_stages(s);tc_init();
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(o));
    for(unsigned stage=0;stage<2;stage++){
        memcpy(bad,s,sizeof(bad));bad[stage].pad=1;tc_reject(o,bad,-EINVAL);
        memcpy(bad,s,sizeof(bad));bad[stage].active_mask|=4096;tc_reject(o,bad,-EINVAL);
        for(unsigned b=0;b<12;b++)for(unsigned kind=0;kind<7;kind++){
            memcpy(bad,s,sizeof(bad));struct drm_opengpu_compute_uniform_view *v=&bad[stage].blocks[b];
            switch(kind){case 0:v->handle=0;break;case 1:v->bytes=0;break;case 2:v->bytes=16388;break;
            case 3:v->offset=2;break;case 4:v->offset=~(u64)3;break;case 5:v->bytes=7;break;
            case 6:bad[stage].active_mask&=~(1u<<b);break;}
            tc_reject(o,bad,-EINVAL);
        }
        for(unsigned kind=0;kind<6;kind++){
            memcpy(bad,s,sizeof(bad));struct drm_opengpu_compute_uniform_view *v=&bad[stage].defaults;
            switch(kind){case 0:v->handle=0;break;case 1:v->bytes=0;break;case 2:v->bytes=4100;break;
            case 3:v->offset=2;break;case 4:v->offset=~(u64)3;break;case 5:v->bytes=7;break;}
            tc_reject(o,bad,-EINVAL);
        }
    }
    assert(!tc_copies);
    tc_ready=false;tc_reject(o,s,-EAGAIN);assert(!tc_copies);tc_ready=true;
    tc_retained[1]=false;tc_reject(o,s,-ENOENT);assert(!tc_copies);tc_retained[1]=true;
    memcpy(bad,s,sizeof(bad));bad[0].blocks[0].offset=16896;tc_reject(o,bad,-ERANGE);assert(!tc_copies);
    for(unsigned fail=1;fail<=26;fail++){
        tc_init();tc_fail_copy=fail;tc_reject(o,s,-EIO);assert(tc_copies==fail);
    }
    assert(!opengpu_private_snapshot_retire(o));
    unsigned reached=0;
    for(int allocation=0;allocation<160;allocation++){
        tc_init();o=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(o));
        struct opengpu_tessellation_uniform_roots out,saved;memset(&out,0x5a,sizeof(out));saved=out;
        private_alloc_fail_after=allocation;
        int ret=opengpu_tessellation_uniform_snapshot_append(o,s,7,8,9,tc_copy,tc_sources,&out);
        private_alloc_fail_after=-1;
        if(ret)assert(ret==-ENOMEM && !memcmp(&out,&saved,sizeof(out)));else reached=allocation;
        assert(!opengpu_private_snapshot_retire(o));if(!ret)break;
    }
    assert(reached>100 && reached<160);
    tc_accept(&gpu,&ctx,s,26,true);tc_stages(s);
    for(unsigned b=0;b<12;b++)s[1].blocks[b]=s[0].blocks[11-b];
    /* Defaults remain separate even if their original view is identical. */
    s[1].defaults=s[0].defaults;tc_accept(&gpu,&ctx,s,14,false);
    tc_stages(s);for(unsigned b=1;b<12;b++)s[0].blocks[b]=s[0].blocks[0];
    tc_accept(&gpu,&ctx,s,15,false);
    /* Same original GEM: distinct offsets/sizes MUST NOT merge; exact dup does. */
    memset(s,0,sizeof(s));s[0].active_mask=7;s[1].active_mask=1;
    s[0].blocks[0]=(struct drm_opengpu_compute_uniform_view){.handle=1,.offset=4,.bytes=16};
    s[0].blocks[1]=s[0].blocks[0];s[0].blocks[1].offset=8;
    s[0].blocks[2]=s[0].blocks[0];s[0].blocks[2].bytes=8;s[1].blocks[0]=s[0].blocks[0];
    tc_accept(&gpu,&ctx,s,3,false);
    for(unsigned stage=0;stage<2;stage++){
        memset(s,0,sizeof(s));s[stage].defaults=(struct drm_opengpu_compute_uniform_view){.handle=25,.offset=4,.bytes=4096};
        tc_accept(&gpu,&ctx,s,1,true);
        memset(s,0,sizeof(s));s[stage].active_mask=1u<<11;
        s[stage].blocks[11]=(struct drm_opengpu_compute_uniform_view){.handle=1,.offset=4,.bytes=4};
        tc_accept(&gpu,&ctx,s,1,false);
    }
    memset(s,0,sizeof(s));tc_accept(&gpu,&ctx,s,0,false);
    /* Real PA lease exclusion, R-NX proof, retirement failure quarantine. */
    tc_stages(s);struct opengpu_tessellation_uniform_roots out;
    o=tc_build(&gpu,&ctx,s,26,false,&out);struct snapshot_segment *seg=o->segments;
    struct opengpu_vm attacker={0};assert(!opengpu_mmu_vm_create(&gpu,&attacker));
    assert(opengpu_mmu_vm_map(&gpu,&attacker,0x50000000,seg->backing.dma,4096,2)==-EACCES);
    assert(opengpu_mmu_vm_map_code_snapshot(&gpu,&attacker,0x50000000,seg->backing.dma,4096,2)==-EACCES);
    mutex_lock(&gpu.hw.submit_lock);assert(!opengpu_private_snapshot_verify_locked(o,&gpu,&ctx));mutex_unlock(&gpu.hw.submit_lock);
    int allocations=live_allocations;idle_error=-ETIMEDOUT;
    assert(opengpu_private_snapshot_retire(o)==-ETIMEDOUT && ctx.references==2 && live_allocations==allocations);
    idle_error=0;gpu.hw.wedged=false;asid_flush_failures=1;
    assert(opengpu_private_snapshot_retire(o)==-EIO && ctx.references==2 && live_allocations==allocations);
    gpu.hw.wedged=false;assert(!opengpu_private_snapshot_retire(o));
    /* A pre-existing writer alias blocks PA sealing BEFORE any private copy.
     * Preserve all staging/backing/context until the foreign alias is revoked. */
    tc_init();tc_stages(s);o=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(o));
    assert(!opengpu_tessellation_uniform_snapshot_append(o,s,7,8,9,tc_copy,tc_sources,&out));
    assert(!opengpu_mmu_vm_map(&gpu,&attacker,0x50000000,0x78000000,4096,2));
    assert(!opengpu_mmu_vm_unmap(&gpu,&attacker,0x50000000,4096));
    assert(!opengpu_mmu_vm_map(&gpu,&attacker,0x50000000,next_dma,4096,2));
    assert(opengpu_private_snapshot_prepare(o)==-EACCES && o->segments->staging);
    allocations=live_allocations;
    assert(opengpu_private_snapshot_retire(o)==-EACCES && ctx.references==2 && live_allocations==allocations);
    gpu.hw.wedged=false;
    assert(!opengpu_mmu_vm_unmap(&gpu,&attacker,0x50000000,4096));
    assert(!opengpu_private_snapshot_retire(o));
    opengpu_mmu_vm_destroy(&gpu,&attacker);tc_stages(s);tc_accept(&gpu,&ctx,s,26,false);
    opengpu_mmu_vm_destroy(&gpu,&ctx.vm);opengpu_mmu_fini(&gpu);
    assert(ctx.references==1 && !live_allocations && !fixture_buffers);
    puts("TCS/TES GUR3/GUB3: full24x16KiB+2x1024defaults, literal all-byte oracle, exact-view dedup, actual R-NX owner/MMU/alias/retirement PASS (CPU only; no protected submit)");
    return 0;
}
