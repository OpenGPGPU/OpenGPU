/* Production MMIO composition test. The control peer executes no GPU code. */
#define OPENGPU_NATIVE_MMIO 1
struct opengpu_device;
static void test_cleanup_peer(struct opengpu_device *,const char *,unsigned);
#define OPENGPU_NATIVE_MMIO_CLEANUP_TEST_PEER(g,d,s) test_cleanup_peer(g,d,s)
#define OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN native_submit_component_regression
#include "opengpu_native_submit_fixture.c"
#include "opengpu_joint_drm_inputs.inc"
#include <sys/wait.h>
static unsigned test_cleanup_bad,test_cleanup_bad_phase,test_context_flushes,test_final_flushes;
static void test_cleanup_peer(struct opengpu_device *gpu,const char *directory,unsigned sequence)
{
    assert(!gpu->hw.wedged);
    struct native_mmio_state *m=native_mmio_state(gpu);char path[4096];
    snprintf(path,sizeof(path),"%s/cleanup-%u.trace",directory,sequence);
    FILE *f=fopen(path,"rb");assert(f);char magic[8];u32 count;
    assert(fread(magic,1,8,f)==8 && !memcmp(magic,"OGCLN001",8) && !native_mmio_read_le(f,&count));
    unsigned clears=0,flushes=0,vector_zero=0,instruction_zero=0;
    for(u32 n=0;n<count;n++){
        u32 a[4];for(unsigned k=0;k<4;k++)assert(!native_mmio_read_le(f,&a[k]));
        if(a[0]==4){
            assert(a[2]==0 && (a[3]&1));bool found=false;
            for(struct native_mmio_pte_shadow *p=m->pte;p;p=p->next)if(a[1]>=p->physical && (u64)a[1]+4<=p->physical+p->size){
                assert(!p->words[(a[1]-p->physical)/4]);found=true;break;}
            assert(found);++clears;
        }else if(a[0]==1){
            if(a[1]==GPU_REG_UCMD_TLB_FLUSH)++flushes;
            else if(a[1]==GPU_REG_UCMD_VECTOR_SATP)vector_zero=a[2]==0;
            else if(a[1]==GPU_REG_UCMD_INSTRUCTION_SATP)instruction_zero=a[2]==0;
            else assert(0);
        }
    }
    assert(fgetc(f)==EOF && !fclose(f) && flushes<=1);
    assert(!clears || flushes==1);
    if(flushes)assert(gpu->hw.submit_lock.held && gpu->mmu.lock.held);
    /* Actual owner backing and original references remain live while the
     * production MMU call is blocked, before the CPU test peer acknowledges. */
    bool owner_live=false;
    for(struct native_kernel_record *r=running->records;r;r=r->next)if(!r->freed){
        assert(r->job && r->job->native_owner);owner_live=true;
        for(struct snapshot_segment *segment=r->job->native_owner->segments;segment;segment=segment->next)
            assert(segment->backing.cpu && segment->lease.active);
    }
    unsigned phase=owner_live?0:gpu->mmu.vms?1:2;
    if(flushes && phase){
        assert(running->context.vm.root.cpu || phase==2);
        assert(gpu->mmu.enabled && gpu->mmu.root.cpu);
        if(phase==1){++test_context_flushes;assert(!vector_zero && !instruction_zero);}
        else {++test_final_flushes;assert(vector_zero && instruction_zero);
            assert(!m->regs[GPU_REG_UCMD_VECTOR_SATP/4] && !m->regs[GPU_REG_UCMD_INSTRUCTION_SATP/4]);}
    }
    unsigned bad=(!test_cleanup_bad_phase || phase==test_cleanup_bad_phase)?test_cleanup_bad:0;
    snprintf(path,sizeof(path),"%s/cleanup-%u.observed",directory,sequence);
    f=fopen(path,"wbx");assert(f);fwrite("OGCLA001",1,8,f);
    native_mmio_le(f,sequence+(bad==1),4);
    native_mmio_le(f,bad==2?GPU_UCMD_STATUS_VM_BUSY:GPU_UCMD_STATUS_READY,4);
    native_mmio_le(f,GPU_IRQ_ENABLE,4);native_mmio_le(f,0,4);native_mmio_le(f,1,4);assert(!fclose(f));
}
static void test_failed_cleanup(struct native_kernel_session *s,const char *base,unsigned bad)
{
    char directory[4096];snprintf(directory,sizeof(directory),"%s/failed-%u",base,bad);assert(!mkdir(directory,0700));
    pid_t child=fork();assert(child>=0);
    if(!child){
        test_cleanup_bad=bad;int allocations=live_allocations;
        assert(!native_kernel_begin_cleanup(s,directory));
        assert(native_kernel_finish_observed(s,0)==-EIO);
        struct native_kernel_record *r=native_session_record(s,0);
        assert(s->gpu.hw.wedged && r->job && r->job->native_owner && !r->freed);
        assert(live_allocations==allocations && !s->gpu.mmu.lock.held && !s->gpu.hw.submit_lock.held);
        assert(native_kernel_end_cleanup(s)==-EBUSY && native_kernel_session_destroy(s)==-EBUSY);
        /* A failed CPU-only session deliberately remains quarantined. The
         * child exits without inventing a successful GPU reset/ack/release. */
        _exit(0);
    }
    int status;assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
}
static void test_failed_context_cleanup(struct native_kernel_session *s,const char *base,unsigned phase)
{
    char directory[4096];snprintf(directory,sizeof(directory),"%s/context-failed-%u",base,phase);
    assert(!mkdir(directory,0700));pid_t child=fork();assert(child>=0);
    if(!child){
        struct native_mmio_state *m=native_mmio_state(&s->gpu);
        free(m->cleanup_directory);m->cleanup_directory=strdup(directory);assert(m->cleanup_directory);
        test_cleanup_bad=1;test_cleanup_bad_phase=phase;
        int allocations=live_allocations;void *vmroot=s->context.vm.root.cpu,*global=s->gpu.mmu.root.cpu;
        u32 asid=s->context.vm.asid;unsigned tables=s->context.vm.l1_count;
        assert(native_kernel_end_cleanup(s)==-EINVAL);
        assert(s->gpu.hw.wedged && !s->context_destroyed && !m->cleanup_done && m->cleanup_active);
        assert(s->gpu.mmu.enabled && s->gpu.mmu.root.cpu==global);
        if(phase==1){
            assert(s->context.vm.enabled && s->gpu.mmu.vms==&s->context.vm);
            assert(s->context.vm.root.cpu==vmroot && s->context.vm.asid==asid && live_allocations==allocations);
        }else {
            assert(!s->context.vm.enabled && !s->gpu.mmu.vms && !s->context.vm.root.cpu);
            assert(live_allocations==allocations-(int)tables-1);
        }
        assert(!s->gpu.mmu.lock.held && !s->gpu.hw.submit_lock.held);
        assert(native_kernel_session_destroy(s)==-EBUSY);
        _exit(0); /* Quarantine, without fabricated reset/ACK or host freeing. */
    }
    int status;assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
}
static void test_path(char *out,size_t size,const char *base,const char *name)
{int n=snprintf(out,size,"%s/%s",base,name);assert(n>0 && (size_t)n<size);}
static struct native_kernel_held_receipt test_held(struct native_kernel_session *s,unsigned index)
{
    struct native_kernel_held_receipt h={.record_index=index,.command_id=s->gpu.hw.unified_command_id,
        .completion=0x8700u|s->gpu.hw.unified_command_id,.append_read_bytes=(1ull<<32)+64,
        .held_legacy_status=GPU_STATUS_DONE,.held_status=GPU_UCMD_STATUS_READY|GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY,
        .held_irq=GPU_IRQ_ENABLE|GPU_IRQ_PENDING,.held_irq_asserted=1};
    h.gnx[0]=OPENGPU_NATIVE_BINNED_COMPLETION_MAGIC;h.gnx[2]=0x12345678;h.gnx[3]=0x1234;
    h.gnx[6]=s->gpu.hw.native_context;h.gnx[7]=s->gpu.hw.native_pass;h.gnx[8]=s->gpu.hw.native_generation;
    return h;
}
static struct native_kernel_observed_receipt test_observed(struct native_kernel_held_receipt held)
{return (struct native_kernel_observed_receipt){.held=held,.retired_status=GPU_UCMD_STATUS_READY,
    .retired_irq=GPU_IRQ_ENABLE,.external_drained=1};}
