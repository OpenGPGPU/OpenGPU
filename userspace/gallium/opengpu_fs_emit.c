/* SPDX-License-Identifier: MIT */
/* Lower a TGSI fragment program onto the fragment core. The rasterizer
 * has already written integer pixel x,y. RSQ is vfrsqrt7.v. SQRT is
 * fsqrt.s: a uniform source is one sqrt plus a splat, and a varying
 * source is one sqrt per lane. The vector unit joins every physical
 * lane, and a vfsqrt whose tail lanes were never written did not retire.
 * One uniform if/else becomes two forward branches. The condition may be
 * a constant or a temporary computed only from constants. KILL_IF clears
 * the fragment validity word for lanes whose source is negative. A 2D
 * TEX the clang menu did not claim loads the interpolated U and V and
 * issues vtex.sample. Immediates are materialized with fmv.w.x so the
 * eight-float uniform tail stays available for constants. A program that
 * does not fit in 256 instructions fails the draw. */
#include "opengpu_fs_emit.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define OP_MOV 1
#define OP_ADD 2
#define OP_MUL 3
#define OP_MAX 4
#define OP_SLT 5
#define OP_SGE 6
#define OP_SEQ 7
#define OP_DIV 8
#define OP_RSQ 9
#define OP_RCP 10
#define OP_CMP 11
#define OP_DP2 12
#define OP_DP4 13
#define OP_MAD 14
#define OP_MIN 15
#define OP_SQRT 16
#define OP_IF 17
#define OP_ELSE 18
#define OP_ENDIF 19
#define OP_KILL 20
#define OP_KILL_IF 21
#define OP_TEX 22
#define OP_FMA 23
#define OP_DP3 24

#define FILE_TEMP 1
#define FILE_CONST 2
#define FILE_IMM 3
#define FILE_IN 4
#define FILE_OUT 5

#define MAX_INSN 400
#define MAX_USER_TEMP 16
#define SNAPSHOT_TEMP MAX_USER_TEMP
#define MAX_TEMP (MAX_USER_TEMP + 1)

struct fs_src {
    int file;
    int index;
    int chan;
    int neg;
};

struct fs_insn {
    int op;
    int dst_file;
    int dst_index;
    int dst_chan;
    int nsrc;
    int sat;
    int comps;
    int snapshot_copy;
    /* Set on IF when the predicate is uniform at that instruction, before
     * a later write of the same temporary. */
    int uniform_src;
    struct fs_src src[3];
    int comp[3][4];
};

static int dot_components(int op)
{
    return op == OP_DP2 ? 2 : op == OP_DP3 ? 3 : op == OP_DP4 ? 4 : 0;
}

/* A TGSI dot reads every source component before writing any destination.
 * Compute it once in the existing private temporary, then replicate only to
 * the original write mask. This also keeps an aliased destination from
 * changing a source needed by another component of the same instruction.
 * The private slot is reused only after its preceding copies have completed.
 */
static int replicate_dot_result(struct fs_insn *ir, int first, int *count,
                                 unsigned mask)
{
    struct fs_insn dot;
    int c, copies = 0;

    if (first < 0 || *count != first + 1 || !dot_components(ir[first].op) ||
        ir[first].nsrc != 2 || !mask || (mask & ~15u))
        return -1;
    dot = ir[first];
    if ((dot.dst_file != FILE_OUT && dot.dst_file != FILE_TEMP) ||
        (dot.dst_file == FILE_OUT && dot.dst_index != 0) ||
        (dot.dst_file == FILE_TEMP &&
         (dot.dst_index < 0 || dot.dst_index >= MAX_USER_TEMP)))
        return -1;
    for (c = 0; c < 4; c++)
        copies += !!(mask & (1u << c));
    if (*count + copies > MAX_INSN)
        return -1;
    ir[first].dst_file = FILE_TEMP;
    ir[first].dst_index = SNAPSHOT_TEMP;
    ir[first].dst_chan = 0;
    for (c = 0; c < 4; c++) {
        struct fs_insn *copy;

        if (!(mask & (1u << c)))
            continue;
        copy = &ir[(*count)++];
        memset(copy, 0, sizeof(*copy));
        copy->op = OP_MOV;
        copy->dst_file = dot.dst_file;
        copy->dst_index = dot.dst_index;
        copy->dst_chan = c;
        copy->nsrc = copy->comps = 1;
        copy->src[0].file = FILE_TEMP;
        copy->src[0].index = SNAPSHOT_TEMP;
    }
    return 0;
}

/* Save channels that a later scalarized component reads after an earlier
 * component of this TGSI instruction overwrites them. The reserved temporary
 * is private to the emitter and reused after each instruction. */
static int snapshot_fused_hazards(struct fs_insn *ir, int first, int *count)
{
    int earlier, later, s, c, needed[4] = {0}, copies = 0, index, flow = 0;

    if (first >= *count || ir[first].dst_file != FILE_TEMP)
        return 0;
    index = ir[first].dst_index;
    for (earlier = first; earlier < *count; earlier++) {
        const struct fs_insn *dst = &ir[earlier];

        for (later = earlier + 1; later < *count; later++) {
            for (s = 0; s < ir[later].nsrc; s++) {
                const struct fs_src *src = &ir[later].src[s];

                if (src->file == FILE_TEMP && src->index == dst->dst_index &&
                    ir[later].comp[s][0] == dst->dst_chan)
                    needed[dst->dst_chan] = 1;
            }
        }
    }
    for (c = 0; c < 4; c++)
        copies += needed[c];
    if (!copies)
        return 0;
    /* The conditional allocator does not yet preserve these private snapshots
     * across all branch joins. Keep the previous rejection in flow regions. */
    for (earlier = 0; earlier < first; earlier++) {
        if (ir[earlier].op == OP_IF)
            flow++;
        else if (ir[earlier].op == OP_ENDIF)
            flow--;
    }
    if (flow)
        return -1;
    if (*count + copies > MAX_INSN)
        return -1;
    for (later = first; later < *count; later++) {
        for (s = 0; s < ir[later].nsrc; s++) {
            struct fs_src *src = &ir[later].src[s];
            c = ir[later].comp[s][0];
            if (src->file == FILE_TEMP && src->index == index && needed[c])
                src->index = SNAPSHOT_TEMP;
        }
    }
    memmove(&ir[first + copies], &ir[first], (size_t)(*count - first) * sizeof(*ir));
    later = first;
    for (c = 0; c < 4; c++) {
        struct fs_insn *copy;

        if (!needed[c])
            continue;
        copy = &ir[later++];
        memset(copy, 0, sizeof(*copy));
        copy->op = OP_MOV;
        copy->dst_file = FILE_TEMP;
        copy->dst_index = SNAPSHOT_TEMP;
        copy->dst_chan = c;
        copy->nsrc = copy->comps = copy->snapshot_copy = 1;
        copy->src[0].file = FILE_TEMP;
        copy->src[0].index = index;
        copy->comp[0][0] = c;
    }
    *count += copies;
    return 0;
}

struct bld {
    uint32_t code[256];
    int n;
    int used[32];
    int home[MAX_TEMP][4];
    int pin[MAX_TEMP][4];
    int in_home[4];
    int out_home[4];
    int y_int;
    int have_y_int;
    int fconst[8][4];
    int fimm[2][4];
    int nconst;
    int nimm;
    int f_zero;
    int f_one;
    int f_byte;
    int spilled;
    int fail;
    int uniform[MAX_TEMP][4];
    int origin_in[MAX_TEMP][4];
    int origin_ch[MAX_TEMP][4];
    int packed[MAX_TEMP];
    int packed_out;
    int in_uv[8];
    int sem_in[8];
    int sem_in_index[8];
    int fragcoord_is_input0;
    int samples;
    int sample_in;
    int patch_n;
    int patch_at[4];
    int patch_src[4];
    int snapping;
    int snap_temp[MAX_TEMP][4];
    int snap_in[4];
    const struct fs_insn *ir;
    int nir;
    int ip;
};

static int ir_reads_in(const struct bld *b, int chan);

static uint32_t opv(unsigned f6, unsigned vm, unsigned vs2, unsigned vs1,
                    unsigned f3, unsigned vd)
{
    return (f6 << 26) | (vm << 25) | (vs2 << 20) | (vs1 << 15) |
           (f3 << 12) | (vd << 7) | 0x57u;
}

static uint32_t fpu(unsigned f5, unsigned rs2, unsigned rs1, unsigned rm,
                    unsigned rd)
{
    return (f5 << 27) | (rs2 << 20) | (rs1 << 15) | (rm << 12) | (rd << 7) |
           0x53u;
}

/* opengpu.vtex.sample vd, vs1, vs2. vs1 is U and vs2 is V. */
static uint32_t vtex(unsigned vs1, unsigned vs2, unsigned vd)
{
    return (1u << 26) | (1u << 25) | (vs2 << 20) | (vs1 << 15) | (vd << 7) |
           0x2bu;
}

static int put(struct bld *b, uint32_t w)
{
    if (b->n >= 256) {
        b->fail = 1;
        return -1;
    }
    b->code[b->n++] = w;
    return 0;
}

static int addi(struct bld *b, int rd, int rs1, int imm)
{
    return put(b, ((unsigned)imm << 20) | ((unsigned)rs1 << 15) |
                      ((unsigned)rd << 7) | 0x13u);
}

static uint32_t vload(int vd)
{
    return (0x02036087u & ~0x00000f80u) | ((unsigned)vd << 7);
}

static uint32_t vstore(int vd)
{
    return (0x020360a7u & ~0x00000f80u) | ((unsigned)vd << 7);
}

static int flw(struct bld *b, int rd, int imm)
{
    return put(b, ((unsigned)imm << 20) | (1u << 15) | (2u << 12) |
                      ((unsigned)rd << 7) | 0x07u);
}

static int take(struct bld *b)
{
    int v;

    for (v = 4; v <= 29; v++) {
        if (!b->used[v]) {
            b->used[v] = 1;
            return v;
        }
    }
    if (!b->spilled) {
        int victim = -1;
        int vt = 0, vc = 0, t, c, best = -1;

        for (t = 0; t < MAX_TEMP; t++) {
            for (c = 0; c < 4; c++) {
                int h = b->home[t][c];
                int u, dist;

                if (h < 5 || h > 29)
                    continue;
                dist = 1000;
                for (u = b->ip + 1; u < b->nir; u++) {
                    int s;

                    for (s = 0; s < b->ir[u].nsrc; s++) {
                        int k;

                        for (k = 0; k < b->ir[u].comps; k++) {
                            if (b->ir[u].src[s].file == FILE_TEMP &&
                                b->ir[u].src[s].index == t &&
                                b->ir[u].comp[s][k] == c) {
                                dist = u - b->ip;
                                goto found;
                            }
                        }
                    }
                }
            found:
                if (dist > best) {
                    best = dist;
                    victim = h;
                    vt = t;
                    vc = c;
                }
            }
        }
        if (victim >= 0 && b->n + 2 < 256) {
            addi(b, 6, 5, 224);
            put(b, vstore(victim));
            b->home[vt][vc] = -2;
            b->used[victim] = 0;
            b->spilled = 1;
            b->used[victim] = 1;
            return victim;
        }
    }
    b->fail = 1;
    return -1;
}

