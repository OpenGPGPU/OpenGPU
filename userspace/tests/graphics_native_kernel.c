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
