/* Compose the actual private owner with the actual MMU/map/lease algorithms.
 * Reuse only kernel primitive mocks from the existing MMU fixture. */
#define main mmu_regression_main
#include "opengpu_mmu_test.c"
#undef main
#define OPENGPU_SCHEDULER_H
#define OPENGPU_MMU_PAGE_SIZE 4096u
struct opengpu_render_context {
    struct opengpu_device *gpu;
    struct opengpu_vm vm;
    unsigned references;
    u32 id;
#ifdef OPENGPU_PRIVATE_SNAPSHOT_CONTEXT_EXTRA
    OPENGPU_PRIVATE_SNAPSHOT_CONTEXT_EXTRA
#endif
};
static void opengpu_context_get(struct opengpu_render_context *c)
{ assert(c->references); ++c->references; }
static void opengpu_context_put(struct opengpu_render_context *c)
{ assert(c->references > 1); --c->references; }
#define OPENGPU_DRAW_WAIT_MS 10u
#define READ_ONCE(x) (x)
#define msecs_to_jiffies(x) (x)
#define time_after(a,b) ((long)((a)-(b)) > 0)
static unsigned long jiffies;
static void opengpu_hw_progress_tick(struct opengpu_device *gpu)
{ assert(!gpu->hw.submit_lock.held); ++jiffies;
#ifdef OPENGPU_PRIVATE_SNAPSHOT_PROGRESS
  OPENGPU_PRIVATE_SNAPSHOT_PROGRESS(gpu);
#endif
}
static void usleep_range(unsigned minimum, unsigned maximum)
{ assert(minimum==100 && maximum==200); }
#include "snapshot_submission_under_test.inc"
static unsigned invalidations;
static int invalidate_failure;
static int opengpu_hw_invalidate_buffer_locked(struct opengpu_device *gpu,
    const struct opengpu_buffer *buffer)
{
    struct opengpu_snapshot_lease *lease;
    assert(gpu->hw.submit_lock.held && buffer->cpu && buffer->size);
    for (lease=gpu->mmu.snapshot_leases; lease; lease=lease->next)
        if (lease->active && lease->mapped && lease->base==buffer->dma && lease->size==buffer->size)break;
    assert(lease);++invalidations;
    if(invalidate_failure && invalidations==(unsigned)invalidate_failure)return -EIO;
#ifdef OPENGPU_PRIVATE_SNAPSHOT_INVALIDATE
    return OPENGPU_PRIVATE_SNAPSHOT_INVALIDATE(gpu,buffer);
#else
    return 0;
#endif
}
static struct opengpu_device *copy_gpu;
static unsigned sealed_dma_copies;
static void *snapshot_copy(void *destination,const void *source,size_t bytes);
#pragma push_macro("memcpy")
#undef memcpy
#define memcpy snapshot_copy
#include "../opengpu_private_snapshot.c"
#pragma pop_macro("memcpy")
static void *snapshot_copy(void *destination,const void *source,size_t bytes)
{
    struct fixture_buffer_record *record;
    for(record=fixture_buffers;record;record=record->next)if(record->cpu==destination){
        struct opengpu_snapshot_lease *lease;
        assert(copy_gpu && copy_gpu->hw.submit_lock.held && !idle_error && bytes==record->size);
        for(lease=copy_gpu->mmu.snapshot_leases;lease;lease=lease->next)
            if(lease->active && lease->base==record->dma && lease->size==record->size)break;
        assert(lease && !lease->mapped);++sealed_dma_copies;
        break;
    }
    return memcpy(destination,source,bytes);
}

typedef uint8_t u8;
#include "../opengpu_native_uniform_snapshot.c"

