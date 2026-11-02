#include "../opengpu_shader_image_publication_abi.h"
#include "../opengpu_typed_fragment_state_abi.h"

_Static_assert(sizeof(struct opengpu_shader_image_publication) == 64, "GIR1 size");
_Static_assert(OPENGPU_TYPED_FRAGMENT_STATE_IMAGE_VERSION_WORD == 13, "GFS1 image version");
_Static_assert(OPENGPU_TYPED_FRAGMENT_STATE_IMAGE_ADDRESS_WORD == 14, "GFS1 image address");
_Static_assert(OPENGPU_SHADER_IMAGE_PUBLICATION_TABLE_LIMIT_BITS == 33, "exclusive 4 GiB limit");
_Static_assert(OPENGPU_SHADER_IMAGE_PUBLICATION_TABLE_LIMIT_WORD == 9, "GIR1 limit position");
_Static_assert(OPENGPU_SHADER_IMAGE_PUBLICATION_STAGE_FRAGMENT == 1, "fragment stage");

int main(void) { return 0; }
