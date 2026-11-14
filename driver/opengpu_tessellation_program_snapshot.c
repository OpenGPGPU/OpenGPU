// SPDX-License-Identifier: GPL-2.0
#include <linux/errno.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include <linux/string.h>
#include "opengpu_tessellation_program_snapshot.h"
#include "opengpu_private_snapshot.h"

int opengpu_tessellation_program_snapshot_append(struct opengpu_private_snapshot *owner,
    const struct drm_opengpu_native_program_view *view,
    const struct opengpu_tessellation_program_profile *profile,
    int (*copy_source)(void *,const struct drm_opengpu_compute_uniform_view *,void *),
    void *copy_context,struct opengpu_native_program_snapshot *output)
{
    struct drm_opengpu_native_program_view captured;
    struct opengpu_tessellation_program_profile policy;
    struct opengpu_native_program_snapshot result;
    struct drm_opengpu_compute_uniform_view source;
    u8 *bytes;u32 *words;int ret;
    if(!owner || !view || !profile || !copy_source || !output)return -EINVAL;
    captured=*view;policy=*profile;view=&captured;profile=&policy;
    if(!opengpu_tessellation_program_profile_valid(profile) || !view->handle || view->pad ||
        !view->bytes || view->bytes>2048 || ((view->offset|view->bytes|view->entry_offset)&3u) ||
        view->entry_offset>=view->bytes || view->offset>~(u64)0-view->bytes)return -EINVAL;
    bytes=kvzalloc(2u*view->bytes,GFP_KERNEL);if(!bytes)return -ENOMEM;
    words=(u32 *)(bytes+view->bytes);
    source=(struct drm_opengpu_compute_uniform_view){.offset=view->offset,.handle=view->handle,.bytes=view->bytes};
    ret=copy_source(copy_context,&source,bytes);if(ret)goto done;
    for(u32 i=0;i<view->bytes/4;i++)words[i]=(u32)bytes[4*i]|(u32)bytes[4*i+1]<<8|(u32)bytes[4*i+2]<<16|(u32)bytes[4*i+3]<<24;
    if(!opengpu_tessellation_shader_validate_words(words+view->entry_offset/4,
        (view->bytes-view->entry_offset)/4,profile)){ret=-EINVAL;goto done;}
    ret=opengpu_private_snapshot_append_code(owner,bytes,view->bytes,&result.segment);
    if(!ret){result.entry_offset=view->entry_offset;*output=result;}
done:
    kvfree(bytes);return ret;
}
