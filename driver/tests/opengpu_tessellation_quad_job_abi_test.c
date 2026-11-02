#include "../opengpu_native_tessellation_quad_job_abi.h"
#include "../opengpu_native_binned_abi.h"
#include "../opengpu_native_tessellation_job_abi.h"
#include "../opengpu_native_tessellation_quad_sequence_abi.h"

_Static_assert(OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_MAGIC == 0x474e5103u, "GNQ3 magic");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_MAGIC != OPENGPU_NATIVE_BINNED_JOB_MAGIC, "distinct job formats");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_MAGIC != OPENGPU_NATIVE_TESSELLATION_JOB_MAGIC, "distinct tessellation domains");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_MAGIC != 0x474e5102u, "distinct triangle job format");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_VERSION == 1u, "GNQ3 version");
_Static_assert(sizeof(struct opengpu_native_tessellation_quad_job) == 512u, "GNQ3 size");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_JOB_DRAWBASE_WORD == OPENGPU_NATIVE_BINNED_JOB_JOB_DRAWBASE_WORD, "draw base offset");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_JOB_DRAWLIMIT_BITS == 33u, "exclusive draw limit width");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_JOB_FRAMEBASE_WORD == OPENGPU_NATIVE_BINNED_JOB_JOB_FRAMEBASE_WORD, "frame base offset");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_FRAME_MAGIC == OPENGPU_NATIVE_BINNED_FRAME_MAGIC, "GNF1 frame");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_DRAW_MAGIC == OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_MAGIC, "GNT3 draws");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_DRAW_BYTES == OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_BYTES, "GNT3 size");
int main(void) { return 0; }
