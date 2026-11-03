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
#endif
