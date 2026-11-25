/* SPDX-License-Identifier: MIT */
/* Exact Linux ioctl/MMU/scheduler/MMIO bodies. This process remains the owner
 * of the original GEMs until the external DUT drain and cleanup are observed. */
#define OPENGPU_NATIVE_MMIO 1
#define OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN unused_native_submit_corpus
#include "../driver/tests/opengpu_native_submit_fixture.c"
#undef OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN
#include "native_sample_live_transport.h"
#include "native_joint_live_bridge.h"

struct native_kernel_profile *joint_live_profile(const char *path)
{
    struct native_kernel_profile_word words[92],registers[5];
    if (path) {
        FILE *f=fopen(path,"rb");
        live_require(f!=NULL,"joint profile open");
        live_magic(f,"OGNP0003");
        live_require(live_u32(f)==92,"joint discovery count");
        for (unsigned i=0;i<92;++i) {
            words[i].address=live_u32(f);
            words[i].value=live_u32(f);
        }
        live_require(live_u32(f)==5,"joint auxiliary count");
        for (unsigned i=0;i<5;++i) {
            registers[i].address=live_u32(f);
            registers[i].value=live_u32(f);
        }
        live_end(f);
    } else {
        /* Explicit CPU-only control. An active session always takes
         * the words from the external DUT probe. */
        struct native_kernel_profile_word base[96];
        native_test_profile_words(base,registers);
        unsigned count=0;
        for (unsigned i=0;i<96;++i)
            if (base[i].address<0x4c0 || base[i].address>=0x4e0)words[count++]=base[i];
        words[2].value=0x4367ff;
        const u32 gjp[]={0x474a5001,0x4ff,0x08100207,4};
        for (unsigned i=0;i<4;++i)
            words[count++]=(struct native_kernel_profile_word){0x550+4u*i,gjp[i]};
        assert(count==92);
        registers[1].value|=GPU_CAP_NATIVE_PIXEL_FORMATS|(128u<<GPU_CAP_FRAGMENT_BATCH_SHIFT);
    }
    return native_kernel_joint_profile_create(words,92,registers,5);
}

static struct native_kernel_held_receipt joint_held(FILE *f)
{
    struct native_kernel_held_receipt receipt = {0};
    receipt.record_index=live_u32(f);
    receipt.command_id=live_u32(f);
    receipt.completion=live_u32(f);
    for (unsigned i=0;i<OPENGPU_TESS_JOINT_HOST_COMPLETION_WORDS;++i)
        receipt.gnz2[i]=live_u32(f);
    receipt.append_read_bytes=live_u32(f);
    receipt.append_read_bytes|=(u64)live_u32(f)<<32;
    receipt.held_legacy_status=live_u32(f);
    receipt.held_status=live_u32(f);
    receipt.held_irq=live_u32(f);
    receipt.held_irq_asserted=live_u32(f);
    return receipt;
}
static struct native_kernel_reset_receipt joint_reset_receipt(unsigned index,const char *phase,
    const char *magic)
{
    char name[96];
    snprintf(name,sizeof(name),"record-%u.reset.%s.response",index,phase);
    FILE *f=live_wait(name);
    live_magic(f,magic);
    struct native_kernel_reset_receipt receipt={0};
    receipt.record_index=live_u32(f);
    receipt.command_id=live_u32(f);
    receipt.legacy_status=live_u32(f);
    receipt.status=live_u32(f);
    receipt.irq=live_u32(f);
    receipt.irq_asserted=live_u32(f);
    receipt.external_drained=live_u32(f);
    live_end(f);
    live_require(receipt.record_index==index,"joint reset record identity");
    return receipt;
}
static void joint_live_cancel(struct native_kernel_session *session,unsigned index)
{
    char name[96],path[4096];
    struct native_kernel_reset_receipt receipt=joint_reset_receipt(index,"held","OGJCH001");
    snprintf(name,sizeof(name),"record-%u.reset.request",index);
    live_path(path,name);
    live_require(!native_kernel_cancel_joint(session,&receipt,path),"joint command abort");
    receipt=joint_reset_receipt(index,"ack","OGJCA001");
    snprintf(name,sizeof(name),"record-%u.reset.irq",index);
    live_path(path,name);
    live_require(!native_kernel_drain_joint_reset(session,&receipt,path),"joint reset IRQ drain");
    receipt=joint_reset_receipt(index,"retired","OGJCV001");
    live_require(!native_kernel_observe_joint_reset_retired(session,&receipt),"joint reset retirement");
}

