/* Host-side decoding of the immutable GRI1 line-width range.
 *
 * GRI1 word 7 packs integer minima, maxima, granularity and flags. Software
 * converts the integer bounds to float for ALIASED_LINE_WIDTH_RANGE and the
 * matching multisample query; this header is not a GPU register interface.
 */
#ifndef OPENGPU_RASTER_CAPABILITIES_H
#define OPENGPU_RASTER_CAPABILITIES_H

#include <stdint.h>

#define OPENGPU_RASTER_LINE_WIDTH_MIN_SHIFT 0u
#define OPENGPU_RASTER_LINE_WIDTH_MIN_MASK 0xffu
#define OPENGPU_RASTER_LINE_WIDTH_MAX_SHIFT 8u
#define OPENGPU_RASTER_LINE_WIDTH_MAX_MASK 0xff00u
#define OPENGPU_RASTER_LINE_WIDTH_GRANULARITY_SHIFT 16u
#define OPENGPU_RASTER_LINE_WIDTH_GRANULARITY_MASK 0xff0000u
#define OPENGPU_RASTER_LINE_WIDTH_FLAGS_SHIFT 24u
#define OPENGPU_RASTER_LINE_WIDTH_FLAGS_MASK 0xff000000u
#define OPENGPU_RASTER_LINE_WIDTH_MULTISAMPLE_SAME 1u

struct opengpu_line_width_range {
    uint32_t minimum;
    uint32_t maximum;
    uint32_t granularity;
    int multisample_same;
};

static inline int opengpu_decode_line_width_range(
    uint32_t word, struct opengpu_line_width_range *out)
{
    if (!out)
        return -1;
    out->minimum = (word & OPENGPU_RASTER_LINE_WIDTH_MIN_MASK) >>
                   OPENGPU_RASTER_LINE_WIDTH_MIN_SHIFT;
    out->maximum = (word & OPENGPU_RASTER_LINE_WIDTH_MAX_MASK) >>
                   OPENGPU_RASTER_LINE_WIDTH_MAX_SHIFT;
    out->granularity = (word & OPENGPU_RASTER_LINE_WIDTH_GRANULARITY_MASK) >>
                       OPENGPU_RASTER_LINE_WIDTH_GRANULARITY_SHIFT;
    out->multisample_same =
        (int)(((word & OPENGPU_RASTER_LINE_WIDTH_FLAGS_MASK) >>
               OPENGPU_RASTER_LINE_WIDTH_FLAGS_SHIFT) &
              OPENGPU_RASTER_LINE_WIDTH_MULTISAMPLE_SAME);
    if (!out->minimum || out->minimum > out->maximum || !out->granularity)
        return -1;
    return 0;
}

static inline float opengpu_line_width_as_float(uint32_t width)
{
    return (float)width;
}

#endif
