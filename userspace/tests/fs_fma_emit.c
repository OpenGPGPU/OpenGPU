/* Execute the emitted RVV words with independent FP32 fmaf expectations. */
#define _POSIX_C_SOURCE 200809L
#define OPENGPU_FS_EMIT_TEST
#define main opengpu_fs_emit_debug_main
#include "../gallium/opengpu_fs_emit.c"
#undef main

#include <assert.h>
#include <math.h>

static float from_bits(uint32_t bits)
{
    float value;
    memcpy(&value, &bits, sizeof(value));
    return value;
}

static uint32_t bits_of(float value)
{
    uint32_t bits;
    memcpy(&bits, &value, sizeof(bits));
    return bits;
}

static void execute(const struct bld *b, uint32_t v[32], uint32_t f[32])
{
    int i;
    for (i = 0; i < b->n; i++) {
        uint32_t w = b->code[i];
        unsigned opcode = w & 127u, vd = (w >> 7) & 31u, f3 = (w >> 12) & 7u;
        unsigned vs1 = (w >> 15) & 31u, vs2 = (w >> 20) & 31u, f6 = w >> 26;
        if (opcode == 0x53u && (w >> 27) == 0x04u && f3 == 1u) {
            f[vd] = (f[vs1] & 0x7fffffffu) | (~f[vs2] & 0x80000000u);
        } else if (opcode == 0x57u && f6 == 0x17u && f3 == 0u) {
            v[vd] = v[vs1];
        } else if (opcode == 0x57u && f6 == 0x17u && f3 == 5u) {
            v[vd] = f[vs1];
        } else if (opcode == 0x57u && f6 == 0x09u && f3 == 1u) {
            v[vd] = (v[vs2] & 0x7fffffffu) | (~v[vs1] & 0x80000000u);
        } else if (opcode == 0x57u && f6 == 0x2cu && f3 == 1u) {
            v[vd] = bits_of(fmaf(from_bits(v[vs1]), from_bits(v[vs2]), from_bits(v[vd])));
        } else {
            fprintf(stderr, "unexpected emitted instruction %08x\n", w);
            abort();
        }
    }
}

static void init_builder(struct bld *b)
{
    memset(b, 0, sizeof(*b));
    memset(b->home, 0xff, sizeof(b->home));
    memset(b->snap_temp, 0xff, sizeof(b->snap_temp));
    memset(b->in_home, 0xff, sizeof(b->in_home));
    memset(b->snap_in, 0xff, sizeof(b->snap_in));
    memset(b->out_home, 0xff, sizeof(b->out_home));
    memset(b->fconst, 0xff, sizeof(b->fconst));
    memset(b->fimm, 0xff, sizeof(b->fimm));
    memset(b->origin_in, 0xff, sizeof(b->origin_in));
    memset(b->packed, 0xff, sizeof(b->packed));
    b->used[1] = b->used[2] = b->used[30] = b->used[31] = 1;
    b->snapping = 1;
}

static void check_case(unsigned scalar_mask, unsigned negate_mask, int alias,
                       const uint32_t values[3])
{
    struct bld b;
    struct fs_insn in;
    uint32_t v[32] = {0}, f[32] = {0}, expected_bits[3];
    int i, vd = 12;

    init_builder(&b);
    memset(&in, 0, sizeof(in));
    in.op = OP_FMA;
    in.nsrc = 3;
    in.comps = 1;
    in.dst_file = FILE_OUT;
    for (i = 0; i < 3; i++) {
        expected_bits[i] = values[i] ^ ((negate_mask & (1u << i)) ? 0x80000000u : 0u);
        in.src[i].index = i;
        in.src[i].neg = !!(negate_mask & (1u << i));
        in.comp[i][0] = 0;
        if (scalar_mask & (1u << i)) {
            in.src[i].file = FILE_CONST;
            b.fconst[i][0] = 8 + i;
            f[8 + i] = values[i];
        } else {
            in.src[i].file = FILE_TEMP;
            b.home[i][0] = b.snap_temp[i][0] = 4 + i;
            b.used[4 + i] = 1;
            v[4 + i] = values[i];
            if (alias == i)
                vd = 4 + i;
        }
    }
    b.used[vd] = 1;
    assert(fused_sources_ready(&b, &in));
    assert(!emit_fused_mad(&b, &in, vd));
    execute(&b, v, f);
    assert(v[vd] == bits_of(fmaf(from_bits(expected_bits[0]),
                                 from_bits(expected_bits[1]),
                                 from_bits(expected_bits[2]))));
}

