/* Experimental GNQ1/GNQ2/GNQ3 envelope decoder. This does not validate the
 * referenced frame or draw records, pin resources, or authorize submission. */
#ifndef OPENGPU_TESSELLATION_JOB_VALIDATE_H
#define OPENGPU_TESSELLATION_JOB_VALIDATE_H

#include "opengpu_native_tessellation_job_abi.h"
#include "opengpu_native_tessellation_triangle_job_abi.h"
#include "opengpu_native_tessellation_quad_job_abi.h"

#define OPENGPU_TESSELLATION_ISOLINE 0u
#define OPENGPU_TESSELLATION_TRIANGLE 1u
#define OPENGPU_TESSELLATION_QUAD 2u
#define OPENGPU_TESSELLATION_ADDRESS_END 0x100000000ULL

struct opengpu_tessellation_job_view {
    opengpu_native_tessellation_job_word domain, context, pass, generation;
    opengpu_native_tessellation_job_word frame_base, draw_base, draw_count, draw_stride;
    unsigned long long frame_limit, draw_limit;
};

static inline opengpu_native_tessellation_job_word
opengpu_tessellation_job_le32(const unsigned char *p)
{
    return (opengpu_native_tessellation_job_word)p[0] |
           ((opengpu_native_tessellation_job_word)p[1] << 8) |
           ((opengpu_native_tessellation_job_word)p[2] << 16) |
           ((opengpu_native_tessellation_job_word)p[3] << 24);
}

/* `bytes` is a stable CPU snapshot, not a user pointer. Limits are exclusive;
 * address 2^32 is valid only as a limit. Output is unchanged on failure. */
static inline int opengpu_tessellation_job_decode(
    const unsigned char *bytes, unsigned long long byte_count,
    unsigned long long descriptor_base, unsigned long long descriptor_limit,
    struct opengpu_tessellation_job_view *out)
{
    struct opengpu_tessellation_job_view job;
    opengpu_native_tessellation_job_word words[OPENGPU_NATIVE_TESSELLATION_JOB_WORDS];
    unsigned long long end;
    unsigned int i;

    if (!bytes || !out || byte_count < OPENGPU_NATIVE_TESSELLATION_JOB_BYTES ||
        descriptor_base >= OPENGPU_TESSELLATION_ADDRESS_END ||
        (descriptor_base & 63u) || descriptor_limit > OPENGPU_TESSELLATION_ADDRESS_END ||
        descriptor_base + OPENGPU_NATIVE_TESSELLATION_JOB_BYTES > descriptor_limit)
        return 0;
    for (i = 0; i < OPENGPU_NATIVE_TESSELLATION_JOB_WORDS; ++i)
        words[i] = opengpu_tessellation_job_le32(bytes + i * 4u);
    switch (words[0]) {
    case OPENGPU_NATIVE_TESSELLATION_JOB_MAGIC: job.domain = OPENGPU_TESSELLATION_ISOLINE; break;
    case OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_MAGIC: job.domain = OPENGPU_TESSELLATION_TRIANGLE; break;
    case OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_MAGIC: job.domain = OPENGPU_TESSELLATION_QUAD; break;
    default: return 0;
    }
    if (words[1] != OPENGPU_NATIVE_TESSELLATION_JOB_BYTES ||
        words[2] != OPENGPU_NATIVE_TESSELLATION_JOB_VERSION ||
        words[3] != 16u || words[4] != 16u || words[5] != 16u ||
        words[6] != 4u || words[7] != 4u || words[19] > 1u || words[23] > 1u)
        return 0;
    for (i = 8; i < 16; ++i)
        if (words[i]) return 0;
    for (i = 27; i < OPENGPU_NATIVE_TESSELLATION_JOB_WORDS; ++i)
        if (words[i]) return 0;

    job.draw_base = words[16]; job.draw_count = words[17];
    job.draw_limit = (unsigned long long)words[18] | ((unsigned long long)words[19] << 32);
    job.draw_stride = words[20]; job.frame_base = words[21];
    job.frame_limit = (unsigned long long)words[22] | ((unsigned long long)words[23] << 32);
    job.context = words[24]; job.generation = words[25]; job.pass = words[26];
    if ((job.frame_base & 63u) || job.frame_limit > OPENGPU_TESSELLATION_ADDRESS_END ||
        (unsigned long long)job.frame_base + OPENGPU_NATIVE_TESSELLATION_JOB_FRAME_BYTES > job.frame_limit)
        return 0;
    if (job.draw_count) {
        if ((job.draw_base & 63u) || (job.draw_limit & 63u) || (job.draw_stride & 63u) ||
            job.draw_stride < OPENGPU_NATIVE_TESSELLATION_JOB_DRAW_BYTES ||
            job.draw_limit > OPENGPU_TESSELLATION_ADDRESS_END)
            return 0;
        /* 64-byte alignment limits both 32-bit operands to at most 2^32-64;
         * even the maximum 32-bit count leaves this sum below 2^64. */
        end = (unsigned long long)job.draw_base +
              (unsigned long long)(job.draw_count - 1u) * job.draw_stride +
              OPENGPU_NATIVE_TESSELLATION_JOB_DRAW_BYTES;
        if (end > job.draw_limit)
            return 0;
    }
    *out = job;
    return 1;
}
#endif
