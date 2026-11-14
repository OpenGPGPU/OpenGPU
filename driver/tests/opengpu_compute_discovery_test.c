// SPDX-License-Identifier: MIT
#include <assert.h>
#include <stddef.h>
#include <string.h>
#include "../opengpu_compute_features.h"
#include "../opengpu_tessellation_transport.h"
#include "../opengpu_native_binned_host_abi.h"
#include "../opengpu_native_binned_abi.h"
#include "../opengpu_native_layered_abi.h"

_Static_assert(OPENGPU_TESS_HOST_WORDS == OPENGPU_NATIVE_BINNED_HOST_WORDS, "GNC word count");
_Static_assert(OPENGPU_TESS_HOST_JOB_MAGIC == OPENGPU_NATIVE_BINNED_JOB_MAGIC, "GNC job magic");
_Static_assert(OPENGPU_TESS_HOST_MAGIC_LAYERED == OPENGPU_NATIVE_LAYERED_CAP_MAGIC &&
    OPENGPU_TESS_HOST_LAYERED_JOB_MAGIC == OPENGPU_NATIVE_LAYERED_JOB_MAGIC, "GNC5/GNJ2 pairing");
_Static_assert(OPENGPU_NATIVE_LAYERED_JOB_BYTES == OPENGPU_NATIVE_TESSELLATION_JOB_BYTES &&
    OPENGPU_NATIVE_LAYERED_FRAME_BYTES == OPENGPU_NATIVE_TESSELLATION_JOB_FRAME_BYTES,
    "GNC5 shared record capacities do not change GNQ/GNT records");
_Static_assert(OPENGPU_TESS_HOST_BASE + 4u * OPENGPU_TESS_HOST_WORDS == GPU_REG_BIN_CAPABILITIES_END &&
    OPENGPU_NATIVE_TESSELLATION_JOB_BYTES == OPENGPU_NATIVE_BINNED_JOB_BYTES &&
    OPENGPU_NATIVE_TESSELLATION_JOB_FRAME_BYTES == OPENGPU_NATIVE_BINNED_FRAME_BYTES &&
    OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES == OPENGPU_NATIVE_BINNED_DRAW_BYTES, "GNC record bounds");
_Static_assert(OPENGPU_TESS_HOST_MAGIC_PHYSICAL == OPENGPU_NATIVE_BINNED_HOST_PHYSICAL_MAGIC &&
    OPENGPU_TESS_HOST_MAGIC_VIRTUAL == OPENGPU_NATIVE_BINNED_HOST_MAGIC &&
    OPENGPU_TESS_HOST_MAGIC_WIDE_VIRTUAL == OPENGPU_NATIVE_BINNED_HOST_WIDE_MAGIC &&
    OPENGPU_TESS_HOST_MAGIC_WIDE_PHYSICAL == OPENGPU_NATIVE_BINNED_HOST_WIDE_PHYSICAL_MAGIC, "GNC magics");
_Static_assert(OPENGPU_TESS_HOST_ENGINES_MASK == OPENGPU_NATIVE_BINNED_HOST_ENGINES_MASK &&
    OPENGPU_TESS_HOST_ENGINES_SHIFT == OPENGPU_NATIVE_BINNED_HOST_ENGINES_SHIFT &&
    OPENGPU_TESS_HOST_COMPLETION_MASK == OPENGPU_NATIVE_BINNED_HOST_COMPLETION_INFO_MASK, "GNC fields");
_Static_assert(OPENGPU_NATIVE_BINNED_HOST_JOB_MAGIC_WORD == 2u &&
    OPENGPU_NATIVE_BINNED_HOST_JOB_BYTES_WORD == 3u && OPENGPU_NATIVE_BINNED_HOST_FRAME_BYTES_WORD == 3u &&
    OPENGPU_NATIVE_BINNED_HOST_DRAW_BYTES_WORD == 4u && OPENGPU_NATIVE_BINNED_HOST_ENGINES_WORD == 6u,
    "GNC record positions");