void joint_live_export(struct native_kernel_session *session,const char *directory)
{
    char path[4096];
    live_set_directory(directory);
    live_path(path,"union.memory");
    live_require(!native_kernel_export_memory(session,path),"joint union export");
}

void joint_live_run(struct native_kernel_session *session,unsigned count,bool cpu_preflight,bool uniform_recovery)
{
    char name[96],path[4096];
    if (cpu_preflight) {
        /* Test admission and Sv32 export only. No fake held receipt may
         * produce a completed qualification session. */
        live_path(path,"record-0.capture");
        live_require(!native_kernel_capture(session,0,path,100000000,32),"joint preflight capture");
        puts("Original Mesa joint DRM and Sv32 preflight PASS. DUT=false");
        fflush(stdout);
        /* The hardware fence intentionally remains pending. */
        _Exit(0);
    }
    for (unsigned i=0;i<count;++i) {
        /* The controller checks the domain and capture before selecting the
         * TCS or TES response line from the original owner graph. */
        unsigned position=i%JOINT_LIVE_RECOVERY_PER_DOMAIN;
        bool cancel=uniform_recovery ? position>=JOINT_LIVE_UNIFORM_CANCEL_FIRST && !(position&1) :
            i==JOINT_LIVE_SOURCE_CANCEL_RECORD;
        bool read_fault=uniform_recovery && position>=JOINT_LIVE_UNIFORM_FAULT_FIRST &&
            position<JOINT_LIVE_UNIFORM_CANCEL_FIRST && !(position&1);
        snprintf(name,sizeof(name),"record-%u.capture",i);
        live_path(path,name);
        int error;
        if(cancel)
            error=native_kernel_capture_joint_cancel(session,i,path,100000000,32);
        else if(read_fault)
            error=native_kernel_capture_joint_uniform_read_fault(session,i,path,100000000,32);
        else if(!uniform_recovery && i==JOINT_LIVE_WRITE_FAULT_RECORD)
            error=native_kernel_capture_joint_write_fault(session,i,path,100000000,32);
        else
            error=native_kernel_capture(session,i,path,100000000,32);
        live_require(!error,"joint capture enqueue");
        if(cancel) {
            joint_live_cancel(session,i);
            continue;
        }
        snprintf(name,sizeof(name),"record-%u.held.response",i);
        FILE *f=live_wait(name);
        live_magic(f,"OGJH0001");
        const struct native_kernel_held_receipt held=joint_held(f);
        live_end(f);
        snprintf(name,sizeof(name),"record-%u.retire",i);
        live_path(path,name);
        live_require(!native_kernel_drain_held(session,i,&held,path),"joint IRQ drain");
        snprintf(name,sizeof(name),"record-%u.retired.response",i);
        f=live_wait(name);
        live_magic(f,"OGJR0001");
        struct native_kernel_observed_receipt observed={.held=joint_held(f)};
        observed.retired_status=live_u32(f);
        observed.retired_irq=live_u32(f);
        observed.retired_irq_asserted=live_u32(f);
        observed.external_drained=live_u32(f);
        live_end(f);
        live_require(!native_kernel_observe_retired(session,i,&observed),"joint retired observation");
    }

    FILE *f=live_wait("oracle-authorized.response");
    live_magic(f,"OGJBA001");
    live_require(live_u32(f)==count,"joint oracle record count");
    live_end(f);
    live_require(!native_kernel_begin_cleanup(session,live_directory),"joint cleanup begin");
    for (unsigned i=0;i<count;++i)
        live_require(!native_kernel_finish_observed(session,i),"joint acknowledged owner retirement");
    live_require(!native_kernel_end_cleanup(session),"joint acknowledged context cleanup");
    f=live_wait("final-authorized.response");
    live_magic(f,"OGJOK001");
    live_require(live_u32(f)==count,"joint final record count");
    live_end(f);
}
