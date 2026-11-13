/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_SOURCES_FEATURES_H
#define OPENGPU_NATIVE_SOURCES_FEATURES_H
#ifndef __KERNEL__
#include <stdbool.h>
#endif
#include "gpu_abi.h"
#include "opengpu_compute_resource_abi.h"
#include "opengpu_native_binned_abi.h"
#include "opengpu_native_binned_host_abi.h"
#include "opengpu_native_preflight_features.h"
#include "opengpu_uniform_abi.h"

struct opengpu_native_source_limits {
    struct opengpu_native_binned_host_caps frame;
    u32 capacity, engines;
    bool available;
};
#define OPENGPU_NATIVE_FIELD(c, name) (((c)->words[OPENGPU_NATIVE_BINNED_HOST_##name##_WORD] & \
    OPENGPU_NATIVE_BINNED_HOST_##name##_MASK) >> OPENGPU_NATIVE_BINNED_HOST_##name##_SHIFT)
static inline bool opengpu_native_source_frame_valid(const struct opengpu_native_binned_host_caps *c)
{
    const u32 storage=OPENGPU_NATIVE_FIELD(c,STORAGE_BYTES),samples=OPENGPU_NATIVE_FIELD(c,SAMPLES);
    const u32 engines=OPENGPU_NATIVE_FIELD(c,ENGINES),owners=OPENGPU_NATIVE_FIELD(c,PAYLOAD_OWNERS);
    const u32 log2=OPENGPU_NATIVE_FIELD(c,PAYLOAD_CAPACITY_LOG2),lines=OPENGPU_NATIVE_FIELD(c,PAYLOAD_SLOT_LINES);
    if ((c->words[0]!=OPENGPU_NATIVE_BINNED_HOST_MAGIC && c->words[0]!=OPENGPU_NATIVE_BINNED_HOST_WIDE_MAGIC) ||
        c->words[1]!=8 || c->words[2]!=OPENGPU_NATIVE_BINNED_JOB_MAGIC ||
        OPENGPU_NATIVE_FIELD(c,JOB_BYTES)!=512 || OPENGPU_NATIVE_FIELD(c,FRAME_BYTES)!=2048 ||
        OPENGPU_NATIVE_FIELD(c,DRAW_BYTES)!=2048 ||
        !OPENGPU_NATIVE_FIELD(c,ATTRIBUTES) || OPENGPU_NATIVE_FIELD(c,ATTRIBUTES)>16 ||
        !OPENGPU_NATIVE_FIELD(c,BINDINGS) || OPENGPU_NATIVE_FIELD(c,BINDINGS)>16 ||
        !OPENGPU_NATIVE_FIELD(c,VARYINGS) || OPENGPU_NATIVE_FIELD(c,VARYINGS)>16 ||
        !OPENGPU_NATIVE_FIELD(c,COLORS) || OPENGPU_NATIVE_FIELD(c,COLORS)>4 ||
        !OPENGPU_NATIVE_FIELD(c,COMPLETION_INFO) ||
        !OPENGPU_NATIVE_FIELD(c,FRAME_WIDTH) || !OPENGPU_NATIVE_FIELD(c,FRAME_HEIGHT) ||
        !OPENGPU_NATIVE_FIELD(c,CUS) || !OPENGPU_NATIVE_FIELD(c,NATIVE_ID_BITS) ||
        !engines || engines>4 || (!OPENGPU_NATIVE_FIELD(c,TILE8) && !OPENGPU_NATIVE_FIELD(c,TILE16)) ||
        storage<1024 || storage>65536 || (storage&(storage-1)) ||
        (samples!=1 && samples!=2 && samples!=4))return false;
    if(OPENGPU_NATIVE_FIELD(c,LOCAL_PAYLOAD)){
        if(owners<engines+1 || log2<6 || log2>30 || !lines ||
            (unsigned long long)lines*64>(1ull<<log2))return false;
    }else if(owners || log2 || lines)return false;
    return true;
}

/* Bit17 guarantees these optional records together, not a Linux ioctl or API
 * capability. GNP alone and compute uniform transport do not guarantee GUC/GSL.
 * No optional register is read without bit17. Malformed combinations fail
 * closed and leave the caller's cached limits unchanged. */
static inline bool opengpu_read_native_source_limits(u32 id,u32 flags,u32 caps,
    unsigned long long mmio_bytes,void *context,u32 (*read_reg)(void *,u32),
    struct opengpu_native_source_limits *limits)
{
    const u32 required=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE;
    const u32 uniform[]={0x47554301,16,1,0x7f,3,12,24,16384,1024,1024,256,64,
        0x0e00000b,0x1c00002b,0x1e00002b,31};
    const u32 execution[]={0x47534c01,8,31,32,1048576,0x04051918,0x320,0x138};
    struct opengpu_native_source_limits value={0};
    unsigned long long capacity;
    u32 i,lanes,warps;
    bool preflight=false;
    if(!limits)return false;
    if(!(flags&OPENGPU_COMPUTE_RESOURCE_NATIVE_PROTECTED_SOURCES_V1)){
        *limits=value;return true;
    }
    if((id&0xffffu)<GPU_ABI_COMPUTE_RESOURCE_VERSION || !read_reg ||
        (caps&required)!=required || (flags&~0x3ffffu) ||
        !(flags&OPENGPU_COMPUTE_RESOURCE_NATIVE_DESCRIPTOR_PREFLIGHT_V1) ||
        (flags&7u)!=7u || mmio_bytes<0x500 ||
        !opengpu_read_native_preflight_support(id,flags,caps,mmio_bytes,context,read_reg,&preflight) ||
        !preflight || read_reg(context,GPU_REG_BIN_CAPABILITY_PTR)!=GPU_REG_BIN_CAPABILITIES)return false;
    for(i=0;i<8;++i)value.frame.words[i]=read_reg(context,GPU_REG_BIN_CAPABILITIES+4*i);
    if(!opengpu_native_source_frame_valid(&value.frame))return false;
    lanes=read_reg(context,OPENGPU_COMPUTE_RESOURCE_REG_LANES);
    warps=read_reg(context,OPENGPU_COMPUTE_RESOURCE_REG_WARPS);
    capacity=(unsigned long long)lanes*warps;
    if(!lanes || !warps || capacity>0xffffffffull ||
        capacity!=read_reg(context,OPENGPU_COMPUTE_RESOURCE_REG_LOCAL_ITEMS) ||
        read_reg(context,OPENGPU_COMPUTE_RESOURCE_REG_LOCAL_AXIS)!=(capacity<65535u?(u32)capacity:65535u) ||
        read_reg(context,OPENGPU_COMPUTE_RESOURCE_REG_COMPUTE_UNITS)!=OPENGPU_NATIVE_FIELD(&value.frame,CUS))return false;
    for(i=0;i<16;++i)if(read_reg(context,0x3a0+4*i)!=uniform[i])return false;
    for(i=0;i<8;++i)if(read_reg(context,0x300+4*i)!=execution[i])return false;
    if(!read_reg(context,0x320))return false;
    value.capacity=(u32)capacity;value.engines=OPENGPU_NATIVE_FIELD(&value.frame,ENGINES);value.available=true;
    *limits=value;return true;
}
#undef OPENGPU_NATIVE_FIELD
#endif