static unsigned uniform_copies;
static int uniform_copy_failure;
static unsigned char uniform_sources[27][16384+64];
static int copy_uniform(void *opaque,const struct drm_opengpu_compute_uniform_view *view,void *destination)
{
    assert(opaque==uniform_sources && view->handle>=1 && view->handle<=26);
    assert(view->offset+view->bytes<=sizeof(uniform_sources[0]));
    ++uniform_copies;
    if(uniform_copy_failure && uniform_copies==(unsigned)uniform_copy_failure)return -EIO;
    memcpy(destination,uniform_sources[view->handle]+view->offset,view->bytes);
    return 0;
}
static u32 read_word(struct opengpu_private_snapshot *owner,u32 segment,u32 offset)
{
    u32 value=0;assert(!opengpu_private_snapshot_read32(owner,segment,offset,&value));return value;
}
static void uniform_snapshot_test(struct opengpu_device *gpu,struct opengpu_render_context *context)
{
    struct drm_opengpu_native_uniform_stage stages[2]={0};
    struct opengpu_native_uniform_roots roots,unchanged;
    struct opengpu_private_snapshot *owner;
    u32 address;
    for(unsigned handle=1;handle<=26;++handle)for(unsigned i=0;i<sizeof(uniform_sources[0]);++i)
        uniform_sources[handle][i]=(unsigned char)(handle*17+i);
    for(unsigned stage=0;stage<2;++stage){
        stages[stage].active_mask=0xfff;
        for(unsigned block=0;block<12;++block){
            stages[stage].blocks[block].handle=1+stage*12+block;
            stages[stage].blocks[block].offset=block%2?4:64;
            stages[stage].blocks[block].bytes=16384;
        }
        stages[stage].defaults.handle=25+stage;stages[stage].defaults.offset=4;stages[stage].defaults.bytes=4096;
    }
    owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
    uniform_copies=0;memset(&roots,0x5a,sizeof(roots));unchanged=roots;private_alloc_fail_after=0;
    assert(opengpu_native_uniform_snapshot_append(owner,stages,7,8,9,copy_uniform,uniform_sources,&roots)==-ENOMEM);
    assert(!uniform_copies && !owner->segments && !memcmp(&roots,&unchanged,sizeof(roots)));
    private_alloc_fail_after=-1;
    uniform_copies=0;uniform_copy_failure=7;
    assert(opengpu_native_uniform_snapshot_append(owner,stages,7,8,9,copy_uniform,uniform_sources,&roots)==-EIO);
    assert(uniform_copies==7 && !owner->segments && !memcmp(&roots,&unchanged,sizeof(roots)));
    uniform_copy_failure=0;uniform_copies=0;
    assert(!opengpu_native_uniform_snapshot_append(owner,stages,7,8,9,copy_uniform,uniform_sources,&roots));
    assert(uniform_copies==26 && roots.present[0] && roots.present[1] && roots.root_offset[0]==0 && roots.root_offset[1]==64);
    assert(owner->segments && !owner->segments->next && owner->segments->bytes>=24*16384+2*4096);
    assert(!opengpu_private_snapshot_prepare(owner));assert(!opengpu_private_snapshot_publish(owner));
    assert(!opengpu_private_snapshot_address(owner,roots.segment,&address));
    for(unsigned stage=0;stage<2;++stage){
        unsigned root=stage*64;
        assert(read_word(owner,roots.segment,root)==0x47555201);
        assert(read_word(owner,roots.segment,root+12)==7 && read_word(owner,roots.segment,root+16)==8 && read_word(owner,roots.segment,root+20)==9);
        assert(read_word(owner,roots.segment,root+24)==stage && read_word(owner,roots.segment,root+28)==0xfff);
        assert(read_word(owner,roots.segment,root+40)==address+128);
        assert(read_word(owner,roots.segment,root+44)==address+1664 && read_word(owner,roots.segment,root+48)==0);
        assert(read_word(owner,roots.segment,root+52)==address+1664+stage*64 && read_word(owner,roots.segment,root+56)==1);
    }
    for(unsigned slot=0;slot<26;++slot){
        unsigned record=slot<24?128+slot*64:1664+(slot-24)*64;
        u32 base=read_word(owner,roots.segment,record+32),limit=read_word(owner,roots.segment,record+36);
        unsigned handle=slot+1,offset=slot<24?(slot%12%2?4:64):4;
        unsigned bytes=slot<24?16384:4096;
        assert(read_word(owner,roots.segment,record)==0x47554201);
        assert(read_word(owner,roots.segment,record+24)==(slot<24?slot:31));
        assert(read_word(owner,roots.segment,record+28)==(slot<12 || slot==24?1:2));
        assert(!(base&255) && limit-base==bytes && read_word(owner,roots.segment,record+40)==0);
        assert(read_word(owner,roots.segment,record+44)==base && read_word(owner,roots.segment,record+48)==limit);
        assert(read_word(owner,roots.segment,record+56)==1 && read_word(owner,roots.segment,record+60)==0);
        for(unsigned i=0;i<bytes;i+=4){
            u32 expected=0;for(unsigned b=0;b<4;++b)expected|=(u32)uniform_sources[handle][offset+i+b]<<(8*b);
            assert(read_word(owner,roots.segment,base-address+i)==expected);
        }
    }
    memset(uniform_sources,0,sizeof(uniform_sources));
    assert(read_word(owner,roots.segment,read_word(owner,roots.segment,160)-address)!=0);
    assert(!opengpu_private_snapshot_retire(owner));
    // Cross-stage identical original views share all twelve slots, while the
    // independent default bindings retain stage-specific masks and addresses.
    for(unsigned block=0;block<12;++block)stages[1].blocks[block]=stages[0].blocks[11-block];
    stages[1].defaults=stages[0].defaults;
    owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));uniform_copies=0;
    assert(!opengpu_native_uniform_snapshot_append(owner,stages,7,8,9,copy_uniform,uniform_sources,&roots));
    assert(uniform_copies==14);assert(!opengpu_private_snapshot_prepare(owner));
    for(unsigned slot=0;slot<12;++slot)assert(read_word(owner,roots.segment,128+slot*64+28)==3);
    assert(read_word(owner,roots.segment,64+32)==((11u)|(10u<<5)|(9u<<10)|(8u<<15)|(7u<<20)|(6u<<25)));
    assert(read_word(owner,roots.segment,64+36)==((5u)|(4u<<5)|(3u<<10)|(2u<<15)|(1u<<20)));
    assert(!opengpu_private_snapshot_retire(owner));
    memset(stages,0,sizeof(stages));owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
    stages[1].blocks[11].handle=1;uniform_copies=0;
    assert(opengpu_native_uniform_snapshot_append(owner,stages,7,8,9,copy_uniform,uniform_sources,&roots)==-EINVAL);
    assert(!uniform_copies && !owner->segments);stages[1].blocks[11].handle=0;
    assert(!opengpu_native_uniform_snapshot_append(owner,stages,7,8,9,copy_uniform,uniform_sources,&roots));
    assert(!roots.present[0] && !roots.present[1] && !owner->segments);
    assert(!opengpu_private_snapshot_retire(owner));
    puts("native uniform snapshot: full 24x16KiB + 2x4096 defaults, real RO leases/relocations, source4B/cross-stage/producer failure/reuse PASS (C only)");
}