static void release(struct bld *b, int v)
{
    if (v >= 5 && v <= 29)
        b->used[v] = 0;
}

static void materialize_words(float val, uint32_t out[3], unsigned rd)
{
    int32_t bits, hi, lo;

    memcpy(&bits, &val, sizeof(bits));
    hi = (bits + 0x800) >> 12;
    lo = bits - (hi << 12);
    out[0] = ((uint32_t)hi & 0xfffffu) << 12 | (7u << 7) | 0x37u;
    out[1] = ((unsigned)lo & 0xfffu) << 20 | (7u << 15) | (7u << 7) | 0x13u;
    out[2] = fpu(0x1e, 0, 7, 0, rd);
}

void opengpu_fs_patch_immediate(uint32_t *code, unsigned at, float val)
{
    uint32_t word[3];
    unsigned rd = (code[at + 2] >> 7) & 31u;

    materialize_words(val, word, rd);
    code[at] = word[0];
    code[at + 1] = word[1];
    code[at + 2] = word[2];
}

static int materialize_f(struct bld *b, float val, int rd)
{
    int32_t bits, hi, lo;

    memcpy(&bits, &val, sizeof(bits));
    hi = (bits + 0x800) >> 12;
    lo = bits - (hi << 12);
    if (put(b, ((uint32_t)hi & 0xfffffu) << 12 | (7u << 7) | 0x37u))
        return -1;
    if (lo && addi(b, 7, 7, lo))
        return -1;
    return put(b, fpu(0x1e, 0, 7, 0, (unsigned)rd));
}

/* A draw-time constant past the eight-float tail. Three words so the
 * later patch can replace both halves of the immediate. */
static int materialize_slot(struct bld *b, int rd, int src)
{
    uint32_t word[3];
    int i;

    if (b->patch_n >= 4)
        return -1;
    b->patch_at[b->patch_n] = b->n;
    b->patch_src[b->patch_n] = src;
    b->patch_n++;
    materialize_words(0.f, word, (unsigned)rd);
    for (i = 0; i < 3; i++) {
        if (put(b, word[i]))
            return -1;
    }
    return 0;
}

/* One fsqrt.s per lane. vslidedown brings that lane to element 0,
 * fmv.x.w carries the root, and vslide1up assembles the vector. */
static int sqrt_lanes(struct bld *b, int src, int vd)
{
    int acc = take(b);
    int slide = take(b);
    int lane;

    if (acc < 0 || slide < 0)
        return -1;
    if (put(b, opv(0x17, 1, 0, (unsigned)b->f_zero, 5, (unsigned)acc)))
        return -1;
    for (lane = 3; lane >= 0; lane--) {
        int got = src;

        if (lane &&
            put(b, opv(0x0f, 1, (unsigned)src, (unsigned)lane, 3,
                       (unsigned)slide)))
            return -1;
        if (lane)
            got = slide;
        if (put(b, opv(0x10, 1, (unsigned)got, 0, 1, 30)) ||
            put(b, fpu(0x0b, 0, 30, 7, 31)) ||
            put(b, fpu(0x1c, 0, 31, 0, 7)) ||
            put(b, opv(0x0e, 1, (unsigned)acc, 7, 6, (unsigned)slide)) ||
            put(b, opv(0x17, 1, 0, (unsigned)slide, 0, (unsigned)acc)))
            return -1;
    }
    if (acc != vd &&
        put(b, opv(0x17, 1, 0, (unsigned)acc, 0, (unsigned)vd)))
        return -1;
    if (slide != vd)
        release(b, slide);
    if (acc != vd)
        release(b, acc);
    return 0;
}

static int materialize(struct bld *b, int t, int c)
{
    int h = b->home[t][c];
    int v;

    if (h >= 0)
        return h;
    if (h != -2 || !b->spilled)
        return -1;
    v = take(b);
    if (v < 0)
        return -1;
    if (addi(b, 6, 5, 224) || put(b, vload(v)))
        return -1;
    b->home[t][c] = v;
    b->spilled = 0;
    return v;
}

static int live_later(const struct bld *b, int file, int index, int chan)
{
    int u, s, k;

    for (u = b->ip + 1; u < b->nir; u++) {
        for (s = 0; s < b->ir[u].nsrc; s++) {
            if (b->ir[u].src[s].file != file || b->ir[u].src[s].index != index)
                continue;
            for (k = 0; k < b->ir[u].comps; k++) {
                if (b->ir[u].comp[s][k] == chan)
                    return 1;
            }
        }
    }
    return 0;
}

static void kill_src(struct bld *b, const struct fs_insn *in, const struct fs_src *s,
                      int chan)
{
    int h;

    if (s->file == in->dst_file && s->index == in->dst_index &&
        chan == in->dst_chan)
        return;
    if (live_later(b, s->file, s->index, chan))
        return;
    if (s->file == FILE_TEMP) {
        int t, c, still = 0;

        h = b->home[s->index][chan];
        if (h >= 0)
            b->home[s->index][chan] = -1;
        for (t = 0; t < MAX_TEMP && !still; t++) {
            for (c = 0; c < 4; c++) {
                if (b->home[t][c] == h)
                    still = 1;
            }
        }
        if (!still)
            release(b, h);
    } else if (s->file == FILE_IN && chan >= 0 && chan < 4 &&
               b->in_home[chan] >= 0) {
        release(b, b->in_home[chan]);
        b->in_home[chan] = -1;
    }
}

struct loc {
    int v;
    int f;
    int is_f;
};

static int locate(struct bld *b, const struct fs_src *s, int chan, struct loc *o)
{
    int f;

    o->v = -1;
    o->f = -1;
    o->is_f = 0;
    if (s->file == FILE_TEMP) {
        if (b->snapping)
            o->v = b->snap_temp[s->index][chan];
        else
            o->v = b->home[s->index][chan];
        if (o->v == -2)
            o->v = materialize(b, s->index, chan);
        if (o->v < 0)
            return -1;
        return 0;
    }
    if (s->file == FILE_IN) {
        o->v = b->snapping ? b->snap_in[chan] : b->in_home[chan];
        return o->v < 0 ? -1 : 0;
    }
    if (s->file == FILE_CONST)
        f = b->fconst[s->index][chan];
    else if (s->file == FILE_IMM)
        f = b->fimm[s->index][chan];
    else
        return -1;
    if (f < 0)
        return -1;
    o->f = f;
    o->is_f = 1;
    return 0;
}

static int as_vec(struct bld *b, struct loc *o, int scratch)
{
    if (!o->is_f)
        return o->v;
    if (put(b, opv(0x17, 1, 0, o->f, 5, scratch)))
        return -1;
    return scratch;
}

static int neg_loc(struct bld *b, struct loc *o, int do_neg, int scratch)
{
    if (!do_neg)
        return as_vec(b, o, scratch);
    if (o->is_f) {
        if (put(b, fpu(0x04, o->f, o->f, 1, 30)))
            return -1;
        o->f = 30;
        return as_vec(b, o, scratch);
    }
    if (put(b, opv(0x09, 1, o->v, o->v, 1, scratch)))
        return -1;
    return scratch;
}

static int dest_reg(struct bld *b, int file, int index, int chan)
{
    int v;

    if (file == FILE_OUT) {
        if (b->out_home[chan] < 0) {
            v = take(b);
            if (v < 0)
                return -1;
            b->out_home[chan] = v;
        }
        return b->out_home[chan];
    }
    /* A destination reserved for both sides of an if keeps one register
     * through every write, so the join reads the value each side stored. */
    if (index >= 0 && index < MAX_TEMP && chan >= 0 && chan < 4 &&
        b->pin[index][chan] && b->home[index][chan] >= 0)
        return b->home[index][chan];
    if (b->home[index][chan] >= 0 && !live_later(b, FILE_TEMP, index, chan))
        return b->home[index][chan];
    v = take(b);
    if (v < 0)
        return -1;
    b->home[index][chan] = v;
    return v;
}

static void gc(struct bld *b)
{
    int live[32] = { 0 };
    int t, c, v;

    live[1] = live[2] = live[30] = live[31] = 1;
    if (b->have_y_int && b->y_int >= 0)
        live[b->y_int] = 1;
    for (t = 0; t < MAX_TEMP; t++) {
        for (c = 0; c < 4; c++) {
            if (b->home[t][c] >= 0)
                live[b->home[t][c]] = 1;
        }
    }
    for (c = 0; c < 4; c++) {
        if (b->in_home[c] >= 0)
            live[b->in_home[c]] = 1;
        if (b->out_home[c] >= 0)
            live[b->out_home[c]] = 1;
    }
    for (v = 4; v <= 29; v++) {
        if (b->used[v] && !live[v])
            b->used[v] = 0;
    }
}

static int sat(struct bld *b, int vd)
{
    int z = take(b);
    int tmp = take(b);

    /* vfmax.vf does not retire. Clamp with vfmax.vv, then vfmin.vf. */
    if (z < 0 || tmp < 0 ||
        put(b, opv(0x17, 1, 0, (unsigned)b->f_zero, 5, (unsigned)z)) ||
        put(b, opv(0x06, 1, (unsigned)vd, (unsigned)z, 1, (unsigned)tmp)) ||
        put(b, opv(0x04, 1, (unsigned)tmp, (unsigned)b->f_one, 5, (unsigned)tmp)) ||
        put(b, opv(0x17, 1, 0, (unsigned)tmp, 0, (unsigned)vd)))
        return -1;
    release(b, z);
    release(b, tmp);
    return 0;
}

static int cmp_mask(struct bld *b, unsigned f6, int va, int vb, int fb, int b_is_f)
{
    if (b_is_f)
        return put(b, opv(f6, 1, va, fb, 5, 0));
    return put(b, opv(f6, 1, va, vb, 1, 0));
}

static int finish_cmp(struct bld *b, int vd)
{
    if (put(b, opv(0x17, 0, 30, 31, 0, 1)))
        return -1;
    if (vd == 1)
        return 0;
    return put(b, opv(0x17, 1, 0, 1, 0, vd));
}

static int emit_cmp(struct bld *b, unsigned f6, struct loc *a, int neg_a,
                    struct loc *c, int neg_c)
{
    int va, vc;

    va = neg_loc(b, a, neg_a, 2);
    if (va < 0)
        return -1;
    if (c->is_f && !neg_c) {
        if (cmp_mask(b, f6, va, 0, c->f, 1))
            return -1;
    } else {
        vc = neg_loc(b, c, neg_c, va == 2 ? 1 : 2);
        if (vc < 0 || va == vc)
            return -1;
        if (cmp_mask(b, f6, va, vc, 0, 0))
            return -1;
    }
    return 0;
}

static int bin(struct bld *b, unsigned f6, int vd, struct loc *a, int neg_a,
               struct loc *c, int neg_c, int vv)
{
    int va, vc;
    int scratch_a = 2;
    int scratch_c = 1;