static void test_joint_mmio(const char *directory)
{
    struct native_kernel_profile_word base[96],registers[5],words[92];
    native_test_profile_words(base,registers);
    unsigned count=0;
    for(unsigned i=0;i<96;++i)
        if(base[i].address<0x4c0 || base[i].address>=0x4e0)words[count++]=base[i];
    words[2].value=0x4367ff;
    const u32 gjp[]={0x474a5001,0x4ff,0x08100207,4};
    for(unsigned i=0;i<4;++i)
        words[count++]=(struct native_kernel_profile_word){0x550+4u*i,gjp[i]};
    assert(count==92);
    registers[1].value|=GPU_CAP_NATIVE_PIXEL_FORMATS|(128u<<GPU_CAP_FRAGMENT_BATCH_SHIFT);
    assert(!native_kernel_joint_profile_create(words,count-1,registers,5));
    words[count-1].value=1;
    assert(!native_kernel_joint_profile_create(words,count,registers,5));
    words[count-1].value=4;
    struct native_kernel_profile *profile=native_kernel_joint_profile_create(words,count,registers,5);
    assert(profile);
    struct native_kernel_session *s=native_kernel_session_create_profile(17,profile);
    assert(s && s->context.vm.reserve_joint_lds);
    native_kernel_profile_destroy(profile);

    test_next_handle=0;
    u32 code=test_gem(s,4096).handle,defaults=test_gem(s,4096).handle;
    word(original_handle(s,code)->gem.vaddr,0,0x30500073);
    struct joint_drm_inputs original;
    joint_drm_inputs_init(s,&original,1,code,defaults);
    assert(!native_kernel_submit_joint(s,&original.request,NULL,NULL));
    struct native_kernel_record *record=native_session_record(s,0);
    assert(record && record->job && record->job->joint_report);
    assert(!native_kernel_original_close(s,code) && native_kernel_original_refcount(s,code)>0);

    char memory[4096],capture[4096],retire[4096],cleanup[4096];
    test_path(memory,sizeof(memory),directory,"joint.memory");
    test_path(capture,sizeof(capture),directory,"joint.capture");
    test_path(retire,sizeof(retire),directory,"joint.retire");
    test_path(cleanup,sizeof(cleanup),directory,"joint-cleanup");
    assert(!mkdir(cleanup,0700));
    assert(!native_kernel_export_memory(s,memory));
    assert(!native_kernel_capture(s,0,capture,1000000,16));
    struct native_mmio_state *mmio=native_mmio_state(&s->gpu);
    assert(mmio->native_waiting && mmio->invalidates && s->gpu.hw.unified_opcode==15);
    assert(mmio->regs[GPU_REG_UCMD_PATTERN/4]==0x474a0102);
    assert(!record->job->joint_report->valid && !dma_fence_is_signaled(record->hardware));

    /* A CPU control receipt tests the production drain's wire ordering and
     * high words. Live qualification obtains these values from the model. */
    u32 id=s->gpu.hw.unified_command_id;
    struct native_kernel_held_receipt held={
        .command_id=id,.completion=0x18700|id,.append_read_bytes=0x100000040ull,
        .held_status=GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY,
        .held_irq=GPU_IRQ_ENABLE|GPU_IRQ_PENDING,.held_irq_asserted=1,
        .gnz2={0x474e5a02,0,0,17,8,9,1,1,2,0,3,0,4,2,5,3},
    };
    struct native_kernel_held_receipt alien=held;
    alien.gnz2[5]++;
    assert(native_kernel_drain_held(s,0,&alien,retire)==-EINVAL);
    alien=held;
    alien.completion=0x8700|id;
    assert(native_kernel_drain_held(s,0,&alien,retire)==-EINVAL);
    assert(mmio->native_waiting && !record->job->joint_report->valid);
    assert(!native_kernel_drain_held(s,0,&held,retire));
    const struct opengpu_tessellation_joint_completion *report=record->job->joint_report;
    assert(report->valid && report->read_bytes==held.append_read_bytes &&
        report->stored_bytes==0x200000004ull && report->resolved_bytes==0x300000005ull);
    assert(!mmio->native_waiting && !s->gpu.hw.joint_pending_completion);
    struct native_kernel_observed_receipt observed=test_observed(held);
    observed.held.gnz2[15]^=1;
    assert(native_kernel_observe_retired(s,0,&observed)==-EINVAL);
    observed=test_observed(held);
    assert(!native_kernel_observe_retired(s,0,&observed));
    assert(!native_kernel_begin_cleanup(s,cleanup));
    assert(!native_kernel_finish_observed(s,0) && !native_kernel_original_refcount(s,code));
    assert(!native_kernel_end_cleanup(s));
    assert(!native_kernel_session_destroy(s) && !live_allocations && !fixture_buffers);
}
int main(int argc,char **argv)
{
    assert(argc==2);char memory[4096],capture[2][4096],retire[2][4096];
    unsigned timeout;assert(!unsetenv("OPENGPU_NATIVE_HOST_TIMEOUT"));
    assert(!native_mmio_timeout_seconds(&timeout) && timeout==300);
    const char *valid[]={"1","3600","86400"};const unsigned expected[]={1,3600,86400};
    for(unsigned n=0;n<3;n++){
        assert(!setenv("OPENGPU_NATIVE_HOST_TIMEOUT",valid[n],1));
        assert(!native_mmio_timeout_seconds(&timeout) && timeout==expected[n]);
    }
    const char *invalid[]={"","0","-1","+1"," 1","1 ","1x","86401","999999999999999999999999"};
    for(unsigned n=0;n<sizeof(invalid)/sizeof(invalid[0]);n++){
        assert(!setenv("OPENGPU_NATIVE_HOST_TIMEOUT",invalid[n],1));
        assert(native_mmio_timeout_seconds(&timeout)==-EINVAL);
    }
    assert(!unsetenv("OPENGPU_NATIVE_HOST_TIMEOUT"));
    test_path(memory,sizeof(memory),argv[1],"union.memory");
    for(unsigned n=0;n<2;n++){char name[64];snprintf(name,sizeof(name),"record-%u.capture",n);
        test_path(capture[n],sizeof(capture[n]),argv[1],name);snprintf(name,sizeof(name),"record-%u.retire",n);
        test_path(retire[n],sizeof(retire[n]),argv[1],name);}
    struct native_kernel_profile_word words[96],regs[5];native_test_profile_words(words,regs);
    struct native_kernel_profile *p=native_kernel_profile_create(words,96,regs,5);assert(p);
    struct native_kernel_session *s=native_kernel_session_create_profile(7,p);assert(s);native_kernel_profile_destroy(p);
    u32 code=test_gem(s,4096).handle,defaults=test_gem(s,4096).handle;
    word(original_handle(s,code)->gem.vaddr,0,0x30500073u);
    word(original_handle(s,defaults)->gem.vaddr,4,0x11223344u);
    struct native_test_record first,second;test_record_init(s,&first,code,defaults);test_record_init(s,&second,code,defaults);
    assert(!native_kernel_submit(s,&first.submit,NULL,NULL));
    word(original_handle(s,defaults)->gem.vaddr,4,0x55667788u);
    assert(!native_kernel_submit(s,&second.submit,NULL,NULL));
    assert(test_default_word(s,0)==0x11223344 && test_default_word(s,1)==0x55667788);
    assert(native_kernel_capture(s,0,capture[0],1000000,16)==-EBUSY);
    assert(!native_kernel_export_memory(s,memory));assert(native_kernel_export_memory(s,memory)==-EBUSY);
    assert(native_kernel_capture(s,1,capture[1],1000000,16)==-EBUSY);
    assert(!native_kernel_capture(s,0,capture[0],1000000,16));
    struct native_kernel_record *a=native_session_record(s,0),*b=native_session_record(s,1);
    struct native_mmio_state *m=native_mmio_state(&s->gpu);
    assert(a && b && m->native_submissions==1 && m->invalidates && m->pops==m->invalidates);
    assert(!dma_fence_is_signaled(a->hardware) && !a->job->base.s_fence->finished.signaled);
    assert(native_kernel_capture(s,0,capture[0],1000000,16)==-EINVAL);
    assert(native_kernel_capture(s,1,capture[1],1000000,16)==-EBUSY);
    struct native_kernel_held_receipt h0=test_held(s,0),bad;
#define REJECT_HELD(statement) do {bad=h0;statement;assert(native_kernel_drain_held(s,0,&bad,retire[0])==-EINVAL);assert(!a->drained && !dma_fence_is_signaled(a->hardware));}while(0)
    REJECT_HELD(bad.record_index=1);REJECT_HELD(bad.command_id^=1);REJECT_HELD(bad.completion^=0x8000);
    REJECT_HELD(bad.gnx[0]^=1);REJECT_HELD(bad.gnx[1]=1);REJECT_HELD(bad.gnx[5]=1);
    REJECT_HELD(bad.gnx[6]^=1);REJECT_HELD(bad.gnx[7]^=1);REJECT_HELD(bad.gnx[8]^=1);
    REJECT_HELD(bad.gnx[11]=1);REJECT_HELD(bad.append_read_bytes++);
    REJECT_HELD(bad.append_read_bytes=(1ull<<33)+64);REJECT_HELD(bad.held_status=0);
    REJECT_HELD(bad.held_irq=1);REJECT_HELD(bad.held_irq_asserted=0);
#undef REJECT_HELD
    assert(!native_kernel_drain_held(s,0,&h0,retire[0]));
    assert(a->drained && dma_fence_is_signaled(a->hardware) && !a->hardware->error && !s->gpu.hw.native_pending_owner);
    assert(!a->job->base.s_fence->finished.signaled && native_kernel_capture(s,1,capture[1],1000000,16)==-EBUSY);
    struct native_kernel_observed_receipt o0=test_observed(h0),wrong=o0;wrong.held.held_legacy_status^=1;
    assert(native_kernel_observe_retired(s,0,&wrong)==-EINVAL);wrong=o0;wrong.retired_irq_asserted=1;
    assert(native_kernel_observe_retired(s,0,&wrong)==-EINVAL);wrong=o0;wrong.retired_status|=GPU_UCMD_STATUS_VM_BUSY;
    assert(native_kernel_observe_retired(s,0,&wrong)==-EINVAL);wrong=o0;wrong.external_drained=0;
    assert(native_kernel_observe_retired(s,0,&wrong)==-EINVAL);
    assert(!native_kernel_observe_retired(s,0,&o0));
    assert(a->verified && a->job->native_owner && b->job->native_owner && native_kernel_original_refcount(s,defaults)==3);
    assert(native_kernel_finish_observed(s,0)==-EBUSY);
    assert(!native_kernel_capture(s,1,capture[1],1000000,16));
    assert(test_default_word(s,0)==0x11223344 && test_default_word(s,1)==0x55667788);
    struct native_kernel_held_receipt h1=test_held(s,1);
    assert(!native_kernel_drain_held(s,1,&h1,retire[1]));
    struct native_kernel_observed_receipt o1=test_observed(h1);assert(!native_kernel_observe_retired(s,1,&o1));
    assert(native_kernel_finish_observed(s,0)==-EBUSY);
    test_failed_cleanup(s,argv[1],1);test_failed_cleanup(s,argv[1],2);
    assert(!native_kernel_begin_cleanup(s,argv[1]));
    assert(native_kernel_begin_cleanup(s,argv[1])==-EBUSY);
    assert(!native_kernel_finish_observed(s,0));assert(a->freed && !a->job && b->job);
    assert(native_session_record(s,1)==b && !native_kernel_finish_observed(s,1));
    test_failed_context_cleanup(s,argv[1],1);test_failed_context_cleanup(s,argv[1],2);
    assert(!native_kernel_end_cleanup(s));assert(native_kernel_end_cleanup(s)==-EBUSY);
    assert(s->context_destroyed && !s->context.vm.enabled && !s->context.vm.root.cpu);
    assert(!s->gpu.mmu.enabled && !s->gpu.mmu.root.cpu && !s->gpu.mmu.vms);
    assert(test_context_flushes==1 && test_final_flushes==1);
    size_t actions=native_mmio_state(&s->gpu)->count;
    /* Final session bookkeeping must issue no unobserved MMIO. */
    assert(actions==native_mmio_state(&s->gpu)->cleanup_start);
    assert(!native_kernel_session_destroy(s));assert(!live_allocations && !fixture_buffers);
    test_joint_mmio(argv[1]);
    puts("native and joint MMIO production composition CPU checks: PASS");return 0;
}
