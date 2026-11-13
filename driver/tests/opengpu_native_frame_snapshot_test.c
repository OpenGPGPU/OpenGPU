/* Exercise actual typed resource patching with actual owner/MMU mappings. */
#define OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN private_snapshot_regression_main
#include "opengpu_private_snapshot_test.c"
#undef OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN
#include "../opengpu_native_frame.h"
#include "../opengpu_native_frame_snapshot.c"

static struct opengpu_buffer original[28];
static int resolution_failure;
static unsigned resolutions;
static int resolve_original(void *opaque,u32 handle,struct opengpu_native_frame_backing *out)
{
    assert(opaque==original);++resolutions;
    if(resolution_failure && resolutions==(unsigned)resolution_failure)return -EIO;
    if(!handle || handle>=28 || !original[handle].cpu)return -ENOENT;
    *out=(struct opengpu_native_frame_backing){original[handle].dma,original[handle].size};return 0;
}
static u32 translated(struct opengpu_vm *vm,u32 va,u32 permissions)
{
    const struct opengpu_buffer *l1=vm_live_l1(vm,va/MMU_SUPERPAGE_SIZE);
    u32 leaf;
    assert(l1);leaf=((u32 *)l1->cpu)[(va%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE];
    assert((leaf&0xfu)==permissions);return ((leaf>>10)<<12)+(va&4095u);
}
static void rejected(struct opengpu_device *gpu,struct opengpu_render_context *context,
                     struct opengpu_native_frame_encoding *e,u32 count,int expected)
{
    unsigned char states[1024]={0};u32 state,output=0xfeed1234;
    struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);
    assert(!IS_ERR(owner));assert(!opengpu_private_snapshot_append(owner,states,sizeof(states),&state));
    resolutions=0;
    assert(opengpu_native_frame_snapshot_append(owner,&e->frame,state,sizeof(states),e->resources,count,
        4,7,8,9,resolve_original,original,&output)==expected);
    assert(output==0xfeed1234 && !owner->resources && !gpu->mmu.snapshot_leases && !context->vm.reservations);
    assert(!opengpu_private_snapshot_retire(owner));
}
static void state_headers(u32 *words,u32 engines)
{
    memset(words,0,1024);
    for(unsigned h=0;h<2;++h){
        u32 *w=words+h*128;
        w[0]=0x47465301;w[1]=512;w[2]=1;w[3]=4;
        w[4]=7;w[5]=8;w[6]=9;w[7]=h;w[28]=engines;
    }
}
static void complete_rejected(struct opengpu_device *gpu,struct opengpu_render_context *context,
    const struct opengpu_native_frame_encoding *e,const u32 *words,size_t real_bytes,
    size_t claimed_bytes,u32 engines,u32 count,int expected)
{
    u32 state,output=0xfeed1234;
    struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);
    assert(!IS_ERR(owner));assert(!opengpu_private_snapshot_append(owner,words,real_bytes,&state));
    resolutions=0;
    assert(opengpu_native_frame_snapshot_append_complete(owner,&e->frame,state,claimed_bytes,e->resources,count,
        engines,7,8,9,resolve_original,original,&output)==expected);
    assert(output==0xfeed1234 && !owner->resources && !gpu->mmu.snapshot_leases && !context->vm.reservations);
    assert(!opengpu_private_snapshot_retire(owner));
}
static void complete_states(struct opengpu_device *gpu,struct opengpu_render_context *context,
                             const struct opengpu_native_frame_encoding *e,
                             const struct opengpu_native_frame_request *request)
{
    // Literal published GFS offsets, independent of the producer's tables.
    const u32 fields[4][4]={{51,52,48,49},{60,61,57,58},{66,67,63,64},{72,73,69,70}};
    u32 words[256],state,frame;
    struct opengpu_private_snapshot *owner;
    state_headers(words,4);
    for(unsigned h=0;h<2;++h)for(unsigned engine=0;engine<4;++engine){
        const u32 *f=fields[engine];const u32 addresses[6]={f[0],f[1],f[1]+1,f[2],f[3],f[3]+1};
        for(unsigned i=0;i<6;++i){
            words[h*128+addresses[i]]=1;
            complete_rejected(gpu,context,e,words,sizeof(words),sizeof(words),4,27,-EINVAL);
            words[h*128+addresses[i]]=0;
        }
    }
    for(unsigned h=0;h<2;++h)for(unsigned wrong=0;wrong<=5;++wrong)if(wrong!=4){
        words[h*128+28]=wrong;
        complete_rejected(gpu,context,e,words,sizeof(words),sizeof(words),4,27,-EINVAL);
        words[h*128+28]=4;
    }
    complete_rejected(gpu,context,e,words,512,1024,4,27,-ERANGE);
    complete_rejected(gpu,context,e,words,sizeof(words),sizeof(words),4,26,-EINVAL);
    {struct opengpu_native_frame_encoding broken=*e;broken.resources[0].role=0;
     complete_rejected(gpu,context,&broken,words,sizeof(words),sizeof(words),4,27,-EINVAL);}
    // Even unused GFS engine fields must be zero. A reduced host may not retain
    // an untrusted address in the slot that a later larger host would consume.
    state_headers(words,2);words[128+72]=64;
    complete_rejected(gpu,context,e,words,sizeof(words),sizeof(words),2,25,-EINVAL);
    state_headers(words,4);
    // Exercise every allocation gate, including failures partway through GFS
    // relocation registration. The unpublished owner must always retire cleanly.
    for(int fail=0;;++fail){
        u32 output=0xfeed1234;int ret;
        assert(fail<256);owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
        assert(!opengpu_private_snapshot_append(owner,words,sizeof(words),&state));
        private_alloc_fail_after=fail;
        ret=opengpu_native_frame_snapshot_append_complete(owner,&e->frame,state,sizeof(words),e->resources,27,
            4,7,8,9,resolve_original,original,&output);
        private_alloc_fail_after=-1;
        if(ret){assert(ret==-ENOMEM && output==0xfeed1234);assert(!opengpu_private_snapshot_retire(owner));continue;}
        frame=output;break;
    }
    assert(!opengpu_private_snapshot_prepare(owner));assert(!opengpu_private_snapshot_publish(owner));
    for(unsigned h=0;h<2;++h)for(unsigned engine=0;engine<4;++engine){
        const u32 *f=fields[engine];u32 first=read_word(owner,frame,(279+engine*3)*4);
        u32 base=read_word(owner,state,h*512+4*f[0]),end=read_word(owner,state,h*512+4*f[1]);
        u32 physical=original[request->scratch[engine].handle].dma+request->scratch[engine].offset+h*256;
        assert(base==first+h*256 && end==base+256);
        assert(read_word(owner,state,h*512+4*f[2])==base && read_word(owner,state,h*512+4*f[3])==end);
        assert(!read_word(owner,state,h*512+4*(f[1]+1)) && !read_word(owner,state,h*512+4*(f[3]+1)));
        assert(translated(&context->vm,base,7)==physical && translated(&context->vm,end-1,7)==physical+255);
    }
    for(unsigned h=0;h<2;++h)for(unsigned w=0;w<128;++w){
        bool patched=false;
        for(unsigned engine=0;engine<4;++engine){const u32 *f=fields[engine];
            patched|=w==f[0] || w==f[1] || w==f[1]+1 || w==f[2] || w==f[3] || w==f[3]+1;}
        if(!patched)assert(read_word(owner,state,h*512+w*4)==words[h*128+w]);
    }
    {u32 va;assert(!opengpu_private_snapshot_address(owner,state,&va));
     translated(&context->vm,va,3); /* Actual private GFS is RO/NX, scratch is RW/NX. */}
    assert(!opengpu_private_snapshot_retire(owner));
    assert(context->references==1 && !gpu->mmu.snapshot_leases && !context->vm.reservations);
}
int main(void)
{
    const struct opengpu_native_binned_host_caps caps={{
        0x474e4303,8,0x474e4a01,0x08000200,0xc2100800,0x08000800,0xe4908000,0x902}};
    struct opengpu_native_frame_request request={.caps=&caps,.width=4,.height=4,.samples=4,
        .color_mask=15,.context=7,.pass=8,.generation=9,.state_count=2,.scratch_stride=256,.spill_count=8};
    struct opengpu_native_frame_encoding encoded,saved;
    struct opengpu_device gpu={0};
    struct opengpu_render_context context={.gpu=&gpu,.references=1,.id=7};
    unsigned char state_bytes[1024]={0};
    u32 handle=0,state,frame,frame_va;
    struct opengpu_private_snapshot *owner;
    // Literal published word positions independently check all twelve image
    // vectors, including the nonuniform line-contained resolve entries.
    const u32 image_fields[6][4]={{210,213,207,208},{222,225,219,220},{234,237,231,232},
        {246,249,243,244},{258,261,255,256},{270,273,267,268}};
    const u32 resolve_fields[6][4]={{130,133,126,128},{143,146,140,141},{156,160,153,154},
        {170,173,167,168},{183,186,180,181},{196,199,193,194}};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu));assert(!opengpu_mmu_vm_create(&gpu,&context.vm));copy_gpu=&gpu;
