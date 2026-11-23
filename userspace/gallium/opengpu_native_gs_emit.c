/* SPDX-License-Identifier: MIT */
#include "opengpu_native_gs_emit.h"
#include "opengpu_shader_validator.h"
#include "pipe/p_defines.h"
#include "pipe/p_shader_tokens.h"

#include <errno.h>
#include <string.h>

#define TGSI_WORD_LIMIT 4096u
#define TGSI_REGISTERS 64u
#define COMPONENTS OPENGPU_NATIVE_GEOMETRY_MAX_COMPONENTS
#define VERTEX_COMPONENTS OPENGPU_NATIVE_GS_VERTEX_COMPONENTS

_Static_assert(sizeof(struct tgsi_token) == sizeof(uint32_t), "TGSI packet word");

enum geometry_op {
    READ_INPUT = 32, WRITE_OUTPUT, WRITE_LAYER, EMIT_VERTEX, END_PRIMITIVE,
    READ_INFO, WRITE_PRIMITIVE_ID
};

enum scalar_register {
    VERTEX = 2, COMPONENT, VALUE, IMMEDIATE, INPUT_PRIMITIVE_ID, SCALAR_REGISTERS
};

enum geometry_builtin {
    LAYER = VERTEX_COMPONENTS,
    PRIMITIVE_ID,
    LAYER_MASK = 1u,
    PRIMITIVE_ID_MASK = 2u,
    INPUT_PRIMITIVE_ID_SELECTOR = 1u
};

enum gs_property {
    INPUT_PRIMITIVE = 1u, OUTPUT_PRIMITIVE = 2u,
    MAX_VERTICES = 4u, INVOCATIONS = 8u
};

struct geometry_register {
    unsigned base, mask;
};

struct builder {
    struct opengpu_native_gs_program program;
    struct geometry_register inputs[TGSI_REGISTERS], outputs[TGSI_REGISTERS];
    uint32_t immediates[TGSI_REGISTERS][4];
    uint8_t input_indices[VERTEX_COMPONENTS];
    bool input_declared[PRIMITIVE_ID + 1];
    unsigned num_immediates, properties, emitted_vertices, written_builtins;
    uint64_t written_components;
    uint32_t constants[SCALAR_REGISTERS];
    unsigned known_constants;
    bool started, ended, code_overflow, primitive_id_loaded;
};

static void emit_word(struct builder *b, uint32_t word)
{
    if (b->program.words == OPENGPU_NATIVE_GEOMETRY_MAX_CODE_WORDS)
        b->code_overflow = true;
    else
        b->program.code[b->program.words++] = word;
}

static void geometry(struct builder *b, enum geometry_op op, unsigned rd,
                     unsigned rs1, unsigned rs2)
{
    emit_word(b, (uint32_t)op << 25 | rs2 << 20 | rs1 << 15 | rd << 7 | 0x0bu);
}

static unsigned constant(struct builder *b, enum scalar_register reg, uint32_t value)
{
    if (!value)
        return 0;
    if ((b->known_constants & (1u << reg)) && b->constants[reg] == value)
        return reg;
    uint32_t hi = (value + 0x800u) & 0xfffff000u;
    uint32_t lo = value & 0xfffu;
    if (hi)
        emit_word(b, hi | reg << 7 | 0x37u);
    if (lo)
        emit_word(b, lo << 20 | (hi ? reg : 0u) << 15 | reg << 7 | 0x13u);
    b->known_constants |= 1u << reg;
    b->constants[reg] = value;
    return reg;
}