static struct opengpu_private_snapshot *new_owner(struct opengpu_device *gpu,
    struct opengpu_render_context *context, u32 *metadata, u32 *data)
{
    unsigned char words[64];
    const u32 code[2] = {0x00700513u,0x00100073u};
    u32 code_id = 0xdeadbeef;
    struct opengpu_private_snapshot *owner;
    memset(words, 0x5a, sizeof(words));
    owner = opengpu_private_snapshot_create(gpu, context);
    assert(!IS_ERR(owner));
    assert(!opengpu_private_snapshot_append(owner, words, sizeof(words), metadata));
    words[16] = 0xff;
    assert(!opengpu_private_snapshot_append(owner, words, sizeof(words), data));
    assert(opengpu_private_snapshot_append_code(owner, code, 5, &code_id)==-EINVAL);
    assert(code_id==0xdeadbeef);
    assert(!opengpu_private_snapshot_append_code(owner, code, sizeof(code), &code_id));
    assert(!opengpu_private_snapshot_relocate(owner, *metadata, 12, code_id, 0,
                                             OPENGPU_PRIVATE_SNAPSHOT_VA32));
    assert(!opengpu_private_snapshot_relocate(owner, *metadata, 0, *data, 4,
                                             OPENGPU_PRIVATE_SNAPSHOT_VA32));
    assert(!opengpu_private_snapshot_relocate(owner, *metadata, 4, *data, sizeof(words),
                                             OPENGPU_PRIVATE_SNAPSHOT_LIMIT33));
    return owner;
}

