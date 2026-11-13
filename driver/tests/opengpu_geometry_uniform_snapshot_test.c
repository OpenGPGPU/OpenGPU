/* The real private owner and MMU; only kernel/OS primitives are modeled. */
#define OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN geometry_owner_regression_main
#include "opengpu_private_snapshot_test.c"
#undef OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN
#include "../opengpu_geometry_uniform_snapshot.c"

static u8 gs_sources[14][16640];
static unsigned gs_copies,gs_fail_copy;
static bool gs_producer_ready;
static int gs_copy_source(void *opaque,const struct drm_opengpu_compute_uniform_view *v,void *out)
{
    assert(opaque==gs_sources && gs_producer_ready && v->handle>=1 && v->handle<=13);
    assert(v->offset+v->bytes<=sizeof(gs_sources[0]));
    ++gs_copies;if(gs_fail_copy && gs_copies==gs_fail_copy)return -EIO;
    memcpy(out,gs_sources[v->handle]+v->offset,v->bytes);return 0;
}
static u8 gs_pattern(unsigned handle,unsigned offset)
{ return (u8)((handle*29u)^(offset*13u)^(offset>>8)); }
static void gs_source_init(void)
{
    for(unsigned h=1;h<=13;h++)for(unsigned i=0;i<sizeof(gs_sources[0]);i++)gs_sources[h][i]=gs_pattern(h,i);
    gs_copies=0;gs_fail_copy=0;gs_producer_ready=true;
}
static struct drm_opengpu_native_uniform_stage gs_stage_init(void)
{
    struct drm_opengpu_native_uniform_stage s={.active_mask=0xfff};
    for(unsigned b=0;b<12;b++)s.blocks[b]=(struct drm_opengpu_compute_uniform_view){
        .handle=b+1,.offset=b%3==0?4:b%3==1?64:256,.bytes=16384};
    s.defaults=(struct drm_opengpu_compute_uniform_view){.handle=13,.offset=4,.bytes=4096};
    return s;
}
static void gs_reject(struct opengpu_private_snapshot *o,struct drm_opengpu_native_uniform_stage s,int error)
{
    struct opengpu_geometry_uniform_snapshot out,saved;memset(&out,0x5a,sizeof(out));saved=out;
    void *segments=o->segments;
    assert(opengpu_geometry_uniform_snapshot_append(o,&s,7,8,9,gs_copy_source,gs_sources,&out)==error);
    assert(!memcmp(&out,&saved,sizeof(out)) && o->segments==segments);
}
static u32 gs_read(struct opengpu_private_snapshot *o,u32 segment,u32 offset)
{ u32 v;assert(!opengpu_private_snapshot_read32(o,segment,offset,&v));return v; }
static void gs_oracle(struct opengpu_private_snapshot *o,struct opengpu_render_context *ctx,
    struct opengpu_geometry_uniform_snapshot out,const struct drm_opengpu_native_uniform_stage *s)
{
    u32 base;assert(out.present && out.root_offset==0);
    assert(!opengpu_private_snapshot_address(o,out.segment,&base));
    assert(gs_read(o,out.segment,0)==0x47555202u && gs_read(o,out.segment,4)==64 && gs_read(o,out.segment,8)==2);
    assert(gs_read(o,out.segment,12)==7 && gs_read(o,out.segment,16)==8 && gs_read(o,out.segment,20)==9);
    assert(gs_read(o,out.segment,24)==3 && gs_read(o,out.segment,28)==s->active_mask);
    assert(gs_read(o,out.segment,40)==(s->active_mask?base+64:0));
    assert(gs_read(o,out.segment,44)==(s->active_mask?base+1600:0) && !gs_read(o,out.segment,48));
    assert(gs_read(o,out.segment,52)==(s->defaults.bytes?base+1600:0));
    assert(gs_read(o,out.segment,56)==!!s->defaults.bytes && !gs_read(o,out.segment,60));
    unsigned count=0;struct drm_opengpu_compute_uniform_view unique[12]={0};
    for(unsigned b=0;b<12;b++){
        unsigned slot=0;
        if(s->active_mask&(1u<<b)){
            while(slot<count && memcmp(&unique[slot],&s->blocks[b],sizeof(unique[0])))slot++;
            if(slot==count)unique[count++]=s->blocks[b];
        }
        unsigned mapping=gs_read(o,out.segment,b<6?32:36)>>(5*(b%6))&31;
        assert(mapping==slot);
    }
    for(unsigned slot=0;slot<=24;slot++){
        unsigned record=64+slot*64;
        const struct drm_opengpu_compute_uniform_view *v=slot<count?&unique[slot]:slot==24?&s->defaults:NULL;
        if(!v || !v->bytes){for(unsigned w=0;w<16;w++)assert(!gs_read(o,out.segment,record+w*4));continue;}
        assert(gs_read(o,out.segment,record)==0x47554202u);
        assert(gs_read(o,out.segment,record+4)==64 && gs_read(o,out.segment,record+8)==2);
        assert(gs_read(o,out.segment,record+12)==7 && gs_read(o,out.segment,record+16)==8 && gs_read(o,out.segment,record+20)==9);
        assert(gs_read(o,out.segment,record+24)==(slot==24?31:slot) && gs_read(o,out.segment,record+28)==8);
        u32 data=gs_read(o,out.segment,record+32),end=gs_read(o,out.segment,record+36);
        assert(!(data&255) && end-data==v->bytes && !gs_read(o,out.segment,record+40));
        assert(gs_read(o,out.segment,record+44)==data && gs_read(o,out.segment,record+48)==((end+63)&~63u));
        assert(!gs_read(o,out.segment,record+52) && gs_read(o,out.segment,record+56)==1 && !gs_read(o,out.segment,record+60));
        for(unsigned i=0;i<v->bytes;i+=4){
            u32 expected=0;for(unsigned byte=0;byte<4;byte++)expected|=(u32)gs_pattern(v->handle,v->offset+i+byte)<<(byte*8);
            assert(gs_read(o,out.segment,data-base+i)==expected);
        }
    }
    for(u64 va=base;va<(u64)base+o->segments->bytes;va+=4096){
        const struct opengpu_buffer *l1=vm_live_l1(&ctx->vm,va/MMU_SUPERPAGE_SIZE);assert(l1);
        u32 leaf=((u32 *)l1->cpu)[(va%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE];
        assert((leaf&15)==(MMU_PTE_V|MMU_PTE_R));
    }
}
static void gs_accept(struct opengpu_device *gpu,struct opengpu_render_context *ctx,
    struct drm_opengpu_native_uniform_stage s,unsigned copies)
{
    gs_source_init();struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);assert(!IS_ERR(o));
    struct opengpu_geometry_uniform_snapshot out;
    assert(!opengpu_geometry_uniform_snapshot_append(o,&s,7,8,9,gs_copy_source,gs_sources,&out));
    assert(gs_copies==copies);memset(gs_sources,0x77,sizeof(gs_sources));
    assert(!opengpu_private_snapshot_prepare(o) && !opengpu_private_snapshot_publish(o));
    gs_oracle(o,ctx,out,&s);assert(!opengpu_private_snapshot_retire(o));
}
int main(void)
{
    struct opengpu_device gpu={0};struct opengpu_render_context ctx={.gpu=&gpu,.references=1,.id=7};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu,&ctx.vm));copy_gpu=&gpu;
    struct drm_opengpu_native_uniform_stage s=gs_stage_init(),bad;
    gs_source_init();struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(o));
    bad=s;bad.pad=1;gs_reject(o,bad,-EINVAL);bad=s;bad.active_mask|=4096;gs_reject(o,bad,-EINVAL);
    for(unsigned b=0;b<12;b++){
        bad=s;bad.blocks[b].handle=0;gs_reject(o,bad,-EINVAL);
        bad=s;bad.blocks[b].bytes=16388;gs_reject(o,bad,-EINVAL);
        bad=s;bad.blocks[b].offset=2;gs_reject(o,bad,-EINVAL);
        bad=s;bad.blocks[b].offset=~(u64)3;gs_reject(o,bad,-EINVAL);
        bad=s;bad.active_mask&=~(1u<<b);gs_reject(o,bad,-EINVAL);
    }
    bad=s;bad.defaults.bytes=4100;gs_reject(o,bad,-EINVAL);assert(!gs_copies);
    struct drm_opengpu_native_uniform_stage empty={0};struct opengpu_geometry_uniform_snapshot zero={0},out;
    memset(&out,0x5a,sizeof(out));assert(!opengpu_geometry_uniform_snapshot_append(o,&empty,7,8,9,gs_copy_source,gs_sources,&out));
    assert(!memcmp(&out,&zero,sizeof(out)) && !o->segments && !gs_copies);
    for(unsigned fail=1;fail<=13;fail++){gs_copies=0;gs_fail_copy=fail;gs_reject(o,s,-EIO);assert(gs_copies==fail);}
    gs_fail_copy=0;assert(!opengpu_private_snapshot_retire(o));
    /* Every aggregate/node/relocation allocation fails in turn; retire the
     * unpublished owner, then a fresh owner completes at full capacity. */
    unsigned reached=0;
    for(int allocation=0;allocation<128;allocation++){
        gs_source_init();o=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(o));
        memset(&out,0x5a,sizeof(out));struct opengpu_geometry_uniform_snapshot saved=out;
        private_alloc_fail_after=allocation;
        int ret=opengpu_geometry_uniform_snapshot_append(o,&s,7,8,9,gs_copy_source,gs_sources,&out);
        private_alloc_fail_after=-1;
        if(ret)assert(ret==-ENOMEM && !memcmp(&out,&saved,sizeof(out)));
        else reached=allocation;
        assert(!opengpu_private_snapshot_retire(o));
        if(!ret)break;
    }
    assert(reached>50 && reached<128);
    gs_accept(&gpu,&ctx,s,13);
    for(unsigned b=1;b<12;b++)s.blocks[b]=s.blocks[0];
    gs_accept(&gpu,&ctx,s,2);
    s=gs_stage_init();s.active_mask=0;memset(s.blocks,0,sizeof(s.blocks));gs_accept(&gpu,&ctx,s,1);
    s=gs_stage_init();memset(&s.defaults,0,sizeof(s.defaults));gs_accept(&gpu,&ctx,s,12);
    s=gs_stage_init();for(unsigned b=1;b<12;b++){s.active_mask&=~(1u<<b);memset(&s.blocks[b],0,sizeof(s.blocks[b]));}
    s.blocks[0].bytes=4;memset(&s.defaults,0,sizeof(s.defaults));gs_accept(&gpu,&ctx,s,1);
    opengpu_mmu_vm_destroy(&gpu,&ctx.vm);opengpu_mmu_fini(&gpu);
    assert(ctx.references==1 && !live_allocations && !fixture_buffers);
    puts("geometry GUR2/GUB2 stage3: full12x16KiB+1024defaults, independent literal oracle, RO/NX owner/MMU, producer-copy/alloc failures/reuse PASS (CPU only)");
    return 0;
}