static bool property(struct builder *b, const struct tgsi_token *tokens, unsigned count)
{
    struct tgsi_property p;
    unsigned value, seen;
    memcpy(&p, tokens, sizeof(p));
    if (count != 2 || p.Padding)
        return false;
    memcpy(&value, tokens + 1, sizeof(value));
    switch (p.PropertyName) {
    case TGSI_PROPERTY_GS_INPUT_PRIM:
        seen = INPUT_PRIMITIVE;
        switch (value) {
        case PIPE_PRIM_POINTS: b->program.input_vertices = 1; break;
        case PIPE_PRIM_LINES: b->program.input_vertices = 2; break;
        case PIPE_PRIM_TRIANGLES: b->program.input_vertices = 3; break;
        case PIPE_PRIM_LINES_ADJACENCY: b->program.input_vertices = 4; break;
        case PIPE_PRIM_TRIANGLES_ADJACENCY: b->program.input_vertices = 6; break;
        default: return false;
        }
        break;
    case TGSI_PROPERTY_GS_OUTPUT_PRIM:
        seen = OUTPUT_PRIMITIVE;
        switch (value) {
        case PIPE_PRIM_POINTS: b->program.output_topology = 0; break;
        case PIPE_PRIM_LINE_STRIP: b->program.output_topology = 1; break;
        case PIPE_PRIM_TRIANGLE_STRIP: b->program.output_topology = 2; break;
        default: return false;
        }
        break;
    case TGSI_PROPERTY_GS_MAX_OUTPUT_VERTICES:
        seen = MAX_VERTICES;
        if (!value || value > OPENGPU_NATIVE_GEOMETRY_MAX_OUTPUT_VERTICES)
            return false;
        b->program.max_vertices = value;
        break;
    case TGSI_PROPERTY_GS_INVOCATIONS:
        seen = INVOCATIONS;
        if (!value || value > OPENGPU_NATIVE_GEOMETRY_MAX_INVOCATIONS)
            return false;
        b->program.invocations = value;
        break;
    default:
        return false;
    }
    if (b->properties & seen)
        return false;
    b->properties |= seen;
    return true;
}

static bool declaration(struct builder *b, const struct tgsi_token *tokens, unsigned count)
{
    struct tgsi_declaration d;
    struct tgsi_declaration_range range;
    struct tgsi_declaration_semantic semantic;
    memcpy(&d, tokens, sizeof(d));
    if (count != 3 || !d.Semantic || d.Dimension || d.Interpolate || d.Local ||
        d.Array || d.Atomic || d.MemType || d.Padding || !d.UsageMask ||
        (d.File != TGSI_FILE_INPUT && d.File != TGSI_FILE_OUTPUT))
        return false;
    memcpy(&range, tokens + 1, sizeof(range));
    memcpy(&semantic, tokens + 2, sizeof(semantic));
    if (range.First > range.Last || range.Last >= TGSI_REGISTERS ||
        semantic.StreamX || semantic.StreamY || semantic.StreamZ || semantic.StreamW)
        return false;

    bool input = d.File == TGSI_FILE_INPUT;
    unsigned base, mask = d.UsageMask;
    unsigned slots = range.Last - range.First + 1;
    if (semantic.Name == TGSI_SEMANTIC_GENERIC) {
        unsigned end = semantic.Index + slots;
        if (end > OPENGPU_NATIVE_GEOMETRY_MAX_VARYING_SLOTS)
            return false;
        uint32_t *varyings = input ? &b->program.vertex_varying_slots :
            &b->program.fragment_varying_slots;
        if (end > *varyings)
            *varyings = end;
        base = 4u + 4u * semantic.Index;
    } else {
        if (semantic.Index || slots != 1)
            return false;
        switch (semantic.Name) {
        case TGSI_SEMANTIC_POSITION: base = 0; break;
        case TGSI_SEMANTIC_PRIMID: base = PRIMITIVE_ID; break;
        case TGSI_SEMANTIC_LAYER:
            if (input)
                return false;
            base = LAYER;
            break;
        default: return false;
        }
        if (base >= VERTEX_COMPONENTS) {
            /* Mesa can declare a scalar geometry input with an XYZW mask. */
            if (!(mask & TGSI_WRITEMASK_X) || (!input && mask != TGSI_WRITEMASK_X))
                return false;
            mask = TGSI_WRITEMASK_X;
        }
    }

    struct geometry_register *regs = input ? b->inputs : b->outputs;
    for (unsigned i = range.First; i <= range.Last; ++i, base += 4) {
        if (regs[i].mask)
            return false;
        regs[i] = (struct geometry_register){base, mask};
        for (unsigned component = 0; component < 4; ++component) {
            if (!(mask & (1u << component)))
                continue;
            unsigned index = base + component;
            if (input) {
                if (b->input_declared[index])
                    return false;
                b->input_declared[index] = true;
            } else if (index >= VERTEX_COMPONENTS) {
                unsigned builtin = index == LAYER ? LAYER_MASK : PRIMITIVE_ID_MASK;
                if (b->program.builtin_output_mask & builtin)
                    return false;
                b->program.builtin_output_mask |= builtin;
            } else {
                if (b->program.output_sources[index] != UINT8_MAX ||
                    b->program.output_components == COMPONENTS)
                    return false;
                b->program.output_sources[index] = b->program.output_components++;
            }
        }
    }
    return true;
}

