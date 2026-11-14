/* Actual typed input relocation -> opaque owner -> MMU permissions/lifetime. */
#define OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN private_input_owner_regression_main
#include "opengpu_private_snapshot_test.c"
#undef OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN
#include "../opengpu_native_input_snapshot.c"

static struct opengpu_buffer input_originals[20];
static unsigned input_resolutions;
static int input_resolve_error;
static int input_resolve(void *opaque,u32 handle,struct opengpu_native_frame_backing *out)
{
    assert(opaque==input_originals);++input_resolutions;
    if(input_resolve_error && input_resolutions==(unsigned)input_resolve_error)return -EIO;
    if(!handle || handle>=20 || !input_originals[handle].cpu)return -ENOENT;
    *out=(struct opengpu_native_frame_backing){input_originals[handle].dma,input_originals[handle].size};return 0;
}
static void typed_input(struct opengpu_native_binned_draw *draw,struct drm_opengpu_native_draw_plan *plan)
{
    memset(draw,0,sizeof(*draw));memset(plan,0,sizeof(*plan));
    u32 *d=draw->words;
    d[0]=OPENGPU_NATIVE_BINNED_DRAW_MAGIC;d[1]=2048;d[2]=1;d[3]=d[4]=d[5]=16;d[6]=d[7]=4;
    d[31]=1;d[35]=16;d[345]=16;d[346]=1;d[327]=2;
    d[311]=3;d[313]=1;d[325]=2;d[180]=4;
    for(unsigned a=0;a<16;a++) {
        d[36+a*9]=a;d[37+a*9]=4;d[42+a*9]=1;d[43+a*9]=a;
        d[binding_words[a].stride]=16;d[binding_words[a].divisor]=a&1?2:0;
        plan->bindings[a]=(struct drm_opengpu_compute_uniform_view){.handle=a+1,.offset=64,.bytes=512};
    }
    plan->vertex_scratch=(struct drm_opengpu_compute_uniform_view){.handle=19,.offset=64,.bytes=135*128*4};
}
static u32 translated_input(struct opengpu_vm *vm,u32 va,u32 permissions)
{
    const struct opengpu_buffer *l1=vm_live_l1(vm,va/MMU_SUPERPAGE_SIZE);assert(l1);
    u32 leaf=((u32 *)l1->cpu)[(va%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE];assert((leaf&15u)==permissions);
    return ((leaf>>10)<<12)+(va&4095u);
}
static struct opengpu_private_snapshot *input_owner(struct opengpu_device *gpu,
    struct opengpu_render_context *context,const struct opengpu_native_binned_draw *draw,u32 *segment)
{
    unsigned char bytes[64+2*2048+64];memset(bytes,0xa5,sizeof(bytes));
    memcpy(bytes+64,draw,2048);memcpy(bytes+64+2048,draw,2048);
    struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
    assert(!opengpu_private_snapshot_append(owner,bytes,sizeof(bytes),segment));return owner;
}
static void reject_input(struct opengpu_device *gpu,struct opengpu_render_context *context,
    struct opengpu_native_binned_draw *draw,struct drm_opengpu_native_draw_plan *plan,int error)
{
    u32 segment;struct opengpu_private_snapshot *owner=input_owner(gpu,context,draw,&segment);
    input_resolutions=0;
    assert(opengpu_native_input_snapshot_patch(owner,segment,2112,draw,plan,128,input_resolve,input_originals)==error);
    assert(!owner->resources && !gpu->mmu.snapshot_leases && !context->vm.reservations);
    for(unsigned word=0;word<512;word++)assert(read_word(owner,segment,2112+word*4)==draw->words[word]);
    assert(!opengpu_private_snapshot_retire(owner));
}
static void publish_input(struct opengpu_device *gpu,struct opengpu_render_context *context,
    struct opengpu_native_binned_draw *draw,struct drm_opengpu_native_draw_plan *plan)
{
    /* Published literal field positions independently check the generated
     * binding table, including the alignment hole in binding1/index views. */
    static const u32 fields[19][4]={
        {185,187,182,183},{194,196,190,192},{202,204,199,200},{210,212,207,208},
        {218,220,215,216},{226,228,223,224},{234,236,231,232},{242,244,239,240},
        {250,252,247,248},{258,260,255,256},{266,268,263,264},{274,276,271,272},
        {282,284,279,280},{290,292,287,288},{298,300,295,296},{306,308,303,304},
        {322,323,318,320},{500,501,503,504},{339,340,336,337}};
    u32 segment;struct opengpu_private_snapshot *owner=input_owner(gpu,context,draw,&segment);
    assert(!opengpu_native_input_snapshot_patch(owner,segment,2112,draw,plan,128,input_resolve,input_originals));
    assert(!opengpu_private_snapshot_prepare(owner));assert(!opengpu_private_snapshot_publish(owner));
    for(unsigned word=0;word<16;word++)assert(read_word(owner,segment,word*4)==0xa5a5a5a5u);
    for(unsigned word=0;word<512;word++)assert(read_word(owner,segment,64+word*4)==draw->words[word]);
    for(unsigned word=0;word<16;word++)assert(read_word(owner,segment,4160+word*4)==0xa5a5a5a5u);
    for(unsigned i=0;i<19;i++) {
        const struct drm_opengpu_compute_uniform_view *v=i<16?&plan->bindings[i]:i==16?&plan->indices:
            i==17?&plan->indirect:&plan->vertex_scratch;
        if(input_empty(v))continue;
        u32 base=read_word(owner,segment,2112+fields[i][0]*4),limit=read_word(owner,segment,2112+fields[i][1]*4),
            allocation=read_word(owner,segment,2112+fields[i][2]*4),end=read_word(owner,segment,2112+fields[i][3]*4);
        assert(translated_input(&context->vm,base,i==18?7:3)==input_originals[v->handle].dma+v->offset);
        assert((u64)limit+((u64)read_word(owner,segment,2112+(fields[i][1]+1)*4)<<32)-(u64)base==v->bytes);
        assert(allocation==(base&~63u) && end==((base+v->bytes+63u)&~63u));
        assert(!read_word(owner,segment,2112+(fields[i][3]+1)*4));
    }
    for(unsigned word=0;word<512;word++) {
        bool address=false;
        for(unsigned i=0;i<19;i++)for(unsigned f=0;f<4;f++)
            if(word==fields[i][f] || ((f==1 || f==3) && word==fields[i][f]+1))address=true;
        if(!address)assert(read_word(owner,segment,2112+word*4)==draw->words[word]);
    }
    /* Borrowed resource mappings retain their reserved VAs through held
     * publication. They do not freeze original GEM CPU contents; producer
     * reservations and application resource lifetime belong to the caller. */
    if(!input_empty(&plan->bindings[0])) {
        u32 input_va=read_word(owner,segment,2112+185*4)&~4095u;
        assert(opengpu_mmu_vm_unmap(gpu,&context->vm,input_va,4096)==-EACCES);
    }
    assert(!opengpu_private_snapshot_retire(owner));
    assert(!opengpu_mmu_vm_map(gpu,&context->vm,0x70000000,input_originals[1].dma,4096,
        2));
    assert(!opengpu_mmu_vm_unmap(gpu,&context->vm,0x70000000,4096));
}
int main(void)
{
    struct opengpu_device gpu={0};struct opengpu_render_context context={.gpu=&gpu,.references=1,.id=7};
    struct opengpu_native_binned_draw draw,saved;struct drm_opengpu_native_draw_plan plan,original;
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;assert(!opengpu_mmu_init(&gpu));
    assert(!opengpu_mmu_vm_create(&gpu,&context.vm));copy_gpu=&gpu;
    for(unsigned i=1;i<20;i++)assert(!opengpu_buffer_alloc(&gpu,&input_originals[i],i==19?73728:4096));
    typed_input(&draw,&plan);saved=draw;original=plan;
    for(unsigned kind=0;kind<25;kind++) {
        draw=saved;plan=original;
        switch(kind) {
        case 0:draw.words[327]=3;break;
        case 1:draw.words[336]=0x1000;break;
        case 2:draw.words[191]=1;break; /* published alignment hole */
        case 3:draw.words[319]=1;break;
        case 4:draw.words[178]=25;break; /* enabled high slot format */
        case 5:draw.words[177]=2;break; /* high slot enabled reserved bit */
        case 6:draw.words[172]=8;break; /* high slot components */
        case 7:draw.words[35]=15;break;
        case 8:plan.bindings[15].offset=4096;break;
        case 9:plan.bindings[15].offset=~(u64)0;break;
        case 10:plan.bindings[15].bytes=0;break;
        case 11:draw.words[313]=0xffffffffu;break;
        case 12:draw.words[180]=0xffffffffu;draw.words[325]=5;break;
        case 13:draw.words[187]=1;break;
        case 14:draw.words[499]=1;plan.indirect=(struct drm_opengpu_compute_uniform_view){.handle=18,.offset=64,.bytes=12};break;
        case 15:draw.words[317]=2;plan.indices=(struct drm_opengpu_compute_uniform_view){.handle=17,.offset=65,.bytes=32};break;
        case 16:plan.vertex_scratch.bytes-=64;break;
        case 17:draw.words[346]=2;break; /* missing point-size output array */
        case 18:plan.vertex_scratch.offset=4;break;
        case 19:draw.words[317]=3;break; /* missing active index GEM */
        case 20:draw.words[317]=1;draw.words[499]=1;break; /* arrays mode cannot read indices */
        case 21:draw.words[42]=0;break; /* unreferenced plan must be canonical empty */
        case 22:draw.words[43]=20;draw.words[37]=3;break;
        case 23:draw.words[327]=1;break; /* version1 cannot use new conversion formats */
        default:plan.bindings[0].handle=511;break;
        }
        reject_input(&gpu,&context,&draw,&plan,kind==24?-ENOENT:-EINVAL);
    }
    draw=saved;plan=original;
    input_resolve_error=16;reject_input(&gpu,&context,&draw,&plan,-EIO);input_resolve_error=0;
    for(int allocation=0;;allocation++) {
        assert(allocation<256);
        u32 segment;struct opengpu_private_snapshot *owner=input_owner(&gpu,&context,&draw,&segment);
        private_alloc_fail_after=allocation;
        int error=opengpu_native_input_snapshot_patch(owner,segment,2112,&draw,&plan,128,input_resolve,input_originals);
        assert(!error || error==-ENOMEM);
        private_alloc_fail_after=-1;
        assert(!opengpu_private_snapshot_retire(owner));
        assert(context.references==1&&!gpu.mmu.snapshot_leases&&!context.vm.reservations);
        if(!error)break;
    }
    {u32 segment;struct opengpu_private_snapshot *owner=input_owner(&gpu,&context,&draw,&segment);
     assert(opengpu_native_input_snapshot_patch(owner,segment,2113,&draw,&plan,128,input_resolve,input_originals)==-EINVAL);
     assert(opengpu_native_input_snapshot_patch(owner,segment,1ULL<<32,&draw,&plan,128,input_resolve,input_originals)==-EINVAL);
     assert(opengpu_native_input_snapshot_patch(owner,segment,2112,&draw,&plan,0,input_resolve,input_originals)==-EINVAL);
     draw.words[311]++;
     assert(opengpu_native_input_snapshot_patch(owner,segment,2112,&draw,&plan,128,input_resolve,input_originals)==-EINVAL);
     draw=saved;assert(!owner->resources);assert(!opengpu_private_snapshot_retire(owner));}
    /* Different GEM handles sharing physical input/scratch lines still alias. */
    {dma_addr_t dma=input_originals[19].dma;input_originals[19].dma=input_originals[1].dma;
     reject_input(&gpu,&context,&draw,&plan,-EINVAL);input_originals[19].dma=dma;}
    publish_input(&gpu,&context,&draw,&plan);
    draw=saved;plan=original;draw.words[327]=1;
    for(unsigned a=0;a<16;a++)draw.words[43+a*9]=a%7;
    publish_input(&gpu,&context,&draw,&plan);
    for(unsigned format=16;format<=24;format++) {
        draw=saved;plan=original;draw.words[43]=format;publish_input(&gpu,&context,&draw,&plan);
    }
    for(unsigned index=1;index<=3;index++) {
        draw=saved;plan=original;draw.words[317]=index;draw.words[326]=1;draw.words[312]=2;
        plan.indices=(struct drm_opengpu_compute_uniform_view){.handle=17,.offset=64,.bytes=512};
        publish_input(&gpu,&context,&draw,&plan);
        draw.words[499]=2;plan.indirect=(struct drm_opengpu_compute_uniform_view){.handle=18,.offset=60,.bytes=20};
        publish_input(&gpu,&context,&draw,&plan);
    }
    draw=saved;plan=original;draw.words[499]=1;
    plan.indirect=(struct drm_opengpu_compute_uniform_view){.handle=18,.offset=60,.bytes=16};
    publish_input(&gpu,&context,&draw,&plan);
    draw=saved;plan=original;draw.words[346]=2;plan.vertex_scratch.bytes+=512;
    publish_input(&gpu,&context,&draw,&plan);
    /* Physical source and logical subword end can equal exactly 4GiB. The
     * owner's independent VA allocator still supplies every resource VA. */
    draw=saved;plan=original;draw.words[43]=5;draw.words[binding_words[0].stride]=0;
    plan.bindings[0].offset=4092;plan.bindings[0].bytes=4;
    {dma_addr_t dma=input_originals[1].dma;input_originals[1].dma=0xfffff000ULL;
     publish_input(&gpu,&context,&draw,&plan);input_originals[1].dma=dma;}
    /* Empty direct work does not invent input allocations. Disabled generic
     * attributes remain constants and require no binding in nonempty work. */
    draw=saved;memset(&plan,0,sizeof(plan));draw.words[311]=0;publish_input(&gpu,&context,&draw,&plan);
    draw=saved;memset(&plan,0,sizeof(plan));for(unsigned a=0;a<16;a++)draw.words[42+a*9]=0;
    plan.vertex_scratch=original.vertex_scratch;publish_input(&gpu,&context,&draw,&plan);
    for(unsigned i=1;i<20;i++)opengpu_buffer_free(&gpu,&input_originals[i]);
    opengpu_mmu_vm_destroy(&gpu,&context.vm);opengpu_mmu_fini(&gpu);
    assert(context.references==1&&!live_allocations&&!fixture_buffers);
    puts("native input: contiguous typed GND,16bindings/25formats/U8-U32indices/indirect,actual owner/MMU RO+RW NX,guards/aliases/reject/reuse PASS (CPU only)");return 0;
}
