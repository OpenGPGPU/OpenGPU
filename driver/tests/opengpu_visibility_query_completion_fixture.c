/* Actual Linux completion-status decoder with the GQV1 command contract. */
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include "opengpu_unified_completion.h"
#include "query_result_error_under_test.inc"

int main(void)
{
    const int status_error[] = {0, -ERANGE, -EINVAL, -EIO, -ECANCELED, -EINVAL, -ENOSPC};
    for (u32 opcode = GPU_UCMD_OP_QUERY_BEGIN; opcode <= GPU_UCMD_OP_QUERY_RESULT; opcode++) {
        for (u32 status = 0; status < sizeof(status_error)/sizeof(status_error[0]); status++)
            assert(opengpu_hw_unified_result_error(opcode, status) == status_error[status]);
        assert(opengpu_hw_unified_result_error(opcode, 7) == -EIO);
        assert(opengpu_hw_unified_result_error(opcode, GPU_UCMD_RESULT_EVENT_DEPENDENCY_FAILED) == -ECANCELED);
        assert(opengpu_unified_completion_data_valid(opcode, 0, 64));
        assert(opengpu_unified_completion_data_valid(opcode, 2, 64));
        assert(opengpu_unified_completion_data_valid(opcode, 3, 64));
        assert(!opengpu_unified_completion_data_valid(opcode, 1, 64));
        for (unsigned bit = 2; bit < 64; bit++)
            assert(!opengpu_unified_completion_data_valid(opcode, 1ull << bit, 64));
    }
    for (u32 opcode = GPU_UCMD_OP_COPY; opcode <= GPU_UCMD_OP_RENDER; opcode++) {
        assert(opengpu_unified_completion_data_valid(opcode, 64, 64));
        assert(!opengpu_unified_completion_data_valid(opcode, 63, 64));
    }
    assert(opengpu_hw_unified_result_error(GPU_UCMD_OP_COPY, GPU_UCMD_RESULT_INVALID_LENGTH) == -ERANGE);
    assert(opengpu_hw_unified_result_error(GPU_UCMD_OP_COPY, GPU_UCMD_RESULT_OVERLAP_UNSUPPORTED) == -EINVAL);
    puts("native visibility query completion semantics: PASS");
    return 0;
}