static bool immediate(struct builder *b, const struct tgsi_token *tokens, unsigned count)
{
    struct tgsi_immediate imm;
    memcpy(&imm, tokens, sizeof(imm));
    if (count != 5 || imm.NrTokens != count || imm.Padding ||
        (imm.DataType != TGSI_IMM_FLOAT32 && imm.DataType != TGSI_IMM_UINT32 &&
         imm.DataType != TGSI_IMM_INT32) || b->num_immediates == TGSI_REGISTERS)
        return false;
    memcpy(b->immediates[b->num_immediates++], tokens + 1, 4 * sizeof(uint32_t));
    return true;
}

static int load_source(struct builder *b, const struct tgsi_src_register *s,
                       unsigned channel, unsigned vertex)
{
    if (s->File == TGSI_FILE_IMMEDIATE) {
        if (s->Dimension || (unsigned)s->Index >= b->num_immediates)
            return -1;
        return constant(b, IMMEDIATE, b->immediates[s->Index][channel]);
    }
    if (s->File != TGSI_FILE_INPUT || (unsigned)s->Index >= TGSI_REGISTERS)
        return -1;
    const struct geometry_register *reg = b->inputs + s->Index;
    if (!(reg->mask & (1u << channel)))
        return -1;
    if (reg->base == PRIMITIVE_ID) {
        if (s->Dimension)
            return -1;
        if (!b->primitive_id_loaded) {
            unsigned selector = constant(b, IMMEDIATE, INPUT_PRIMITIVE_ID_SELECTOR);
            geometry(b, READ_INFO, INPUT_PRIMITIVE_ID, selector, 0);
            b->primitive_id_loaded = true;
        }
        return INPUT_PRIMITIVE_ID;
    }
    if (!s->Dimension || vertex >= b->program.input_vertices)
        return -1;
    unsigned physical = reg->base + channel;
    if (b->input_indices[physical] == UINT8_MAX) {
        unsigned next = b->program.input_components;
        if (next == COMPONENTS)
            return -1;
        b->program.input_sources[next] = physical;
        b->input_indices[physical] = next;
        ++b->program.input_components;
    }
    unsigned v = constant(b, VERTEX, vertex);
    unsigned i = constant(b, COMPONENT, b->input_indices[physical]);
    geometry(b, READ_INPUT, VALUE, v, i);
    return VALUE;
}

static bool move(struct builder *b, const struct tgsi_src_register *src,
                 const struct tgsi_dst_register *dst, unsigned vertex)
{
    if (dst->File != TGSI_FILE_OUTPUT || (unsigned)dst->Index >= TGSI_REGISTERS ||
        dst->Indirect || dst->Dimension || dst->Padding || !dst->WriteMask)
        return false;
    const struct geometry_register *reg = b->outputs + dst->Index;
    if (dst->WriteMask & ~reg->mask)
        return false;
    const unsigned swizzle[] = {src->SwizzleX, src->SwizzleY, src->SwizzleZ, src->SwizzleW};
    for (unsigned c = 0; c < 4; ++c) {
        if (!(dst->WriteMask & (1u << c)))
            continue;
        int value = load_source(b, src, swizzle[c], vertex);
        if (value < 0)
            return false;
        unsigned index = reg->base + c;
        if (index == LAYER) {
            geometry(b, WRITE_LAYER, 0, value, 0);
            b->written_builtins |= LAYER_MASK;
        } else if (index == PRIMITIVE_ID) {
            geometry(b, WRITE_PRIMITIVE_ID, 0, value, 0);
            b->written_builtins |= PRIMITIVE_ID_MASK;
        } else {
            unsigned component = b->program.output_sources[index];
            unsigned rs1 = constant(b, COMPONENT, component);
            geometry(b, WRITE_OUTPUT, 0, rs1, value);
            b->written_components |= UINT64_C(1) << component;
        }
    }
    return true;
}

static bool output_complete(const struct builder *b)
{
    /* All declarations occupy consecutive compact components. Position
     * contributes at least four, so the shift also covers the 64-word case. */
    uint64_t full = UINT64_MAX >> (COMPONENTS - b->program.output_components);
    return b->written_components == full &&
        b->written_builtins == b->program.builtin_output_mask;
}

