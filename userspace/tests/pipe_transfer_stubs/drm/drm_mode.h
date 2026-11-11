/* Compile-only placeholders for unreachable presentation helpers. */
#ifndef PIPE_TRANSFER_TEST_DRM_MODE_H
#define PIPE_TRANSFER_TEST_DRM_MODE_H
#include <stdint.h>
#define DRM_IOCTL_MODE_ADDFB2 4
#define DRM_IOCTL_MODE_GETCONNECTOR 5
#define DRM_IOCTL_MODE_GETCRTC 6
#define DRM_IOCTL_MODE_GETRESOURCES 7
#define DRM_IOCTL_MODE_RMFB 8
#define DRM_IOCTL_MODE_SETCRTC 9
struct drm_mode_modeinfo { uint32_t hdisplay, vdisplay; };
struct drm_mode_card_res {
    uint64_t connector_id_ptr, crtc_id_ptr, encoder_id_ptr, fb_id_ptr;
    uint32_t count_connectors, count_crtcs, count_encoders, count_fbs;
};
struct drm_mode_get_connector {
    uint64_t modes_ptr, encoders_ptr, props_ptr, prop_values_ptr;
    uint32_t connector_id, count_modes, count_encoders, count_props;
};
struct drm_mode_fb_cmd2 {
    uint32_t fb_id, width, height, pixel_format, handles[4], pitches[4];
};
struct drm_mode_crtc {
    uint64_t set_connectors_ptr;
    uint32_t count_connectors, crtc_id, fb_id, mode_valid;
    struct drm_mode_modeinfo mode;
};
#endif
