/* Only the kernel scalar primitives supplied by linux/types.h are needed. */
typedef _Bool bool;
typedef int s32;
#define true 1
#define false 0
#include "../opengpu_native_sources_features.h"
#include "../opengpu_native_geometry_transport_features.h"
_Static_assert(sizeof(struct opengpu_native_binned_host_caps)==32,"GNC kernel layout");
_Static_assert(sizeof(struct opengpu_native_binned_completion_info)==48,"GNX kernel layout");
