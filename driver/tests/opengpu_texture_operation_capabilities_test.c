// SPDX-License-Identifier: MIT
#include "../opengpu_native_typed_abi.h"
#include "../opengpu_native_binned_abi.h"
#include "../opengpu_typed_fragment_state_abi.h"

#define CHECK(prefix) \
    _Static_assert(prefix##TEXTURE_CAP_OPERATION_DISCOVERY == 1024u, "safe discovery bit"); \
    _Static_assert(prefix##TEXTURE_OPERATION_CAPABILITIES == 0x240u, "MMIO base"); \
    _Static_assert(prefix##TEXTURE_OPERATION_CAPABILITY_MAGIC == 0x47544f01u, "GTO1 magic"); \
    _Static_assert(prefix##TEXTURE_OPERATION_CAPABILITY_VERSION == 1u, "record version"); \
    _Static_assert(prefix##TEXTURE_OPERATION_CAPABILITY_WORDS == 16u, "record size"); \
    _Static_assert(prefix##TEXTURE_OPERATION_CAPABILITY_SCALAR_FUNCTIONS_WORD == 3u, "scalar mask slot"); \
    _Static_assert(prefix##TEXTURE_OPERATION_CAPABILITY_VECTOR_FUNCTIONS_WORD == 4u, "vector mask slot"); \
    _Static_assert(prefix##TEXTURE_OPERATION_CAPABILITY_OFFSETS_WORD == 15u, "offset slot"); \
    _Static_assert(prefix##TEXTURE_OPERATION_PACKET_FETCH == 2u, "packet opcode"); \
    _Static_assert(prefix##TEXTURE_OPERATION_PARAMETERS == 3u, "parameter opcode"); \
    _Static_assert(prefix##TEXTURE_OPERATION_SAMPLE == 4u, "sample opcode"); \
    _Static_assert(prefix##TEXTURE_OPERATION_CAPTURED_FETCH == 5u, "captured fetch opcode"); \
    _Static_assert(prefix##TEXTURE_OPERATION_SIZE == 6u, "size opcode"); \
    _Static_assert(prefix##TEXTURE_OPERATION_CAP_CAPTURED_FETCH_RELATIVE_LOD == 1u, "fetch LOD semantics"); \
    _Static_assert(prefix##TEXTURE_OPERATION_CAP_SIZE_RELATIVE_LOD == 2u, "size LOD semantics"); \
    _Static_assert(prefix##TEXTURE_OPERATION_CAP_METADATA_ONLY_SIZE == 32u, "metadata semantics"); \
    _Static_assert(prefix##TEXTURE_OPERATION_OFFSET_MIN_SHIFT == 0u, "offset minimum"); \
    _Static_assert(prefix##TEXTURE_OPERATION_OFFSET_MAX_SHIFT == 16u, "offset maximum")
CHECK(OPENGPU_NATIVE_TYPED_);
CHECK(OPENGPU_NATIVE_BINNED_);
CHECK(OPENGPU_TYPED_FRAGMENT_STATE_);
int main(void) { return 0; }
