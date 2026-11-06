/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stddef.h>
#include <string.h>
#include "../../userspace/opengpu_buffer_texture.h"

/* Linux asm-generic ioctl encoding (including arm64), independently of the
 * host fixture's deliberately inert DRM_IOWR macro. */
#undef DRM_IOWR
#define DRM_IOWR(n,t) ((3u<<30) | ((unsigned)sizeof(t)<<16) | ('d'<<8) | (n))
_Static_assert(sizeof(struct drm_opengpu_compute_texture_level)==64, "old level bytes");
_Static_assert(sizeof(struct drm_opengpu_compute_texture_view)==1128, "old texture bytes");
_Static_assert(sizeof(struct drm_opengpu_compute_image_view)==80, "old image bytes");
_Static_assert(offsetof(struct drm_opengpu_compute_texture_level,offset)==0, "offset unchanged");
_Static_assert(offsetof(struct drm_opengpu_compute_texture_level,row_pitch)==8, "pitch unchanged");
_Static_assert(offsetof(struct drm_opengpu_compute_texture_level,buffer_bytes)==8, "range alias");
_Static_assert(offsetof(struct drm_opengpu_compute_texture_level,width)==36, "width unchanged");
_Static_assert(offsetof(struct drm_opengpu_compute_texture_level,buffer_texels)==36, "count alias");
_Static_assert(offsetof(struct drm_opengpu_compute_texture_view,width)==12, "root width unchanged");
_Static_assert(offsetof(struct drm_opengpu_compute_texture_view,levels)==104, "levels unchanged");
_Static_assert(offsetof(struct drm_opengpu_compute_image_view,width)==56, "image width unchanged");
_Static_assert(offsetof(struct drm_opengpu_compute_image_view,row_pitch)==8, "image pitch unchanged");
_Static_assert(DRM_IOCTL_OPENGPU_COMPUTE_TEXTURES==0xc058644eu, "old texture ioctl");
_Static_assert(DRM_IOCTL_OPENGPU_COMPUTE_IMAGES==0xc1886451u, "old image ioctl");

int main(void)
{
    const unsigned strides[]={1,2,4,8,12,16};
    unsigned count=17;
    for (unsigned i=0;i<sizeof(strides)/sizeof(*strides);++i) {
        unsigned stride=strides[i];
        unsigned long long bytes=65536ULL*stride+stride-1;
        assert(opengpu_buffer_view_count((1ULL<<32)-bytes,bytes,0,bytes,stride,&count));
        assert(count==65536);
        count=17;
        assert(!opengpu_buffer_view_count((1ULL<<32)-bytes+1,bytes,0,bytes,stride,&count));
        assert(count==17);
        assert(!opengpu_buffer_view_count(0,bytes-1,0,bytes,stride,&count));
        assert(count==17);
    }
    assert(opengpu_buffer_view_count(0,1ULL<<32,0,1ULL<<32,1,&count) && count==134217728u);
    assert(!opengpu_buffer_view_count(0,1ULL<<32,1,1ULL<<32,1,&count));
    assert(!opengpu_buffer_view_count(0,1ULL<<32,0,(1ULL<<32)+1,1,&count));
    assert(!opengpu_buffer_view_count(0,64,0,3,4,&count));
    assert(!opengpu_buffer_view_count(0,64,0,0,4,&count));
    assert(!opengpu_buffer_view_count(0,64,0,64,0,&count));
    assert(!opengpu_buffer_view_count(0,64,64,1,1,&count));
    assert(!opengpu_buffer_view_count(0,64,~0ULL,64,1,&count));
    {
        struct drm_opengpu_compute_texture_view view,saved;
        assert(opengpu_buffer_texture_view_init(&view,34,OPENGPU_TEXTURE_FORMAT_R16F,131073,1,131072));
        assert(view.buffer_texels==65536 && view.width==65536 && view.levels[0].row_pitch==131072);
        assert(view.levels[0].offset==1 && view.levels[0].buffer_texels==65536);
        saved=view;
        assert(!opengpu_buffer_texture_view_init(&view,34,OPENGPU_TEXTURE_FORMAT_RGB8,131073,1,131072));
        assert(!memcmp(&view,&saved,sizeof(view)));
    }
    {
        struct drm_opengpu_compute_image_view view,saved;
        assert(opengpu_buffer_image_view_init(&view,13,OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_BY_SIZE,
            OPENGPU_TEXTURE_FORMAT_R32F,OPENGPU_TEXTURE_FORMAT_RGBA8,262208,63,262144));
        assert(view.buffer_texels==65536 && view.buffer_bytes==262144 && view.flags==5 && view.offset==63);
        saved=view;
        assert(!opengpu_buffer_image_view_init(&view,13,OPENGPU_COMPUTE_IMAGE_READ,
            OPENGPU_TEXTURE_FORMAT_R32F,OPENGPU_TEXTURE_FORMAT_RGBA8,262208,63,262144));
        assert(!memcmp(&view,&saved,sizeof(view)));
        assert(!opengpu_buffer_image_view_init(&view,13,OPENGPU_COMPUTE_IMAGE_READ,
            OPENGPU_TEXTURE_FORMAT_R16F,OPENGPU_TEXTURE_FORMAT_R16F,131073,1,131072));
        assert(!memcmp(&view,&saved,sizeof(view)));
        assert(opengpu_buffer_image_view_init(&view,13,OPENGPU_COMPUTE_IMAGE_WRITE,
            OPENGPU_TEXTURE_FORMAT_R32UI,OPENGPU_TEXTURE_FORMAT_R32I,262208,64,262144));
        assert(view.flags==OPENGPU_COMPUTE_IMAGE_WRITE && view.buffer_texels==65536);
    }
    return 0;
}
