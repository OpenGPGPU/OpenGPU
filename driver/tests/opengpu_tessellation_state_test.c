#include "../opengpu_tessellation_job_validate.h"
#include <assert.h>
#include <string.h>

static void put(unsigned char *p, unsigned int word, unsigned int value)
{
    unsigned int i;
    for (i = 0; i < 4; ++i) p[4u * word + i] = (unsigned char)(value >> (8u * i));
}

static void fixture(unsigned char *frame, unsigned char *states)
{
    unsigned char *state = states + 512;
    memset(frame, 0, 2048);
    memset(states, 0, 1024);
    /* A table and second entry that end exactly at 4 GiB. */
    put(frame, OPENGPU_TESS_FRAME_STATE_BASE, 0xfffffc00u);
    put(frame, OPENGPU_TESS_FRAME_STATE_LIMIT + 1, 1);
    put(frame, OPENGPU_TESS_FRAME_STATE_VERSION, 1);
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_SLOT_STRIDE, 256);
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE, 0x100000);
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE + 1, 0x100200);
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE + 3, 0x200000);
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE + 4, 0x200200);
    put(state, 0, 0x47465301); put(state, 1, 512); put(state, 2, 1); put(state, 3, 4);
    put(state, 4, 7); put(state, 5, 8); put(state, 6, 9); put(state, 7, 1);
    put(state, OPENGPU_TESS_STATE_ENGINE_COUNT_WORD, 2);
    put(state, OPENGPU_TESS_STATE_IDENTITY_CONTEXT_WORD, 7);
    put(state, OPENGPU_TESS_STATE_IDENTITY_GENERATION_WORD, 9);
    put(state, OPENGPU_TESS_STATE_IDENTITY_PASS_WORD, 8);
    put(state, OPENGPU_TESS_STATE_SCRATCH0_ALLOCATION_BASE_WORD, 0x100000);
    put(state, OPENGPU_TESS_STATE_SCRATCH0_ALLOCATION_LIMIT_WORD, 0x100200);
    put(state, OPENGPU_TESS_STATE_SCRATCH0_BASE_WORD, 0x100100);
    put(state, OPENGPU_TESS_STATE_SCRATCH0_LIMIT_WORD, 0x100180);
    put(state, OPENGPU_TESS_STATE_SCRATCH1_ALLOCATION_BASE_WORD, 0x200000);
    put(state, OPENGPU_TESS_STATE_SCRATCH1_ALLOCATION_LIMIT_WORD, 0x200200);
    put(state, OPENGPU_TESS_STATE_SCRATCH1_BASE_WORD, 0x200100);
    put(state, OPENGPU_TESS_STATE_SCRATCH1_LIMIT_WORD, 0x200180);
}