static bool instruction(struct builder *b, const struct tgsi_token *tokens, unsigned count)
{
    struct tgsi_instruction i;
    memcpy(&i, tokens, sizeof(i));
    if (i.Label || i.Texture || i.Memory || i.Saturate || i.Padding)
        return false;
    if (i.Opcode == TGSI_OPCODE_END) {
        if (count != 1 || i.NumDstRegs || i.NumSrcRegs)
            return false;
        emit_word(b, OPENGPU_SHADER_CEASE);
        b->ended = true;
        return true;
    }
    bool mov = i.Opcode == TGSI_OPCODE_MOV;
    if (!mov && i.Opcode != TGSI_OPCODE_EMIT && i.Opcode != TGSI_OPCODE_ENDPRIM)
        return false;
    if (i.NumDstRegs != (unsigned)mov || i.NumSrcRegs != 1 ||
        count < 2u + i.NumDstRegs)
        return false;
    struct tgsi_src_register src;
    memcpy(&src, tokens + 1 + i.NumDstRegs, sizeof(src));
    if (src.Indirect || src.Absolute || src.Negate ||
        count != 2u + i.NumDstRegs + src.Dimension)
        return false;
    if (mov) {
        struct tgsi_dst_register dst;
        unsigned vertex = 0;
        memcpy(&dst, tokens + 1, sizeof(dst));
        if (src.Dimension) {
            struct tgsi_dimension dim;
            memcpy(&dim, tokens + 3, sizeof(dim));
            if (dim.Dimension || dim.Indirect || dim.Padding || dim.Index < 0)
                return false;
            vertex = dim.Index;
        }
        return move(b, &src, &dst, vertex);
    }
    if (src.File != TGSI_FILE_IMMEDIATE || src.Dimension ||
        (unsigned)src.Index >= b->num_immediates ||
        b->immediates[src.Index][src.SwizzleX])
        return false;
    if (i.Opcode == TGSI_OPCODE_EMIT) {
        if (b->emitted_vertices == b->program.max_vertices || !output_complete(b))
            return false;
        geometry(b, EMIT_VERTEX, 0, 0, 0);
        ++b->emitted_vertices;
        b->written_components = 0;
        b->written_builtins = 0;
    } else {
        geometry(b, END_PRIMITIVE, 0, 0, 0);
    }
    return true;
}

static bool begin_body(struct builder *b)
{
    unsigned total = b->program.output_components +
        !!(b->program.builtin_output_mask & LAYER_MASK) +
        !!(b->program.builtin_output_mask & PRIMITIVE_ID_MASK);
    if (b->properties != (INPUT_PRIMITIVE | OUTPUT_PRIMITIVE | MAX_VERTICES | INVOCATIONS) ||
        total > COMPONENTS ||
        total * b->program.max_vertices > OPENGPU_NATIVE_GEOMETRY_MAX_TOTAL_COMPONENTS)
        return false;
    for (unsigned c = 0; c < 4; ++c)
        if (b->program.output_sources[c] == UINT8_MAX)
            return false;
    b->started = true;
    return true;
}

int opengpu_native_gs_emit(const void *tokens, unsigned token_words,
                          struct opengpu_native_gs_program *out)
{
    const struct tgsi_token *t = tokens;
    struct tgsi_header header;
    struct tgsi_processor processor;
    struct builder b = {0};
    if (!t || !out || token_words < 3 || token_words > TGSI_WORD_LIMIT)
        return -EINVAL;
    memcpy(&header, t, sizeof(header));
    memcpy(&processor, t + 1, sizeof(processor));
    if (header.HeaderSize != 2 || header.BodySize != token_words - 2 ||
        processor.Processor != PIPE_SHADER_GEOMETRY || processor.Padding)
        return -EINVAL;
    memset(b.input_indices, UINT8_MAX, sizeof(b.input_indices));
    memset(b.program.output_sources, UINT8_MAX, sizeof(b.program.output_sources));
    for (unsigned pos = 2; pos < token_words;) {
        struct tgsi_token packet;
        memcpy(&packet, t + pos, sizeof(packet));
        unsigned count = packet.NrTokens + (packet.Type == TGSI_TOKEN_TYPE_INSTRUCTION);
        if (b.ended || !count || count > token_words - pos)
            return -EINVAL;
        if (packet.Type == TGSI_TOKEN_TYPE_INSTRUCTION) {
            if ((!b.started && !begin_body(&b)) || !instruction(&b, t + pos, count))
                return -EINVAL;
        } else if (b.started) {
            return -EINVAL;
        } else {
            switch (packet.Type) {
            case TGSI_TOKEN_TYPE_DECLARATION:
                if (!declaration(&b, t + pos, count))
                    return -EINVAL;
                break;
            case TGSI_TOKEN_TYPE_IMMEDIATE:
                if (!immediate(&b, t + pos, count))
                    return -EINVAL;
                break;
            case TGSI_TOKEN_TYPE_PROPERTY:
                if (!property(&b, t + pos, count))
                    return -EINVAL;
                break;
            default: return -EINVAL;
            }
        }
        pos += count;
    }
    if (b.code_overflow)
        return -E2BIG;
    if (!b.ended || !opengpu_geometry_shader_validate_words(b.program.code, b.program.words,
            OPENGPU_NATIVE_GEOMETRY_SCALAR_PROGRAM_VERSION, false, false, true))
        return -EINVAL;
    *out = b.program;
    return 0;
}