struct device { unsigned reads; u32 magic, words, flags, lanes, warps, items, axis, shared_base, shared_bytes, discovery, host[OPENGPU_TESS_HOST_WORDS]; };
static u32 read_reg(void *context, u32 address)
{
    struct device *d = context;
    d->reads++;
    if (address == OPENGPU_TESS_HOST_DISCOVERY) return d->discovery;
    if (address >= OPENGPU_TESS_HOST_BASE && address < OPENGPU_TESS_HOST_BASE + 4u * OPENGPU_TESS_HOST_WORDS &&
        !(address & 3u)) return d->host[(address - OPENGPU_TESS_HOST_BASE) / 4u];
    switch (address) {
    case OPENGPU_COMPUTE_RESOURCE_REG_MAGIC: return d->magic;
    case OPENGPU_COMPUTE_RESOURCE_REG_WORDS: return d->words;
    case OPENGPU_COMPUTE_RESOURCE_REG_FLAGS: return d->flags;
    case OPENGPU_COMPUTE_RESOURCE_REG_LANES: return d->lanes;
    case OPENGPU_COMPUTE_RESOURCE_REG_WARPS: return d->warps;
    case OPENGPU_COMPUTE_RESOURCE_REG_LOCAL_ITEMS: return d->items;
    case OPENGPU_COMPUTE_RESOURCE_REG_LOCAL_AXIS: return d->axis;
    case OPENGPU_COMPUTE_RESOURCE_REG_SHARED_BASE: return d->shared_base;
    case OPENGPU_COMPUTE_RESOURCE_REG_SHARED_BYTES: return d->shared_bytes;
    default: assert(0); return 0;
    }
}

static void reject_tessellation_host(struct device *device)
{
    for (unsigned initial = 0; initial < 2; ++initial) {
        u32 engines = 99;
        bool virtual_images = initial;

        assert(!opengpu_read_tessellation_host(true, 0x180, device, read_reg,
                                              &engines, &virtual_images));
        assert(engines == 99 && virtual_images == (bool)initial);
    }
}

