/* Freestanding exact UAPI layout probe on 32- and 64-bit targets. */
#include "../opengpu_drm.h"
#define OFFSET(type,field,bytes) _Static_assert(__builtin_offsetof(type,field)==bytes,"wire offset " #field)
_Static_assert(sizeof(struct drm_opengpu_native_draw_plan)==792,"GNJ1 plan unchanged");
_Static_assert(sizeof(struct drm_opengpu_native_layered_draw_plan)==2056,"GNJ2 plan");
_Static_assert(sizeof(struct drm_opengpu_native_completion_view)==24,"GNY original view");
_Static_assert(sizeof(struct drm_opengpu_submit_native_binned)==128,"submit unchanged");
OFFSET(struct drm_opengpu_native_layered_draw_plan,draw,0);
OFFSET(struct drm_opengpu_native_layered_draw_plan,geometry_program,792);
OFFSET(struct drm_opengpu_native_layered_draw_plan,geometry_uniforms,816);
OFFSET(struct drm_opengpu_native_layered_draw_plan,geometry_metadata,1032);
OFFSET(struct drm_opengpu_native_completion_view,reserved,16);
OFFSET(struct drm_opengpu_submit_native_binned,job,24);
OFFSET(struct drm_opengpu_submit_native_binned,plans,72);
OFFSET(struct drm_opengpu_submit_native_binned,reserved,112);
