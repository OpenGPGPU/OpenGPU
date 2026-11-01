#include "../opengpu_native_tessellation_sequence_abi.h"

_Static_assert(OPENGPU_NATIVE_TESSELLATION_SEQUENCE_MAGIC == 0x474e5401u, "GNT1 magic");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_SEQUENCE_VERSION == 1u, "GNT1 version");
_Static_assert(sizeof(struct opengpu_native_tessellation_sequence) == 2048u, "GNT1 size");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD == 16u, "patch count offset");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_SEQUENCE_PATCHSTRIDE_WORD == 17u, "patch stride offset");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_INPUT_BASE_WORD == 21u, "input base offset");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_COMMANDID_WORD == 191u, "reserved tag offset");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_KERNEL_INVOCATIONS_HELPERS_BITS == 128u, "helper mask width");
int main(void) { return 0; }