    va = neg_loc(b, a, neg_a, scratch_a);
    if (va < 0)
        return -1;
    if (va == vd) {
        int tmp = take(b);

        if (tmp < 0 || put(b, opv(0x17, 1, 0, (unsigned)va, 0, (unsigned)tmp)))
            return -1;
        va = tmp;
    }
    if (c->is_f && !neg_c && !vv)
        return put(b, opv(f6, 1, va, c->f, 5, vd));
    if (va == scratch_c)
        scratch_c = 2;
    vc = neg_loc(b, c, neg_c, scratch_c);
    if (vc < 0)
        return -1;
    if (vc == vd) {
        int tmp = take(b);

        if (tmp < 0 || put(b, opv(0x17, 1, 0, (unsigned)vc, 0, (unsigned)tmp)))
            return -1;
        vc = tmp;
    }
    if (va == scratch_a && vc == scratch_a)
        return -1;
    return put(b, opv(f6, 1, va, vc, 1, vd));
}

/* Materialize three independent vector operands before writing the result.
 * vfmacc.vv reads its old destination, so aliasing any source with the
 * destination must not destroy it while the addend is being prepared.
 */
static int fused_sources_ready(const struct bld *b, const struct fs_insn *in)
{
    int free_regs = 0, i;

    if (in->nsrc != 3)
        return 0;
    if (in->dst_file == FILE_TEMP &&
        (in->dst_index < 0 || in->dst_index >= MAX_TEMP ||
         b->packed[in->dst_index] >= 0))
        return 0;
    for (i = 4; i <= 29; i++)
        free_regs += !b->used[i];
    /* One destination and three operands; never evict a snapshot source. */
    if (free_regs < 4)
        return 0;
    for (i = 0; i < 3; i++) {
        const struct fs_src *s = &in->src[i];
        int ch = in->comp[i][0], reg;

        if (ch < 0 || ch >= 4 || s->index < 0)
            return 0;
        switch (s->file) {
        case FILE_TEMP:
            if (s->index >= MAX_TEMP || b->packed[s->index] >= 0)
                return 0;
            reg = b->snapping ? b->snap_temp[s->index][ch] : b->home[s->index][ch];
            if (reg < 0) /* Includes a spilled source; fail before emitting. */
                return 0;
            break;
        case FILE_IN:
            /* Only the position input at index zero has numeric homes.
             * Other inputs may be texture metadata, not fragment coordinates. */
            if (s->index != 0 || !b->fragcoord_is_input0)
                return 0;
            reg = b->snapping ? b->snap_in[ch] : b->in_home[ch];
            if (reg < 0)
                return 0;
            break;
        case FILE_CONST:
            if (s->index >= 8 || b->fconst[s->index][ch] < 0)
                return 0;
            break;
        case FILE_IMM:
            if (s->index >= 2 || b->fimm[s->index][ch] < 0)
                return 0;
            break;
        default:
            return 0;
        }
    }
    return 1;
}

static int emit_fused_mad(struct bld *b, const struct fs_insn *in, int vd)
{
    int v[3], i;

    for (i = 0; i < 3; i++) {
        struct loc source;
        int value;

        if (locate(b, &in->src[i], in->comp[i][0], &source))
            return -1;
        v[i] = take(b);
        if (v[i] < 0)
            return -1;
        value = neg_loc(b, &source, in->src[i].neg, v[i]);
        if (value < 0 || (value != v[i] &&
            put(b, opv(0x17, 1, 0, (unsigned)value, 0, (unsigned)v[i]))))
            return -1;
    }
    /* v2 is the copied addend and remains distinct from both multipliers. */
    if (put(b, opv(0x2c, 1, (unsigned)v[1], (unsigned)v[0], 1, (unsigned)v[2])) ||
        put(b, opv(0x17, 1, 0, (unsigned)v[2], 0, (unsigned)vd)))
        return -1;
    for (i = 0; i < 3; i++)
        release(b, v[i]);
    return 0;
}

/* The divider and the reciprocal estimate scoreboard their destination
 * before reading sources. An in-place opcode waits on itself and the
 * warp never retires. */
static int copy_to(struct bld *b, int vd, int tmp)
{
    if (tmp == vd)
        return 0;
    if (put(b, opv(0x17, 1, 0, (unsigned)tmp, 0, (unsigned)vd)))
        return -1;
    release(b, tmp);
    return 0;
}

static int not_inplace(struct bld *b, int vd, int src_a, int src_b)
{
    if (vd != src_a && vd != src_b)
        return vd;
    return take(b);
}

static int emit_dot(struct bld *b, const struct fs_insn *in)
{
    int c, s, free_regs = 0, ncomp = dot_components(in->op);
    int vd, product, other, acc, spill_t = -1, spill_ch = -1;

    if (!ncomp || in->comps != ncomp || in->nsrc != 2 ||
        in->dst_file != FILE_TEMP || in->dst_index != SNAPSHOT_TEMP ||
        in->dst_chan != 0)
        return -1;
    for (c = 4; c <= 29; c++)
        free_regs += !b->used[c];
    /* Destination plus two work registers. Never spill/evict a source from
     * this instruction's snapshot while allocating a reduction register.
     */
    if (free_regs < 3)
        return -1;
    for (s = 0; s < 2; s++) {
        const struct fs_src *src = &in->src[s];

        if (src->index < 0)
            return -1;
        for (c = 0; c < ncomp; c++) {
            int ch = in->comp[s][c], reg;

            if (ch < 0 || ch >= 4)
                return -1;
            switch (src->file) {
            case FILE_TEMP:
                if (src->index >= MAX_USER_TEMP || b->packed[src->index] >= 0)
                    return -1;
                reg = b->snapping ? b->snap_temp[src->index][ch] :
                                   b->home[src->index][ch];
                if (reg == -2) {
                    if (!b->spilled || (spill_t >= 0 &&
                        (spill_t != src->index || spill_ch != ch)))
                        return -1;
                    spill_t = src->index;
                    spill_ch = ch;
                } else if (reg < 0)
                    return -1;
                break;
            case FILE_IN:
                if (src->index != 0 || !b->fragcoord_is_input0)
                    return -1;
                reg = b->snapping ? b->snap_in[ch] : b->in_home[ch];
                if (reg < 0)
                    return -1;
                break;
            case FILE_CONST:
                if (src->index >= 8 || b->fconst[src->index][ch] < 0)
                    return -1;
                break;
            case FILE_IMM:
                if (src->index >= 2 || b->fimm[src->index][ch] < 0)
                    return -1;
                break;
            default:
                return -1;
            }
        }
    }
    if (spill_t >= 0) {
        int loaded;

        /* Reload the one existing spill before reduction allocation, while
         * every source snapshot still owns its original physical register.
         */
        if (free_regs < 4 || (loaded = materialize(b, spill_t, spill_ch)) < 0)
            return -1;
        b->snap_temp[spill_t][spill_ch] = loaded;
    }
    vd = dest_reg(b, FILE_TEMP, SNAPSHOT_TEMP, 0);
    product = take(b);
    other = take(b);
    if (vd < 0 || product < 0 || other < 0)
        return -1;
    acc = vd;
    for (c = 0; c < ncomp; c++) {
        struct loc a, d;
        int va, vc;

        if (locate(b, &in->src[0], in->comp[0][c], &a) ||
            locate(b, &in->src[1], in->comp[1][c], &d))
            return -1;
        va = neg_loc(b, &a, in->src[0].neg, 1);
        vc = neg_loc(b, &d, in->src[1].neg, va == 1 ? 2 : 1);
        if (va < 0 || vc < 0 ||
            put(b, opv(0x24, 1, (unsigned)va, (unsigned)vc, 1,
                        (unsigned)product)))
            return -1;
        if (!c) {
            if (put(b, opv(0x17, 1, 0, (unsigned)product, 0, (unsigned)acc)))
                return -1;
        } else {
            int next = acc == vd ? other : vd;

            /* Explicit FP32 multiply then left-to-right add, not FMA. Keep
             * arithmetic destinations distinct from both source registers.
             */
            if (put(b, opv(0x00, 1, (unsigned)acc, (unsigned)product, 1,
                            (unsigned)next)))
                return -1;
            acc = next;
        }
    }
    if (acc != vd && put(b, opv(0x17, 1, 0, (unsigned)acc, 0, (unsigned)vd)))
        return -1;
    release(b, product);
    release(b, other);
    return in->sat ? sat(b, vd) : 0;
}

static int emit_tex(struct bld *b, const struct fs_insn *in)
{
    int idx = in->src[0].index;
    int u_ch = in->comp[0][0];
    int v_ch = in->comp[0][1];
    int u_in, v_in, vu, vv, vd, c;

    if (in->src[0].file != FILE_TEMP || idx < 0 || idx >= MAX_TEMP)
        return -1;
    u_in = b->origin_in[idx][u_ch];
    v_in = b->origin_in[idx][v_ch];
    if (u_in < 0 || u_in != v_in || u_in >= 8 || !b->in_uv[u_in] ||
        b->origin_ch[idx][u_ch] != 0 || b->origin_ch[idx][v_ch] != 1)
        return -1;
    vu = take(b);
    vv = take(b);
    vd = take(b);
    if (vu < 0 || vv < 0 || vd < 0)
        return -1;
    /* U at byte 128 and V at byte 160, the same slots the clang sample uses. */
    if (addi(b, 6, 5, 128) || put(b, vload(vu)) || addi(b, 6, 5, 160) ||
        put(b, vload(vv)) || put(b, vtex((unsigned)vu, (unsigned)vv, (unsigned)vd)))
        return -1;
    release(b, vu);
    release(b, vv);
    b->samples = 1;
    b->sample_in = u_in;
    if (in->dst_file == FILE_OUT) {
        b->packed_out = vd;
        return 0;
    }
    b->packed[in->dst_index] = vd;
    for (c = 0; c < 4; c++) {
        b->home[in->dst_index][c] = vd;
        b->origin_in[in->dst_index][c] = -1;
    }
    return 0;
}