static void check_position_y_mad(uint32_t y, uint32_t scale, uint32_t bias)
{
    struct bld b;
    struct fs_insn in;
    uint32_t v[32] = {0}, f[32] = {0};

    init_builder(&b);
    memset(&in, 0, sizeof(in));
    b.have_y_int = 1;
    b.y_int = 4;
    b.used[4] = b.used[5] = 1;
    b.fragcoord_is_input0 = 1;
    b.in_home[1] = b.snap_in[1] = 5;
    b.fconst[0][0] = 8;
    b.fconst[0][1] = 9;
    v[5] = y;
    f[8] = scale;
    f[9] = bias;
    in.op = OP_MAD;
    in.nsrc = 3;
    in.comps = 1;
    in.dst_file = FILE_OUT;
    in.src[0].file = FILE_IN;
    in.comp[0][0] = 1;
    in.src[1].file = in.src[2].file = FILE_CONST;
    in.comp[2][0] = 1;
    assert(!emit_one(&b, &in));
    assert(b.out_home[0] >= 0);
    execute(&b, v, f);
    assert(v[b.out_home[0]] == bits_of(fmaf(from_bits(y), from_bits(scale), from_bits(bias))));
}

int main(void)
{
    const uint32_t samples[][3] = {
        {0x3f800001u, 0x3f7ffffeu, 0xbf800000u}, /* fused result is -2^-46 */
        {0x3fc00000u, 0xc0000000u, 0x40400000u},
        {0x80000000u, 0x40400000u, 0x00000000u},
        {0x41200000u, 0x3e800000u, 0xc0a00000u},
    };
    unsigned sample, scalar_mask, negate_mask;
    struct bld b;
    struct fs_insn in;
    volatile float product = from_bits(samples[0][0]) * from_bits(samples[0][1]);
    volatile float separate = product + from_bits(samples[0][2]);
    assert(bits_of(fmaf(from_bits(samples[0][0]), from_bits(samples[0][1]),
                        from_bits(samples[0][2]))) == 0xa8800000u);
    assert(bits_of(separate) == 0);
    for (sample = 0; sample < sizeof(samples) / sizeof(samples[0]); sample++)
        for (scalar_mask = 0; scalar_mask < 8; scalar_mask++)
            for (negate_mask = 0; negate_mask < 8; negate_mask++) {
                int alias;
                for (alias = -1; alias < 3; alias++)
                    check_case(scalar_mask, negate_mask, alias, samples[sample]);
            }
    check_position_y_mad(0x00000000u, 0x3e000000u, 0x3e000000u);
    check_position_y_mad(0x40400000u, 0x3e000000u, 0x3e000000u);
    check_position_y_mad(0x40400000u, 0x3f800000u, 0x00000000u);
    check_position_y_mad(0x40400000u, 0xbf800000u, 0x40800000u);

    init_builder(&b);
    memset(&in, 0, sizeof(in));
    in.op = OP_FMA;
    in.nsrc = 3;
    in.comps = 1;
    for (int i = 0; i < 3; i++) {
        in.src[i].file = FILE_TEMP;
        in.src[i].index = i;
        b.home[i][0] = b.snap_temp[i][0] = 4 + i;
        b.used[4 + i] = 1;
    }
    b.snap_temp[0][0] = -2;
    assert(!fused_sources_ready(&b, &in));
    b.snap_temp[0][0] = 4;
    b.packed[0] = 4;
    assert(!fused_sources_ready(&b, &in));
    b.packed[0] = -1;
    in.dst_file = FILE_TEMP;
    in.dst_index = 1;
    b.packed[1] = 5;
    assert(!fused_sources_ready(&b, &in));
    b.packed[1] = -1;
    for (int i = 4; i <= 29; i++) b.used[i] = 1;
    b.used[27] = b.used[28] = b.used[29] = 0;
    assert(!fused_sources_ready(&b, &in));
    assert(b.n == 0);

    /* A snapshot cannot evict an unavailable source or overflow the IR. */
    init_builder(&b);
    memset(&in, 0, sizeof(in));
    in.op = OP_MOV;
    in.snapshot_copy = 1;
    in.nsrc = in.comps = 1;
    in.dst_file = FILE_TEMP;
    in.dst_index = SNAPSHOT_TEMP;
    in.src[0].file = FILE_TEMP;
    b.home[0][0] = b.snap_temp[0][0] = -2;
    assert(emit_one(&b, &in));
    b.home[0][0] = b.snap_temp[0][0] = 4;
    b.packed[0] = 4;
    assert(emit_one(&b, &in));
    b.packed[0] = -1;
    for (int i = 4; i <= 29; i++) b.used[i] = 1;
    assert(emit_one(&b, &in));
    assert(b.n == 0);
    {
        struct fs_insn ir[MAX_INSN] = {{0}};
        int count = MAX_INSN, first = MAX_INSN - 2;

        ir[first].dst_file = FILE_TEMP;
        ir[first].dst_chan = 0;
        ir[first + 1].nsrc = 1;
        ir[first + 1].src[0].file = FILE_TEMP;
        assert(snapshot_fused_hazards(ir, first, &count));
        assert(count == MAX_INSN);
    }
    puts("fragment fused multiply-add emitted semantics: PASS");
    return 0;
}
