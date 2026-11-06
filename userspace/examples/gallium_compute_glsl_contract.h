/* SPDX-License-Identifier: MIT */
/* Ordinary uint32 arithmetic shared by the runtime checker and CPU preflight.
 * This header generates no TGSI/native instructions and contains no decoder. */
#ifndef OPENGPU_GALLIUM_COMPUTE_GLSL_CONTRACT_H
#define OPENGPU_GALLIUM_COMPUTE_GLSL_CONTRACT_H
#include <assert.h>
#include <stdint.h>

#define GLC_VIEW_BYTES UINT32_C(134217728)
#define GLC_PREFIX_BYTES 8192u
#define GLC_TAIL_BYTES 64u
#define GLC_GUARD UINT32_C(0xdeadface)
#define GLC_CHECK_WORDS (4u * (GLC_PREFIX_BYTES + GLC_TAIL_BYTES) / 4u)

struct glc_parent {
    const char *name;
    uint32_t local[3], grid[3];
    unsigned epoch, uniform_epoch, consumer, fault;
};
static const struct glc_parent glc_parents[5] = {
    {"epoch1-p", {8,4,4}, {2,1,1}, 1,1,0,0},
    {"epoch1-c", {8,4,4}, {2,1,1}, 1,2,1,0},
    {"selected-fault", {8,4,4}, {1,1,1}, 1,1,0,1},
    {"epoch3-p", {5,5,5}, {2,1,1}, 3,3,0,0},
    {"epoch3-c", {5,5,5}, {2,1,1}, 3,4,1,0},
};

static inline unsigned glc_items(const struct glc_parent *p)
{ return p->local[0] * p->local[1] * p->local[2]; }

static inline uint32_t glc_uniform(unsigned epoch, unsigned slot, unsigned word)
{
    return (UINT32_C(0x4ab319d7) + epoch * UINT32_C(0x1020304) +
        slot * UINT32_C(0x718293) + word * UINT32_C(0x9e3779b9)) ^
        ((uint32_t)(slot + 13u) << (word % 23u));
}

static inline uint32_t glc_checksum(unsigned uniform_epoch)
{
    uint32_t a = glc_uniform(uniform_epoch,0,2) ^ glc_uniform(uniform_epoch,0,1023);
    for (unsigned slot=1;slot<=12;slot++)
        a = a * 33u ^ glc_uniform(uniform_epoch,slot,0) ^ glc_uniform(uniform_epoch,slot,4095);
    return a;
}

static inline uint32_t glc_produced(unsigned epoch, unsigned binding, unsigned id)
{
    uint32_t a=glc_checksum(epoch);
    if (!binding) return id&1u ? a^id : a+id;
    a=id&1u ? a+17u : a^0x77u;
    return a ^ id ^ binding * UINT32_C(0x10203);
}

static inline uint32_t glc_result(unsigned epoch, unsigned binding, unsigned id, unsigned consumed)
{
    uint32_t v=glc_produced(epoch,binding,id), a=glc_checksum(epoch+1);
    if (!consumed) return v;
    a=id&1u ? a+17u : a^0x77u;
    return v ^ a ^ (binding ? binding*UINT32_C(0x718293) : 0u);
}

static inline uint32_t glc_expected(unsigned epoch, unsigned binding, unsigned offset,
                                    unsigned total, unsigned consumed)
{
    assert(binding<4 && !(offset&3) && offset<=GLC_VIEW_BYTES-4 && total);
    if (offset==GLC_VIEW_BYTES-4) return glc_result(epoch,binding,total-1,consumed);
    if (offset/4<total-1) return glc_result(epoch,binding,offset/4,consumed);
    return GLC_GUARD;
}

static inline unsigned glc_region_offset(unsigned tail)
{ return tail ? GLC_VIEW_BYTES-GLC_TAIL_BYTES : 0; }
static inline unsigned glc_region_bytes(unsigned tail)
{ return tail ? GLC_TAIL_BYTES : GLC_PREFIX_BYTES; }
#endif
