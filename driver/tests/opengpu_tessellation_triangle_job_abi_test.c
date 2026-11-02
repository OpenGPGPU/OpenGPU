#include "../opengpu_native_tessellation_triangle_job_abi.h"
#include "../opengpu_native_binned_abi.h"
#include "../opengpu_native_tessellation_job_abi.h"
#include "../opengpu_native_tessellation_triangle_sequence_abi.h"

_Static_assert(OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_MAGIC == 0x474e5102u, "GNQ2 magic");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_MAGIC != OPENGPU_NATIVE_BINNED_JOB_MAGIC, "distinct job formats");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_MAGIC != OPENGPU_NATIVE_TESSELLATION_JOB_MAGIC, "distinct tessellation domains");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_VERSION == 1u, "GNQ2 version");
_Static_assert(sizeof(struct opengpu_native_tessellation_triangle_job) == 512u, "GNQ2 size");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_JOB_DRAWBASE_WORD == OPENGPU_NATIVE_BINNED_JOB_JOB_DRAWBASE_WORD, "draw base offset");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_JOB_DRAWLIMIT_BITS == 33u, "exclusive draw limit width");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_JOB_FRAMEBASE_WORD == OPENGPU_NATIVE_BINNED_JOB_JOB_FRAMEBASE_WORD, "frame base offset");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_FRAME_MAGIC == OPENGPU_NATIVE_BINNED_FRAME_MAGIC, "GNF1 frame");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_DRAW_MAGIC == OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_MAGIC, "GNT2 draws");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_DRAW_BYTES == OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_BYTES, "GNT2 size");
int main(void) { return 0; }