#define GEM(bytes_) (struct opengpu_native_frame_gem){++handle,0,(bytes_)}
    for(unsigned i=0;i<6;++i){
        struct opengpu_native_frame_attachment *a=&request.attachments[i];
        a->format=i<4?26:(i==4?1:6);a->load_op=0;a->channel_mask=i<4?15:0;
        a->image=(struct opengpu_native_frame_image){.gem=GEM(1024),.row_pitch=i<4?256:64,.store=1};
        a->image.gem.offset=3;
        a->resolve=(struct opengpu_native_frame_image){.gem=GEM(1024),.row_pitch=64,.store=1};
        a->resolve.gem.offset=5;
    }
    request.primitives=GEM(256);request.directory=GEM(64);
    for(unsigned i=0;i<9;++i)request.pool[i]=GEM(4096);
    for(unsigned i=0;i<4;++i)request.scratch[i]=GEM(512);
#undef GEM
    assert(handle==27);
    for(unsigned i=1;i<=handle;++i){assert(!opengpu_buffer_alloc(&gpu,&original[i],4096));memset(original[i].cpu,0xa0+i,4096);}
    for(unsigned i=0;i<4;++i)request.scratch[i].offset=64;
    assert(!opengpu_native_frame_encode(&request,&encoded));assert(encoded.resource_count==27);saved=encoded;
    // Independent original-GEM, role, access and address rejection matrix.
    for(unsigned kind=0;kind<12;++kind){
        encoded=saved;
        switch(kind){
        case 0:encoded.resources[0].role=0;break;
        case 1:encoded.resources[0].index=6;break;
        case 2:encoded.resources[0].access=1;break;
        case 3:encoded.resources[0].offset=4096;break;
        case 4:encoded.resources[0].bytes=0;break;
        case 5:encoded.resources[1]=encoded.resources[0];break;
        case 6:encoded.resources[1].gem_handle=encoded.resources[0].gem_handle;break;
        case 7:encoded.frame.words[210]=0x1000;break;
        case 8:encoded.frame.words[129]=1;break; // high allocationLimit
        case 9:encoded.frame.words[293]^=1;break; // accepted context
        case 10:encoded.resources[26].index=4;break;
        default:encoded.frame.words[23]=0x1000; // initial pool limit
        }
        rejected(&gpu,&context,&encoded,27,-EINVAL);
    }
    encoded=saved;rejected(&gpu,&context,&encoded,26,-EINVAL);
    resolution_failure=5;rejected(&gpu,&context,&encoded,27,-EIO);resolution_failure=0;
    // Same physical page under two distinct handles must still fail alias checks.
    {dma_addr_t saved_dma=original[2].dma;original[2].dma=original[1].dma;
     rejected(&gpu,&context,&encoded,27,-EINVAL);original[2].dma=saved_dma;}
    complete_states(&gpu,&context,&encoded,&request);
    owner=opengpu_private_snapshot_create(&gpu,&context);assert(!IS_ERR(owner));
    assert(!opengpu_private_snapshot_append(owner,state_bytes,sizeof(state_bytes),&state));
    assert(!opengpu_native_frame_snapshot_append(owner,&encoded.frame,state,sizeof(state_bytes),encoded.resources,27,
        4,7,8,9,resolve_original,original,&frame));
    assert(!opengpu_private_snapshot_prepare(owner));assert(!opengpu_private_snapshot_publish(owner));
    assert(!opengpu_private_snapshot_address(owner,frame,&frame_va));
    mutex_lock(&gpu.hw.submit_lock);assert(!opengpu_private_snapshot_verify_locked(owner,&gpu,&context));mutex_unlock(&gpu.hw.submit_lock);
    for(unsigned i=0;i<6;++i)for(unsigned resolved=0;resolved<2;++resolved){
        const u32 *fields=resolved?resolve_fields[i]:image_fields[i];
        const struct opengpu_native_frame_gem *gem=resolved?&request.attachments[i].resolve.gem:&request.attachments[i].image.gem;
        u32 base=read_word(owner,frame,4*fields[0]),limit=read_word(owner,frame,4*fields[1]);
        u32 allocation=read_word(owner,frame,4*fields[2]),allocation_end=read_word(owner,frame,4*fields[3]);
        assert(limit-base==gem->bytes && (base&4095u)==gem->offset);
        assert(allocation==(base&~63u) && allocation_end==((limit+63)&~63u));
        assert(translated(&context.vm,base,7)==original[gem->handle].dma+gem->offset);
        assert(!read_word(owner,frame,4*(fields[1]+1)) && !read_word(owner,frame,4*(fields[3]+1)));
    }
    {u32 pool=read_word(owner,frame,19*4),end=read_word(owner,frame,25*4);
     assert(end-pool==9*4096 && read_word(owner,frame,23*4)==pool+4096);
     for(unsigned i=0;i<9;++i){assert(translated(&context.vm,pool+i*4096,7)==original[request.pool[i].handle].dma);
        if(i){assert(read_word(owner,frame,4*(29+(i-1)*3))==pool+i*4096);
              assert(read_word(owner,frame,4*(30+(i-1)*3))==pool+(i+1)*4096);}}}
    assert(!opengpu_private_snapshot_retire(owner));assert(context.references==1);
    // Hardware-legal zero initial pool still has all eight spills and no fake
    // pool-zero GEM; the initialLimit equals the first real spill's private VA.
    request.pool[0]=(struct opengpu_native_frame_gem){0};
    request.attachments[0].image.store=0;
    assert(!opengpu_native_frame_encode(&request,&encoded));
    assert(encoded.resource_count==26);owner=opengpu_private_snapshot_create(&gpu,&context);assert(!IS_ERR(owner));
    assert(!opengpu_private_snapshot_append(owner,state_bytes,sizeof(state_bytes),&state));
    assert(!opengpu_native_frame_snapshot_append(owner,&encoded.frame,state,sizeof(state_bytes),encoded.resources,26,
        4,7,8,9,resolve_original,original,&frame));
    assert(!opengpu_private_snapshot_prepare(owner));assert(!opengpu_private_snapshot_publish(owner));
    assert(translated(&context.vm,read_word(owner,frame,210*4),3)==
        original[request.attachments[0].image.gem.handle].dma+3);
    assert(read_word(owner,frame,19*4)==read_word(owner,frame,23*4));
    assert(read_word(owner,frame,25*4)-read_word(owner,frame,19*4)==8*4096);
    assert(!opengpu_private_snapshot_retire(owner));
    for(unsigned i=1;i<=handle;++i){assert(((unsigned char *)original[i].cpu)[0]==(unsigned char)(0xa0+i));opengpu_buffer_free(&gpu,&original[i]);}
    opengpu_mmu_vm_destroy(&gpu,&context.vm);opengpu_mmu_fini(&gpu);
    assert(!live_allocations && !fixture_buffers);
    puts("native typed frame snapshot: all27 roles/6image+resolve/8spills/zero-initial, actual RO state and RW NX original GEM mappings, aliases/reject/reuse PASS (C only)");
    return 0;
}
