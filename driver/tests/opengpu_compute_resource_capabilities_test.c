#include <stdint.h>
#include "../opengpu_compute_resource_abi.h"
#include "../opengpu_indirect_compute_abi.h"
#include "../opengpu_shader_execution_abi.h"

_Static_assert(OPENGPU_COMPUTE_RESOURCE_MAGIC == UINT32_C(0x47435201), "GCR1 magic");
_Static_assert(OPENGPU_COMPUTE_RESOURCE_WORDS == 16u, "GCR1 length");
_Static_assert(OPENGPU_COMPUTE_RESOURCE_CAPABILITIES ==
               OPENGPU_INDIRECT_COMPUTE_CAPABILITIES + OPENGPU_INDIRECT_COMPUTE_WORDS * 4u,
               "GCR1 follows GCI1 without overlap");
_Static_assert(OPENGPU_COMPUTE_RESOURCE_REG_COMPUTE_UNITS == 0x36cu, "CU quantity");
_Static_assert(OPENGPU_COMPUTE_RESOURCE_REG_LOCAL_ITEMS == 0x378u, "resident local items");
_Static_assert(OPENGPU_COMPUTE_RESOURCE_REG_SHARED_BASE == 0x390u, "shared address space");
_Static_assert(OPENGPU_COMPUTE_RESOURCE_REG_L2_BANKS + 4u == 0x3a0u, "exclusive end");
_Static_assert(OPENGPU_SHADER_INSTRUCTION_QUOTA < OPENGPU_INDIRECT_COMPUTE_CAPABILITIES,
               "mutable quota remains outside read-only compute records");
_Static_assert((OPENGPU_COMPUTE_RESOURCE_CHECKED_LOCAL_PRODUCT |
                OPENGPU_COMPUTE_RESOURCE_UINT32_GRID_AXES |
                OPENGPU_COMPUTE_RESOURCE_ONE_GROUP_PER_CU |
                OPENGPU_COMPUTE_RESOURCE_WORD_ATOMICS) == 15u, "baseline flags");
_Static_assert(OPENGPU_COMPUTE_RESOURCE_IMAGE_COMMANDS == 16u,
               "opt-in image command transport is independent of word atomics");

int main(void) { return 0; }