static void put_word(struct opengpu_native_geometry_program *p, unsigned index,
                     uint32_t value)
{
    uint8_t *bytes = (uint8_t *)&p->words[index];
    for (unsigned i = 0; i < 4; ++i)
        bytes[i] = value >> (i * 8);
}

int opengpu_native_gs_metadata(const struct opengpu_native_gs_program *p,
    uint32_t context, uint32_t pass, uint32_t generation, uint64_t flat_mask,
    struct opengpu_native_geometry_program *out)
{
    struct opengpu_native_geometry_program metadata = {0};
    if (!p || !out || !p->words || p->input_components > COMPONENTS ||
        p->vertex_varying_slots > OPENGPU_NATIVE_GEOMETRY_MAX_VARYING_SLOTS ||
        p->fragment_varying_slots > OPENGPU_NATIVE_GEOMETRY_MAX_VARYING_SLOTS ||
        (p->fragment_varying_slots < OPENGPU_NATIVE_GEOMETRY_MAX_VARYING_SLOTS &&
         (flat_mask >> (4 * p->fragment_varying_slots))))
        return -EINVAL;

#define GNG(field) OPENGPU_NATIVE_GEOMETRY_PROGRAM_##field##_WORD
    put_word(&metadata, 0, OPENGPU_NATIVE_GEOMETRY_PROGRAM_MAGIC);
    put_word(&metadata, 1, OPENGPU_NATIVE_GEOMETRY_PROGRAM_BYTES);
    put_word(&metadata, 2, OPENGPU_NATIVE_GEOMETRY_PROGRAM_VERSION);
    put_word(&metadata, GNG(IDENTITY_CONTEXT), context);
    put_word(&metadata, GNG(IDENTITY_PASS), pass);
    put_word(&metadata, GNG(IDENTITY_GENERATION), generation);
    put_word(&metadata, GNG(INPUTVERTICES), p->input_vertices);
    put_word(&metadata, GNG(INVOCATIONS), p->invocations);
    put_word(&metadata, GNG(OUTPUTTOPOLOGY), p->output_topology);
    put_word(&metadata, GNG(MAXVERTICES), p->max_vertices);
    put_word(&metadata, GNG(INPUTCOMPONENTS), p->input_components);
    put_word(&metadata, GNG(OUTPUTCOMPONENTS), p->output_components);
    put_word(&metadata, GNG(BUILTINOUTPUTMASK), p->builtin_output_mask);
    put_word(&metadata, GNG(VERTEXVARYINGSLOTS), p->vertex_varying_slots);
    put_word(&metadata, GNG(FRAGMENTVARYINGSLOTS), p->fragment_varying_slots);
    put_word(&metadata, GNG(PROGRAMVERSION), OPENGPU_NATIVE_GEOMETRY_SCALAR_PROGRAM_VERSION);
    put_word(&metadata, GNG(FLATMASK), (uint32_t)flat_mask);
    put_word(&metadata, GNG(FLATMASK) + 1, (uint32_t)(flat_mask >> 32));
    for (unsigned i = 0; i < p->input_components; ++i)
        put_word(&metadata, GNG(INPUTSOURCES0) + i, p->input_sources[i]);
    for (unsigned i = 0; i < VERTEX_COMPONENTS; ++i) {
        if (p->output_sources[i] == UINT8_MAX)
            continue;
        put_word(&metadata, GNG(OUTPUTSOURCES0_VALID) + 2 * i, 1);
        put_word(&metadata, GNG(OUTPUTSOURCES0_SOURCE) + 2 * i, p->output_sources[i]);
    }
#undef GNG
    if (!opengpu_native_geometry_program_encoding_valid(&metadata))
        return -EINVAL;
    *out = metadata;
    return 0;
}
