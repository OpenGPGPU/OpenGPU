// SPDX-License-Identifier: GPL-2.0
#include <linux/errno.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include "opengpu_native_program_snapshot.h"
#include "opengpu_private_snapshot.h"
#include "opengpu_shader_validator.h"

int opengpu_native_program_snapshot_append(struct opengpu_private_snapshot *owner,
    const struct drm_opengpu_native_program_view *view,
    const struct opengpu_native_program_profile *profile,
    int (*copy_source)(void *,const struct drm_opengpu_compute_uniform_view *,void *),
    void *copy_context,struct opengpu_native_program_snapshot *output)
{
    struct opengpu_native_program_snapshot result;
    struct drm_opengpu_compute_uniform_view source;
    u32 *words;
    u8 *bytes;
    int ret;
    if(!owner || !view || !profile || !copy_source || !output || !view->handle || view->pad ||
        !view->bytes || view->bytes>4u*OPENGPU_SHADER_MAX_INSTRUCTIONS ||
        ((view->offset|view->bytes|view->entry_offset)&3u) ||
        view->entry_offset>=view->bytes || view->offset>~(u64)0-view->bytes ||
        !profile->capacity || profile->capacity>255 || profile->stage>1 ||
        profile->attributes>16 || profile->varyings>16 || profile->colors>4 ||
        !profile->staging_bytes || profile->staging_bytes>0xffffffffu ||
        (!profile->stage && ((profile->version!=1 && profile->version!=2) || profile->colors)) ||
        (profile->geometry_inputs && profile->stage!=1) ||
        (profile->stage && (!profile->version || profile->version>(profile->geometry_inputs?5u:4u) || profile->attributes)))return -EINVAL;
    /* Keep original instruction bytes distinct from host-endian analysis.
     * Neither a nonzero entry nor a big-endian CPU may rewrite the RX copy. */
    bytes=kvzalloc(view->bytes*2u,GFP_KERNEL);if(!bytes)return -ENOMEM;
    words=(u32 *)(bytes+view->bytes);
    source=(struct drm_opengpu_compute_uniform_view){.offset=view->offset,.handle=view->handle,.bytes=view->bytes};
    ret=copy_source(copy_context,&source,bytes);
    if(ret)goto done;
    for(u32 i=0;i<view->bytes/4;++i)
        words[i]=(u32)bytes[4*i]|(u32)bytes[4*i+1]<<8|(u32)bytes[4*i+2]<<16|(u32)bytes[4*i+3]<<24;
    if(!opengpu_native_shader_validate_words(words+view->entry_offset/4,
        (view->bytes-view->entry_offset)/4,profile->staging_bytes,profile->capacity,
        profile->stage,profile->version,profile->attributes,profile->varyings,profile->colors,
        profile->scalar_fpu,profile->owned_uniforms,profile->geometry_inputs)) {ret=-EINVAL;goto done;}
    ret=opengpu_private_snapshot_append_code(owner,bytes,view->bytes,&result.segment);
    if(!ret){result.entry_offset=view->entry_offset;*output=result;}
done:
    kvfree(bytes);return ret;
}
