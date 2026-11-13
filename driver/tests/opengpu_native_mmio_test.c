/* Production MMIO composition test. The control peer executes no GPU code. */
#define OPENGPU_NATIVE_MMIO 1
#define OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN native_submit_component_regression
#include "opengpu_native_submit_fixture.c"
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
int main(int argc,char **argv)
{
    assert(argc==2);char memory[4096],capture[2][4096],retire[2][4096];
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
    assert(!native_kernel_finish_observed(s,0));assert(a->freed && !a->job && b->job);
    assert(native_session_record(s,1)==b && !native_kernel_finish_observed(s,1));
    assert(!native_kernel_session_destroy(s));assert(!live_allocations && !fixture_buffers);
    puts("native MMIO production composition CPU checks: PASS");return 0;
}