static int emit_one(struct bld *b, const struct fs_insn *in)
{
    struct loc a, c;
    int vd, k;

    if (in->snapshot_copy) {
        int free_regs = 0;
        int index = in->src[0].index, chan = in->comp[0][0];
        int source;

        if (in->src[0].file != FILE_TEMP || index < 0 || index >= MAX_USER_TEMP ||
            chan < 0 || chan >= 4 || b->packed[index] >= 0)
            return -1;
        source = b->snapping ? b->snap_temp[index][chan] : b->home[index][chan];
        for (k = 4; k <= 29; k++)
            free_regs += !b->used[k];
        /* Do not spill or evict the value before the copy reads it. */
        if (source < 0 || !free_regs)
            return -1;
    }
    if (in->op == OP_TEX)
        return emit_tex(b, in);
    if (dot_components(in->op))
        return emit_dot(b, in);
    if (in->op == OP_MOV && in->nsrc == 1 && in->src[0].file == FILE_TEMP &&
        in->src[0].index >= 0 && in->src[0].index < MAX_TEMP &&
        b->packed[in->src[0].index] >= 0) {
        if (in->dst_file != FILE_OUT)
            return -1;
        b->packed_out = b->packed[in->src[0].index];
        return 0;
    }
    if (in->op == OP_MOV && in->nsrc == 1 && !in->src[0].neg &&
        in->src[0].file == FILE_IN && in->dst_file == FILE_TEMP &&
        in->src[0].index >= 0 && in->src[0].index < 8 &&
        b->in_uv[in->src[0].index]) {
        int ch = in->dst_chan;

        if (ch < 0 || ch >= 4)
            return -1;
        b->origin_in[in->dst_index][ch] = in->src[0].index;
        b->origin_ch[in->dst_index][ch] = in->comp[0][0];
        b->uniform[in->dst_index][ch] = 0;
        return 0;
    }
    if (in->op == OP_MOV && in->nsrc == 1 && !in->src[0].neg &&
        in->src[0].file == in->dst_file && in->src[0].index == in->dst_index &&
        in->comp[0][0] == in->dst_chan)
        return 0;
    if ((in->op == OP_MAD || in->op == OP_FMA) && !fused_sources_ready(b, in))
        return -1;
    vd = dest_reg(b, in->dst_file, in->dst_index, in->dst_chan);
    if (vd < 0)
        return -1;
    if (in->dst_file == FILE_TEMP) {
        if (b->packed[in->dst_index] >= 0)
            return -1;
        b->origin_in[in->dst_index][in->dst_chan] = -1;
    }
    if (in->op == OP_MAD || in->op == OP_FMA)
        return emit_fused_mad(b, in, vd) || (in->sat && sat(b, vd));
    if (in->op == OP_RSQ || in->op == OP_RCP || in->op == OP_SQRT) {
        if (locate(b, &in->src[0], in->comp[0][0], &a))
            return -1;
        /* f31 is not a constant register. fsqrt.s into it, then one
         * vfmv.v.f, keeps a uniform root off the 16-lane vector sqrt. */
        if (in->op == OP_SQRT && a.is_f) {
            unsigned fs = (unsigned)a.f;

            if (in->src[0].neg) {
                if (put(b, fpu(0x04, fs, fs, 1, 30)))
                    return -1;
                fs = 30;
            }
            if (fs == 31 || put(b, fpu(0x0b, 0, fs, 7, 31)) ||
                put(b, opv(0x17, 1, 0, 31, 5, (unsigned)vd)))
                return -1;
            return in->sat ? sat(b, vd) : 0;
        }
        k = neg_loc(b, &a, in->src[0].neg, 2);
        if (k < 0)
            return -1;
        {
            int tmp = not_inplace(b, vd, k, -1);

            if (tmp < 0)
                return -1;
            if (in->op == OP_RSQ) {
                /* vfrsqrt7.v, the approximate reciprocal square root. */
                if (put(b, opv(0x13, 1, (unsigned)k, 4, 1, (unsigned)tmp)))
                    return -1;
            } else if (in->op == OP_SQRT) {
                if (sqrt_lanes(b, k, tmp))
                    return -1;
            } else if (put(b, opv(0x21, 1, (unsigned)k, (unsigned)b->f_one, 5,
                                  (unsigned)tmp))) {
                return -1;
            }
            if (copy_to(b, vd, tmp))
                return -1;
        }
        return in->sat ? sat(b, vd) : 0;
    }
    if (in->nsrc == 1) {
        if (locate(b, &in->src[0], in->comp[0][0], &a))
            return -1;
        k = neg_loc(b, &a, in->src[0].neg, 2);
        if (k < 0)
            return -1;
        if (k != vd && put(b, opv(0x17, 1, 0, k, 0, vd)))
            return -1;
        return in->sat ? sat(b, vd) : 0;
    }
    if (locate(b, &in->src[0], in->comp[0][0], &a))
        return -1;
    if (in->op == OP_SLT || in->op == OP_SGE || in->op == OP_SEQ) {
        unsigned f6 = in->op == OP_SLT ? 0x1b : in->op == OP_SGE ? 0x1f : 0x18;

        if (locate(b, &in->src[1], in->comp[1][0], &c))
            return -1;
        if (!a.is_f && c.is_f) {
            if (emit_cmp(b, f6, &a, in->src[0].neg, &c, in->src[1].neg))
                return -1;
        } else if (a.is_f && !c.is_f) {
            unsigned swap = in->op == OP_SLT ? 0x1d : in->op == OP_SGE ? 0x19
                                                                        : 0x18;
            if (emit_cmp(b, swap, &c, in->src[1].neg, &a, in->src[0].neg))
                return -1;
        } else if (in->op == OP_SGE) {
            /* vmfge.vv is not in the fragment profile. b <= a is vmfle.vv. */
            if (emit_cmp(b, 0x19, &c, in->src[1].neg, &a, in->src[0].neg))
                return -1;
        } else {
            if (emit_cmp(b, f6, &a, in->src[0].neg, &c, in->src[1].neg))
                return -1;
        }
        if (finish_cmp(b, vd))
            return -1;
        return in->sat ? sat(b, vd) : 0;
    }
    if (in->op == OP_CMP) {
        struct loc tval, fval;
        int cond, tv, fv;

        if (locate(b, &in->src[0], in->comp[0][0], &a) ||
            locate(b, &in->src[1], in->comp[1][0], &tval) ||
            locate(b, &in->src[2], in->comp[2][0], &fval))
            return -1;
        /* CMP is (src0 < 0). A negated source is src > 0, compared with
         * vmfgt so the value is not run through vfsgnjn. */
        if (in->src[0].neg) {
            cond = as_vec(b, &a, 2);
            if (cond < 0 || cmp_mask(b, 0x1d, cond, 0, b->f_zero, 1))
                return -1;
        } else {
            cond = neg_loc(b, &a, 0, 2);
            if (cond < 0 || cmp_mask(b, 0x1b, cond, 0, b->f_zero, 1))
                return -1;
        }
        tv = tval.is_f ? -1 : tval.v;
        fv = fval.is_f ? -1 : fval.v;
        if (tval.is_f) {
            tv = take(b);
            if (tv < 0 || put(b, opv(0x17, 1, 0, tval.f, 5, tv)))
                return -1;
        }
        if (fval.is_f) {
            fv = (tv == 1) ? 2 : 1;
            if (fv == cond)
                fv = take(b);
            if (fv < 0 || put(b, opv(0x17, 1, 0, fval.f, 5, fv)))
                return -1;
        }
        if (put(b, opv(0x17, 0, fv, tv, 0, 1)))
            return -1;
        if (tval.is_f)
            release(b, tv);
        if (vd != 1 && put(b, opv(0x17, 1, 0, 1, 0, vd)))
            return -1;
        return in->sat ? sat(b, vd) : 0;
    }
    if (locate(b, &in->src[1], in->comp[1][0], &c))
        return -1;
    if (in->op == OP_MUL) {
        if (bin(b, 0x24, vd, &a, in->src[0].neg, &c, in->src[1].neg, 1))
            return -1;
    } else if (in->op == OP_ADD) {
        if (bin(b, 0x00, vd, &a, in->src[0].neg, &c, in->src[1].neg, 1))
            return -1;
    } else if (in->op == OP_MAX) {
        /* vfmax.vf does not retire on this fragment core. vfmax.vv is the
         * form fp_binary already runs. */
        if (bin(b, 0x06, vd, &a, in->src[0].neg, &c, in->src[1].neg, 1))
            return -1;
    } else if (in->op == OP_MIN) {
        if (bin(b, 0x04, vd, &a, in->src[0].neg, &c, in->src[1].neg, 0))
            return -1;
    } else if (in->op == OP_DIV) {
        int va, vc, rec, prod;

        /* vfdiv.vv of a just-converted FragCoord does not retire, and the
         * quotients that do retire are zeros. Reciprocal then multiply
         * stays on vfrdiv.vf and vfmul.vv, which this core completes. */
        va = neg_loc(b, &a, in->src[0].neg, 2);
        vc = neg_loc(b, &c, in->src[1].neg, 1);
        if (va < 0 || vc < 0)
            return -1;
        rec = take(b);
        if (rec < 0)
            return -1;
        if (put(b, opv(0x21, 1, (unsigned)vc, (unsigned)b->f_one, 5,
                       (unsigned)rec)))
            return -1;
        prod = not_inplace(b, vd, va, rec);
        if (prod < 0 ||
            put(b, opv(0x24, 1, (unsigned)va, (unsigned)rec, 1,
                       (unsigned)prod)) ||
            copy_to(b, vd, prod))
            return -1;
        if (rec != vd)
            release(b, rec);
    } else {
        return -1;
    }
    return in->sat ? sat(b, vd) : 0;
}

static int prologue(struct bld *b, const float *imm, int nimm)
{
    int i, c, nfloat, v;

    memset(b->home, 0xff, sizeof(b->home));
    memset(b->in_home, 0xff, sizeof(b->in_home));
    memset(b->out_home, 0xff, sizeof(b->out_home));
    memset(b->fconst, 0xff, sizeof(b->fconst));
    memset(b->fimm, 0xff, sizeof(b->fimm));
    memset(b->origin_in, 0xff, sizeof(b->origin_in));
    memset(b->packed, 0xff, sizeof(b->packed));
    b->packed_out = -1;
    b->f_zero = -1;
    b->f_one = -1;
    nfloat = 1;
    for (i = 0; i < b->nconst; i++) {
        for (c = 0; c < 4; c++) {
            int src = i * 4 + c;

            if (nfloat > 28)
                return -1;
            b->fconst[i][c] = nfloat;
            /* The kernarg tail holds eight floats. Later constants, such
             * as gl_FbWposYTransform, are patched into the instruction
             * stream at draw time. */
            if (src < 8) {
                if (flw(b, nfloat, 288 + src * 4))
                    return -1;
            } else if (materialize_slot(b, nfloat, src)) {
                return -1;
            }
            nfloat++;
        }
    }
    /* Immediates stay in scalar registers. The eight-float tail is the
     * declared constants, which is all pipe_opengpu_write_fs_block accepts. */
    if (nimm > 8)
        return -1;
    for (i = 0; i < nimm / 4; i++) {
        for (c = 0; c < 4; c++) {
            float val = imm[i * 4 + c];

            if (val == 0.0f || val == 1.0f)
                continue;
            if (nfloat > 28)
                return -1;
            b->fimm[i][c] = nfloat;
            if (materialize_f(b, val, nfloat))
                return -1;
            nfloat++;
        }
    }
    /* Clamp and reciprocal need 0 and 1 even when the shader never wrote
     * those immediates. fcvt.s.w of an integer is exact for both. */
    if (b->f_zero < 0) {
        if (nfloat > 28)
            return -1;
        b->f_zero = nfloat++;
        if (put(b, fpu(0x1a, 0, 0, 7, b->f_zero)))
            return -1;
    }
    if (b->f_one < 0) {
        if (nfloat > 28)
            return -1;
        b->f_one = nfloat++;
        if (addi(b, 7, 0, 1) || put(b, fpu(0x1a, 0, 7, 7, b->f_one)))
            return -1;
    }
    for (i = 0; i < nimm / 4; i++) {
        for (c = 0; c < 4; c++) {
            float val = imm[i * 4 + c];

            if (val == 0.0f)
                b->fimm[i][c] = b->f_zero;
            else if (val == 1.0f)
                b->fimm[i][c] = b->f_one;
        }
    }
    if (nfloat > 29)
        return -1;
    b->f_byte = 29;
    if (addi(b, 7, 0, 255) || put(b, fpu(0x1a, 0, 7, 7, b->f_byte)))
        return -1;
    if (put(b, 0x00241293u) || put(b, 0x005082b3u) || put(b, 0xc1027057u))
        return -1;
    /* Reserve v4 for the integer gl_FragCoord.y before allocating the
     * float x register. Retaining it keeps existing register allocation. */
    b->y_int = 4;
    b->used[4] = 1;
    b->have_y_int = 1;
    v = take(b);
    if (v < 0)
        return -1;
    b->in_home[0] = v;
    if (addi(b, 6, 5, 0) || put(b, vload(v)))
        return -1;
    if (put(b, opv(0x12, 1, v, 3, 1, v)))
        return -1;
    if (addi(b, 6, 5, 32) || put(b, vload(4)))
        return -1;
    /* Float gl_FragCoord.y. Programs that never read it keep the integer
     * in v4 only, so the checked-in straight-line images stay put. */
    if (ir_reads_in(b, 1)) {
        int yv = take(b);

        if (yv < 0)
            return -1;
        b->in_home[1] = yv;
        if (put(b, opv(0x12, 1, 4, 3, 1, (unsigned)yv)))
            return -1;
    }
    if (put(b, opv(0x17, 1, 0, b->f_zero, 5, 30)))
        return -1;
    if (put(b, opv(0x17, 1, 0, b->f_one, 5, 31)))
        return -1;
    return 0;
}