static void simultaneous_owner_test(struct opengpu_device *gpu,
                                    struct opengpu_render_context *context)
{
    struct opengpu_private_snapshot *old, *fresh, *failed;
    struct drm_opengpu_native_uniform_stage stages[2]={0};
    struct opengpu_native_uniform_roots old_uniforms,fresh_uniforms;
    u32 old_segment, fresh_segment, old_va, fresh_va, value;
    u32 source[4]={0x12345678,0x89abcdef,0,0};
    stages[0].active_mask=1;
    stages[0].blocks[0]=(struct drm_opengpu_compute_uniform_view){.offset=64,.handle=1,.bytes=16};
    stages[0].defaults=(struct drm_opengpu_compute_uniform_view){.offset=4,.handle=25,.bytes=8};
    word(uniform_sources[1],64,0x11223344);word(uniform_sources[25],4,0xaabbccdd);
    old=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(old));
    assert(!opengpu_private_snapshot_append(old,source,sizeof(source),&old_segment));
    assert(!opengpu_native_uniform_snapshot_append(old,stages,7,8,9,copy_uniform,uniform_sources,&old_uniforms));
    assert(!opengpu_private_snapshot_prepare(old));assert(!opengpu_private_snapshot_publish(old));
    assert(!opengpu_private_snapshot_address(old,old_segment,&old_va));
    failed=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(failed));
    assert(!opengpu_private_snapshot_append(failed,source,sizeof(source),&fresh_segment));
    mmu_fail_private_maps=1;assert(opengpu_private_snapshot_prepare(failed)==-ENOMEM);
    assert(!opengpu_private_snapshot_retire(failed));
    mutex_lock(&gpu->hw.submit_lock);assert(!opengpu_private_snapshot_verify_locked(old,gpu,context));mutex_unlock(&gpu->hw.submit_lock);
    assert(context->references==2);
    source[0]=0xfedcba98;source[1]=0x76543210;
    word(uniform_sources[1],64,0x55667788);word(uniform_sources[25],4,0xdeadbeef);
    fresh=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(fresh));
    assert(!opengpu_private_snapshot_append(fresh,source,sizeof(source),&fresh_segment));
    assert(!opengpu_native_uniform_snapshot_append(fresh,stages,7,8,9,copy_uniform,uniform_sources,&fresh_uniforms));
    assert(!opengpu_private_snapshot_prepare(fresh));assert(!opengpu_private_snapshot_publish(fresh));
    assert(!opengpu_private_snapshot_address(fresh,fresh_segment,&fresh_va));
    assert(old_va!=fresh_va && old->segments->lease.active && fresh->segments->lease.active);
    assert(old->segments->mapped && fresh->segments->mapped && context->references==3);
    assert(old->segments->backing.dma!=fresh->segments->backing.dma);
    assert(read_word(old,old_uniforms.segment,1792)==0x11223344);
    assert(read_word(fresh,fresh_uniforms.segment,1792)==0x55667788);
    assert(read_word(old,old_uniforms.segment,2048)==0xaabbccdd);
    assert(read_word(fresh,fresh_uniforms.segment,2048)==0xdeadbeef);
    assert(!opengpu_private_snapshot_read32(old,old_segment,0,&value) && value==0x12345678);
    assert(!opengpu_private_snapshot_read32(fresh,fresh_segment,0,&value) && value==0xfedcba98);
    assert(!opengpu_private_snapshot_read32(old,old_segment,4,&value) && value==0x89abcdef);
    assert(!opengpu_private_snapshot_read32(fresh,fresh_segment,4,&value) && value==0x76543210);
    mutex_lock(&gpu->hw.submit_lock);
    assert(!opengpu_private_snapshot_verify_locked(old,gpu,context));
    assert(!opengpu_private_snapshot_verify_locked(fresh,gpu,context));
    mutex_unlock(&gpu->hw.submit_lock);
    assert(opengpu_hw_snapshot_submission_lock(gpu)==-ETIMEDOUT);
    assert(!gpu->hw.submit_lock.held);
    assert(!opengpu_private_snapshot_retire(old));
    assert(context->references==2 && fresh->segments->lease.active && fresh->segments->mapped);
    mutex_lock(&gpu->hw.submit_lock);
    assert(!opengpu_private_snapshot_verify_locked(fresh,gpu,context));
    mutex_unlock(&gpu->hw.submit_lock);
    assert(opengpu_hw_snapshot_submission_lock(gpu)==-ETIMEDOUT);
    assert(!opengpu_private_snapshot_retire(fresh));
    assert(context->references==1 && !gpu->mmu.snapshot_leases);
    assert(!opengpu_hw_snapshot_submission_lock(gpu));mutex_unlock(&gpu->hw.submit_lock);
    puts("private native owners: simultaneous old/new copied pages, real leases/VA, independent retirement and ordinary writer deferral PASS (C only)");
}

static void native_root_view_test(struct opengpu_device *gpu,
                                  struct opengpu_render_context *context)
{
    unsigned char packet[1024]={0};
    const u32 header[]={0x474e4a01,512,1,16,16,16,4,4};
    struct opengpu_private_snapshot *owner;
    u32 segment,address;
    for(unsigned kind=0;kind<10;++kind){
        memset(packet,0,sizeof(packet));
        for(unsigned i=0;i<8;++i)word(packet,i*4,header[i]);
        word(packet,24*4,context->id);word(packet,25*4,9);word(packet,26*4,8);
        switch(kind){
        case 0:word(packet,0,0);break;case 1:word(packet,4,1024);break;
        case 2:word(packet,8,2);break;case 3:word(packet,12,15);break;
        case 4:word(packet,24*4,context->id+1);break;case 5:word(packet,8*4,1);break;
        case 6:word(packet,27*4,1);break;
        }
        owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
        if(kind==8)assert(!opengpu_private_snapshot_append_code(owner,packet,512,&segment));
        else assert(!opengpu_private_snapshot_append(owner,packet,kind==7?1024:512,&segment));
        address=0xdeadbeef;
        assert(opengpu_private_snapshot_native_job_address(owner,segment,&address)==-EINVAL && address==0xdeadbeef);
        assert(!opengpu_private_snapshot_prepare(owner));assert(!opengpu_private_snapshot_publish(owner));
        if(kind==9){assert(!opengpu_private_snapshot_native_job_address(owner,segment,&address));assert(address==owner->segments->va);}
        else assert(opengpu_private_snapshot_native_job_address(owner,segment,&address)==-EINVAL && address==0xdeadbeef);
        assert(!opengpu_private_snapshot_retire(owner));
    }
    puts("native GNJ view: exact RO/NX envelope, immutable accepted identity, canonical header/padding and failure output ownership PASS (C only)");
}

