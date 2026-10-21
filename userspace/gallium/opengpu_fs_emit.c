/* SPDX-License-Identifier: MIT */
/* Lower a TGSI fragment program onto the fragment core. The rasterizer
 * has already written integer pixel x,y. RSQ is vfrsqrt7.v. SQRT of a
 * uniform is fsqrt.s plus a splat: the vector unit joins every physical
 * lane, and a vfsqrt whose tail lanes were never written did not retire.
 * A varying SQRT stays vfsqrt.v. One uniform if/else becomes two forward
 * branches. KILL_IF clears the fragment validity word for lanes whose
 * source is negative. A program that does not fit in 256 instructions
 * fails the draw. */
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

#define FILE_TEMP 1
#define FILE_CONST 2
#define FILE_IMM 3
#define FILE_IN 4
#define FILE_OUT 5

#define MAX_INSN 400
#define MAX_TEMP 16

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
    struct fs_src src[3];
    int comp[3][4];
};

struct bld {
    uint32_t code[256];
    int n;
    int used[32];
    int home[MAX_TEMP][4];
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
        h = b->home[s->index][chan];
        release(b, h);
        if (h >= 0)
            b->home[s->index][chan] = -1;
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
    if (put(b, opv(0x06, 1, vd, b->f_zero, 5, vd)))
        return -1;
    return put(b, opv(0x04, 1, vd, b->f_one, 5, vd));
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
    if (c->is_f && !neg_c && !vv)
        return put(b, opv(f6, 1, va, c->f, 5, vd));
    if (va == scratch_c)
        scratch_c = 2;
    vc = neg_loc(b, c, neg_c, scratch_c);
    if (vc < 0)
        return -1;
    if (va == scratch_a && vc == scratch_a)
        return -1;
    return put(b, opv(f6, 1, va, vc, 1, vd));
}