int main(void)
{
    unsigned char frame[2048], storage[1025];
    unsigned char *states = storage + 1; /* Unaligned CPU snapshots are supported. */
    unsigned char *state = states + 512;
    const struct opengpu_tessellation_job_view job = { .context = 7, .pass = 8, .generation = 9 };
#define CHECK() opengpu_tessellation_fragment_state_precheck(frame, sizeof(frame), states, 1024, &job, 2, 1)
#define REJECT(word, value) do { fixture(frame, states); put(state, (word), (value)); assert(!CHECK()); } while (0)
    fixture(frame, states);
    assert(CHECK());
    assert(!opengpu_tessellation_fragment_state_precheck(frame, sizeof(frame), states, 1024, &job, 2, 0));
    assert(!opengpu_tessellation_fragment_state_precheck(frame, sizeof(frame), states, 1024, &job, 2, 0xffffffffu));
    assert(!opengpu_tessellation_fragment_state_precheck(frame, sizeof(frame) - 1, states, 1024, &job, 2, 1));
    assert(!opengpu_tessellation_fragment_state_precheck(frame, sizeof(frame), states, 1023, &job, 2, 1));
    assert(!opengpu_tessellation_fragment_state_precheck(frame, sizeof(frame), states, 1024, &job, 0, 1));

    REJECT(0, 0); REJECT(1, 511); REJECT(2, 2); REJECT(3, 3);
    REJECT(4, 8); REJECT(5, 9); REJECT(6, 8); REJECT(7, 0);
    REJECT(OPENGPU_TESS_STATE_ENGINE_COUNT_WORD, 1);
    REJECT(OPENGPU_TESS_STATE_IDENTITY_CONTEXT_WORD, 8);
    REJECT(OPENGPU_TESS_STATE_IDENTITY_GENERATION_WORD, 8);
    REJECT(OPENGPU_TESS_STATE_IDENTITY_PASS_WORD, 9);
    REJECT(15, 1); /* Reserved word. */
    REJECT(41, 0x1f); /* Signed five-bit value must sign-extend. */
    REJECT(OPENGPU_TESS_STATE_SCRATCH0_ALLOCATION_LIMIT_WORD + 1, 2);
    REJECT(OPENGPU_TESS_STATE_SCRATCH0_BASE_WORD, 0x1000c0);
    REJECT(OPENGPU_TESS_STATE_SCRATCH0_LIMIT_WORD, 0x100201);
    REJECT(OPENGPU_TESS_STATE_SCRATCH0_ALLOCATION_BASE_WORD, 0x100101);
    REJECT(OPENGPU_TESS_STATE_SCRATCH0_ALLOCATION_LIMIT_WORD, 0x100140);
    REJECT(OPENGPU_TESS_STATE_SCRATCH2_BASE_WORD, 0x300000);

    fixture(frame, states); put(state, 41, 0xffffffffu); assert(CHECK());
    fixture(frame, states); put(frame, OPENGPU_TESS_FRAME_STATE_VERSION, 0); assert(!CHECK());
    fixture(frame, states); put(frame, OPENGPU_TESS_FRAME_STATE_LIMIT, 64); assert(!CHECK());
    fixture(frame, states); put(frame, OPENGPU_TESS_FRAME_SCRATCH_SLOT_STRIDE, 255); assert(!CHECK());
    fixture(frame, states); put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE + 1, 0x1001c0); assert(!CHECK());
    fixture(frame, states); put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE + 6, 0x300000); assert(!CHECK());
    fixture(frame, states);
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE, 0xfffffc00u);
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE + 1, 0xfffffe00u);
    put(state, OPENGPU_TESS_STATE_SCRATCH0_ALLOCATION_BASE_WORD, 0xfffffc00u);
    put(state, OPENGPU_TESS_STATE_SCRATCH0_ALLOCATION_LIMIT_WORD, 0xfffffe00u);
    put(state, OPENGPU_TESS_STATE_SCRATCH0_BASE_WORD, 0xfffffd00u);
    put(state, OPENGPU_TESS_STATE_SCRATCH0_LIMIT_WORD, 0xfffffd80u);
    assert(!CHECK()); /* Scratch view aliases the copied state table. */

    fixture(frame, states);
    put(frame, OPENGPU_TESS_FRAME_STATE_BASE, 0x40000000u);
    put(frame, OPENGPU_TESS_FRAME_STATE_LIMIT, 0x40000400u);
    put(frame, OPENGPU_TESS_FRAME_STATE_LIMIT + 1, 0);
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_SLOT_STRIDE, 64);
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE, 0xffffff80u);
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE + 1, 0);
    put(frame, OPENGPU_TESS_FRAME_SCRATCH_BASE + 2, 1);
    put(state, OPENGPU_TESS_STATE_SCRATCH0_ALLOCATION_BASE_WORD, 0xffffff80u);
    put(state, OPENGPU_TESS_STATE_SCRATCH0_ALLOCATION_LIMIT_WORD, 0);
    put(state, OPENGPU_TESS_STATE_SCRATCH0_ALLOCATION_LIMIT_WORD + 1, 1);
    put(state, OPENGPU_TESS_STATE_SCRATCH0_BASE_WORD, 0xffffffc0u);
    put(state, OPENGPU_TESS_STATE_SCRATCH0_LIMIT_WORD, 0);
    put(state, OPENGPU_TESS_STATE_SCRATCH0_LIMIT_WORD + 1, 1);
    put(state, OPENGPU_TESS_STATE_SCRATCH1_BASE_WORD, 0x200040u);
    put(state, OPENGPU_TESS_STATE_SCRATCH1_LIMIT_WORD, 0x200080u);
    assert(CHECK()); /* Both allocation and slot may end exactly at 4 GiB. */
#undef REJECT
#undef CHECK
    return 0;
}