static int epilogue(struct bld *b)
{
    int ch, v[4];

    if (b->packed_out >= 0) {
        if (addi(b, 6, 5, 192) || put(b, vstore(b->packed_out)))
            return -1;
        return put(b, 0x30500073u);
    }
    for (ch = 0; ch < 4; ch++) {
        v[ch] = b->out_home[ch];
        if (v[ch] < 0)
            return -1;
        if (put(b, opv(0x24, 1, v[ch], b->f_byte, 5, v[ch])))
            return -1;
        if (put(b, opv(0x12, 1, v[ch], 1, 1, v[ch])))
            return -1;
    }
    if (put(b, opv(0x25, 1, v[0], 24, 3, v[0])))
        return -1;
    if (put(b, opv(0x25, 1, v[1], 16, 3, v[1])))
        return -1;
    if (put(b, opv(0x25, 1, v[2], 8, 3, v[2])))
        return -1;
    if (put(b, opv(0x0a, 1, v[0], v[1], 0, v[0])))
        return -1;
    if (put(b, opv(0x0a, 1, v[0], v[2], 0, v[0])))
        return -1;
    if (put(b, opv(0x0a, 1, v[0], v[3], 0, v[0])))
        return -1;
    if (addi(b, 6, 5, 192))
        return -1;
    if (put(b, vstore(v[0])))
        return -1;
    return put(b, 0x30500073u);
}

static int ir_reads_in(const struct bld *b, int chan)
{
    int i, s, k;

    for (i = 0; i < b->nir; i++) {
        for (s = 0; s < b->ir[i].nsrc; s++) {
            if (b->ir[i].src[s].file != FILE_IN || b->ir[i].src[s].index != 0)
                continue;
            for (k = 0; k < b->ir[i].comps; k++) {
                if (b->ir[i].comp[s][k] == chan)
                    return 1;
            }
        }
    }
    return 0;
}

static int is_flow(int op)
{
    return op == OP_IF || op == OP_ELSE || op == OP_ENDIF || op == OP_KILL ||
           op == OP_KILL_IF;
}

static uint32_t branch_word(unsigned funct3, unsigned rs1, unsigned rs2,
                            int byte_off)
{
    unsigned imm = (unsigned)byte_off;

    return ((imm >> 12) & 1u) << 31 | ((imm >> 5) & 0x3fu) << 25 |
           (rs2 << 20) | (rs1 << 15) | (funct3 << 12) |
           ((imm >> 1) & 0xfu) << 8 | ((imm >> 11) & 1u) << 7 | 0x63u;
}

static int insn_reads(const struct fs_insn *in, int file, int index, int chan)
{
    int s, k;

    for (s = 0; s < in->nsrc; s++) {
        if (in->src[s].file != file || in->src[s].index != index)
            continue;
        for (k = 0; k < in->comps; k++) {
            if (in->comp[s][k] == chan)
                return 1;
        }
    }
    return 0;
}

struct alloc_snap {
    int used[32];
    int home[MAX_TEMP][4];
    int pin[MAX_TEMP][4];
    int in_home[4];
    int out_home[4];
    int have_y_int;
    int spilled;
};

static void save_alloc(const struct bld *b, struct alloc_snap *s)
{
    memcpy(s->used, b->used, sizeof(s->used));
    memcpy(s->home, b->home, sizeof(s->home));
    memcpy(s->pin, b->pin, sizeof(s->pin));
    memcpy(s->in_home, b->in_home, sizeof(s->in_home));
    memcpy(s->out_home, b->out_home, sizeof(s->out_home));
    s->have_y_int = b->have_y_int;
    s->spilled = b->spilled;
}

static void restore_alloc(struct bld *b, const struct alloc_snap *s)
{
    memcpy(b->used, s->used, sizeof(b->used));
    memcpy(b->home, s->home, sizeof(b->home));
    memcpy(b->pin, s->pin, sizeof(b->pin));
    memcpy(b->in_home, s->in_home, sizeof(b->in_home));
    memcpy(b->out_home, s->out_home, sizeof(b->out_home));
    b->have_y_int = s->have_y_int;
    b->spilled = s->spilled;
}

static int emit_span(struct bld *b, struct fs_insn *ir, int begin, int end)
{
    int i;

    for (i = begin; i < end; i++) {
        int s;

        if (is_flow(ir[i].op))
            return -1;
        b->ip = i;
        memcpy(b->snap_temp, b->home, sizeof(b->home));
        memcpy(b->snap_in, b->in_home, sizeof(b->in_home));
        b->snapping = 1;
        if (emit_one(b, &ir[i])) {
            fprintf(stderr, "opengpu: fragment op %d failed after %d words\n",
                    ir[i].op, b->n);
            return -1;
        }
        b->snapping = 0;
        gc(b);
        for (s = 0; s < ir[i].nsrc; s++) {
            int k;

            for (k = 0; k < ir[i].comps; k++)
                kill_src(b, &ir[i], &ir[i].src[s], ir[i].comp[s][k]);
        }
        if (b->fail)
            return -1;
    }
    return 0;
}

/* A zero validity word drops that lane. A nonzero word keeps it. */
static int emit_kill(struct bld *b, const struct fs_insn *in)
{
    int vd, i;

    if (in->op == OP_KILL) {
        if (addi(b, 6, 5, 256) || put(b, vstore(30)))
            return -1;
        return 0;
    }
    vd = take(b);
    if (vd < 0)
        return -1;
    if (put(b, opv(0x17, 1, 0, 31, 0, (unsigned)vd)))
        return -1;
    for (i = 0; i < 4; i++) {
        struct loc a;
        int src;

        if (locate(b, &in->src[0], in->comp[0][i], &a))
            return -1;
        src = neg_loc(b, &a, in->src[0].neg, 2);
        if (src < 0 || cmp_mask(b, 0x1b, src, 0, b->f_zero, 1))
            return -1;
        /* vd = mask ? 0 : vd. Negative sources become a zero word. */
        if (put(b, opv(0x17, 0, (unsigned)vd, 30, 0, (unsigned)vd)))
            return -1;
    }
    if (addi(b, 6, 5, 256) || put(b, vstore(vd)))
        return -1;
    release(b, vd);
    return 0;
}

struct dest_id {
    int file;
    int index;
    int chan;
};

static int same_dest(struct dest_id a, struct dest_id b)
{
    return a.file == b.file && a.index == b.index && a.chan == b.chan;
}

static void push_dest(struct dest_id *seq, int *n, const struct fs_insn *in)
{
    struct dest_id d;
    int i;

    if (in->dst_file != FILE_TEMP && in->dst_file != FILE_OUT)
        return;
    d.file = in->dst_file;
    d.index = in->dst_index;
    d.chan = in->dst_chan;
    for (i = 0; i < *n; i++) {
        if (same_dest(seq[i], d))
            return;
    }
    if (*n < 64)
        seq[(*n)++] = d;
}

/* Uniform if/else. The condition is a constant, an immediate, or a
 * temporary computed only from those, so every lane takes the same side.
 * Both sides write the same destinations and the allocator is restarted
 * for the else side, which keeps those registers defined on the join.
 * When the two sides allocate in a different order, destinations written
 * on both sides are reserved first. */
static int emit_if(struct bld *b, struct fs_insn *ir, int n, int *io)
{
    int if_i = *io;
    int else_i = -1, endif_i = -1, j;
    int bne_at, beq_at, else_at, endif_at;
    struct loc cond;
    struct alloc_snap snap;

    for (j = if_i + 1; j < n; j++) {
        if (ir[j].op == OP_IF)
            return -1;
        if (ir[j].op == OP_ELSE) {
            if (else_i >= 0)
                return -1;
            else_i = j;
        } else if (ir[j].op == OP_ENDIF) {
            endif_i = j;
            break;
        }
    }
    if (else_i < 0 || endif_i < 0)
        return -1;
    if (ir[if_i].src[0].neg)
        return -1;
    b->ip = if_i;
    if (ir[if_i].src[0].file == FILE_TEMP) {
        int t = ir[if_i].src[0].index;
        int ch = ir[if_i].comp[0][0];
        struct dest_id then_s[64], else_s[64];
        int nt = 0, ne = 0, same, k;

        if (t < 0 || t >= MAX_TEMP || ch < 0 || ch >= 4 || !ir[if_i].uniform_src)
            return -1;
        if (locate(b, &ir[if_i].src[0], ch, &cond) || cond.is_f || cond.v < 0)
            return -1;
        /* Lane 0 holds the uniform predicate. feq against zero matches the
         * constant form below. */
        if (put(b, opv(0x10, 1, (unsigned)cond.v, 0, 1, 30)) ||
            put(b, fpu(0x14, (unsigned)b->f_zero, 30, 2, 7)))
            return -1;
        for (k = if_i + 1; k < else_i; k++)
            push_dest(then_s, &nt, &ir[k]);
        for (k = else_i + 1; k < endif_i; k++)
            push_dest(else_s, &ne, &ir[k]);
        same = nt == ne;
        for (k = 0; same && k < nt; k++)
            same = same_dest(then_s[k], else_s[k]);
        if (!same) {
            for (k = 0; k < nt; k++) {
                int e;

                for (e = 0; e < ne; e++) {
                    if (!same_dest(then_s[k], else_s[e]))
                        continue;
                    if (dest_reg(b, then_s[k].file, then_s[k].index,
                                 then_s[k].chan) < 0)
                        return -1;
                    if (then_s[k].file == FILE_TEMP &&
                        then_s[k].index >= 0 && then_s[k].index < MAX_TEMP &&
                        then_s[k].chan >= 0 && then_s[k].chan < 4)
                        b->pin[then_s[k].index][then_s[k].chan] = 1;
                }
            }
        }
    } else if (ir[if_i].src[0].file == FILE_CONST ||
               ir[if_i].src[0].file == FILE_IMM) {
        if (locate(b, &ir[if_i].src[0], ir[if_i].comp[0][0], &cond) || !cond.is_f)
            return -1;
        /* feq.s x7, fcond, fzero; bne x7, x0, else. Fall-through is the
         * true side. x7 is free after the prologue. */
        if (put(b, fpu(0x14, (unsigned)b->f_zero, (unsigned)cond.f, 2, 7)))
            return -1;
    } else {
        return -1;
    }
    bne_at = b->n;
    if (put(b, 0))
        return -1;
    save_alloc(b, &snap);
    if (emit_span(b, ir, if_i + 1, else_i))
        return -1;
    beq_at = b->n;
    if (put(b, 0))
        return -1;
    else_at = b->n;
    restore_alloc(b, &snap);
    if (emit_span(b, ir, else_i + 1, endif_i))
        return -1;
    endif_at = b->n;
    if ((else_at - bne_at) * 4 <= 0 || (endif_at - beq_at) * 4 <= 0)
        return -1;
    b->code[bne_at] = branch_word(1, 7, 0, (else_at - bne_at) * 4);
    b->code[beq_at] = branch_word(0, 0, 0, (endif_at - beq_at) * 4);
    *io = endif_i;
    return 0;
}

