#define OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN gng_owner_regression_main
#include "opengpu_private_snapshot_test.c"
#undef OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN
#include "../opengpu_geometry_uniform_snapshot.c"
#include "../opengpu_geometry_program_snapshot.c"
#include "../opengpu_geometry_snapshot.c"

static u8 gng_original[15][16640],gng_raw[1024];
static struct drm_opengpu_native_program_view gng_program;
static struct drm_opengpu_native_uniform_stage gng_uniforms;
static unsigned gng_copies,gng_copy_failure;
static bool gng_producer,gng_mutate;
static u8 gng_pattern(unsigned handle,unsigned offset)
{ return (u8)((handle*31u)+(offset*7u)+(offset>>8)); }
static void gng_literal(u8 *p,unsigned word,u32 value)
{ for(unsigned b=0;b<4;b++)p[word*4+b]=(u8)(value>>(b*8)); }
static u32 gng_instruction(unsigned function,unsigned rd,unsigned a,unsigned b)
{ return function<<25|b<<20|a<<15|rd<<7|0x0bu; }
static const u32 gng_words[]={0xffffffffu,7u<<25|5u<<7|0x0bu,33u<<25|5u<<20|0x0bu,35u<<25|0x0bu,OPENGPU_SHADER_CEASE};
static void gng_fixture(void)
{
    memset(gng_raw,0,sizeof(gng_raw));
    gng_literal(gng_raw,0,0x474e4701u);gng_literal(gng_raw,1,1024);gng_literal(gng_raw,2,1);
    gng_literal(gng_raw,3,7);gng_literal(gng_raw,4,11);gng_literal(gng_raw,5,13);
    gng_literal(gng_raw,6,6);gng_literal(gng_raw,7,32);gng_literal(gng_raw,8,2);gng_literal(gng_raw,9,16);
    gng_literal(gng_raw,10,64);gng_literal(gng_raw,11,64);gng_literal(gng_raw,13,16);gng_literal(gng_raw,14,16);gng_literal(gng_raw,15,1);
    gng_literal(gng_raw,25,0x87654321u);gng_literal(gng_raw,26,0x12345678u);
    for(unsigned i=0;i<64;i++)gng_literal(gng_raw,32+i,(i*13)%68);
    for(unsigned i=0;i<68;i++){gng_literal(gng_raw,96+i*2,1);gng_literal(gng_raw,97+i*2,(i*7)%64);}
    for(unsigned h=1;h<=14;h++)for(unsigned i=0;i<sizeof(gng_original[0]);i++)gng_original[h][i]=gng_pattern(h,i);
    for(unsigned i=0;i<5;i++)gng_literal(gng_original[14]+64,i,gng_words[i]);
    gng_program=(struct drm_opengpu_native_program_view){.handle=14,.offset=64,.bytes=20,.entry_offset=4};
    memset(&gng_uniforms,0,sizeof(gng_uniforms));gng_uniforms.active_mask=0xfff;
    for(unsigned i=0;i<12;i++)gng_uniforms.blocks[i]=(struct drm_opengpu_compute_uniform_view){
        .handle=i+1,.offset=i%3==0?4:i%3==1?64:256,.bytes=16384};
    gng_uniforms.defaults=(struct drm_opengpu_compute_uniform_view){.handle=13,.offset=4,.bytes=4096};
    gng_copies=0;gng_copy_failure=0;gng_producer=true;gng_mutate=false;
}
static int gng_copy_source(void *opaque,const struct drm_opengpu_compute_uniform_view *view,void *out)
{
    assert(opaque==gng_original && gng_producer && view->handle>=1 && view->handle<=14 &&
        view->offset+view->bytes<=sizeof(gng_original[0]));
    ++gng_copies;if(gng_copy_failure && gng_copies==gng_copy_failure)return -EIO;
    memcpy(out,gng_original[view->handle]+view->offset,view->bytes);
    if(gng_mutate){memset(gng_raw,0xff,sizeof(gng_raw));memset(&gng_program,0,sizeof(gng_program));
        memset(&gng_uniforms,0,sizeof(gng_uniforms));gng_mutate=false;}
    return 0;
}
static struct opengpu_geometry_source gng_source(void)
{
    return (struct opengpu_geometry_source){.metadata=gng_raw,.metadata_bytes=1024,.program=&gng_program,.uniforms=&gng_uniforms,
        .context=7,.pass=11,.generation=13,.topology=12,.vertex_varyings=16,.fragment_varyings=16,.geometry_enabled=true,.scalar_fpu=true};
}
static u32 gng_vm_read(struct opengpu_vm *vm,u32 va,u32 permissions)
{
    assert(!(va&3));const struct opengpu_buffer *l1=vm_live_l1(vm,va/MMU_SUPERPAGE_SIZE);assert(l1);
    u32 leaf=((u32 *)l1->cpu)[va%MMU_SUPERPAGE_SIZE/MMU_PAGE_SIZE];assert((leaf&15)==permissions);
    u32 pa=((leaf>>10)<<12)+(va&4095u);
    for(const struct fixture_buffer_record *b=fixture_buffers;b;b=b->next)if(pa>=b->dma && (u64)pa+4<=b->dma+b->size){
        const u8 *p=(const u8 *)b->cpu+pa-b->dma;
        return (u32)p[0]|(u32)p[1]<<8|(u32)p[2]<<16|(u32)p[3]<<24;
    }
    assert(!"actual GNG VA lacks physical backing");return 0;
}
static void gng_oracle(struct opengpu_private_snapshot *o,struct opengpu_render_context *ctx,u32 segment,const u8 expected[1024],bool uniforms)
{
    u32 va;assert(!opengpu_private_snapshot_address(o,segment,&va));
    for(unsigned i=0;i<256;i++){
        if(i>=16 && i<=24)continue;
        assert(gng_vm_read(&ctx->vm,va+i*4,3)==opengpu_native_geometry_word_at(expected,i));
    }
    u32 code=gng_vm_read(&ctx->vm,va+17*4,3),pc=gng_vm_read(&ctx->vm,va+16*4,3);
    assert(pc==code+4 && gng_vm_read(&ctx->vm,va+18*4,3)==code+20 && !gng_vm_read(&ctx->vm,va+19*4,3));
    assert(gng_vm_read(&ctx->vm,va+20*4,3)==code && gng_vm_read(&ctx->vm,va+21*4,3)==code+64 && !gng_vm_read(&ctx->vm,va+22*4,3));
    for(unsigned i=0;i<16;i++)assert(gng_vm_read(&ctx->vm,code+i*4,11)==(i<5?gng_words[i]:0));
    assert(gng_vm_read(&ctx->vm,va+23*4,3)==(uniforms?2u:0u));
    u32 root=gng_vm_read(&ctx->vm,va+24*4,3);
    if(!uniforms){assert(!root);return;}
    assert(gng_vm_read(&ctx->vm,root,3)==0x47555202u && gng_vm_read(&ctx->vm,root+24,3)==3);
    assert(gng_vm_read(&ctx->vm,root+28,3)==0xfff);
    u32 table=gng_vm_read(&ctx->vm,root+40,3),defaults=gng_vm_read(&ctx->vm,root+52,3);
    assert(gng_vm_read(&ctx->vm,root+44,3)==table+1536);
    for(unsigned slot=0;slot<13;slot++){
        u32 record=slot<12?table+slot*64:defaults;
        assert(gng_vm_read(&ctx->vm,record,3)==0x47554202u && gng_vm_read(&ctx->vm,record+8,3)==2);
        assert(gng_vm_read(&ctx->vm,record+24,3)==(slot<12?slot:31) && gng_vm_read(&ctx->vm,record+28,3)==8);
        u32 data=gng_vm_read(&ctx->vm,record+32,3),bytes=slot<12?16384:4096;
        unsigned offset=slot<12?(slot%3==0?4:slot%3==1?64:256):4;
        assert(gng_vm_read(&ctx->vm,record+36,3)==data+bytes);
        for(unsigned i=0;i<bytes;i+=4){u32 w=0;for(unsigned b=0;b<4;b++)w|=(u32)gng_pattern(slot+1,offset+i+b)<<(8*b);
            assert(gng_vm_read(&ctx->vm,data+i,3)==w);}
    }
}
static void gng_reject(struct opengpu_device *gpu,struct opengpu_render_context *ctx,struct opengpu_geometry_source source,int error,bool cheap)
{
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(gpu,ctx);assert(!IS_ERR(o));u32 out=0x1234abcd;
    unsigned before=gng_copies;
    assert(opengpu_geometry_snapshot_append(o,&source,gng_copy_source,gng_original,&out)==error && out==0x1234abcd);
    if(cheap)assert(gng_copies==before && !o->segments);
    assert(!opengpu_private_snapshot_retire(o));
}
int main(void)
{
    struct opengpu_device gpu={0};struct opengpu_render_context ctx={.gpu=&gpu,.references=1,.id=7};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu,&ctx.vm));copy_gpu=&gpu;
    gng_fixture();struct opengpu_geometry_source s=gng_source(),bad=s;
    bad.geometry_enabled=false;gng_reject(&gpu,&ctx,bad,-EINVAL,true);
    bad=s;bad.metadata_bytes=1020;gng_reject(&gpu,&ctx,bad,-EINVAL,true);
    bad=s;bad.context=8;gng_reject(&gpu,&ctx,bad,-EINVAL,true);
    bad=s;bad.vertex_varyings=15;gng_reject(&gpu,&ctx,bad,-EINVAL,true);
    bad=s;bad.fragment_varyings=15;gng_reject(&gpu,&ctx,bad,-EINVAL,true);
    bad=s;bad.topology=4;gng_reject(&gpu,&ctx,bad,-EINVAL,true);
    for(unsigned word=16;word<=24;word++){
        gng_fixture();gng_literal(gng_raw,word,1);gng_reject(&gpu,&ctx,s,-EINVAL,true);
    }
    for(unsigned word=27;word<256;word++){
        if(word>=32 && word<232)continue;
        gng_fixture();gng_literal(gng_raw,word,1);gng_reject(&gpu,&ctx,s,-EINVAL,true);
    }
    const unsigned corrupt[][2]={{0,0x474e4702u},{1,512},{2,2},{6,5},{7,33},{7,0},{8,3},{9,17},{9,257},
        {10,65},{11,65},{12,4},{13,17},{14,17},{15,2},{32,68},{96,3},{97,64},{98,0}};
    for(unsigned i=0;i<sizeof(corrupt)/sizeof(corrupt[0]);i++){
        gng_fixture();gng_literal(gng_raw,corrupt[i][0],corrupt[i][1]);gng_reject(&gpu,&ctx,s,-EINVAL,true);
    }
    for(unsigned fail=1;fail<=14;fail++){gng_fixture();gng_copy_failure=fail;gng_reject(&gpu,&ctx,s,-EIO,false);}
    bool success=false;unsigned allocation;
    for(allocation=0;allocation<128;allocation++){
        gng_fixture();struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(o));
        u32 out=0x1234abcd;private_alloc_fail_after=(int)allocation;
        int ret=opengpu_geometry_snapshot_append(o,&s,gng_copy_source,gng_original,&out);private_alloc_fail_after=-1;
        if(ret)assert(ret==-ENOMEM && out==0x1234abcd);else success=true;
        assert(!opengpu_private_snapshot_retire(o));if(success)break;
    }
    assert(success && allocation>60);
    struct opengpu_private_snapshot *owners[2];u32 segments[2];u8 expected[1024];
    for(unsigned n=0;n<2;n++){
        gng_fixture();memcpy(expected,gng_raw,1024);gng_mutate=n==1;
        owners[n]=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(owners[n]));
        assert(!opengpu_geometry_snapshot_append(owners[n],&s,gng_copy_source,gng_original,&segments[n]));assert(gng_copies==14);
        memset(gng_original,0x77,sizeof(gng_original));assert(!opengpu_private_snapshot_prepare(owners[n]) && !opengpu_private_snapshot_publish(owners[n]));
        gng_oracle(owners[n],&ctx,segments[n],expected,true);
    }
    assert(!opengpu_private_snapshot_retire(owners[0]));gng_oracle(owners[1],&ctx,segments[1],expected,true);
    assert(!opengpu_private_snapshot_retire(owners[1]));
    /* A program without uniform instructions has no synthetic GUR dependency. */
    gng_fixture();memset(&gng_uniforms,0,sizeof(gng_uniforms));
    gng_literal(gng_original[14]+64,1,gng_instruction(35,0,0,0));
    struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(o));u32 segment;
    assert(!opengpu_geometry_snapshot_append(o,&s,gng_copy_source,gng_original,&segment));assert(gng_copies==1);
    assert(!opengpu_private_snapshot_prepare(o) && !opengpu_private_snapshot_publish(o));u32 va;
    assert(!opengpu_private_snapshot_address(o,segment,&va));assert(!gng_vm_read(&ctx.vm,va+23*4,3) && !gng_vm_read(&ctx.vm,va+24*4,3));
    assert(!opengpu_private_snapshot_retire(o));
    opengpu_mmu_vm_destroy(&gpu,&ctx.vm);opengpu_mmu_fini(&gpu);
    assert(ctx.references==1 && !live_allocations && !fixture_buffers);
    puts("GNG1 actual RX/RO graph: full12x16KiB/1024defaults, fixed64/68 mappings, immutable metadata/code/data, dual owners, failures/reuse PASS (CPU only)");
    return 0;
}
