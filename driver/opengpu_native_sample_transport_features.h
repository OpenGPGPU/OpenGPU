/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_SAMPLE_TRANSPORT_FEATURES_H
#define OPENGPU_NATIVE_SAMPLE_TRANSPORT_FEATURES_H
#include "opengpu_native_geometry_transport_features.h"
#include "opengpu_native_sample_transport_abi.h"

/* Composition of the original GGS1 contract with an independently validated
 * GSS1 record. Flags are never masked or relabelled to impersonate an old host.
 * No GSS1 word is touched without bit20 and a complete BAR. Every error leaves
 * all three caller outputs unchanged. No GLES/GLSL feature is advertised here.
 */
static inline bool opengpu_read_native_sample_transport_limits(u32 id,u32 flags,u32 caps,
    unsigned long long mmio_bytes,void *context,u32 (*read_reg)(void *,u32),
    struct opengpu_native_source_limits *limits,bool *layered,bool *sample)
{
    const u32 expected[]={OPENGPU_NATIVE_SAMPLE_TRANSPORT_MAGIC,OPENGPU_NATIVE_SAMPLE_TRANSPORT_WORDS,
        OPENGPU_NATIVE_SAMPLE_TRANSPORT_VERSION,OPENGPU_NATIVE_SAMPLE_TRANSPORT_FEATURES,
        OPENGPU_NATIVE_SAMPLE_TRANSPORT_INPUT_VERSIONS,OPENGPU_NATIVE_SAMPLE_TRANSPORT_LIMITS,
        OPENGPU_NATIVE_SAMPLE_TRANSPORT_OUTPUT_CONTROL_MASK,OPENGPU_NATIVE_SAMPLE_TRANSPORT_SAMPLE_MODES};
    const u32 frame[]={0x474e4305,8,0x474e4a02,0x08000200,0xc2100800,0x08000800,0xe4908000,0x8813db02};
    const u32 required=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE|
        GPU_CAP_COMPUTE_SCALAR_FPU|GPU_CAP_SHADER_CONFIG_INFO|GPU_CAP_NATIVE_PIXEL_FORMATS;
    struct opengpu_native_source_limits value={0};
    bool geometry=false;
    u32 words[8],i,shader;
    if(!limits || !layered || !sample)return false;
    if(!(flags&OPENGPU_NATIVE_SAMPLE_TRANSPORT_PRESENCE)){
        if(!opengpu_read_native_geometry_transport_limits(id,flags,caps,mmio_bytes,context,read_reg,
                &value,&geometry))return false;
        *limits=value;*layered=geometry;*sample=false;return true;
    }
    if((id&0xffffu)<GPU_ABI_COMPUTE_RESOURCE_VERSION || flags!=0x1fffffu ||
        (caps&required)!=required || !read_reg || mmio_bytes<0x540)return false;
    for(i=0;i<8;++i){
        words[i]=read_reg(context,OPENGPU_NATIVE_SAMPLE_TRANSPORT_CAPABILITIES+4*i);
        if(words[i]!=expected[i])return false;
    }
    if(!opengpu_native_geometry_transport_limits_impl(id,flags,caps,0x1fffffu,
            mmio_bytes,context,read_reg,&value,&geometry) || !geometry ||
        value.lanes!=16 || value.warps!=8 || value.capacity!=128 || value.engines!=4)return false;
    for(i=0;i<8;++i)if(value.frame.words[i]!=frame[i])return false;
    shader=read_reg(context,GPU_REG_SHADER_CONFIG);
    if((shader&255u)!=16 || ((shader>>8)&255u)!=8 || (shader>>24)!=2 ||
        ((shader>>16)&255u)<2 || (((shader>>16)&255u)&1))return false;
    for(i=0;i<8;++i)value.sample_words[i]=words[i];
    *limits=value;*layered=true;*sample=true;return true;
}

/* Shader compiler/native assembler SoA interface. Array indices, not bytes:
 * offsets are multiplied by capacity*4. ABI6/7 use all covered sample planes,
 * including 1x center. Builtins are always present; dynamic interpolation,
 * API enable/minimum state and static-use policy are separate missing services.
 */
struct opengpu_native_sample_shader_layout {
    u32 version,capacity,input_arrays,depth_array,control_array,color_array;
    u32 sample_id_array,sample_position_x_array,sample_position_y_array;
    u32 sample_mask_in_array,prefix_bytes;
};
static inline bool opengpu_native_sample_shader_layout(u32 version,u32 varyings,u32 colors,u32 capacity,
    struct opengpu_native_sample_shader_layout *out)
{
    struct opengpu_native_sample_shader_layout value={0};
    unsigned long long bytes;
    if(!out || (version!=6 && version!=7) || varyings>16 || !colors || colors>4 ||
        !capacity || capacity>128)return false;
    value.version=version;value.capacity=capacity;value.sample_mask_in_array=5;
    value.sample_id_array=8+4*varyings+(version==7?2:0);
    value.sample_position_x_array=value.sample_id_array+1;
    value.sample_position_y_array=value.sample_id_array+2;
    value.input_arrays=value.sample_id_array+3;
    value.color_array=value.input_arrays;value.depth_array=value.input_arrays+4*colors;
    value.control_array=value.depth_array+1;
    bytes=(unsigned long long)(value.input_arrays+2+4*colors)*capacity*4;
    if(bytes>0xffffffffull)return false;
    value.prefix_bytes=(u32)bytes;*out=value;return true;
}
static inline bool opengpu_native_sample_control_word(u32 color_mask,bool depth_written,bool enabled,
    bool mask_written,u32 sample_mask,u32 *out)
{
    if(!out || color_mask>15 || sample_mask>15)return false;
    *out=(enabled?1u:0u)|(depth_written?2u:0u)|(color_mask<<2)|
        (mask_written?64u:0u)|(sample_mask<<7);return true;
}
#endif