static void resource_owner_test(struct opengpu_device *gpu,
                                struct opengpu_render_context *context)
{
    struct opengpu_buffer first={0}, last={0};
    struct opengpu_private_snapshot *owner;
    struct snapshot_resource *resource;
    u32 metadata, id, value, address;
    unsigned char descriptor[64]={0};
    u32 *leaf, saved;
    int allocations;
    assert(!opengpu_buffer_alloc(gpu,&first,MMU_PAGE_SIZE));
    assert(!opengpu_buffer_alloc(gpu,&last,MMU_PAGE_SIZE));
    memset(first.cpu,0x71,first.size);memset(last.cpu,0x39,last.size);
    owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
    assert(!opengpu_private_snapshot_append(owner,descriptor,sizeof(descriptor),&metadata));
    assert(!opengpu_private_snapshot_resource(owner,4*MMU_PAGE_SIZE,&id));
    assert(!opengpu_private_snapshot_resource_map(owner,id,0,first.dma,first.size,true));
    assert(!opengpu_private_snapshot_resource_map(owner,id,3*MMU_PAGE_SIZE,last.dma,last.size,false));
    assert(opengpu_private_snapshot_resource_map(owner,id,0,last.dma,last.size,false)==-EINVAL);
    assert(opengpu_private_snapshot_resource_map(owner,id,1,last.dma,last.size,false)==-EINVAL);
    assert(opengpu_private_snapshot_resource_map(owner,id,4*MMU_PAGE_SIZE,last.dma,last.size,false)==-ERANGE);
    assert(opengpu_private_snapshot_resource_map(owner,id,0,1ULL<<32,last.size,false)==-EINVAL);
    assert(!opengpu_private_snapshot_relocate_resource(owner,metadata,0,id,64,OPENGPU_PRIVATE_SNAPSHOT_VA32));
    assert(!opengpu_private_snapshot_relocate_resource(owner,metadata,4,id,4*MMU_PAGE_SIZE,OPENGPU_PRIVATE_SNAPSHOT_LIMIT33));
    assert(opengpu_private_snapshot_relocate_resource(owner,metadata,8,id,0,OPENGPU_PRIVATE_SNAPSHOT_VA32)==-EINVAL);
    assert(!context->vm.reservations && !gpu->mmu.snapshot_leases);
    // No DMA-backed descriptor or mapping exists while producer waits run.
    assert(!opengpu_hw_snapshot_submission_lock(gpu));mutex_unlock(&gpu->hw.submit_lock);
    assert(!opengpu_private_snapshot_prepare(owner));
    assert(!opengpu_private_snapshot_publish(owner));
    resource=owner->resources;address=(u32)resource->reservation.va;
    assert(!opengpu_private_snapshot_read32(owner,metadata,0,&value) && value==address+64);
    assert(!opengpu_private_snapshot_read32(owner,metadata,4,&value) && value==address+4*MMU_PAGE_SIZE);
    assert(!opengpu_private_snapshot_read32(owner,metadata,8,&value) && !value);
    assert(opengpu_mmu_vm_map(gpu,&context->vm,address+MMU_PAGE_SIZE,last.dma,last.size,2)==-EACCES);
    assert(opengpu_mmu_vm_unmap(gpu,&context->vm,address,MMU_PAGE_SIZE)==-EACCES);
    mutex_lock(&gpu->hw.submit_lock);
    assert(!opengpu_private_snapshot_verify_locked(owner,gpu,context));
    leaf=&((u32 *)vm_live_l1(&context->vm,address/MMU_SUPERPAGE_SIZE)->cpu)
         [(address%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE];
    saved=*leaf;*leaf|=MMU_PTE_X;
    assert(opengpu_private_snapshot_verify_locked(owner,gpu,context)==-EACCES);*leaf=saved;
    *leaf^=1u<<MMU_PTE_POLICY_SHIFT;
    assert(opengpu_private_snapshot_verify_locked(owner,gpu,context)==-EACCES);*leaf=saved;
    *leaf^=MMU_PTE_W;
    assert(opengpu_private_snapshot_verify_locked(owner,gpu,context)==-EACCES);*leaf=saved;
    assert(!opengpu_private_snapshot_verify_locked(owner,gpu,context));
    mutex_unlock(&gpu->hw.submit_lock);
    // A resource revoke fault keeps every descriptor PA lease, allocation and
    // context; no borrowed original GEM is ever freed by the private owner.
    assert(!opengpu_mmu_snapshot_unmap(gpu,&owner->segments->lease));owner->segments->mapped=false;
    allocations=live_allocations;asid_flush_failures=1;
    assert(opengpu_private_snapshot_retire(owner)==-EIO);
    assert(gpu->hw.wedged && gpu->mmu.snapshot_leases && context->references==2);
    assert(context->vm.reservations && allocations==live_allocations);
    assert(((unsigned char *)first.cpu)[0]==0x71 && ((unsigned char *)last.cpu)[0]==0x39);
    gpu->hw.wedged=false;
    assert(!opengpu_private_snapshot_retire(owner));
    assert(!context->vm.reservations && !gpu->mmu.snapshot_leases && context->references==1);
    assert(((unsigned char *)first.cpu)[0]==0x71 && ((unsigned char *)last.cpu)[0]==0x39);
    // Map failure has no publication and rolls back the full envelope on retire.
    owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
    assert(!opengpu_private_snapshot_append(owner,descriptor,sizeof(descriptor),&metadata));
    assert(!opengpu_private_snapshot_resource(owner,4*MMU_PAGE_SIZE,&id));
    assert(!opengpu_private_snapshot_resource_map(owner,id,3*MMU_PAGE_SIZE,last.dma,last.size,false));
    mmu_fail_private_maps=1;
    assert(opengpu_private_snapshot_prepare(owner)==-ENOMEM);
    assert(context->vm.reservations && !gpu->mmu.snapshot_leases);
    assert(!opengpu_private_snapshot_retire(owner));
    assert(!context->vm.reservations && context->references==1);
    // Cancellation before prepare leaves all GPU and VA state untouched.
    owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
    assert(!opengpu_private_snapshot_append(owner,descriptor,sizeof(descriptor),&metadata));
    assert(!opengpu_private_snapshot_resource(owner,4*MMU_PAGE_SIZE,&id));
    assert(!opengpu_private_snapshot_resource_map(owner,id,0,first.dma,first.size,true));
    idle_error=-ETIMEDOUT;assert(!opengpu_private_snapshot_retire(owner));idle_error=0;
    assert(!context->vm.reservations && !gpu->mmu.snapshot_leases && context->references==1);
    // Reuse the same real original pages with a new owner and full mappings.
    owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
    assert(!opengpu_private_snapshot_append(owner,descriptor,sizeof(descriptor),&metadata));
    assert(!opengpu_private_snapshot_resource(owner,4*MMU_PAGE_SIZE,&id));
    assert(!opengpu_private_snapshot_resource_map(owner,id,0,first.dma,first.size,true));
    assert(!opengpu_private_snapshot_prepare(owner));assert(!opengpu_private_snapshot_publish(owner));
    mutex_lock(&gpu->hw.submit_lock);assert(!opengpu_private_snapshot_verify_locked(owner,gpu,context));mutex_unlock(&gpu->hw.submit_lock);
    assert(!opengpu_private_snapshot_retire(owner));
    opengpu_buffer_free(gpu,&last);opengpu_buffer_free(gpu,&first);
    puts("private native resources: real RO/RW NX leaves, reserved gaps, metadata relocation, revoke quarantine/cancel and reuse PASS (C only)");
}

#ifndef OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN
#define OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN main
#endif
int OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN(void)
{
    struct opengpu_device gpu = {0};
    struct opengpu_render_context context = {.gpu=&gpu, .references=1,.id=7};
    struct opengpu_vm attacker;
    struct opengpu_private_snapshot *owner;
    u32 metadata, data, value, address;
    int allocations;
    assert(!mmu_regression_main());
    /* Independent literal byte oracle at the 33-bit exclusive-limit edge.
     * The source guards must survive both success and rejected pointer cases. */
    {
        unsigned char bytes[16], expected[8] = {0,0,0,0,1,0,0,0};
        struct snapshot_segment source = {.backing.cpu=bytes};
        struct snapshot_segment target = {.va=0xfffffff0ULL};
        struct snapshot_relocation relocation = {.source=&source, .target=&target,
            .offset=4, .addend=16, .kind=OPENGPU_PRIVATE_SNAPSHOT_LIMIT33};
        memset(bytes, 0xa5, sizeof(bytes));
        assert(!snapshot_apply_relocation(&relocation));
        assert(!memcmp(bytes+4, expected, 8));
        assert(bytes[0]==0xa5 && bytes[3]==0xa5 && bytes[12]==0xa5 && bytes[15]==0xa5);
        memset(bytes, 0xa5, sizeof(bytes));
        relocation.kind=OPENGPU_PRIVATE_SNAPSHOT_VA32;
        assert(snapshot_apply_relocation(&relocation)==-ERANGE);
        for(unsigned i=0;i<sizeof(bytes);++i)assert(bytes[i]==0xa5);
        relocation.kind=OPENGPU_PRIVATE_SNAPSHOT_LIMIT33;relocation.addend=17;
        assert(snapshot_apply_relocation(&relocation)==-ERANGE);
        for(unsigned i=0;i<sizeof(bytes);++i)assert(bytes[i]==0xa5);
    }
    gpu.hw.capabilities = GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu));
    assert(!opengpu_mmu_vm_create(&gpu, &context.vm));
    assert(!opengpu_mmu_vm_create(&gpu, &attacker));
    copy_gpu=&gpu;
    // Building snapshots cannot block source producers: there are no DMA pages
    // or leases until all dependency waits and CPU encoding have completed.
    owner=new_owner(&gpu,&context,&metadata,&data);
    assert(!owner->segments->backing.cpu && owner->segments->staging && !gpu.mmu.snapshot_leases);
    assert(!opengpu_hw_snapshot_submission_lock(&gpu));mutex_unlock(&gpu.hw.submit_lock);
    idle_error=-ETIMEDOUT;
    assert(!opengpu_private_snapshot_retire(owner));
    assert(!gpu.hw.wedged && context.references==1);idle_error=0;
    owner = new_owner(&gpu, &context, &metadata, &data);
    assert(context.references == 2);
    assert(!opengpu_private_snapshot_read32(owner, metadata, 16, &value));
    assert(value == 0x5a5a5a5a);
    assert(!opengpu_private_snapshot_read32(owner, data, 16, &value));
    assert(value == 0x5a5a5aff);
    assert(opengpu_private_snapshot_read32(owner, data, 61, &value) == -EINVAL);
    assert(opengpu_private_snapshot_read32(owner, data, 64, &value) == -ERANGE);
    assert(opengpu_private_snapshot_relocate(owner, metadata, 8, data, 0,
        OPENGPU_PRIVATE_SNAPSHOT_VA32) == -EINVAL); /* overlaps upper limit */
    assert(opengpu_private_snapshot_relocate(owner, metadata, 60, data, 0,
        OPENGPU_PRIVATE_SNAPSHOT_LIMIT33) == -ERANGE);
    assert(!opengpu_private_snapshot_prepare(owner));
    assert(!opengpu_private_snapshot_address(owner, data, &address));
    assert(!opengpu_private_snapshot_read32(owner, metadata, 0, &value));
    assert(value == address + 4);
    assert(!opengpu_private_snapshot_read32(owner, metadata, 4, &value));
    assert(value == address + 64);
    assert(!opengpu_private_snapshot_read32(owner, metadata, 8, &value) && !value);
    {
        struct snapshot_segment *code = owner->segments->next->next;
        u32 code_address;
        assert(code->executable && code->lease.executable);
        assert(!opengpu_private_snapshot_address(owner, code->id, &code_address));
        assert(!opengpu_private_snapshot_read32(owner, metadata, 12, &value));
        assert(value==code_address);
        assert(!opengpu_private_snapshot_read32(owner, code->id, 0, &value));
        assert(value==0x00700513u);
        assert(!verify_code(&gpu, &context.vm, code_address, code->backing.dma, code->bytes));
        assert(verify_snapshot(&gpu, &context.vm, code_address, code->backing.dma, code->bytes)==-EACCES);
        assert(opengpu_mmu_vm_map_snapshot(&gpu, &context.vm, 0x60000000,
            code->backing.dma, code->backing.size, 2)==-EACCES);
    }
    assert(opengpu_private_snapshot_append(owner, &value, 4, &data) == -EINVAL);
    assert(opengpu_private_snapshot_relocate(owner, metadata, 20, data, 0,
        OPENGPU_PRIVATE_SNAPSHOT_VA32) == -EINVAL);
    assert(!opengpu_private_snapshot_publish(owner));
    assert(opengpu_private_snapshot_publish(owner) == -EINVAL);
    mutex_lock(&gpu.hw.submit_lock);
    invalidations=0;
    assert(opengpu_private_snapshot_invalidate_locked(owner,&gpu,NULL)==-EINVAL && !invalidations);
    assert(!opengpu_private_snapshot_invalidate_locked(owner,&gpu,&context) && invalidations==3);
    invalidations=0;invalidate_failure=2;
    assert(opengpu_private_snapshot_invalidate_locked(owner,&gpu,&context)==-EIO && invalidations==2);
    invalidate_failure=0;
    assert(!opengpu_private_snapshot_verify_locked(owner, &gpu, &context));
    assert(opengpu_private_snapshot_verify_locked(owner, &gpu, NULL) == -EINVAL);
    mutex_unlock(&gpu.hw.submit_lock);
    assert(opengpu_mmu_vm_replace(&gpu, &context.vm, owner->segments->va,
        0x71000000, MMU_PAGE_SIZE, 2, 0, 0) == -EACCES);
    assert(opengpu_mmu_vm_unmap(&gpu, &context.vm, owner->segments->va,
        owner->segments->backing.size) == -EACCES);
    assert(opengpu_mmu_vm_map(&gpu, &attacker, 0x50000000,
        owner->segments->backing.dma, MMU_PAGE_SIZE, 2) == -EACCES);
    allocations = live_allocations;
    idle_error = -ETIMEDOUT;
    assert(opengpu_private_snapshot_retire(owner) == -ETIMEDOUT);
    assert(gpu.hw.wedged && context.references == 2 && live_allocations == allocations);
    assert(gpu.mmu.snapshot_leases);
    /* Explicit recovery; no automatic freeing occurs behind the failed wait. */
    idle_error = 0; gpu.hw.wedged = false;
    asid_flush_failures = 1;
    assert(opengpu_private_snapshot_retire(owner) == -EIO);
    assert(gpu.hw.wedged && live_allocations == allocations && context.references == 2);
    gpu.hw.wedged = false;
    assert(!opengpu_private_snapshot_retire(owner));
    assert(!gpu.mmu.snapshot_leases && context.references == 1);
    /* Existing cross-context mappings cannot turn a failed prepare into a
     * use-after-free. Keep the complete owner until that alias is revoked. */
    owner = new_owner(&gpu, &context, &metadata, &data);
    // Allocate the attacker's page table first; the next actual DMA result is
    // then known without creating a second/fictional producer address map.
    assert(!opengpu_mmu_vm_map(&gpu,&attacker,0x50000000,0x78000000,MMU_PAGE_SIZE,2));
    assert(!opengpu_mmu_vm_unmap(&gpu,&attacker,0x50000000,MMU_PAGE_SIZE));
    assert(!opengpu_mmu_vm_map(&gpu,&attacker,0x50000000,next_dma,MMU_PAGE_SIZE,2));
    assert(opengpu_private_snapshot_prepare(owner) == -EACCES);
    assert(owner->segments->staging && ((unsigned char *)owner->segments->backing.cpu)[0]==0xa5);
    allocations = live_allocations;
    assert(opengpu_private_snapshot_retire(owner) == -EACCES);
    assert(gpu.hw.wedged && live_allocations == allocations && context.references == 2);
    gpu.hw.wedged = false;
    assert(!opengpu_mmu_vm_unmap(&gpu, &attacker, 0x50000000, MMU_PAGE_SIZE));
    assert(!opengpu_private_snapshot_retire(owner));
    /* Fresh owner reuses the VA holes after complete drain/revocation. */
    owner = new_owner(&gpu, &context, &metadata, &data);
    assert(!opengpu_private_snapshot_prepare(owner));
    assert(!opengpu_private_snapshot_publish(owner));
    assert(!opengpu_private_snapshot_retire(owner));
    assert(context.references == 1 && !gpu.mmu.snapshot_leases);
    uniform_snapshot_test(&gpu, &context);
    resource_owner_test(&gpu, &context);
    simultaneous_owner_test(&gpu, &context);
    native_root_view_test(&gpu, &context);
    opengpu_mmu_vm_destroy(&gpu, &attacker);
    opengpu_mmu_vm_destroy(&gpu, &context.vm);
    opengpu_mmu_fini(&gpu);
    assert(!live_allocations && !fixture_buffers && sealed_dma_copies>=8);
    puts("private snapshot: copied bytes, relocation, cross-VM lease, timeout/shootdown quarantine and healthy reuse PASS (C only)");
    return 0;
}
