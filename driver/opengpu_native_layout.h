/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_NATIVE_LAYOUT_H
#define OPENGPU_NATIVE_LAYOUT_H

/* OPENGPU_PARAM_NATIVE_LAYOUT. Only valid with DRIVER_FEATURE_NATIVE_BINNED.
 * Capacity is the physical per-CU lane*warp count from checked GCR1, not an
 * application workgroup limit. GNC supplies runtime slot counts; each version
 * mask uses bit N for shader ABI version N. No ordinary geometry ABI is enabled.
 */
#define OPENGPU_NATIVE_LAYOUT_CAPACITY_SHIFT 0u
#define OPENGPU_NATIVE_LAYOUT_CAPACITY_MASK 0xffffffffull
#define OPENGPU_NATIVE_LAYOUT_ATTRIBUTES_SHIFT 32u
#define OPENGPU_NATIVE_LAYOUT_BINDINGS_SHIFT 37u
#define OPENGPU_NATIVE_LAYOUT_VARYINGS_SHIFT 42u
#define OPENGPU_NATIVE_LAYOUT_SLOTS_MASK 0x1full
#define OPENGPU_NATIVE_LAYOUT_COLORS_SHIFT 47u
#define OPENGPU_NATIVE_LAYOUT_COLORS_MASK 0x7ull
#define OPENGPU_NATIVE_LAYOUT_VERTEX_VERSIONS_SHIFT 50u
#define OPENGPU_NATIVE_LAYOUT_VERTEX_VERSIONS_MASK 0x7ull
#define OPENGPU_NATIVE_LAYOUT_FRAGMENT_VERSIONS_SHIFT 53u
#define OPENGPU_NATIVE_LAYOUT_FRAGMENT_VERSIONS_MASK 0x3full
#define OPENGPU_NATIVE_LAYOUT_KNOWN_MASK ((1ull << 59) - 1)
#define OPENGPU_NATIVE_LAYOUT_VERTEX_VERSIONS 0x6u
#define OPENGPU_NATIVE_LAYOUT_FRAGMENT_VERSIONS 0x1eu

struct opengpu_native_shader_layout {
    unsigned int capacity, attributes, bindings, varyings, colors;
    unsigned int vertex_versions, fragment_versions;
};

/* Strict decoding does not alter output on rejection, including zero/unknown
 * versions or any reserved bit. Driver encode and frontend decode use the same
 * layout, and neither may substitute a legacy SHADER_CONFIG approximation.
 */
static inline int opengpu_native_layout_decode(unsigned long long word,
    struct opengpu_native_shader_layout *output)
{
    struct opengpu_native_shader_layout result;
    if (!output || (word & ~OPENGPU_NATIVE_LAYOUT_KNOWN_MASK))
        return 0;
    result.capacity = word & OPENGPU_NATIVE_LAYOUT_CAPACITY_MASK;
    result.attributes = (word >> OPENGPU_NATIVE_LAYOUT_ATTRIBUTES_SHIFT) & OPENGPU_NATIVE_LAYOUT_SLOTS_MASK;
    result.bindings = (word >> OPENGPU_NATIVE_LAYOUT_BINDINGS_SHIFT) & OPENGPU_NATIVE_LAYOUT_SLOTS_MASK;
    result.varyings = (word >> OPENGPU_NATIVE_LAYOUT_VARYINGS_SHIFT) & OPENGPU_NATIVE_LAYOUT_SLOTS_MASK;
    result.colors = (word >> OPENGPU_NATIVE_LAYOUT_COLORS_SHIFT) & OPENGPU_NATIVE_LAYOUT_COLORS_MASK;
    result.vertex_versions = (word >> OPENGPU_NATIVE_LAYOUT_VERTEX_VERSIONS_SHIFT) & OPENGPU_NATIVE_LAYOUT_VERTEX_VERSIONS_MASK;
    result.fragment_versions = (word >> OPENGPU_NATIVE_LAYOUT_FRAGMENT_VERSIONS_SHIFT) & OPENGPU_NATIVE_LAYOUT_FRAGMENT_VERSIONS_MASK;
    if (!result.capacity || !result.attributes || result.attributes > 16 ||
        !result.bindings || result.bindings > 16 || !result.varyings || result.varyings > 16 ||
        !result.colors || result.colors > 4 ||
        result.vertex_versions != OPENGPU_NATIVE_LAYOUT_VERTEX_VERSIONS ||
        result.fragment_versions != OPENGPU_NATIVE_LAYOUT_FRAGMENT_VERSIONS)
        return 0;
    *output = result;
    return 1;
}
#endif
