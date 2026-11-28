#define OPENGPU_NATIVE_MMIO 1
#define OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN unused_joint_geometry_submit_corpus
#include "../driver/tests/opengpu_native_submit_fixture.c"
#undef OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN
#include "native_sample_live_transport.h"
#include "native_joint_geometry_live_bridge.h"

struct native_kernel_profile *joint_geometry_live_profile(const char *path)
{
    struct native_kernel_profile_word words[100],registers[5];
    if(path) {
        FILE *f=fopen(path,"rb");
        live_require(f!=NULL,"joint geometry profile open");
        live_magic(f,"OGNP0004");
        live_require(live_u32(f)==100,"joint geometry discovery count");
        for(unsigned i=0;i<100;++i) {
            words[i].address=live_u32(f);
            words[i].value=live_u32(f);
        }
        live_require(live_u32(f)==5,"joint geometry auxiliary count");
        for(unsigned i=0;i<5;++i) {
            registers[i].address=live_u32(f);
            registers[i].value=live_u32(f);
        }
        live_end(f);
    }else {
        native_test_joint_geometry_profile_words(words,registers);
    }
    struct native_kernel_profile *profile=native_kernel_joint_geometry_profile_create(words,100,registers,5);
    live_require(profile!=NULL,"joint geometry production profile rejected");
    return profile;
}

static struct native_kernel_held_receipt joint_geometry_held(FILE *f)
{
    struct native_kernel_held_receipt receipt={0};
    receipt.record_index=live_u32(f);
    receipt.command_id=live_u32(f);
    receipt.completion=live_u32(f);
    for(unsigned i=0;i<OPENGPU_JOINT_GEOMETRY_COMPLETION_WORDS;++i)
        receipt.gnz3[i]=live_u32(f);
    receipt.append_read_bytes=live_u32(f);
    receipt.append_read_bytes|=(u64)live_u32(f)<<32;
    receipt.held_legacy_status=live_u32(f);
    receipt.held_status=live_u32(f);
    receipt.held_irq=live_u32(f);
    receipt.held_irq_asserted=live_u32(f);
    return receipt;
}

void joint_geometry_live_export(struct native_kernel_session *session,const char *directory)
{
    char path[4096];
    live_set_directory(directory);
    live_path(path,"union.memory");
    live_require(!native_kernel_export_memory(session,path),"joint geometry union export");
}

void joint_geometry_live_run(struct native_kernel_session *session,unsigned count,bool cpu_preflight)
{
    char name[96],path[4096];
    if(cpu_preflight) {
        live_path(path,"record-0.capture");
        live_require(!native_kernel_capture(session,0,path,100000000,32),"joint geometry preflight capture");
        puts("Original joint geometry DRM and Sv32 preflight PASS. DUT=false");
        fflush(stdout);
        _Exit(0);
    }
    for(unsigned i=0;i<count;++i) {
        snprintf(name,sizeof(name),"record-%u.capture",i);
        live_path(path,name);
        live_require(!native_kernel_capture(session,i,path,100000000,32),"joint geometry capture enqueue");
        snprintf(name,sizeof(name),"record-%u.held.response",i);
        FILE *f=live_wait(name);
        live_magic(f,"OGJH0001");
        const struct native_kernel_held_receipt held=joint_geometry_held(f);
        live_end(f);
        snprintf(name,sizeof(name),"record-%u.retire",i);
        live_path(path,name);
        live_require(!native_kernel_drain_held(session,i,&held,path),"joint geometry IRQ drain");
        snprintf(name,sizeof(name),"record-%u.retired.response",i);
        f=live_wait(name);
        live_magic(f,"OGJR0001");
        struct native_kernel_observed_receipt observed={.held=joint_geometry_held(f)};
        observed.retired_status=live_u32(f);
        observed.retired_irq=live_u32(f);
        observed.retired_irq_asserted=live_u32(f);
        observed.external_drained=live_u32(f);
        live_end(f);
        live_require(!native_kernel_observe_retired(session,i,&observed),"joint geometry retired observation");
    }
    FILE *f=live_wait("oracle-authorized.response");
    live_magic(f,"OGJBA001");
    live_require(live_u32(f)==count,"joint geometry oracle record count");
    live_end(f);
    live_require(!native_kernel_begin_cleanup(session,live_directory),"joint geometry cleanup begin");
    for(unsigned i=0;i<count;++i)
        live_require(!native_kernel_finish_observed(session,i),"joint geometry acknowledged owner retirement");
    live_require(!native_kernel_end_cleanup(session),"joint geometry acknowledged context cleanup");
    f=live_wait("final-authorized.response");
    live_magic(f,"OGJOK001");
    live_require(live_u32(f)==count,"joint geometry final record count");
    live_end(f);
}