static int compile_ir(struct bld *b, struct fs_insn *ir, int n,
                      const float *imm, int nimm)
{
    int live[MAX_INSN];
    int i, changed;

    for (i = 0; i < n; i++)
        live[i] = 1;
    do {
        changed = 0;
        for (i = 0; i < n; i++) {
            int j, used = 0;

            if (!live[i] || ir[i].dst_file == FILE_OUT || is_flow(ir[i].op))
                continue;
            for (j = i + 1; j < n; j++) {
                if (live[j] &&
                    insn_reads(&ir[j], ir[i].dst_file, ir[i].dst_index,
                               ir[i].dst_chan)) {
                    used = 1;
                    break;
                }
            }
            if (!used) {
                live[i] = 0;
                changed = 1;
            }
        }
    } while (changed);
    {
        int w = 0;

        for (i = 0; i < n; i++) {
            if (live[i])
                ir[w++] = ir[i];
        }
        n = w;
    }
    b->ir = ir;
    b->nir = n;
    memset(b->uniform, 0, sizeof(b->uniform));
    for (i = 0; i < n; i++) {
        int u = 1, s, k;

        if (is_flow(ir[i].op) || ir[i].op == OP_TEX) {
            /* Later instructions may overwrite the predicate temporary.
             * The if sees the value that is live here. */
            if (ir[i].op == OP_IF && ir[i].src[0].file == FILE_TEMP) {
                int t = ir[i].src[0].index;
                int ch = ir[i].comp[0][0];

                ir[i].uniform_src = t >= 0 && t < MAX_TEMP && ch >= 0 &&
                                    ch < 4 && b->uniform[t][ch];
            }
            continue;
        }
        for (s = 0; s < ir[i].nsrc; s++) {
            for (k = 0; k < ir[i].comps; k++) {
                int file = ir[i].src[s].file;
                int index = ir[i].src[s].index;
                int chan = ir[i].comp[s][k];

                if (file == FILE_IN)
                    u = 0;
                else if (file == FILE_TEMP &&
                         (index < 0 || index >= MAX_TEMP || chan < 0 ||
                          chan >= 4 || !b->uniform[index][chan]))
                    u = 0;
            }
        }
        if (ir[i].dst_file == FILE_TEMP && ir[i].dst_index >= 0 &&
            ir[i].dst_index < MAX_TEMP && ir[i].dst_chan >= 0 &&
            ir[i].dst_chan < 4)
            b->uniform[ir[i].dst_index][ir[i].dst_chan] = u;
    }
    if (prologue(b, imm, nimm))
        return -1;
    for (i = 0; i < n; i++) {
        if (ir[i].op == OP_IF) {
            if (emit_if(b, ir, n, &i))
                return -1;
            continue;
        }
        if (ir[i].op == OP_ELSE || ir[i].op == OP_ENDIF)
            return -1;
        if (ir[i].op == OP_KILL || ir[i].op == OP_KILL_IF) {
            b->ip = i;
            if (emit_kill(b, &ir[i]))
                return -1;
            continue;
        }
        if (emit_span(b, ir, i, i + 1))
            return -1;
    }
    return epilogue(b);
}

#ifndef OPENGPU_FS_EMIT_TEST
#include "tgsi/tgsi_parse.h"

static unsigned swizzle(const struct tgsi_src_register *r, unsigned c)
{
    switch (c) {
    case 0:
        return r->SwizzleX;
    case 1:
        return r->SwizzleY;
    case 2:
        return r->SwizzleZ;
    default:
        return r->SwizzleW;
    }
}

static int src_file(unsigned file, int index, int *out_file, int *out_index)
{
    if (file == TGSI_FILE_TEMPORARY) {
        if (index < 0 || index >= MAX_USER_TEMP)
            return -1;
        *out_file = FILE_TEMP;
        *out_index = index;
    } else if (file == TGSI_FILE_CONSTANT) {
        *out_file = FILE_CONST;
        *out_index = index;
    } else if (file == TGSI_FILE_IMMEDIATE) {
        *out_file = FILE_IMM;
        *out_index = index;
    } else if (file == TGSI_FILE_INPUT) {
        *out_file = FILE_IN;
        *out_index = index;
    } else {
        return -1;
    }
    return 0;
}

static const char *fs_op_name(int op)
{
    switch (op) {
    case OP_MOV: return "MOV";
    case OP_ADD: return "ADD";
    case OP_MUL: return "MUL";
    case OP_MAX: return "MAX";
    case OP_MIN: return "MIN";
    case OP_SLT: return "SLT";
    case OP_SGE: return "SGE";
    case OP_SEQ: return "SEQ";
    case OP_DIV: return "DIV";
    case OP_RSQ: return "RSQ";
    case OP_RCP: return "RCP";
    case OP_CMP: return "CMP";
    case OP_DP2: return "DP2";
    case OP_DP3: return "DP3";
    case OP_DP4: return "DP4";
    case OP_MAD: return "MAD";
    case OP_FMA: return "FMA";
    case OP_SQRT: return "SQRT";
    case OP_IF: return "IF";
    case OP_ELSE: return "ELSE";
    case OP_ENDIF: return "ENDIF";
    case OP_KILL: return "KILL";
    case OP_KILL_IF: return "KILL_IF";
    case OP_TEX: return "TEX";
    default: return "?";
    }
}

static char fs_file_name(int file)
{
    switch (file) {
    case FILE_TEMP: return 'T';
    case FILE_CONST: return 'C';
    case FILE_IMM: return 'I';
    case FILE_IN: return 'N';
    case FILE_OUT: return 'O';
    default: return '?';
    }
}

static void fs_dump(const struct bld *b)
{
    static const char chan_name[4] = { 'x', 'y', 'z', 'w' };
    int i, s, k;

    if (!getenv("OPENGPU_FS_EMIT_DUMP"))
        return;
    for (i = 0; i < b->nir; i++) {
        const struct fs_insn *in = &b->ir[i];

        fprintf(stderr, "ir %d %s %c%d.%c sat=%d comps=%d", i,
                fs_op_name(in->op), fs_file_name(in->dst_file), in->dst_index,
                chan_name[in->dst_chan & 3], in->sat, in->comps);
        for (s = 0; s < in->nsrc; s++) {
            fprintf(stderr, "  s%d=%c%d[%c", s, fs_file_name(in->src[s].file),
                    in->src[s].index, in->src[s].neg ? '-' : '+');
            for (k = 0; k < in->comps; k++)
                fprintf(stderr, "%c", chan_name[in->comp[s][k] & 3]);
            fprintf(stderr, "]");
        }
        fprintf(stderr, "\n");
    }
    fprintf(stderr, "home");
    for (i = 0; i < MAX_TEMP; i++)
        for (k = 0; k < 4; k++)
            if (b->home[i][k] >= 0)
                fprintf(stderr, " T%d.%c=v%d", i, chan_name[k],
                        b->home[i][k]);
    fprintf(stderr, "\nin_home");
    for (k = 0; k < 4; k++)
        if (b->in_home[k] >= 0)
            fprintf(stderr, " %c=v%d", chan_name[k], b->in_home[k]);
    fprintf(stderr, "\nout_home");
    for (k = 0; k < 4; k++)
        if (b->out_home[k] >= 0)
            fprintf(stderr, " %c=v%d", chan_name[k], b->out_home[k]);
    fprintf(stderr, "\nused");
    for (k = 0; k < 32; k++)
        if (b->used[k])
            fprintf(stderr, " v%d", k);
    fprintf(stderr, "\n");
    for (i = 0; i < b->n; i++)
        fprintf(stderr, "w %03d %08x\n", i, b->code[i]);
}

