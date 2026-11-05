/* SPDX-License-Identifier: MIT */
#define _POSIX_C_SOURCE 200809L
/* Exercise the private arithmetic emitter and the separately compiled real
 * TGSI/menu/runtime-ABI path. Only test helpers are included; public lowering
 * still links opengpu_context.c and the normal opengpu_fs_emit.c translation
 * unit, with Mesa's actual TGSI builder/parser.
 */
#include "../gallium/opengpu_fs_emit.h"
#define OPENGPU_FS_EMIT_TEST
#define opengpu_fs_patch_immediate opengpu_dot_private_patch_immediate
#define main opengpu_dot_text_debug_main
#include "../gallium/opengpu_fs_emit.c"
#undef main
#undef opengpu_fs_patch_immediate
#undef OPENGPU_FS_EMIT_TEST
#ifndef OPENGPU_DOT_RAW_ONLY
#define main opengpu_existing_lower_test_main
#include "fs_alu_lower.c"
#undef main
#endif
#include "fs_word_vm.h"

static unsigned raw_cases, rejected_cases;
#ifndef OPENGPU_DOT_RAW_ONLY
static unsigned tgsi_cases, executed_warps;
#endif

/* Independent mathematical oracle: round each FP32 product, then each
 * left-to-right addition. Starting from product X preserves an all-negative
 * zero sum; an artificial initial +0 would not. No fmaf/contraction is used.
 */
static uint32_t reference_dot(const uint32_t a[4], const uint32_t b[4],
                               unsigned components, unsigned neg,
                               const unsigned swizzle_a[4],
                               const unsigned swizzle_b[4])
{
    volatile float products[4], sum;
    unsigned i;
    for (i = 0; i < components; i++) {
        float lhs = vm_float(a[swizzle_a[i]] ^ ((neg & 1u) ? 0x80000000u : 0u));
        float rhs = vm_float(b[swizzle_b[i]] ^ ((neg & 2u) ? 0x80000000u : 0u));
        products[i] = lhs * rhs;
    }
    sum = products[0];
    for (i = 1; i < components; i++)
        sum = sum + products[i];
    return vm_bits(sum);
}

static void init_dot_builder(struct bld *b)
{
    memset(b, 0, sizeof(*b));
    memset(b->home, 0xff, sizeof(b->home));
    memset(b->snap_temp, 0xff, sizeof(b->snap_temp));
    memset(b->in_home, 0xff, sizeof(b->in_home));
    memset(b->snap_in, 0xff, sizeof(b->snap_in));
    memset(b->out_home, 0xff, sizeof(b->out_home));
    memset(b->fconst, 0xff, sizeof(b->fconst));
    memset(b->fimm, 0xff, sizeof(b->fimm));
    memset(b->packed, 0xff, sizeof(b->packed));
    b->used[1] = b->used[2] = b->used[30] = b->used[31] = 1;
    b->snapping = 1;
}

