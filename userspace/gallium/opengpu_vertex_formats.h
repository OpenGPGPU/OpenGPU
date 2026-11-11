/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_VERTEX_FORMATS_H
#define OPENGPU_VERTEX_FORMATS_H

#include <stdbool.h>
#include "pipe/p_format.h"

/* Mesa's plain-format unpackers supply the conversion and component defaults
 * for the legacy float vertex inputs. Keep screen admission and fetch aligned. */
static inline bool opengpu_vertex_format_supported(enum pipe_format format)
{
#define OPENGPU_VERTEX_CASES(bits, kind) \
    case PIPE_FORMAT_R##bits##_##kind: \
    case PIPE_FORMAT_R##bits##G##bits##_##kind: \
    case PIPE_FORMAT_R##bits##G##bits##B##bits##_##kind: \
    case PIPE_FORMAT_R##bits##G##bits##B##bits##A##bits##_##kind:
    switch (format) {
    OPENGPU_VERTEX_CASES(8, UNORM)
    OPENGPU_VERTEX_CASES(8, SNORM)
    OPENGPU_VERTEX_CASES(8, USCALED)
    OPENGPU_VERTEX_CASES(8, SSCALED)
    OPENGPU_VERTEX_CASES(8, UINT)
    OPENGPU_VERTEX_CASES(8, SINT)
    OPENGPU_VERTEX_CASES(16, UNORM)
    OPENGPU_VERTEX_CASES(16, SNORM)
    OPENGPU_VERTEX_CASES(16, USCALED)
    OPENGPU_VERTEX_CASES(16, SSCALED)
    OPENGPU_VERTEX_CASES(16, UINT)
    OPENGPU_VERTEX_CASES(16, SINT)
    OPENGPU_VERTEX_CASES(16, FLOAT)
    OPENGPU_VERTEX_CASES(32, UNORM)
    OPENGPU_VERTEX_CASES(32, SNORM)
    OPENGPU_VERTEX_CASES(32, USCALED)
    OPENGPU_VERTEX_CASES(32, SSCALED)
    OPENGPU_VERTEX_CASES(32, UINT)
    OPENGPU_VERTEX_CASES(32, SINT)
    OPENGPU_VERTEX_CASES(32, FLOAT)
    OPENGPU_VERTEX_CASES(32, FIXED)
    case PIPE_FORMAT_R10G10B10A2_UNORM:
    case PIPE_FORMAT_R10G10B10A2_SNORM:
    case PIPE_FORMAT_R10G10B10A2_USCALED:
    case PIPE_FORMAT_R10G10B10A2_SSCALED:
    case PIPE_FORMAT_R10G10B10A2_UINT:
    case PIPE_FORMAT_R10G10B10A2_SINT:
        return true;
    default:
        return false;
    }
#undef OPENGPU_VERTEX_CASES
}

#endif
