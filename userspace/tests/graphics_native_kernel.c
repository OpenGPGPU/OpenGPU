/* SPDX-License-Identifier: MIT */
/* A separate translation unit avoids Gallium/kernel type substitutions. Reuse
 * the actual submit fixture's OS primitives; never recreate native records,
 * private addresses or page tables in this bridge. */
#define OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN unused_native_submit_component_main
#include "opengpu_native_submit_fixture.c"
#undef OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN
#include "graphics_native_kernel.h"

void graphics_native_source_observer(void *opaque,uint32_t handle,
    uint64_t offset,uint32_t bytes,const void *stable_original_bytes)
{
    assert(opaque);
    assert(!native_graphics_source_observe(opaque,handle,offset,bytes,stable_original_bytes));
}
int graphics_native_source_write(struct native_kernel_session *session,FILE *stream,
    struct native_graphics_source_record *record)
{
    uint64_t physical=0,bytes=0;
    if(!session || !stream || !record)return -EINVAL;
    int ret=native_kernel_original_physical(session,record->color_handle,&physical,&bytes);
    if(!ret)ret=native_graphics_source_allocation(record,record->color_handle,physical,bytes);
    if(!ret)ret=native_graphics_source_write(stream,record);
    return ret;
}

struct native_kernel_profile *graphics_native_profile_read(const char *path)
{
    struct graphics_native_probe probe;
    if(graphics_native_probe_read(path,&probe))return NULL;
    struct native_kernel_profile_word words[96],registers[5];
    for(unsigned i=0;i<96;i++)words[i]=(struct native_kernel_profile_word){probe.words[i][0],probe.words[i][1]};
    for(unsigned i=0;i<5;i++)registers[i]=(struct native_kernel_profile_word){probe.registers[i][0],probe.registers[i][1]};
    return native_kernel_profile_create(words,96,registers,5);
}

#ifdef OPENGPU_NATIVE_MMIO
static int host_path(char path[4096],const char *directory,const char *name)
{
    int bytes=snprintf(path,4096,"%s/%s",directory,name);
    return bytes<0 || bytes>=4096?-ENAMETOOLONG:0;
}
static struct native_kernel_held_receipt held_receipt(const struct graphics_native_observation *value)
{
    struct native_kernel_held_receipt result={.record_index=value->record,.command_id=value->command_id,
        .completion=value->completion,.append_read_bytes=value->append_read_bytes,
        .held_legacy_status=value->held_legacy_status,.held_status=value->held_status,.held_irq=value->held_irq,.held_irq_asserted=value->held_irq_asserted};
    memcpy(result.gnx,value->gnx,sizeof(result.gnx));return result;
}
static int same_held(const struct graphics_native_observation *a,const struct graphics_native_observation *b)
{
    return a->record==b->record && a->command_id==b->command_id && a->completion==b->completion &&
        !memcmp(a->gnx,b->gnx,sizeof(a->gnx)) && a->append_read_bytes==b->append_read_bytes &&
        a->held_legacy_status==b->held_legacy_status && a->held_status==b->held_status && a->held_irq==b->held_irq && a->held_irq_asserted==b->held_irq_asserted;
}
static int same_observed(const struct graphics_native_observation *a,const struct graphics_native_observation *b)
{
    return same_held(a,b) && a->retired_status==b->retired_status && a->retired_irq==b->retired_irq &&
        a->retired_irq_asserted==b->retired_irq_asserted && a->external_drained==b->external_drained;
}
int graphics_native_host_execute(struct native_kernel_session *session,const char *directory,
    struct native_graphics_source_record records[2])
{
    char path[4096],temporary[4096];int ret;
    if(!session || !directory || !records)return -EINVAL;
    for(unsigned index=0;index<2;index++) {
        ret=host_path(path,directory,index?"native-next.source.json":"native.source.json");if(ret)return ret;
        FILE *stream=fopen(path,"wx");if(!stream)return -errno;
        ret=graphics_native_source_write(session,stream,&records[index]);
        if(fclose(stream) && !ret)ret=-EIO;
        if(ret)return ret;
    }
    ret=host_path(path,directory,"union.memory");if(ret)return ret;
    ret=native_kernel_export_memory(session,path);if(ret)return ret;
    ret=host_path(path,directory,"record-0.capture");if(ret)return ret;
    ret=native_kernel_capture(session,0,path,UINT64_C(100000000),37);if(ret)return ret;
    ret=host_path(temporary,directory,"ready.json.new");if(ret)return ret;
    FILE *stream=fopen(temporary,"wx");if(!stream)return -errno;
    const char ready[]="{\"schema\":\"opengpu-native-owner-ready-v1\",\"memory\":\"union.memory\","
        "\"captures\":[\"record-0.capture\",\"record-1.capture\"],"
        "\"sources\":[\"native.source.json\",\"native-next.source.json\"]}\n";
    ret=fwrite(ready,1,sizeof(ready)-1,stream)==sizeof(ready)-1?0:-EIO;
    if(fflush(stream) && !ret)ret=-EIO;
    if(fclose(stream) && !ret)ret=-EIO;
    if(ret)return ret;
    ret=host_path(path,directory,"ready.json");if(ret)return ret;
    if(rename(temporary,path))return -errno;
    struct graphics_native_observation observed[2];
    for(unsigned index=0;index<2;index++) {
        struct graphics_native_observation held;
        ret=graphics_native_held_wait(directory,index,300,&held);if(ret)return ret;
        struct native_kernel_held_receipt witness=held_receipt(&held);
        char name[64];snprintf(name,sizeof(name),"record-%u.retire",index);
        ret=host_path(path,directory,name);if(ret)return ret;
        ret=native_kernel_drain_held(session,index,&witness,path);if(ret)return ret;
        ret=graphics_native_execution_wait(directory,index,300,&observed[index]);if(ret)return ret;
        if(!same_held(&held,&observed[index]))return -EINVAL;
        struct native_kernel_observed_receipt receipt={.held=witness,
            .retired_status=observed[index].retired_status,.retired_irq=observed[index].retired_irq,
            .retired_irq_asserted=observed[index].retired_irq_asserted,.external_drained=observed[index].external_drained};
        ret=native_kernel_observe_retired(session,index,&receipt);if(ret)return ret;
        if(!index) {
            ret=host_path(path,directory,"record-1.capture");if(ret)return ret;
            ret=native_kernel_capture(session,1,path,UINT64_C(100000000),37);if(ret)return ret;
        }
    }
    /* Image/guard authorization is separate from all execution responses. Each
     * real TLB flush blocks until the same live DUT confirms its cleanup batch. */
    ret=graphics_native_cleanup_wait(directory,300);if(ret)return ret;
    ret=native_kernel_begin_cleanup(session,directory);if(ret)return ret;
    for(unsigned index=0;index<2;index++) {
        ret=native_kernel_finish_observed(session,index);if(ret)return ret;
    }
    ret=native_kernel_end_cleanup(session);if(ret)return ret;
    struct graphics_native_observation final[2];
    ret=graphics_native_final_wait(directory,300,final);if(ret)return ret;
    for(unsigned index=0;index<2;index++)if(!same_observed(&final[index],&observed[index]))return -EINVAL;
    return 0;
}

#endif /* OPENGPU_NATIVE_MMIO */