static void raw_dot_case(unsigned components, unsigned scalar, unsigned neg,
                          unsigned swizzled, const uint32_t a[4], const uint32_t c[4])
{
    const unsigned identity[4] = {0, 1, 2, 3};
    const unsigned reverse_a[4] = {2, 0, 1, 3}, reverse_b[4] = {1, 2, 0, 3};
    const unsigned *sa = swizzled ? reverse_a : identity;
    const unsigned *sb = swizzled ? reverse_b : identity;
    struct fs_word_vm m;
    struct bld b;
    struct fs_insn in;
    unsigned s, ch, lane, i;
    int result;
    uint32_t expected = reference_dot(a, c, components, neg, sa, sb);

    init_dot_builder(&b);
    memset(&m, 0, sizeof(m));
    m.lanes = m.vl = 16;
    memset(&in, 0, sizeof(in));
    in.op = components == 2 ? OP_DP2 : components == 3 ? OP_DP3 : OP_DP4;
    in.comps = (int)components;
    in.nsrc = 2;
    in.dst_file = FILE_TEMP;
    in.dst_index = SNAPSHOT_TEMP;
    for (s = 0; s < 2; s++) {
        const uint32_t *values = s ? c : a;
        in.src[s].file = scalar & (1u << s) ? FILE_CONST : FILE_TEMP;
        in.src[s].index = (int)s;
        in.src[s].neg = !!(neg & (1u << s));
        for (ch = 0; ch < 4; ch++) {
            unsigned reg = 4 + s * 4 + ch;
            in.comp[s][ch] = (int)(s ? sb[ch] : sa[ch]);
            if (scalar & (1u << s)) {
                b.fconst[s][ch] = (int)reg;
                m.f[reg] = values[ch];
            } else {
                b.home[s][ch] = b.snap_temp[s][ch] = (int)reg;
                b.used[reg] = 1;
                for (lane = 0; lane < m.lanes; lane++)
                    m.v[reg][lane] = values[ch];
            }
        }
    }
    assert(!emit_dot(&b, &in) && !b.fail && !b.spilled);
    result = b.home[SNAPSHOT_TEMP][0];
    /* Dot must not silently change to FMA, nor use an arithmetic destination
     * as one of its sources (the existing backend's admission convention).
     */
    for (i = 0; i < (unsigned)b.n; i++) {
        uint32_t w = b.code[i];
        if ((w & 127u) == 0x57u && (((w >> 26) == 0x00) || ((w >> 26) == 0x24))) {
            assert((w >> 7 & 31u) != (w >> 15 & 31u));
            assert((w >> 7 & 31u) != (w >> 20 & 31u));
        }
        assert((w & 127u) != 0x57u || (w >> 26) < 0x28 || (w >> 26) > 0x2f);
    }
    vm_execute(&m, b.code, (unsigned)b.n, 0);
    for (lane = 0; lane < m.lanes; lane++) {
        uint32_t got = m.v[result][lane];
        if (isnan(vm_float(expected)))
            assert(isnan(vm_float(got))); /* no host NaN payload assumption */
        else
            assert(got == expected); /* includes signed zero and infinity */
    }
    raw_cases++;
}

static void check_raw_arithmetic(void)
{
    const uint32_t samples[][2][4] = {
        {{0x3e000000, 0x3e800000, 0x3f000000, 0x3f400000},
         {0x3e800000, 0x3e000000, 0x3f000000, 0x3e000000}},
        /* A fused second product+accumulate differs from the chosen FP32 sum. */
        {{0xbf800000, 0x3f800001, 0x00000000, 0x3f800000},
         {0x3f800000, 0x3f7ffffe, 0x00000000, 0x3f800000}},
        {{0x80000000, 0x80000000, 0x80000000, 0x80000000},
         {0x3f800000, 0x3f800000, 0x3f800000, 0x3f800000}},
        {{0x80000000, 0x00000000, 0x80000000, 0x00000000},
         {0x3f800000, 0x3f800000, 0x3f800000, 0x3f800000}},
        {{0x7f800000, 0x3f800000, 0x40000000, 0x7fc12345},
         {0x00000000, 0x3f800000, 0x3f800000, 0x3f800000}},
        {{0x7fc12345, 0x3f800000, 0x40000000, 0x40400000},
         {0x3f800000, 0x3f800000, 0x3f800000, 0x3f800000}},
        {{0x3e000000, 0x3e800000, 0x3f000000, 0x7fc12345},
         {0x3f800000, 0x3f800000, 0x3f800000, 0x7f800000}},
    };
    const unsigned id[4] = {0, 1, 2, 3};
    unsigned n, sample, scalar, neg, swizzle;
    volatile float separately_rounded = vm_float(0x3f800001u) * vm_float(0x3f7ffffeu);

    assert(vm_bits(separately_rounded - 1.0f) !=
           vm_bits(fmaf(vm_float(0x3f800001u), vm_float(0x3f7ffffeu), -1.0f)));
    assert(reference_dot(samples[2][0], samples[2][1], 3, 0, id, id) == 0x80000000u);
    assert(reference_dot(samples[3][0], samples[3][1], 3, 0, id, id) == 0);
    assert(!isnan(vm_float(reference_dot(samples[6][0], samples[6][1], 3, 0, id, id))));
    assert(isnan(vm_float(reference_dot(samples[6][0], samples[6][1], 4, 0, id, id))));
    for (n = 2; n <= 4; n++)
        for (sample = 0; sample < sizeof(samples) / sizeof(samples[0]); sample++)
            for (scalar = 0; scalar < 4; scalar++)
                for (neg = 0; neg < 4; neg++)
                    for (swizzle = 0; swizzle < 2; swizzle++)
                        raw_dot_case(n, scalar, neg, swizzle, samples[sample][0], samples[sample][1]);
}

