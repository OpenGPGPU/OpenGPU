/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_STORAGE_SLOT_H
#define OPENGPU_STORAGE_SLOT_H
/* Slots 13–16 remain ABI-compatible aliases of dedicated slots 35–38. */
static inline bool opengpu_storage_slot_index(unsigned slot, unsigned *index)
{
    if (!index) return false;
    if (slot >= 13 && slot <= 16) *index = slot - 13;
    else if (slot >= 35 && slot <= 38) *index = slot - 35;
    else return false;
    return true;
}

/* An image backing is independent of the four shader storage bindings. */
static inline bool opengpu_image_backing_slot_index(unsigned slot, unsigned *index)
{
    if (!index || slot < 39 || slot > 42) return false;
    *index = slot - 39;
    return true;
}

/* Eight VA windows, not eight SSBO bindings. Keep the legacy aliases above. */
static inline bool opengpu_storage_backing_index(unsigned slot, unsigned *index)
{
    unsigned image;
    if (opengpu_storage_slot_index(slot, index)) return true;
    if (!index || !opengpu_image_backing_slot_index(slot, &image)) return false;
    *index = 4 + image;
    return true;
}
#endif
