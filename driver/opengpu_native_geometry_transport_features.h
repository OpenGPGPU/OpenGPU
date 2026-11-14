/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_GEOMETRY_TRANSPORT_FEATURES_H
#define OPENGPU_NATIVE_GEOMETRY_TRANSPORT_FEATURES_H
#include "opengpu_native_sources_features.h"
#include "opengpu_native_geometry_transport_abi.h"

/* Bit19 is independent of the old VS/FS source record. Read no optional word
 * without this presence bit, a complete BAR, ABI2 and all protected prerequisites.
 * Failure preserves every caller cache byte; no partially validated profile is
 * ever usable by the submission path. This is not a GL feature advertisement. */
static inline bool opengpu_native_geometry_transport_limits_impl(u32 id,u32 flags,u32 caps,u32 known_flags,
    unsigned long long mmio_bytes,void *context,u32 (*read_reg)(void *,u32),
    struct opengpu_native_source_limits *limits,bool *transport)
{
    const u32 expected[]={OPENGPU_NATIVE_GEOMETRY_TRANSPORT_MAGIC,
        OPENGPU_NATIVE_GEOMETRY_TRANSPORT_WORDS,OPENGPU_NATIVE_GEOMETRY_TRANSPORT_FEATURES,
        OPENGPU_NATIVE_GEOMETRY_TRANSPORT_JOB_MAGIC,OPENGPU_NATIVE_GEOMETRY_TRANSPORT_FRAME_MAGIC,
        OPENGPU_NATIVE_GEOMETRY_TRANSPORT_COMPLETION_MAGIC,OPENGPU_NATIVE_GEOMETRY_TRANSPORT_LIMITS,
        OPENGPU_NATIVE_GEOMETRY_TRANSPORT_OUTPUT};
    const u32 uniform[]={0x47554301,16,1,0x7f,3,12,24,16384,1024,1024,256,64,
        0x0e00000b,0x1c00002b,0x1e00002b,31};
    const u32 execution[]={0x47534c01,8,31,32,1048576,0x04051918,0x320,0x138};
    const u32 required=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE;
    const u32 prerequisites=OPENGPU_COMPUTE_RESOURCE_NATIVE_DESCRIPTOR_PREFLIGHT_V1|
        OPENGPU_COMPUTE_RESOURCE_NATIVE_PROTECTED_SOURCES_V1|7u;
    struct opengpu_native_source_limits value={0};
    unsigned long long capacity;
    u32 i,lanes,warps;
    bool preflight=false;
    if(!limits || !transport)return false;
    if(!(flags&OPENGPU_NATIVE_GEOMETRY_TRANSPORT_PRESENCE)){
        *limits=value;*transport=false;return true;
    }
    if((id&0xffffu)<GPU_ABI_COMPUTE_RESOURCE_VERSION || !read_reg ||
        (caps&required)!=required || (flags&~known_flags) ||
        (flags&prerequisites)!=prerequisites || mmio_bytes<0x520)return false;
    for(i=0;i<8;++i){
        value.geometry_words[i]=read_reg(context,OPENGPU_NATIVE_GEOMETRY_TRANSPORT_CAPABILITIES+4*i);
        if(value.geometry_words[i]!=expected[i])return false;
    }
    if(!opengpu_read_native_preflight_support(id,flags,caps,mmio_bytes,context,read_reg,&preflight) ||
        !preflight || read_reg(context,GPU_REG_BIN_CAPABILITY_PTR)!=GPU_REG_BIN_CAPABILITIES)return false;
    for(i=0;i<8;++i)value.frame.words[i]=read_reg(context,GPU_REG_BIN_CAPABILITIES+4*i);
    if(!opengpu_native_source_frame_valid_profile(&value.frame,true) ||
        ((value.frame.words[4]>>16)&31u)!=16 ||
        ((value.frame.words[4]>>21)&31u)!=16 ||
        ((value.frame.words[4]>>26)&31u)!=16 ||
        ((value.frame.words[6]>>21)&7u)!=4)return false;
    lanes=read_reg(context,OPENGPU_COMPUTE_RESOURCE_REG_LANES);
    warps=read_reg(context,OPENGPU_COMPUTE_RESOURCE_REG_WARPS);
    capacity=(unsigned long long)lanes*warps;
    if(!lanes || !warps || capacity>0xffffffffull ||
        capacity!=read_reg(context,OPENGPU_COMPUTE_RESOURCE_REG_LOCAL_ITEMS) ||
        read_reg(context,OPENGPU_COMPUTE_RESOURCE_REG_LOCAL_AXIS)!=(capacity<65535u?(u32)capacity:65535u) ||
        read_reg(context,OPENGPU_COMPUTE_RESOURCE_REG_COMPUTE_UNITS)!=(value.frame.words[7]&255u))return false;
    for(i=0;i<16;++i)if(read_reg(context,0x3a0+4*i)!=uniform[i])return false;
    for(i=0;i<8;++i)if(read_reg(context,0x300+4*i)!=execution[i])return false;
    if(!read_reg(context,0x320))return false;
    value.lanes=lanes;value.warps=warps;value.capacity=(u32)capacity;
    value.engines=(value.frame.words[6]>>27)&7u;value.available=true;
    *limits=value;*transport=true;return true;
}
/* Keep the published old entry point closed to every newer presence bit. */
static inline bool opengpu_read_native_geometry_transport_limits(u32 id,u32 flags,u32 caps,
    unsigned long long mmio_bytes,void *context,u32 (*read_reg)(void *,u32),
    struct opengpu_native_source_limits *limits,bool *transport)
{
    return opengpu_native_geometry_transport_limits_impl(id,flags,caps,0xfffffu,
        mmio_bytes,context,read_reg,limits,transport);
}
#endif