#ifndef OPENGPU_DOT_RAW_ONLY
static void build_dot(struct tgsi_token *tokens, unsigned components, unsigned mask,
                       unsigned alias, unsigned neg, unsigned swizzled, int saturated)
{
    struct tgsi_header *h;
    struct op_src src[2];
    unsigned opcode = components == 2 ? TGSI_OPCODE_DP2 :
                      components == 3 ? TGSI_OPCODE_DP3 : TGSI_OPCODE_DP4;

    begin_fragment(tokens, &h);
    add_decl(tokens, h, TGSI_FILE_CONSTANT, 0, 1, -1);
    add_decl(tokens, h, TGSI_FILE_TEMPORARY, 0, 1, -1);
    add_decl(tokens, h, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    src[0] = op_src_id(TGSI_FILE_CONSTANT, 0);
    add_op(tokens, h, TGSI_OPCODE_MOV, TGSI_FILE_TEMPORARY, 0, 15, 1, src);
    add_op(tokens, h, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0, 15, 1, src);
    src[0] = op_src_id(TGSI_FILE_CONSTANT, 1);
    add_op(tokens, h, TGSI_OPCODE_MOV, TGSI_FILE_TEMPORARY, 1, 15, 1, src);
    src[0] = op_src(TGSI_FILE_TEMPORARY, 0, swizzled ? 2 : 0,
                    swizzled ? 0 : 1, swizzled ? 1 : 2, 3, neg & 1u);
    src[1] = op_src(TGSI_FILE_TEMPORARY, 1, swizzled ? 1 : 0,
                    swizzled ? 2 : 1, swizzled ? 0 : 2, 3, !!(neg & 2u));
    op_saturate = saturated;
    add_op(tokens, h, opcode, alias ? TGSI_FILE_TEMPORARY : TGSI_FILE_OUTPUT,
             alias == 2 ? 1 : 0, mask, 2, src);
    if (alias) {
        src[0] = op_src_id(TGSI_FILE_TEMPORARY, alias - 1);
        add_op(tokens, h, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0, 15, 1, src);
    }
    add_end(tokens, h);
}

static uint32_t expected_color(const uint32_t channel[4])
{
    uint32_t word = 0;
    unsigned c;
    for (c = 0; c < 4; c++) {
        volatile float scaled = vm_float(channel[c]) * 255.0f;
        assert(scaled >= 0 && scaled <= 255);
        word |= (uint32_t)lrintf(scaled) << (24 - 8 * c);
    }
    return word;
}

static void final_words_case(unsigned components, unsigned mask, unsigned alias,
                              unsigned neg, unsigned swizzled, int saturated)
{
    const uint32_t values[2][4] = {{0x3e000000, 0x3e800000, 0x3f000000, 0x3f400000},
                                 {0x3e800000, 0x3e000000, 0x3f000000, 0x3e000000}};
    const unsigned id[4] = {0, 1, 2, 3};
    const unsigned sa[4] = {2, 0, 1, 3}, sb[4] = {1, 2, 0, 3};
    const uint32_t profiles[] = {0, 0x01020204u, 0x02020810u};
    struct tgsi_token tokens[MAX_TOKENS];
    struct opengpu_fs_alu direct;
    uint32_t original[256], runtime[OPENGPU_ABI_TARGET_WORDS], expected_channels[4], want;
    unsigned words = 0, p, c;
    uint32_t dot = reference_dot(values[0], values[1], components, neg,
                                  swizzled ? sa : id, swizzled ? sb : id);

    if (saturated) {
        float f = vm_float(dot);
        dot = vm_bits(f < 0 ? 0.0f : f > 1 ? 1.0f : f);
    }
    for (c = 0; c < 4; c++)
        expected_channels[c] = mask & (1u << c) ? dot : values[alias == 2][c];
    want = expected_color(expected_channels);
    build_dot(tokens, components, mask, alias, neg, swizzled, saturated);
    assert(!opengpu_fragment_lower_kind(tokens, original, 256, &words));
    assert(!opengpu_fs_emit(tokens, &direct));
    assert(direct.words == words && !memcmp(original, direct.code, words * 4u));
    for (p = 0; p < sizeof(profiles) / sizeof(profiles[0]); p++) {
        uint32_t config = profiles[p];
        unsigned batch = opengpu_shader_resource_batch(config);
        unsigned lanes = config ? opengpu_shader_resource_lanes(config) : 4;
        unsigned stride = batch * 4u, prefix = 128, uniform = prefix + 9u * stride;
        unsigned bytes = prefix + opengpu_shader_fragment_bank_stride(config) + 128;
        unsigned warp, i;
        size_t lowered = 0;
        uint8_t memory[8192], before[8192];

        assert(bytes <= sizeof(memory) && batch % lanes == 0);
        assert(!opengpu_shader_lower_runtime_abi(original, words, false, runtime,
                                                OPENGPU_ABI_TARGET_WORDS, &lowered));
        assert(opengpu_shader_validate_words_profile_runtime(runtime, (uint32_t)lowered,
            opengpu_shader_fragment_bank_stride(config), batch, 6, 9, true,
            false, false, true, batch));
        memset(memory, 0xa7, sizeof(memory));
        {
            struct fs_word_vm seed = {0};
            seed.memory = memory;
            seed.bytes = bytes;
            for (i = 0; i < 8; i++)
                vm_store(&seed, uniform + i * 4u, values[i / 4][i % 4]);
        }
        memcpy(before, memory, sizeof(memory));
        for (warp = 0; warp < batch / lanes; warp++) {
            struct fs_word_vm m;
            memset(&m, 0, sizeof(m));
            m.memory = memory;
            m.bytes = bytes;
            m.lanes = lanes;
            m.x[1] = prefix;
            m.x[8] = warp * lanes;
            m.x[9] = batch;
            vm_execute(&m, runtime, (unsigned)lowered, 1);
            assert(m.stores == 1); /* only the final packed color output */
            executed_warps++;
        }
        for (i = 0; i < sizeof(memory); i++) {
            unsigned output = prefix + 6u * stride;
            uint8_t expected = i >= output && i < output + stride ?
                (uint8_t)(want >> (8u * ((i - output) % 4u))) : before[i];
            assert(memory[i] == expected);
        }
    }
    tgsi_cases++;
}

static void check_parser_rejections(void)
{
    struct tgsi_token tokens[MAX_TOKENS];
    unsigned which;
    for (which = 0; which < 8; which++) {
        struct tgsi_header *h;
        struct tgsi_full_instruction dot = tgsi_default_full_instruction();
        struct opengpu_fs_alu out;
        unsigned written;

        begin_fragment(tokens, &h);
        add_decl(tokens, h, TGSI_FILE_CONSTANT, 0, 1, -1);
        add_decl(tokens, h, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
        dot.Instruction.Opcode = TGSI_OPCODE_DP3;
        dot.Instruction.NumDstRegs = 1;
        dot.Instruction.NumSrcRegs = which == 0 ? 1 : which == 1 ? 3 : 2;
        dot.Dst[0].Register.File = TGSI_FILE_OUTPUT;
        dot.Dst[0].Register.WriteMask = which == 2 ? 0 : 15;
        dot.Dst[0].Register.Index = which == 3 ? 1 : 0;
        dot.Src[0].Register.File = dot.Src[1].Register.File = TGSI_FILE_CONSTANT;
        dot.Src[0].Register.Index = 0;
        dot.Src[1].Register.Index = 1;
        if (which == 4) dot.Src[0].Register.Indirect = 1;
        if (which == 5) dot.Src[0].Register.Absolute = 1;
        if (which == 6) dot.Src[1].Register.Index = 8;
        if (which == 7) {
            struct tgsi_full_property property = tgsi_default_full_property();
            property.Property.PropertyName = TGSI_PROPERTY_LEGACY_MATH_RULES;
            property.Property.NrTokens = 2;
            property.u[0].Data = 1;
            written = tgsi_build_full_property(&property, cursor(tokens, h), h, room(h));
            assert(written);
        }
        written = tgsi_build_full_instruction(&dot, cursor(tokens, h), h, room(h));
        assert(written);
        add_end(tokens, h);
        assert(opengpu_fs_emit(tokens, &out) == -1 && out.words == 0);
        rejected_cases++;
    }
}
#endif

static void check_budgets(void)
{
    struct bld b;
    struct fs_insn in;
    unsigned c, available;
    memset(&in, 0, sizeof(in));
    in.op = OP_DP3;
    in.comps = 3;
    in.nsrc = 2;
    in.dst_file = FILE_TEMP;
    in.dst_index = SNAPSHOT_TEMP;
    in.src[0].file = in.src[1].file = FILE_CONST;
    for (available = 2; available <= 3; available++) {
        init_dot_builder(&b);
        for (c = 0; c < 32; c++) b.used[c] = 1;
        for (c = 12; c < 12 + available; c++) b.used[c] = 0;
        b.fconst[0][0] = 1;
        if (available == 2) {
            assert(emit_dot(&b, &in) == -1 && b.n == 0 && !b.spilled);
            rejected_cases++;
        } else {
            struct fs_word_vm m = {0};
            m.lanes = m.vl = 4;
            m.f[1] = vm_bits(0.25f);
            assert(!emit_dot(&b, &in) && !b.fail && !b.spilled);
            vm_execute(&m, b.code, (unsigned)b.n, 0);
            assert(m.v[b.home[SNAPSHOT_TEMP][0]][0] == vm_bits(0.1875f));
        }
    }
    init_dot_builder(&b);
    b.fconst[0][0] = 1;
    assert(!emit_dot(&b, &in));
    {
        unsigned needed = (unsigned)b.n;
        init_dot_builder(&b);
        b.fconst[0][0] = 1;
        b.n = 256 - (int)needed;
        assert(!emit_dot(&b, &in) && b.n == 256 && !b.fail);
        init_dot_builder(&b);
        b.fconst[0][0] = 1;
        b.n = 257 - (int)needed;
        assert(emit_dot(&b, &in) == -1 && b.n == 256 && b.fail);
        rejected_cases++;
    }
}

static void check_existing_spill_reload(void)
{
    struct bld b;
    struct fs_insn in;
    struct fs_word_vm m = {0};
    uint8_t memory[1024];
    unsigned i, before;

    init_dot_builder(&b);
    memset(memory, 0xa7, sizeof(memory));
    m.memory = memory;
    m.bytes = sizeof(memory);
    m.lanes = m.vl = 4;
    m.x[5] = 128;
    m.f[1] = vm_bits(0.5f);
    b.home[0][0] = b.snap_temp[0][0] = 5;
    for (i = 0; i < 32; i++) b.used[i] = 1;
    for (i = 0; i < m.lanes; i++) m.v[5][i] = vm_bits(0.25f + i * 0.125f);
    /* Force the real allocator to publish its existing one-slot spill, then
     * overwrite the victim register: a stale snapshot cannot pass this test.
     */
    assert(take(&b) == 5 && b.spilled && b.home[0][0] == -2);
    assert(!put(&b, opv(0x17, 1, 0, 30, 0, 5)));
    b.snap_temp[0][0] = -2;
    b.fconst[0][0] = 1;
    memset(&in, 0, sizeof(in));
    in.op = OP_DP2;
    in.comps = in.nsrc = 2;
    in.dst_file = FILE_TEMP;
    in.dst_index = SNAPSHOT_TEMP;
    in.src[0].file = FILE_TEMP;
    in.src[1].file = FILE_CONST;
    for (i = 12; i < 15; i++) b.used[i] = 0;
    before = (unsigned)b.n;
    assert(emit_dot(&b, &in) == -1 && (unsigned)b.n == before && b.spilled);
    rejected_cases++;
    b.used[15] = 0; /* one reload register plus the three reduction registers */
    assert(!emit_dot(&b, &in) && !b.spilled && !b.fail);
    vm_execute(&m, b.code, (unsigned)b.n, 0);
    for (i = 0; i < m.lanes; i++) {
        assert(m.v[b.home[SNAPSHOT_TEMP][0]][i] == vm_bits(0.25f + i * 0.125f));
        assert(vm_load(&m, 128 + 224 + i * 4) == vm_bits(0.25f + i * 0.125f));
    }
    for (i = 0; i < sizeof(memory); i++)
        if (i < 128 + 224 || i >= 128 + 224 + m.lanes * 4)
            assert(memory[i] == 0xa7);
    assert(m.stores == 1);
    raw_cases++;
}

#ifndef OPENGPU_DOT_RAW_ONLY
static void check_packed_texture_source(void)
{
    struct tgsi_token tokens[MAX_TOKENS];
    struct tgsi_parse_context parse;
    struct tgsi_header *h;
    struct opengpu_fs_alu out;
    struct op_src src[2];
    unsigned replace = 0;

    sample_miss(tokens); /* the existing real TEX -> packed TEMP -> MOV path */
    assert(!opengpu_fs_emit(tokens, &out) && out.samples == 1);
    assert(!tgsi_parse_init(&parse, tokens));
    while (!tgsi_parse_end_of_tokens(&parse)) {
        unsigned first = parse.Position;
        tgsi_parse_token(&parse);
        if (parse.FullToken.Token.Type == TGSI_TOKEN_TYPE_INSTRUCTION &&
            parse.FullToken.FullInstruction.Instruction.Opcode == TGSI_OPCODE_MOV &&
            parse.FullToken.FullInstruction.Dst[0].Register.File == TGSI_FILE_OUTPUT)
            replace = first;
    }
    tgsi_parse_free(&parse);
    assert(replace);
    h = (struct tgsi_header *)tokens;
    h->BodySize = replace - h->HeaderSize;
    src[0] = src[1] = op_src_id(TGSI_FILE_TEMPORARY, 1);
    add_op(tokens, h, TGSI_OPCODE_DP3, TGSI_FILE_OUTPUT, 0, 15, 2, src);
    add_end(tokens, h);
    /* The legacy TEX opcode returns one packed RGBA8 word, not four FP32
     * channels. It must not be reinterpreted as a numeric dot operand.
     */
    assert(opengpu_fs_emit(tokens, &out) == -1 && out.words == 0);
    rejected_cases++;
}
#endif

int main(void)
{
#ifndef OPENGPU_DOT_RAW_ONLY
    unsigned components, mask, alias, neg, swizzle;
#endif
    assert(!fesetround(FE_TONEAREST));
    check_raw_arithmetic();
#ifndef OPENGPU_DOT_RAW_ONLY
    for (components = 2; components <= 4; components++) {
        for (mask = 1; mask <= 15; mask++)
            for (alias = 0; alias < 3; alias++)
                final_words_case(components, mask, alias, 0, 0, 0);
        for (neg = 0; neg < 4; neg++)
            for (swizzle = 0; swizzle < 2; swizzle++)
                final_words_case(components, 5, 2, neg, swizzle, 1);
    }
    check_parser_rejections();
#endif
    check_budgets();
    check_existing_spill_reload();
#ifndef OPENGPU_DOT_RAW_ONLY
    check_packed_texture_source();
    printf("fragment dot: PASS raw=%u TGSI=%u runtime-warps=%u rejected=%u; "
           "all masks, alias, FP32 rounding, guards, 128-item ABI\n",
           raw_cases, tgsi_cases, executed_warps, rejected_cases);
#else
    printf("fragment dot raw: PASS raw=%u rejected=%u; "
           "FP32 rounding, register/instruction budgets, real spill/reload\n",
           raw_cases, rejected_cases);
#endif
    return 0;
}
