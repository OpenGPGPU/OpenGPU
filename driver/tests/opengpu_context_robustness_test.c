#include <assert.h>
#include <stdint.h>

#include "opengpu_context_robustness.h"

int main(void)
{
    assert(opengpu_gl_reset_status(0u) == OPENGPU_GL_NO_ERROR);
    assert(opengpu_gl_reset_status(1u) == OPENGPU_GL_GUILTY_CONTEXT_RESET);
    assert(opengpu_gl_reset_status(2u) == OPENGPU_GL_INNOCENT_CONTEXT_RESET);
    assert(opengpu_gl_reset_status(3u) == OPENGPU_GL_UNKNOWN_CONTEXT_RESET);
    assert(opengpu_gl_reset_status(7u) == OPENGPU_GL_UNKNOWN_CONTEXT_RESET);
    assert(opengpu_gl_context_flags(0u) == 0u);
    assert(opengpu_gl_context_flags(4u) == OPENGPU_GL_CONTEXT_FLAG_ROBUST_ACCESS_BIT);
    assert(opengpu_gl_context_flags(0xffffffffu) == OPENGPU_GL_CONTEXT_FLAG_ROBUST_ACCESS_BIT);
    return 0;
}