int opengpu_fs_emit(const void *tokens, struct opengpu_fs_alu *out)
{
    struct tgsi_parse_context parse;
    struct fs_insn ir[MAX_INSN];
    struct bld bld;
    float imm[8];
    int n = 0, nimm = 0, nconst = 0, i;
    int saw_end = 0, saw_dot = 0, legacy_math = 0;
    int in_uv[8], sem_in[8], sem_in_index[8];

    memset(in_uv, 0, sizeof(in_uv));
    memset(sem_in, 0xff, sizeof(sem_in));
    memset(sem_in_index, 0, sizeof(sem_in_index));

    if (!tokens || !out)
        return -1;
    memset(out, 0, sizeof(*out));
    memset(imm, 0, sizeof(imm));
    if (tgsi_parse_init(&parse, tokens))
        return -1;
    while (!tgsi_parse_end_of_tokens(&parse) && !saw_end) {
        struct tgsi_full_instruction *insn;
        unsigned op, mask, c, s;
        int kind, comps, ir_first;

        tgsi_parse_token(&parse);
        switch (parse.FullToken.Token.Type) {
        case TGSI_TOKEN_TYPE_DECLARATION:
            if (parse.FullToken.FullDeclaration.Declaration.File ==
                    TGSI_FILE_CONSTANT &&
                parse.FullToken.FullDeclaration.Range.Last + 1 > nconst)
                nconst = parse.FullToken.FullDeclaration.Range.Last + 1;
            if (parse.FullToken.FullDeclaration.Declaration.File ==
                    TGSI_FILE_INPUT &&
                parse.FullToken.FullDeclaration.Declaration.Semantic) {
                int index = (int)parse.FullToken.FullDeclaration.Range.First;
                unsigned sem = parse.FullToken.FullDeclaration.Semantic.Name;

                if (index >= 0 && index < 8) {
                    sem_in[index] = (int)sem;
                    sem_in_index[index] =
                        (int)parse.FullToken.FullDeclaration.Semantic.Index;
                    if (sem == TGSI_SEMANTIC_TEXCOORD ||
                        sem == TGSI_SEMANTIC_GENERIC)
                        in_uv[index] = 1;
                }
            }
            break;
        case TGSI_TOKEN_TYPE_IMMEDIATE:
            if (nimm + 4 > 8) {
                tgsi_parse_free(&parse);
                return -1;
            }
            for (i = 0; i < 4; i++)
                imm[nimm++] = parse.FullToken.FullImmediate.u[i].Float;
            break;
        case TGSI_TOKEN_TYPE_INSTRUCTION:
            insn = &parse.FullToken.FullInstruction;
            op = insn->Instruction.Opcode;
            if (op == TGSI_OPCODE_END) {
                saw_end = 1;
                break;
            }
            if (op == TGSI_OPCODE_IF || op == TGSI_OPCODE_ELSE ||
                op == TGSI_OPCODE_ENDIF || op == TGSI_OPCODE_KILL ||
                op == TGSI_OPCODE_KILL_IF || op == TGSI_OPCODE_TEX) {
                struct fs_insn *in;

                if (n >= MAX_INSN) {
                    tgsi_parse_free(&parse);
                    return -1;
                }
                in = &ir[n++];
                memset(in, 0, sizeof(*in));
                if (op == TGSI_OPCODE_IF)
                    in->op = OP_IF;
                else if (op == TGSI_OPCODE_ELSE)
                    in->op = OP_ELSE;
                else if (op == TGSI_OPCODE_ENDIF)
                    in->op = OP_ENDIF;
                else if (op == TGSI_OPCODE_KILL)
                    in->op = OP_KILL;
                else
                    in->op = OP_KILL_IF;
                if (op == TGSI_OPCODE_TEX) {
                    int file, index;
                    const struct tgsi_full_src_register *src;

                    if (insn->Instruction.Saturate ||
                        insn->Instruction.NumDstRegs != 1 ||
                        insn->Instruction.NumSrcRegs < 1 ||
                        insn->Texture.Texture != TGSI_TEXTURE_2D ||
                        insn->Dst[0].Register.Indirect ||
                        insn->Src[0].Register.Indirect ||
                        insn->Src[0].Register.Absolute) {
                        tgsi_parse_free(&parse);
                        return -1;
                    }
                    in->op = OP_TEX;
                    in->comps = 2;
                    in->nsrc = 1;
                    src = &insn->Src[0];
                    if (src_file(src->Register.File, src->Register.Index,
                                 &file, &index)) {
                        tgsi_parse_free(&parse);
                        return -1;
                    }
                    in->src[0].file = file;
                    in->src[0].index = index;
                    in->src[0].neg = src->Register.Negate;
                    for (i = 0; i < 4; i++)
                        in->comp[0][i] = (int)swizzle(&src->Register, i);
                    if (insn->Dst[0].Register.File == TGSI_FILE_OUTPUT) {
                        in->dst_file = FILE_OUT;
                        in->dst_index = 0;
                    } else if (insn->Dst[0].Register.File == TGSI_FILE_TEMPORARY) {
                        in->dst_file = FILE_TEMP;
                        in->dst_index = insn->Dst[0].Register.Index;
                        if (in->dst_index < 0 || in->dst_index >= MAX_USER_TEMP) {
                            tgsi_parse_free(&parse);
                            return -1;
                        }
                    } else {
                        tgsi_parse_free(&parse);
                        return -1;
                    }
                    break;
                }
                if (op == TGSI_OPCODE_IF || op == TGSI_OPCODE_KILL_IF) {
                    int file, index;
                    const struct tgsi_full_src_register *src;

                    if (insn->Instruction.NumSrcRegs != 1) {
                        tgsi_parse_free(&parse);
                        return -1;
                    }
                    src = &insn->Src[0];
                    if (src->Register.Indirect || src->Register.Absolute ||
                        src_file(src->Register.File, src->Register.Index,
                                 &file, &index)) {
                        tgsi_parse_free(&parse);
                        return -1;
                    }
                    in->nsrc = 1;
                    in->comps = 4;
                    in->src[0].file = file;
                    in->src[0].index = index;
                    in->src[0].neg = src->Register.Negate;
                    for (i = 0; i < 4; i++)
                        in->comp[0][i] = (int)swizzle(&src->Register, i);
                }
                break;
            }
            kind = 0;
            comps = 1;
            switch (op) {
            case TGSI_OPCODE_MOV:
                kind = OP_MOV;
                break;
            case TGSI_OPCODE_ADD:
                kind = OP_ADD;
                break;
            case TGSI_OPCODE_MUL:
                kind = OP_MUL;
                break;
            case TGSI_OPCODE_MAX:
                kind = OP_MAX;
                break;
            case TGSI_OPCODE_MIN:
                kind = OP_MIN;
                break;
            case TGSI_OPCODE_SLT:
                kind = OP_SLT;
                break;
            case TGSI_OPCODE_SGE:
                kind = OP_SGE;
                break;
            case TGSI_OPCODE_SEQ:
                kind = OP_SEQ;
                break;
            case TGSI_OPCODE_DIV:
                kind = OP_DIV;
                break;
            case TGSI_OPCODE_SQRT:
                kind = OP_SQRT;
                break;
            case TGSI_OPCODE_RSQ:
                kind = OP_RSQ;
                break;
            case TGSI_OPCODE_RCP:
                kind = OP_RCP;
                break;
            case TGSI_OPCODE_CMP:
                kind = OP_CMP;
                break;
            case TGSI_OPCODE_DP2:
                kind = OP_DP2;
                comps = 2;
                break;
            case TGSI_OPCODE_DP3:
                kind = OP_DP3;
                comps = 3;
                break;
            case TGSI_OPCODE_DP4:
                kind = OP_DP4;
                comps = 4;
                break;
            case TGSI_OPCODE_MAD:
                kind = OP_MAD;
                break;
            case TGSI_OPCODE_FMA:
                kind = OP_FMA;
                break;
            default:
                tgsi_parse_free(&parse);
                fprintf(stderr, "opengpu: fragment opcode %u is not lowered\n",
                        op);
                return -1;
            }
            if (((kind == OP_MAD || kind == OP_FMA) && insn->Instruction.NumSrcRegs != 3) ||
                (dot_components(kind) && insn->Instruction.NumSrcRegs != 2) ||
                insn->Instruction.NumDstRegs != 1 ||
                insn->Dst[0].Register.Indirect) {
                tgsi_parse_free(&parse);
                return -1;
            }
            mask = insn->Dst[0].Register.WriteMask;
            if (dot_components(kind) && (!mask ||
                insn->Dst[0].Register.Dimension ||
                (insn->Dst[0].Register.File == TGSI_FILE_OUTPUT &&
                 insn->Dst[0].Register.Index != 0))) {
                tgsi_parse_free(&parse);
                return -1;
            }
            if (!mask)
                mask = TGSI_WRITEMASK_XYZW;
            ir_first = n;
            for (c = 0; c < 4; c++) {
                struct fs_insn *in;

                if (comps == 1 && !(mask & (1u << c)))
                    continue;
                if (comps != 1 && c > 0)
                    break;
                if (n >= MAX_INSN) {
                    tgsi_parse_free(&parse);
                    return -1;
                }
                in = &ir[n++];
                memset(in, 0, sizeof(*in));
                in->op = kind;
                in->sat = insn->Instruction.Saturate;
                in->comps = comps;
                in->nsrc = insn->Instruction.NumSrcRegs;
                if (insn->Dst[0].Register.File == TGSI_FILE_OUTPUT) {
                    in->dst_file = FILE_OUT;
                    in->dst_index = 0;
                } else if (insn->Dst[0].Register.File == TGSI_FILE_TEMPORARY) {
                    in->dst_file = FILE_TEMP;
                    in->dst_index = insn->Dst[0].Register.Index;
                } else {
                    tgsi_parse_free(&parse);
                    return -1;
                }
                in->dst_chan = comps == 1 ? (int)c : 0;
                for (s = 0; s < insn->Instruction.NumSrcRegs; s++) {
                    int file, index;
                    const struct tgsi_full_src_register *src = &insn->Src[s];

                    if (src->Register.Indirect || src->Register.Absolute ||
                        (dot_components(kind) && src->Register.Dimension &&
                         (src->Register.File != TGSI_FILE_CONSTANT ||
                          src->Dimension.Indirect || src->Dimension.Index != 0)) ||
                        src_file(src->Register.File, src->Register.Index,
                                 &file, &index)) {
                        tgsi_parse_free(&parse);
                        return -1;
                    }
                    in->src[s].file = file;
                    in->src[s].index = index;
                    in->src[s].neg = src->Register.Negate;
                    for (i = 0; i < 4; i++)
                        in->comp[s][i] = (int)swizzle(&src->Register, i);
                    in->src[s].chan = in->comp[s][in->dst_chan];
                    if (comps == 1)
                        in->comp[s][0] = in->src[s].chan;
                }
                if (in->dst_file == FILE_TEMP &&
                    (in->dst_index < 0 || in->dst_index >= MAX_USER_TEMP)) {
                    tgsi_parse_free(&parse);
                    return -1;
                }
            }
            if ((kind == OP_MAD || kind == OP_FMA) &&
                snapshot_fused_hazards(ir, ir_first, &n)) {
                tgsi_parse_free(&parse);
                return -1;
            }
            if (dot_components(kind)) {
                saw_dot = 1;
                if (replicate_dot_result(ir, ir_first, &n, mask)) {
                    tgsi_parse_free(&parse);
                    return -1;
                }
            }
            break;
        case TGSI_TOKEN_TYPE_PROPERTY:
            if (parse.FullToken.FullProperty.Property.PropertyName ==
                    TGSI_PROPERTY_LEGACY_MATH_RULES) {
                if (parse.FullToken.FullProperty.Property.NrTokens != 2) {
                    tgsi_parse_free(&parse);
                    return -1;
                }
                legacy_math |= parse.FullToken.FullProperty.u[0].Data != 0;
            }
            break;
        default:
            break;
        }
    }
    tgsi_parse_free(&parse);
    if (!saw_end || nconst < 0 || nconst > 8 || (saw_dot && legacy_math)) {
        fprintf(stderr, "opengpu: fragment program is not a straight-line ALU\n");
        return -1;
    }
    memset(&bld, 0, sizeof(bld));
    bld.nconst = nconst;
    memcpy(bld.in_uv, in_uv, sizeof(in_uv));
    memcpy(bld.sem_in, sem_in, sizeof(sem_in));
    memcpy(bld.sem_in_index, sem_in_index, sizeof(sem_in_index));
    bld.fragcoord_is_input0 = sem_in[0] == TGSI_SEMANTIC_POSITION;
    bld.sample_in = -1;
    if (compile_ir(&bld, ir, n, imm, nimm) || bld.fail) {
        fprintf(stderr, "opengpu: fragment ALU did not fit (%d instructions)\n",
                bld.n);
        return -1;
    }
    fprintf(stderr, "opengpu: fragment ALU %d instructions\n", bld.n);
    fs_dump(&bld);
    if (getenv("OPENGPU_FS_EMIT_DUMP")) {
        fprintf(stderr, "nconst %d nimm %d\n", nconst, nimm);
        for (i = 0; i < nimm; i++)
            fprintf(stderr, "imm %d %.9g\n", i, imm[i]);
    }
    memcpy(out->code, bld.code, (unsigned)bld.n * 4u);
    out->words = (unsigned)bld.n;
    out->nconst = (unsigned)nconst;
    /* Immediates were materialized. The uniform block is constants only. */
    out->nimm = 0;
    out->samples = bld.samples ? 1u : 0u;
    out->patch_n = (unsigned)bld.patch_n;
    for (i = 0; i < bld.patch_n; i++) {
        out->patch_at[i] = (unsigned)bld.patch_at[i];
        out->patch_src[i] = (unsigned)bld.patch_src[i];
    }
    out->sample_sem = bld.samples && bld.sample_in >= 0 ? bld.sem_in[bld.sample_in] : -1;
    out->sample_sem_index =
        bld.samples && bld.sample_in >= 0 ? bld.sem_in_index[bld.sample_in] : 0;
    memcpy(out->imm, imm, sizeof(float) * (unsigned)nimm);
    return 0;
}
#endif