static int emit_mad(struct bld *b, const struct fs_insn *in, int vd)
{
    struct loc scale, bias;
    int yf;

    if (in->src[0].file != FILE_IN || !b->have_y_int || in->comp[0][0] != 1)
        return -1;
    if (locate(b, &in->src[1], in->comp[1][0], &scale) || !scale.is_f)
        return -1;
    if (locate(b, &in->src[2], in->comp[2][0], &bias) || !bias.is_f)
        return -1;
    if (in->src[1].neg || in->src[2].neg)
        return -1;
    /* gl_FbWposYTransform scale is +1 or -1. Stay in integer. Compare the
     * scale as a scalar (0 > scale) so a negative value never goes through
     * vfmul.vf, which returns 0 on this core and would make y' equal the
     * bias. */
    yf = 2;
    if (put(b, opv(0x17, 1, 0, (unsigned)b->y_int, 0, (unsigned)yf)) ||
        put(b, opv(0x03, 1, (unsigned)yf, 0, 3, 1)) ||
        put(b, opv(0x1d, 1, 30, scale.f, 5, 0)) ||
        put(b, opv(0x17, 0, (unsigned)yf, 1, 0, (unsigned)yf)))
        return -1;
    if (put(b, opv(0x17, 1, 0, bias.f, 5, 1)))
        return -1;
    if (put(b, opv(0x12, 1, 1, 1, 1, 1)))
        return -1;
    if (put(b, opv(0x00, 1, 1, yf, 0, yf)))
        return -1;
    if (put(b, opv(0x12, 1, yf, 3, 1, vd)))
        return -1;
    b->have_y_int = 0;
    b->used[b->y_int] = 0;
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

static int emit_one(struct bld *b, const struct fs_insn *in)
{
    struct loc a, c;
    int vd, k;

    if (in->op == OP_MOV && in->nsrc == 1 && !in->src[0].neg &&
        in->src[0].file == in->dst_file && in->src[0].index == in->dst_index &&
        in->comp[0][0] == in->dst_chan)
        return 0;
    if (in->op == OP_MAD)
        vd = dest_reg(b, in->dst_file, in->dst_index, in->dst_chan);
    else
        vd = dest_reg(b, in->dst_file, in->dst_index, in->dst_chan);
    if (vd < 0)
        return -1;
    if (in->op == OP_MAD)
        return emit_mad(b, in, vd) || (in->sat && sat(b, vd));
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
                /* vfsqrt.v. vs1 selects the unary op and is not a register. */
                if (put(b, opv(0x13, 1, (unsigned)k, 0, 1, (unsigned)tmp)))
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
    if (in->op == OP_DP2 || in->op == OP_DP4) {
        int ncomp = in->op == OP_DP2 ? 2 : 4;
        int acc = 2;
        int i;

        for (i = 0; i < ncomp; i++) {
            int va, vc;
            int prod = i == 0 ? 2 : 1;

            if (locate(b, &in->src[0], in->comp[0][i], &a) ||
                locate(b, &in->src[1], in->comp[1][i], &c))
                return -1;
            va = neg_loc(b, &a, in->src[0].neg, 1);
            if (va < 0)
                return -1;
            vc = neg_loc(b, &c, in->src[1].neg, va == 1 ? 2 : 1);
            if (vc < 0)
                return -1;
            if (i == 0) {
                if (put(b, opv(0x24, 1, va, vc, 1, acc)))
                    return -1;
            } else {
                if (put(b, opv(0x24, 1, va, vc, 1, prod)))
                    return -1;
                if (put(b, opv(0x00, 1, acc, prod, 1, acc)))
                    return -1;
            }
        }
        if (acc != vd && put(b, opv(0x17, 1, 0, acc, 0, vd)))
            return -1;
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
    b->f_zero = -1;
    b->f_one = -1;
    nfloat = 1;
    for (i = 0; i < b->nconst; i++) {
        for (c = 0; c < 4; c++) {
            b->fconst[i][c] = nfloat;
            if (flw(b, nfloat, 288 + (i * 4 + c) * 4))
                return -1;
            nfloat++;
        }
    }
    for (i = 0; i < nimm / 4; i++) {
        for (c = 0; c < 4; c++) {
            float val = imm[i * 4 + c];

            b->fimm[i][c] = nfloat;
            if (flw(b, nfloat, 288 + (b->nconst * 4 + i * 4 + c) * 4))
                return -1;
            if (val == 0.0f)
                b->f_zero = nfloat;
            if (val == 1.0f)
                b->f_one = nfloat;
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
    if (nfloat > 29)
        return -1;
    b->f_byte = 29;
    if (addi(b, 7, 0, 255) || put(b, fpu(0x1a, 0, 7, 7, b->f_byte)))
        return -1;
    if (put(b, 0x00241293u) || put(b, 0x005082b3u) || put(b, 0xc1027057u))
        return -1;
    /* v4 holds integer gl_FragCoord.y until the Y transform consumes it.
     * Reserve it before allocating the float x register. */
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
            if (b->ir[i].src[s].file != FILE_IN)
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
    int in_home[4];
    int out_home[4];
    int have_y_int;
    int spilled;
};

static void save_alloc(const struct bld *b, struct alloc_snap *s)
{
    memcpy(s->used, b->used, sizeof(s->used));
    memcpy(s->home, b->home, sizeof(s->home));
    memcpy(s->in_home, b->in_home, sizeof(s->in_home));
    memcpy(s->out_home, b->out_home, sizeof(s->out_home));
    s->have_y_int = b->have_y_int;
    s->spilled = b->spilled;
}

static void restore_alloc(struct bld *b, const struct alloc_snap *s)
{
    memcpy(b->used, s->used, sizeof(b->used));
    memcpy(b->home, s->home, sizeof(b->home));
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
        if (emit_one(b, &ir[i]))
            return -1;
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

/* Uniform if/else. The condition is a constant or an immediate, so every
 * lane takes the same side. Both sides write the same destinations and
 * the allocator is restarted for the else side, which keeps those
 * registers defined on the join. */
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
    if (ir[if_i].src[0].file != FILE_CONST && ir[if_i].src[0].file != FILE_IMM)
        return -1;
    if (ir[if_i].src[0].neg)
        return -1;
    b->ip = if_i;
    if (locate(b, &ir[if_i].src[0], ir[if_i].comp[0][0], &cond) || !cond.is_f)
        return -1;
    /* feq.s x7, fcond, fzero; bne x7, x0, else. Fall-through is the
     * true side. x7 is free after the prologue. */
    if (put(b, fpu(0x14, (unsigned)b->f_zero, (unsigned)cond.f, 2, 7)))
        return -1;
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
    case OP_DP4: return "DP4";
    case OP_MAD: return "MAD";
    case OP_SQRT: return "SQRT";
    case OP_IF: return "IF";
    case OP_ELSE: return "ELSE";
    case OP_ENDIF: return "ENDIF";
    case OP_KILL: return "KILL";
    case OP_KILL_IF: return "KILL_IF";
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
    int saw_end = 0;

    if (!tokens || !out)
        return -1;
    memset(out, 0, sizeof(*out));
    memset(imm, 0, sizeof(imm));
    if (tgsi_parse_init(&parse, tokens))
        return -1;
    while (!tgsi_parse_end_of_tokens(&parse) && !saw_end) {
        struct tgsi_full_instruction *insn;
        unsigned op, mask, c, s;
        int kind, comps;

        tgsi_parse_token(&parse);
        switch (parse.FullToken.Token.Type) {
        case TGSI_TOKEN_TYPE_DECLARATION:
            if (parse.FullToken.FullDeclaration.Declaration.File ==
                    TGSI_FILE_CONSTANT &&
                parse.FullToken.FullDeclaration.Range.Last + 1 > nconst)
                nconst = parse.FullToken.FullDeclaration.Range.Last + 1;
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
                op == TGSI_OPCODE_KILL_IF) {
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
            case TGSI_OPCODE_DP4:
                kind = OP_DP4;
                comps = 4;
                break;
            case TGSI_OPCODE_MAD:
                kind = OP_MAD;
                break;
            default:
                tgsi_parse_free(&parse);
                fprintf(stderr, "opengpu: fragment opcode %u is not lowered\n",
                        op);
                return -1;
            }
            if (insn->Instruction.NumDstRegs != 1 ||
                insn->Dst[0].Register.Indirect) {
                tgsi_parse_free(&parse);
                return -1;
            }
            mask = insn->Dst[0].Register.WriteMask;
            if (!mask)
                mask = TGSI_WRITEMASK_XYZW;
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
                if (comps != 1) {
                    for (s = 0; s < 4 && mask != (1u << s); s++)
                        ;
                    in->dst_chan = (int)s;
                }
                for (s = 0; s < insn->Instruction.NumSrcRegs; s++) {
                    int file, index;
                    const struct tgsi_full_src_register *src = &insn->Src[s];

                    if (src->Register.Indirect || src->Register.Absolute ||
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
                    (in->dst_index < 0 || in->dst_index >= MAX_TEMP)) {
                    tgsi_parse_free(&parse);
                    return -1;
                }
            }
            break;
        default:
            break;
        }
    }
    tgsi_parse_free(&parse);
    if (!saw_end || nconst <= 0 || nconst > 8) {
        fprintf(stderr, "opengpu: fragment program is not a straight-line ALU\n");
        return -1;
    }
    memset(&bld, 0, sizeof(bld));
    bld.nconst = nconst;
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
    out->nimm = (unsigned)nimm;
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
        int kind = 0, comps = 1, mask_n, chs[4], ci;
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
        if (!strncmp(rest, "MAD", 3))
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
        for (ci = 0; ci < (comps == 1 ? mask_n : 1); ci++) {
            struct fs_insn *in = &ir[n++];
            int s;

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
    }
    fclose(fp);
    fprintf(stderr, "ir %d const %d imm %d\n", n, nconst, nimm);
    memset(&bld, 0, sizeof(bld));
    bld.nconst = nconst;
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