static void layered_tessellation_discovery_cases(void)
{
    /* Exact GNC block of both complete 104/112-word owner factories. The
     * sample presence record changes GCR flags, not this shared GNC5 block.
     * Exercise the production hw-init sequence: GCR -> presence -> GNC. */
    const u32 gnc[] = {0x474e4305u,8u,0x474e4a02u,0x08000200u,
        0xc2100800u,0x08000800u,0xe4908000u,0x8813db02u};
    const u32 profiles[] = {0xfffffu,0x1fffffu};
    const u32 caps = GPU_CAP_UNIFIED_COMMANDS | GPU_CAP_PROTECTED_VM_STATE;
    struct device valid = {.magic=OPENGPU_COMPUTE_RESOURCE_MAGIC,.words=16,
        .discovery=OPENGPU_TESS_HOST_BASE};
    memcpy(valid.host,gnc,sizeof(gnc));
    for (unsigned p=0;p<sizeof(profiles)/sizeof(profiles[0]);++p) {
        struct device d=valid;
        u32 flags=0,engines=99;
        bool transport=false,feedback=false,virtual_images=false;
        d.flags=profiles[p];
        assert(opengpu_read_compute_features(0x47550002u,0x540,&d,read_reg,&flags));
        assert(flags==profiles[p]);
        assert(opengpu_tessellation_transport_features(flags,caps,&transport,&feedback));
        assert(transport && feedback);
        assert(opengpu_read_tessellation_host(transport,0x540,&d,read_reg,&engines,&virtual_images));
        assert(engines==4 && virtual_images);
    }
    /* Only GNC1..4/GNJ1 and GNC5/GNJ2 are legal. No newer GNC, GNJ,
     * tessellation job magic or cross-version pair is inferred from size. */
    for (u32 version=0;version<=6;++version)
        for (u32 job=0;job<=3;++job) {
            struct device d=valid;
            u32 engines=99;
            bool virtual_images=false;
            d.host[0]=0x474e4300u|version;d.host[2]=0x474e4a00u|job;
            bool expected=(version>=1 && version<=4 && job==1) || (version==5 && job==2);
            assert(opengpu_read_tessellation_host(true,0x180,&d,read_reg,&engines,&virtual_images)==expected);
            assert(expected ? engines==4 && virtual_images : engines==99 && !virtual_images);
        }
    for (u32 count=1;count<=4;++count) {
        struct device d=valid;u32 engines=99;bool virtual_images=false;
        d.host[6]=(d.host[6]&~OPENGPU_TESS_HOST_ENGINES_MASK)|(count<<OPENGPU_TESS_HOST_ENGINES_SHIFT);
        assert(opengpu_read_tessellation_host(true,0x180,&d,read_reg,&engines,&virtual_images));
        assert(engines==count && virtual_images);
    }
    for (unsigned test=0;test<16;++test) {
        struct device d=valid;
        switch (test) {
        case 0:d.host[0]=0xffffffffu;break;
        case 1:d.host[2]=OPENGPU_NATIVE_TESSELLATION_JOB_MAGIC;break;
        case 2:d.host[1]=0;break;
        case 3:d.host[1]=7;break;
        case 4:d.host[1]=9;break;
        case 5:d.host[3]^=1;break;
        case 6:d.host[3]^=1u<<16;break;
        case 7:d.host[3]=0;break;
        case 8:d.host[4]^=1;break;
        case 9:d.host[4]&=~OPENGPU_TESS_HOST_COMPLETION_MASK;break;
        case 10:d.host[6]&=~OPENGPU_TESS_HOST_ENGINES_MASK;break;
        case 11:case 12:case 13:
            d.host[6]=(d.host[6]&~OPENGPU_TESS_HOST_ENGINES_MASK)|((test-6u)<<OPENGPU_TESS_HOST_ENGINES_SHIFT);break;
        case 14:d.discovery=0;break;
        default:d.discovery=OPENGPU_TESS_HOST_BASE+4;break;
        }
        reject_tessellation_host(&d);
    }
    for (unsigned bytes=0;bytes<0x180;++bytes) {
        struct device d=valid;u32 engines=99;bool virtual_images=true;
        assert(!opengpu_read_tessellation_host(true,bytes,&d,read_reg,&engines,&virtual_images));
        assert(!d.reads && engines==99 && virtual_images);
    }
}

static u32 read_indirect(void *context, u32 address)
{
    u32 *words = context;
    assert(address >= 0x340 && address < 0x360 && !(address & 3));
    words[8]++;
    return words[(address - 0x340) / 4];
}

static void indirect_cases(void)
{
    u32 words[9] = {0x47434901u,8,0x7f,14,12,4,0xffffffffu,128,0};
    struct opengpu_compute_local_limits local = {128,128,true};
    const u32 caps = GPU_CAP_UNIFIED_COMMANDS | GPU_CAP_PROTECTED_VM_STATE;
    bool supported = true;
    assert(opengpu_read_compute_indirect_support(false,0x47550001,0,0,NULL,NULL,NULL,&supported));
    assert(!supported && !words[8]);
    assert(opengpu_read_compute_indirect_support(false,0x47550002,0x1000,caps,&local,words,read_indirect,&supported));
    assert(!supported && !words[8]); /* ABI2 alone does not promise optional MMIO. */
    assert(!opengpu_read_compute_indirect_support(true,0x47550001,0x1000,caps,&local,words,read_indirect,&supported));
    assert(!supported && !words[8]);
    assert(!opengpu_read_compute_indirect_support(true,0x47550002,0x35f,caps,&local,words,read_indirect,&supported));
    assert(!words[8]);
    assert(!opengpu_read_compute_indirect_support(true,0x47550002,0x1000,caps&~GPU_CAP_PROTECTED_VM_STATE,&local,words,read_indirect,&supported));
    assert(!words[8]);
    assert(opengpu_read_compute_indirect_support(true,0x47550002,0x360,caps,&local,words,read_indirect,&supported));
    assert(supported && words[8]==8);
    for (unsigned i=0;i<8;i++) {
        words[i]^=1u;words[8]=0;supported=true;
        assert(!opengpu_read_compute_indirect_support(true,0x47550002,0x1000,caps,&local,words,read_indirect,&supported));
        assert(!supported && words[8]==8);words[i]^=1u;
    }
    local.items=local.axis=8;words[7]=8;
    assert(opengpu_read_compute_indirect_support(true,0x47550002,0x360,caps,&local,words,read_indirect,&supported));
    local.discovered=false;words[8]=0;
    assert(!opengpu_read_compute_indirect_support(true,0x47550002,0x1000,caps,&local,words,read_indirect,&supported));
    assert(!words[8]);
}

