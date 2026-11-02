#include "../opengpu_native_tessellation_job_abi.h"
#include "../opengpu_native_tessellation_sequence_abi.h"
#include "../opengpu_native_tessellation_triangle_job_abi.h"
#include "../opengpu_native_tessellation_triangle_sequence_abi.h"
#include "../opengpu_native_tessellation_quad_job_abi.h"
#include "../opengpu_native_tessellation_quad_sequence_abi.h"
#include "../opengpu_tessellation_job_validate.h"
#include "../opengpu_tessellation_job_validate.h"

#define CHECK_RECORD(type, bytes) \
    _Static_assert(sizeof(struct type) == (bytes), "record size"); \
    _Static_assert(sizeof(((struct type *)0)->words[0]) == 4, "word size"); \
    _Static_assert(_Alignof(struct type) == _Alignof(unsigned int), "record alignment")

CHECK_RECORD(opengpu_native_tessellation_job, 512);
CHECK_RECORD(opengpu_native_tessellation_triangle_job, 512);
CHECK_RECORD(opengpu_native_tessellation_quad_job, 512);
CHECK_RECORD(opengpu_native_tessellation_sequence, 2048);
CHECK_RECORD(opengpu_native_tessellation_triangle_sequence, 2048);
CHECK_RECORD(opengpu_native_tessellation_quad_sequence, 2048);
_Static_assert(OPENGPU_NATIVE_TESSELLATION_JOB_DRAW_BYTES == OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_DRAW_BYTES,
               "isoline/triangle draw size");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_JOB_DRAW_BYTES == OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_DRAW_BYTES,
               "isoline/quad draw size");
#ifdef __KERNEL__
_Static_assert(_Generic(((struct opengpu_native_tessellation_quad_job *)0)->words[0],
                        u32: 1, default: 0), "kernel word type");
#endif
int main(void) { return 0; }
