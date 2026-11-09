/* Host mapping for the context robustness owner.
 *
 * The GPU latches NO_ERROR/GUILTY/INNOCENT/UNKNOWN (0..3) and publishes the
 * robust-access bit in its flag word. This header maps those values to the GL
 * reset-status and context-flag enums; it does not implement reset timing or
 * resource-level robust behavior.
 */
#ifndef OPENGPU_CONTEXT_ROBUSTNESS_H
#define OPENGPU_CONTEXT_ROBUSTNESS_H

#include <stdint.h>

#define OPENGPU_RESET_STATUS_NO_ERROR 0u
#define OPENGPU_RESET_STATUS_GUILTY 1u
#define OPENGPU_RESET_STATUS_INNOCENT 2u
#define OPENGPU_RESET_STATUS_UNKNOWN 3u

#define OPENGPU_GL_NO_ERROR 0x0000u
#define OPENGPU_GL_GUILTY_CONTEXT_RESET 0x8253u
#define OPENGPU_GL_INNOCENT_CONTEXT_RESET 0x8254u
#define OPENGPU_GL_UNKNOWN_CONTEXT_RESET 0x8255u
#define OPENGPU_GL_CONTEXT_FLAG_ROBUST_ACCESS_BIT 0x00000004u

static inline uint32_t opengpu_gl_reset_status(uint32_t internal_status)
{
    switch (internal_status) {
    case OPENGPU_RESET_STATUS_NO_ERROR:
        return OPENGPU_GL_NO_ERROR;
    case OPENGPU_RESET_STATUS_GUILTY:
        return OPENGPU_GL_GUILTY_CONTEXT_RESET;
    case OPENGPU_RESET_STATUS_INNOCENT:
        return OPENGPU_GL_INNOCENT_CONTEXT_RESET;
    case OPENGPU_RESET_STATUS_UNKNOWN:
        return OPENGPU_GL_UNKNOWN_CONTEXT_RESET;
    default:
        return OPENGPU_GL_UNKNOWN_CONTEXT_RESET;
    }
}

static inline uint32_t opengpu_gl_context_flags(uint32_t gpu_flags)
{
    return (gpu_flags & 4u) ? OPENGPU_GL_CONTEXT_FLAG_ROBUST_ACCESS_BIT : 0u;
}

#endif
