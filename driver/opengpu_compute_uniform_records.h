/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_COMPUTE_UNIFORM_RECORDS_H
#define OPENGPU_COMPUTE_UNIFORM_RECORDS_H
#include "opengpu_compute_features.h"
#include "opengpu_uniform_abi.h"

/* Compute records retain the GUR1/GUB1 word offsets and use stage 2. */
#define OPENGPU_COMPUTE_UNIFORM_STAGE 2u
#define OPENGPU_COMPUTE_UNIFORM_STAGE_MASK 4u

struct opengpu_compute_uniform_root_config {
    u32 context, pass, generation, active_mask, mapping[12], bindings_base, default_view;
    unsigned long long bindings_limit;
    bool default_present;
};
struct opengpu_compute_uniform_binding_config {
    u32 context, pass, generation, index, base, allocation_base;
    unsigned long long limit, allocation_limit;
};

/* Build word values without changing output on failure. The caller must
 * serialize these values in little-endian order in GPU-visible memory. */
static inline bool opengpu_encode_compute_uniform_root(u32 words[16],
    const struct opengpu_compute_uniform_root_config *r)
{
    u32 result[16]={0}, low=0, high=0;
    if (!words || !r || (r->active_mask & ~0xfffu) || r->bindings_limit > (1ULL<<32)) return false;
    for (unsigned i=0;i<12;++i) {
        if ((r->active_mask & (1u<<i)) ? r->mapping[i]>=24 : r->mapping[i]!=0) return false;
        if (i<6) low |= r->mapping[i] << (5*i); else high |= r->mapping[i] << (5*(i-6));
    }
    if (r->active_mask) {
        if ((r->bindings_base & 63u) || r->bindings_limit < (unsigned long long)r->bindings_base+1536 ||
            ((r->bindings_limit-r->bindings_base)&63u)) return false;
    } else if (r->bindings_base || r->bindings_limit) return false;
    if (r->default_present) {
        if ((r->default_view & 63u) || (unsigned long long)r->default_view+64>(1ULL<<32)) return false;
    } else if (r->default_view) return false;
    result[0]=OPENGPU_UNIFORM_ROOT_MAGIC;result[1]=64;result[2]=1;
    result[3]=r->context;result[4]=r->pass;result[5]=r->generation;
    result[OPENGPU_UNIFORM_ROOT_STAGE_WORD]=OPENGPU_COMPUTE_UNIFORM_STAGE;
    result[OPENGPU_UNIFORM_ROOT_ACTIVEMASK_WORD]=r->active_mask;
    result[OPENGPU_UNIFORM_ROOT_MAPLOW_WORD]=low;result[OPENGPU_UNIFORM_ROOT_MAPHIGH_WORD]=high;
    result[OPENGPU_UNIFORM_ROOT_BINDINGSBASE_WORD]=r->bindings_base;
    result[OPENGPU_UNIFORM_ROOT_BINDINGSLIMIT_WORD]=(u32)r->bindings_limit;
    result[OPENGPU_UNIFORM_ROOT_BINDINGSLIMIT_WORD+1]=(u32)(r->bindings_limit>>32);
    result[OPENGPU_UNIFORM_ROOT_DEFAULTVIEW_WORD]=r->default_view;
    result[OPENGPU_UNIFORM_ROOT_DEFAULTPRESENT_WORD]=r->default_present;
    for(unsigned i=0;i<16;++i) words[i]=result[i];
    return true;
}

static inline bool opengpu_encode_compute_uniform_binding(u32 words[16],
    const struct opengpu_compute_uniform_binding_config *b)
{
    u32 result[16]={0};bool is_default;
    if (!words || !b) return false;
    is_default=b->index==OPENGPU_UNIFORM_DEFAULT_BLOCK;
    if ((!is_default && b->index>=24) || (b->base & (is_default?3u:255u)) ||
        (b->allocation_base&63u) || (b->allocation_limit&63u) ||
        b->limit<=b->base || b->limit>(1ULL<<32) || b->allocation_limit>(1ULL<<32) ||
        b->limit-b->base>(is_default?4096u:16384u) || b->base<b->allocation_base ||
        b->limit>b->allocation_limit) return false;
    result[0]=OPENGPU_UNIFORM_BINDING_MAGIC;result[1]=64;result[2]=1;
    result[3]=b->context;result[4]=b->pass;result[5]=b->generation;
    result[OPENGPU_UNIFORM_BINDING_BINDINGINDEX_WORD]=b->index;
    result[OPENGPU_UNIFORM_BINDING_STAGEMASK_WORD]=OPENGPU_COMPUTE_UNIFORM_STAGE_MASK;
    result[OPENGPU_UNIFORM_BINDING_BASE_WORD]=b->base;
    result[OPENGPU_UNIFORM_BINDING_LIMIT_WORD]=(u32)b->limit;
    result[OPENGPU_UNIFORM_BINDING_LIMIT_WORD+1]=(u32)(b->limit>>32);
    result[OPENGPU_UNIFORM_BINDING_ALLOCATIONBASE_WORD]=b->allocation_base;
    result[OPENGPU_UNIFORM_BINDING_ALLOCATIONLIMIT_WORD]=(u32)b->allocation_limit;
    result[OPENGPU_UNIFORM_BINDING_ALLOCATIONLIMIT_WORD+1]=(u32)(b->allocation_limit>>32);
    result[OPENGPU_UNIFORM_BINDING_READONLY_WORD]=1;
    for(unsigned i=0;i<16;++i) words[i]=result[i];
    return true;
}
#endif
