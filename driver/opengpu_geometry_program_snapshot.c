// SPDX-License-Identifier: GPL-2.0
#include <linux/errno.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include "opengpu_geometry_program_snapshot.h"
#include "opengpu_private_snapshot.h"
#include "opengpu_shader_validator.h"

int opengpu_geometry_program_snapshot_append(struct opengpu_private_snapshot *owner,
    const struct drm_opengpu_native_program_view *view,
    const struct opengpu_geometry_program_profile *profile,
    int (*copy_source)(void *,const struct drm_opengpu_compute_uniform_view *,void *),
    void *copy_context,struct opengpu_native_program_snapshot *output)
{
    struct opengpu_native_program_snapshot result;
    struct drm_opengpu_compute_uniform_view source;
    u32 *words;
    u8 *bytes;
    u32 allocation_bytes;
    int ret;
    if(!owner || !view || !profile || !copy_source || !output || !view->handle || view->pad ||
        !view->bytes || view->bytes>4u*OPENGPU_SHADER_MAX_INSTRUCTIONS ||
        ((view->offset|view->bytes|view->entry_offset)&3u) ||
        view->entry_offset>=view->bytes || view->offset>~(u64)0-view->bytes ||
        !profile->geometry_enabled || profile->version!=1u)return -EINVAL;
    /* Keep original instruction bytes distinct from host-endian analysis.
     * Neither a nonzero entry nor a big-endian CPU may rewrite the RX copy. */
    /* The GNG code allocation view covers whole cache lines, while its
     * logical code limit and validated suffix still stop at the original end. */
    allocation_bytes=(view->bytes+63u)&~63u;
    bytes=kvzalloc(allocation_bytes+view->bytes,GFP_KERNEL);if(!bytes)return -ENOMEM;
    words=(u32 *)(bytes+allocation_bytes);
    source=(struct drm_opengpu_compute_uniform_view){.offset=view->offset,.handle=view->handle,.bytes=view->bytes};
    ret=copy_source(copy_context,&source,bytes);
    if(ret)goto done;
    for(u32 i=0;i<view->bytes/4;++i)
        words[i]=(u32)bytes[4*i]|(u32)bytes[4*i+1]<<8|(u32)bytes[4*i+2]<<16|(u32)bytes[4*i+3]<<24;
    if(!opengpu_geometry_shader_validate_words(words+view->entry_offset/4,
        (view->bytes-view->entry_offset)/4,profile->version,profile->scalar_fpu,
        profile->owned_uniforms,profile->geometry_enabled)) {ret=-EINVAL;goto done;}
    ret=opengpu_private_snapshot_append_code(owner,bytes,allocation_bytes,&result.segment);
    if(!ret){result.entry_offset=view->entry_offset;*output=result;}
done:
    kvfree(bytes);return ret;
}