#ifdef OPENGPU_FS_EMIT_TEST
#include "opengpu_shader_validator.h"

#include <stdlib.h>

static int letter(int ch)
{
    if (ch == 'x')
        return 0;
    if (ch == 'y')
        return 1;
    if (ch == 'z')
        return 2;
    if (ch == 'w')
        return 3;
    return -1;
}

static int parse_reg(const char *tok, int *file, int *index, int swiz[4], int *neg)
{
    int i;

    *neg = 0;
    while (*tok == ' ')
        tok++;
    if (*tok == '-') {
        *neg = 1;
        tok++;
    }
    if (!strncmp(tok, "TEMP[", 5)) {
        *file = FILE_TEMP;
        tok += 5;
    } else if (!strncmp(tok, "CONST[0][", 9)) {
        *file = FILE_CONST;
        tok += 9;
    } else if (!strncmp(tok, "IMM[", 4)) {
        *file = FILE_IMM;
        tok += 4;
    } else if (!strncmp(tok, "IN[", 3)) {
        *file = FILE_IN;
        tok += 3;
    } else if (!strncmp(tok, "OUT[", 4)) {
        *file = FILE_OUT;
        tok += 4;
        *index = 0;
    } else {
        return -1;
    }
    if (*file != FILE_OUT)
        *index = (int)strtol(tok, (char **)&tok, 10);
    if (*file == FILE_TEMP && (*index < 0 || *index >= MAX_USER_TEMP))
        return -1;
    if (*tok == ']')
        tok++;
    for (i = 0; i < 4; i++)
        swiz[i] = i;
    if (*tok == '.') {
        int n = 0;

        tok++;
        while (letter(*tok) >= 0 && n < 4)
            swiz[n++] = letter(*tok++);
        for (i = n; i < 4; i++)
            swiz[i] = swiz[n - 1];
    }
    return 0;
}

int main(int argc, char **argv)
{
    FILE *fp;
    char line[256];
    struct fs_insn ir[MAX_INSN];
    struct bld bld;
    float imm[8] = { 0.f, 2.f, -1.f, 0.5f, 1.f, 0.f, 0.f, 0.f };
    int n = 0, nimm = 8, nconst = 5, saw_imm = 0;
    int i;

    fp = fopen(argc > 1 ? argv[1] : "/tmp/gtk_coverage_run.txt", "r");
    if (!fp)
        return 1;
    while (fgets(line, sizeof(line), fp)) {
        char *colon, *rest, *save;
        char *parts[6];
        int npart = 0;
        int kind = 0, comps = 1, mask_n, chs[4], ci, ir_first;
        int file, index, swiz[4], neg;

        if (strstr(line, "DCL CONST[0][")) {
            int lo, hi;

            if (sscanf(strstr(line, "DCL CONST[0]["),
                       "DCL CONST[0][%d..%d]", &lo, &hi) == 2)
                nconst = hi + 1;
        }
        if (strstr(line, "FLT32") && strchr(line, '{')) {
            float v[4];

            if (sscanf(strchr(line, '{'), "{ %f , %f , %f , %f",
                       &v[0], &v[1], &v[2], &v[3]) == 4 &&
                saw_imm + 4 <= 8) {
                for (i = 0; i < 4; i++)
                    imm[saw_imm++] = v[i];
                nimm = saw_imm;
            }
        }
        colon = strchr(line, ':');
        if (!colon || colon == line || (colon[-1] < '0' || colon[-1] > '9'))
            continue;
        rest = colon + 1;
        while (*rest == ' ')
            rest++;
        if (!strncmp(rest, "FMA", 3))
            kind = OP_FMA;
        else if (!strncmp(rest, "MAD", 3))
            kind = OP_MAD;
        else if (!strncmp(rest, "ADD_SAT", 7))
            kind = OP_ADD;
        else if (!strncmp(rest, "SLT", 3))
            kind = OP_SLT;
        else if (!strncmp(rest, "SGE", 3))
            kind = OP_SGE;
        else if (!strncmp(rest, "SEQ", 3))
            kind = OP_SEQ;
        else if (!strncmp(rest, "MIN", 3))
            kind = OP_MIN;
        else if (!strncmp(rest, "MAX", 3))
            kind = OP_MAX;
        else if (!strncmp(rest, "MUL", 3))
            kind = OP_MUL;
        else if (!strncmp(rest, "ADD", 3))
            kind = OP_ADD;
        else if (!strncmp(rest, "MOV", 3))
            kind = OP_MOV;
        else if (!strncmp(rest, "DIV", 3))
            kind = OP_DIV;
        else if (!strncmp(rest, "DP2", 3)) {
            kind = OP_DP2;
            comps = 2;
        } else if (!strncmp(rest, "DP3", 3)) {
            kind = OP_DP3;
            comps = 3;
        } else if (!strncmp(rest, "DP4", 3)) {
            kind = OP_DP4;
            comps = 4;
        }         else if (!strncmp(rest, "SQRT", 4))
            kind = OP_SQRT;
        else if (!strncmp(rest, "RSQ", 3))
            kind = OP_RSQ;
        else if (!strncmp(rest, "RCP", 3))
            kind = OP_RCP;
        else if (!strncmp(rest, "CMP", 3))
            kind = OP_CMP;
        else
            continue;
        rest = strchr(rest, ' ');
        if (!rest)
            continue;
        parts[npart++] = strtok_r(rest, ",", &save);
        while (npart < 6 && (parts[npart] = strtok_r(NULL, ",", &save)))
            npart++;
        if (((kind == OP_MAD || kind == OP_FMA) && npart != 4) ||
            (dot_components(kind) && npart != 3))
            return 1;
        if (parse_reg(parts[0], &file, &index, swiz, &neg)) {
            fprintf(stderr, "dest %s\n", parts[0]);
            return 1;
        }
        mask_n = 0;
        if (strchr(parts[0], '.')) {
            const char *dot = strchr(parts[0], '.');
            int seen[4] = { 0 };

            while (letter(dot[1]) >= 0) {
                int l = letter(dot[1]);

                if (!seen[l]) {
                    seen[l] = 1;
                    chs[mask_n++] = l;
                }
                dot++;
            }
        } else {
            for (ci = 0; ci < 4; ci++)
                chs[mask_n++] = ci;
        }
        ir_first = n;
        for (ci = 0; ci < (comps == 1 ? mask_n : 1); ci++) {
            struct fs_insn *in;
            int s;

            if (n >= MAX_INSN)
                return 1;
            in = &ir[n++];
            memset(in, 0, sizeof(*in));
            in->op = kind;
            in->sat = strstr(line, "ADD_SAT") != NULL;
            in->comps = comps;
            in->nsrc = npart - 1;
            in->dst_file = file;
            in->dst_index = index;
            in->dst_chan = chs[ci];
            for (s = 0; s < in->nsrc; s++) {
                int sf, si, ss[4], sn;

                if (parse_reg(parts[s + 1], &sf, &si, ss, &sn)) {
                    fprintf(stderr, "src %s\n", parts[s + 1]);
                    return 1;
                }
                in->src[s].file = sf;
                in->src[s].index = si;
                in->src[s].neg = sn;
                for (i = 0; i < 4; i++)
                    in->comp[s][i] = ss[i];
                if (comps == 1)
                    in->comp[s][0] = ss[chs[ci]];
            }
        }
        if ((kind == OP_MAD || kind == OP_FMA) &&
            snapshot_fused_hazards(ir, ir_first, &n))
            return 1;
        if (dot_components(kind)) {
            unsigned mask = 0;

            for (ci = 0; ci < mask_n; ci++)
                mask |= 1u << chs[ci];
            if (replicate_dot_result(ir, ir_first, &n, mask))
                return 1;
        }
    }
    fclose(fp);
    fprintf(stderr, "ir %d const %d imm %d\n", n, nconst, nimm);
    memset(&bld, 0, sizeof(bld));
    bld.nconst = nconst;
    bld.fragcoord_is_input0 = 1; /* The text debugger assumes IN[0] is position. */
    if (compile_ir(&bld, ir, n, imm, nimm) || bld.fail) {
        fprintf(stderr, "compile failed at %d ip %d op %d\n", bld.n, bld.ip,
                bld.ip >= 0 && bld.ip < n ? ir[bld.ip].op : -1);
        {
            uint32_t tmp[258];
            int ncheck = bld.n < 256 ? bld.n : 256;

            for (i = 1; i <= ncheck; i++) {
                memcpy(tmp, bld.code, (unsigned)i * 4u);
                tmp[i] = 0x30500073u;
                if (!opengpu_shader_validate_words(
                        (const opengpu_shader_u32 *)tmp,
                        (opengpu_shader_u32)i + 1, 512, 8, true)) {
                    fprintf(stderr, "validator fails at %d word %08x\n", i - 1,
                            bld.code[i - 1]);
                    return 1;
                }
            }
        }
        return 1;
    }
    fprintf(stderr, "words %d\n", bld.n);
    if (!opengpu_shader_validate_words((const opengpu_shader_u32 *)bld.code,
                                       (opengpu_shader_u32)bld.n, 512, 8,
                                       true)) {
        uint32_t tmp[260];
        int lo = 1, hi = bld.n;

        fprintf(stderr, "validator rejected the program\n");
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            int end = mid;
            int j;

            for (j = 0; j < end; j++) {
                if ((bld.code[j] & 0x7f) == 0x63) {
                    int imm = ((bld.code[j] >> 31) & 1) << 12 |
                              ((bld.code[j] >> 7) & 1) << 11 |
                              ((bld.code[j] >> 25) & 0x3f) << 5 |
                              ((bld.code[j] >> 8) & 0xf) << 1;
                    int target = j + imm / 4;

                    if (target + 1 > end)
                        end = target + 1;
                }
            }
            if (end > bld.n)
                end = bld.n;
            memcpy(tmp, bld.code, (unsigned)end * 4u);
            tmp[end] = 0x30500073u;
            if (opengpu_shader_validate_words((const opengpu_shader_u32 *)tmp,
                                             (opengpu_shader_u32)end + 1, 512,
                                             8, true))
                lo = mid + 1;
            else
                hi = mid;
        }
        fprintf(stderr, "first bad %d word %08x\n", lo - 1, bld.code[lo - 1]);
        return 1;
    }
    fprintf(stderr, "validator ok\n");
    return 0;
}
#endif