int main(void)
{
    indirect_cases();
    layered_tessellation_discovery_cases();
    struct device d = { .magic = OPENGPU_COMPUTE_RESOURCE_MAGIC, .words = 16, .flags = 15 };
    u32 flags = ~0u;
    bool transport = true, feedback = true;
    bool virtual_images = true;
    u32 engines = 99;
    const u32 tess = OPENGPU_COMPUTE_RESOURCE_TESSELLATION_COMMANDS;
    const u32 tf = OPENGPU_COMPUTE_RESOURCE_TESSELLATION_FEEDBACK;
    const u32 host = GPU_CAP_UNIFIED_COMMANDS | GPU_CAP_PROTECTED_VM_STATE;
    assert(opengpu_tessellation_transport_features(0, 0, &transport, &feedback));
    assert(!transport && !feedback);
    assert(!opengpu_tessellation_transport_features(tf, host, &transport, &feedback));
    assert(!transport && !feedback);
    assert(!opengpu_tessellation_transport_features(tess, GPU_CAP_UNIFIED_COMMANDS, &transport, &feedback));
    assert(!transport && !feedback);
    assert(opengpu_tessellation_transport_features(tess, host, &transport, &feedback));
    assert(transport && !feedback);
    assert(opengpu_tessellation_transport_features(tess | tf, host, &transport, &feedback));
    assert(transport && feedback);
    assert(!opengpu_tessellation_transport_features(tess, host, NULL, &feedback));
    assert(opengpu_read_tessellation_host(false, 0, &d, NULL, &engines, &virtual_images));
    assert(!engines && !virtual_images && !d.reads);
    engines = 99; virtual_images = true;
    assert(!opengpu_read_tessellation_host(true, 0x17f, &d, read_reg, &engines, &virtual_images));
    assert(engines == 99 && virtual_images && !d.reads);
    d.discovery = OPENGPU_TESS_HOST_BASE;
    d.host[0] = OPENGPU_TESS_HOST_MAGIC_VIRTUAL; d.host[1] = OPENGPU_TESS_HOST_WORDS;
    d.host[2] = OPENGPU_TESS_HOST_JOB_MAGIC; d.host[3] = (2048u << 16) | 512u;
    d.host[4] = OPENGPU_TESS_HOST_COMPLETION_MASK | 2048u;
    /* GNQ image views use context VAs for every shared GNJ image profile. */
    {
        const u32 magics[] = { OPENGPU_TESS_HOST_MAGIC_VIRTUAL,
            OPENGPU_TESS_HOST_MAGIC_WIDE_VIRTUAL, OPENGPU_TESS_HOST_MAGIC_PHYSICAL,
            OPENGPU_TESS_HOST_MAGIC_WIDE_PHYSICAL };
        for (unsigned i = 0; i < sizeof(magics) / sizeof(magics[0]); ++i)
            for (u32 count = 1; count <= 4; ++count) {
                d.host[0] = magics[i];
                d.host[6] = count << OPENGPU_TESS_HOST_ENGINES_SHIFT;
                engines = 99; virtual_images = false;
                assert(opengpu_read_tessellation_host(true, 0x180, &d, read_reg,
                                                      &engines, &virtual_images));
                assert(engines == count && virtual_images);
            }
    }
    assert(!opengpu_read_tessellation_host(true, 0x180, &d, NULL, &engines, &virtual_images));
    assert(!opengpu_read_tessellation_host(true, 0x180, &d, read_reg, NULL, &virtual_images));
    assert(!opengpu_read_tessellation_host(true, 0x180, &d, read_reg, &engines, NULL));
    assert(engines == 4 && virtual_images);
    d.host[0] = 0;
    reject_tessellation_host(&d);
    d.host[0] = OPENGPU_TESS_HOST_MAGIC_VIRTUAL; d.host[1] = 7;
    reject_tessellation_host(&d);
    d.host[1] = OPENGPU_TESS_HOST_WORDS; d.host[2] ^= 1u;
    reject_tessellation_host(&d);
    d.host[2] ^= 1u; d.host[4] ^= 1u;
    reject_tessellation_host(&d);
    d.host[4] ^= 1u;
    d.host[6] = 5u << OPENGPU_TESS_HOST_ENGINES_SHIFT;
    reject_tessellation_host(&d);
    d.host[6] = 0;
    reject_tessellation_host(&d);
    d.host[6] = 2u << OPENGPU_TESS_HOST_ENGINES_SHIFT;
    d.host[4] &= ~OPENGPU_TESS_HOST_COMPLETION_MASK;
    reject_tessellation_host(&d);
    d.host[4] |= OPENGPU_TESS_HOST_COMPLETION_MASK;
    d.host[3] ^= 1u;
    reject_tessellation_host(&d);
    d.host[3] ^= 1u; d.discovery = 0;
    reject_tessellation_host(&d);
    d.reads = 0;
    assert(opengpu_read_compute_features(0x47550001, 4, &d, read_reg, &flags));
    assert(d.reads == 0 && flags == 0); /* unmapped legacy registers untouched */
    assert(!opengpu_read_compute_features(0x47550002, 0x39f, &d, read_reg, &flags));
    assert(d.reads == 0 && flags == 0);
    assert(opengpu_read_compute_features(0x47550002, 0x3a0, &d, read_reg, &flags));
    assert(d.reads == 3 && flags == 15);
    assert(!opengpu_compute_buffer_texture_supported(flags,
        GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE,true));
    assert(!opengpu_compute_buffer_images_supported(flags,
        GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE,true));
    d.reads=0;d.flags=OPENGPU_COMPUTE_RESOURCE_TEXTURE_COMMANDS |
        OPENGPU_COMPUTE_RESOURCE_IMAGE_COMMANDS | OPENGPU_COMPUTE_RESOURCE_BUFFER_TEXTURE_V1 |
        OPENGPU_COMPUTE_RESOURCE_BUFFER_IMAGE_SIZE_V1;
    assert(opengpu_read_compute_features(0x47550001,0,&d,NULL,&flags));
    assert(!d.reads && !flags); /* even a device model with new bits is not probed on ABI1 */
    assert(opengpu_read_compute_features(0x47550002,0x3a0,&d,read_reg,&flags));
    assert(d.reads==3 && opengpu_compute_buffer_texture_supported(flags,
        GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE,true));
    assert(opengpu_compute_buffer_images_supported(flags,
        GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE,true));
    d.reads = 0; d.magic = 0; flags = ~0u;
    assert(!opengpu_read_compute_features(0x47550002, 0x1000, &d, read_reg, &flags));
    assert(d.reads == 1 && flags == 0);
    d.reads = 0; d.magic = OPENGPU_COMPUTE_RESOURCE_MAGIC; d.words = 15;
    assert(!opengpu_read_compute_features(0x47550002, 0x1000, &d, read_reg, &flags));
    assert(d.reads == 2 && flags == 0);
    d.reads = 0; d.words = 16; d.flags = 7;
    assert(opengpu_read_compute_features(0x47550002, 0x1000, &d, read_reg, &flags));
    assert(d.reads == 3 && !(flags & OPENGPU_COMPUTE_RESOURCE_WORD_ATOMICS));
    {
        struct opengpu_compute_local_limits limits = {0, 0, false};
        u32 local[3] = {32, 1, 1}, count = 99;

        d.reads = 0;
        assert(opengpu_read_compute_local_limits(0x47550001, 0, 0, &d, NULL, &limits));
        assert(!d.reads && limits.items == 32 && limits.axis == 32 && !limits.discovered);
        assert(opengpu_compute_local_size_valid(&limits, local, &count) && count == 32);
        local[0] = 33; count = 99;
        assert(!opengpu_compute_local_size_valid(&limits, local, &count) && count == 99);
        d.lanes = 16; d.warps = 8; d.items = d.axis = 128;
        assert(opengpu_read_compute_local_limits(0x47550002, 0x3a0, flags, &d, read_reg, &limits));
        assert(d.reads == 4 && limits.items == 128 && limits.axis == 128 && limits.discovered);
        local[0] = 128;
        assert(opengpu_compute_local_size_valid(&limits, local, &count) && count == 128);
        local[0] = 8; local[1] = 4; local[2] = 4;
        assert(opengpu_compute_local_size_valid(&limits, local, &count) && count == 128);
        local[2] = 5;
        assert(!opengpu_compute_local_size_valid(&limits, local, &count));
        local[0] = 129; local[1] = local[2] = 1;
        assert(!opengpu_compute_local_size_valid(&limits, local, &count));
        local[0] = 0xffffffffu;
        assert(!opengpu_compute_local_size_valid(&limits, local, &count));
        local[0] = 0;
        assert(!opengpu_compute_local_size_valid(&limits, local, &count));
        d.lanes = 4; d.warps = 2; d.items = d.axis = 8;
        assert(opengpu_read_compute_local_limits(0x47550002, 0x3a0, flags, &d, read_reg, &limits));
        local[0] = 8;
        assert(opengpu_compute_local_size_valid(&limits, local, &count) && count == 8);
        local[0] = 9;
        assert(!opengpu_compute_local_size_valid(&limits, local, &count));
        d.lanes = 16; d.warps = 32; d.items = d.axis = 512;
        assert(opengpu_read_compute_local_limits(0x47550002, 0x3a0, flags, &d, read_reg, &limits));
        assert(limits.items == 255 && limits.axis == 255);
        local[0] = 255;
        assert(opengpu_compute_local_size_valid(&limits, local, &count) && count == 255);
        local[0] = 256;
        assert(!opengpu_compute_local_size_valid(&limits, local, &count));
        limits.items = 77; limits.axis = 66; d.reads = 0;
        assert(!opengpu_read_compute_local_limits(0x47550002, 0x39f, flags, &d, read_reg, &limits));
        assert(!opengpu_read_compute_local_limits(0x47550002, 0x3a0, 0, &d, read_reg, &limits));
        assert(!opengpu_read_compute_local_limits(0x47550002, 0x3a0, flags, &d, NULL, &limits));
        assert(!opengpu_read_compute_local_limits(0x47550002, 0x3a0, flags, &d, read_reg, NULL));
        assert(!d.reads && limits.items == 77 && limits.axis == 66);
        d.items = 511;
        assert(!opengpu_read_compute_local_limits(0x47550002, 0x3a0, flags, &d, read_reg, &limits));
        d.items = 512; d.axis = 511;
        assert(!opengpu_read_compute_local_limits(0x47550002, 0x3a0, flags, &d, read_reg, &limits));
        d.lanes = d.warps = 0xffffffffu; d.items = d.axis = 1;
        assert(!opengpu_read_compute_local_limits(0x47550002, 0x3a0, flags, &d, read_reg, &limits));
        d.lanes = 0; d.warps = 8; d.items = d.axis = 0;
        assert(!opengpu_read_compute_local_limits(0x47550002, 0x3a0, flags, &d, read_reg, &limits));
        assert(limits.items == 77 && limits.axis == 66);
    }
    {
        struct opengpu_compute_shared_limits limits = {77, 66, 55, true};
        const u32 isolated = OPENGPU_COMPUTE_RESOURCE_CHECKED_LOCAL_PRODUCT |
                             OPENGPU_COMPUTE_RESOURCE_ONE_GROUP_PER_CU;
        d.reads = 0;
        assert(opengpu_read_compute_shared_limits(0x47550001, 0, 0, &d, NULL, &limits));
        assert(!d.reads && !limits.discovered && !limits.base && !limits.bytes && !limits.lanes);
        d.lanes = 16; d.warps = 8; d.items = d.axis = 128;
        d.shared_base = 0x10000000u; d.shared_bytes = 16384;
        assert(opengpu_read_compute_shared_limits(0x47550002, 0x3a0, isolated, &d, read_reg, &limits));
        assert(limits.discovered && limits.base == d.shared_base &&
               limits.bytes == 16384 && limits.lanes == 16);
        /* A valid word ending at 2^32 is not confused with a wrapped range. */
        d.shared_base = 0xffffc000u;
        assert(opengpu_read_compute_shared_limits(0x47550002, 0x3a0, isolated, &d, read_reg, &limits));
        d.shared_base += 4;
        assert(!opengpu_read_compute_shared_limits(0x47550002, 0x3a0, isolated, &d, read_reg, &limits));
        d.shared_base = 0x10000001u;
        assert(!opengpu_read_compute_shared_limits(0x47550002, 0x3a0, isolated, &d, read_reg, &limits));
        d.shared_base = 0x10000000u; d.shared_bytes = 16383;
        assert(!opengpu_read_compute_shared_limits(0x47550002, 0x3a0, isolated, &d, read_reg, &limits));
        d.shared_bytes = 0;
        assert(!opengpu_read_compute_shared_limits(0x47550002, 0x3a0, isolated, &d, read_reg, &limits));
        d.shared_bytes = 16384; d.items = 127;
        assert(!opengpu_read_compute_shared_limits(0x47550002, 0x3a0, isolated, &d, read_reg, &limits));
        d.items = 128; d.lanes = 0;
        assert(!opengpu_read_compute_shared_limits(0x47550002, 0x3a0, isolated, &d, read_reg, &limits));
        d.lanes = 16; d.reads = 0;
        assert(!opengpu_read_compute_shared_limits(0x47550002, 0x39f, isolated, &d, read_reg, &limits));
        assert(!opengpu_read_compute_shared_limits(0x47550002, 0x3a0, isolated & ~OPENGPU_COMPUTE_RESOURCE_CHECKED_LOCAL_PRODUCT, &d, read_reg, &limits));
        assert(!opengpu_read_compute_shared_limits(0x47550002, 0x3a0, isolated, &d, NULL, &limits));
        assert(!opengpu_read_compute_shared_limits(0x47550002, 0x3a0, isolated, &d, read_reg, NULL));
        assert(!d.reads && limits.base == 0xffffc000u && limits.bytes == 16384 && limits.lanes == 16);
        /* Isolation is optional: pre-existing ABI2 compute profiles still
         * probe and dispatch, but gain no access to stale local SRAM. */
        assert(opengpu_read_compute_shared_limits(0x47550002, 0x3a0,
            isolated & ~OPENGPU_COMPUTE_RESOURCE_ONE_GROUP_PER_CU, &d, NULL, &limits));
        assert(!d.reads && !limits.discovered && !limits.base && !limits.bytes && !limits.lanes);
    }
    return 0;
}
