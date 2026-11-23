/* SPDX-License-Identifier: MIT */
#include "opengpu_native_fs_emit.h"
#include "opengpu_native_gs_emit.h"
#include "opengpu_tess_frontend.h"
#include "tgsi/tgsi_parse.h"
#include <array>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

struct shader_io {
    unsigned inputs = 0;
    unsigned outputs = 0;
    unsigned emits = 0;
    unsigned ends = 0;
};

static shader_io inspect(const tgsi_token *tokens)
{
    assert(tokens);
    tgsi_parse_context parser = {};
    assert(tgsi_parse_init(&parser, tokens) == TGSI_PARSE_OK);
    shader_io result;
    while (!tgsi_parse_end_of_tokens(&parser)) {
        tgsi_parse_token(&parser);
        if (parser.FullToken.Token.Type == TGSI_TOKEN_TYPE_DECLARATION) {
            const auto &decl = parser.FullToken.FullDeclaration;
            if (!decl.Declaration.Semantic || decl.Semantic.Name != TGSI_SEMANTIC_GENERIC)
                continue;
            const unsigned slots = decl.Range.Last - decl.Range.First + 1;
            assert(decl.Semantic.Index + slots <= 16);
            const unsigned mask = ((1u << slots) - 1) << decl.Semantic.Index;
            if (decl.Declaration.File == TGSI_FILE_INPUT)
                result.inputs |= mask;
            if (decl.Declaration.File == TGSI_FILE_OUTPUT)
                result.outputs |= mask;
        } else if (parser.FullToken.Token.Type == TGSI_TOKEN_TYPE_INSTRUCTION) {
            const auto opcode = parser.FullToken.FullInstruction.Instruction.Opcode;
            result.emits += opcode == TGSI_OPCODE_EMIT;
            result.ends += opcode == TGSI_OPCODE_ENDPRIM;
        }
    }
    tgsi_parse_free(&parser);
    return result;
}

static uint32_t input_word(unsigned vertex, unsigned component)
{
    // Distinct raw position and varying words for each of the three vertices.
    return 0x3f000000u + vertex * 0x80000u + component * 0x200u;
}

static void interpret_geometry(const opengpu_native_gs_program &gs, uint32_t expected_layer)
{
    constexpr uint32_t primitive_id = 193;
    assert(gs.input_vertices == 3 && gs.max_vertices == 3 && gs.invocations == 1);
    assert(gs.output_topology == 2 && gs.builtin_output_mask == 3);
    assert(gs.input_components == 52 && gs.output_components == 52);
    assert(gs.vertex_varying_slots == 16 && gs.fragment_varying_slots == 16);
    assert(gs.words && gs.words <= OPENGPU_NATIVE_GEOMETRY_MAX_CODE_WORDS);
    std::array<uint32_t, 32> x = {};
    std::array<uint32_t, 64> staged = {};
    uint32_t layer = 0, shader_primitive_id = 0;
    unsigned emits = 0, ends = 0;
    for (unsigned pc = 0; pc < gs.words; ++pc) {
        const uint32_t word = gs.code[pc];
        const unsigned opcode = word & 0x7f, rd = (word >> 7) & 31;
        const unsigned rs1 = (word >> 15) & 31, rs2 = (word >> 20) & 31;
        const uint32_t a = x[rs1], b = x[rs2];
        if (opcode == 0x37) {
            assert(rd > 1);
            x[rd] = word & 0xfffff000u;
        } else if (opcode == 0x13) {
            assert(rd > 1 && !(word & 0x7000u));
            const uint32_t immediate = (word >> 20) | (word >> 31 ? 0xfffff000u : 0);
            x[rd] = a + immediate;
        } else if (word == 0x30500073u) {
            assert(pc + 1 == gs.words && emits == 3 && ends == 1);
            return;
        } else {
            assert(opcode == 0x0b && !(word & 0x7000u));
            switch (word >> 25) {
            case 32:
                assert(rd > 1 && a < 3 && b < gs.input_components);
                assert(gs.input_sources[b] < OPENGPU_NATIVE_GS_VERTEX_COMPONENTS);
                x[rd] = input_word(a, gs.input_sources[b]);
                break;
            case 33:
                assert(!rd && a < gs.output_components);
                staged[a] = b;
                break;
            case 34:
                assert(!rd && !rs2);
                layer = a;
                break;
            case 35: {
                assert(!rd && !rs1 && !rs2 && emits < 3 && !ends);
                assert(layer == expected_layer && shader_primitive_id == primitive_id);
                uint64_t checked = 0;
                for (unsigned c = 0; c < OPENGPU_NATIVE_GS_VERTEX_COMPONENTS; ++c) {
                    const unsigned source = gs.output_sources[c];
                    if (c >= 4 && c % 4 == 3) {
                        assert(source == UINT8_MAX);
                        continue;
                    }
                    assert(source < gs.output_components && !(checked & (UINT64_C(1) << source)));
                    checked |= UINT64_C(1) << source;
                    assert(staged[source] == input_word(emits, c));
                }
                assert(checked == (UINT64_C(1) << gs.output_components) - 1);
                staged.fill(0);
                layer = shader_primitive_id = 0;
                ++emits;
                break;
            }
            case 36:
                assert(!rd && !rs1 && !rs2 && emits == 3);
                ++ends;
                break;
            case 37:
                assert(rd > 1 && a == 1 && !rs2);
                x[rd] = primitive_id;
                break;
            case 38:
                assert(!rd && !rs2);
                shader_primitive_id = a;
                break;
            default: assert(false);
            }
        }
    }
    assert(false);
}

static void reject_geometry(const tgsi_token *tokens, unsigned words)
{
    opengpu_native_gs_program out;
    std::array<unsigned char, sizeof(out)> previous;
    previous.fill(0xa5);
    std::memcpy(&out, previous.data(), previous.size());
    assert(opengpu_native_gs_emit(tokens, words, &out));
    assert(!std::memcmp(&out, previous.data(), previous.size()));
}

static void check_geometry(const tgsi_token *tokens, unsigned size, const opengpu_native_gs_program &gs)
{
    interpret_geometry(gs, 1);
    reject_geometry(tokens, size - 1);

    tgsi_parse_context parser = {};
    assert(tgsi_parse_init(&parser, tokens) == TGSI_PARSE_OK);
    unsigned layers = 0, dimensions = 0, events = 0;
    while (!tgsi_parse_end_of_tokens(&parser)) {
        const unsigned start = parser.Position;
        tgsi_parse_token(&parser);
        std::vector<tgsi_token> modified;
        if (!layers && parser.FullToken.Token.Type == TGSI_TOKEN_TYPE_IMMEDIATE) {
            modified.assign(tokens, tokens + size);
            const uint32_t layer = 0xfffff800u;
            std::memcpy(modified.data() + start + 1, &layer, sizeof(layer));
            opengpu_native_gs_program candidate = {};
            assert(!opengpu_native_gs_emit(modified.data(), size, &candidate));
            interpret_geometry(candidate, layer);
            ++layers;
        } else if (parser.FullToken.Token.Type == TGSI_TOKEN_TYPE_INSTRUCTION) {
            const auto &insn = parser.FullToken.FullInstruction;
            if (!dimensions && insn.Instruction.Opcode == TGSI_OPCODE_MOV &&
                insn.Src[0].Register.Dimension) {
                modified.assign(tokens, tokens + size);
                auto dimension = insn.Src[0].Dimension;
                dimension.Index = 3;
                std::memcpy(modified.data() + start + 3, &dimension, sizeof(dimension));
                reject_geometry(modified.data(), size);

                // Keep the outer word count valid while removing the
                // dimension from the instruction's declared payload.
                auto header = insn.Instruction;
                --header.NrTokens;
                std::memcpy(modified.data() + start, &header, sizeof(header));
                reject_geometry(modified.data(), size);
                ++dimensions;
            } else if (!events && insn.Instruction.Opcode == TGSI_OPCODE_EMIT) {
                modified.assign(tokens, tokens + size);
                auto stream = insn.Src[0].Register;
                stream.SwizzleX = TGSI_SWIZZLE_X;
                std::memcpy(modified.data() + start + 1, &stream, sizeof(stream));
                reject_geometry(modified.data(), size);
                ++events;
            }
        }
    }
    tgsi_parse_free(&parser);
    assert(layers == 1 && dimensions == 1 && events == 1);
}

static void write_geometry(const std::string &prefix, const opengpu_native_gs_program &gs)
{
    opengpu_native_geometry_program metadata;
    assert(!opengpu_native_gs_metadata(&gs, 7, 11, 13, 0, &metadata));
    FILE *file = std::fopen((prefix + ".native-gs-gng.bin").c_str(), "wb");
    assert(file);
    assert(std::fwrite(&metadata, sizeof(metadata), 1, file) == 1);
    assert(!std::fclose(file));

    file = std::fopen((prefix + ".native-gs-code.bin").c_str(), "wb");
    assert(file);
    for (unsigned i = 0; i < gs.words; ++i)
        for (unsigned byte = 0; byte < 4; ++byte)
            assert(std::fputc((gs.code[i] >> (8 * byte)) & 255, file) != EOF);
    assert(!std::fclose(file));
}

int main(int argc, char **argv)
{
    assert(argc == 3);
    const std::string vertex = std::string(argv[1]) + "/sample.vert";
    const std::string geometry = std::string(argv[1]) + "/sample.geom";
    const std::string fragment = std::string(argv[1]) + "/sample.frag";
    const char *files[5] = {vertex.c_str(), nullptr, nullptr, geometry.c_str(), fragment.c_str()};
    void *program = opengpu_tess_frontend_compile(files, argv[2]);
    assert(program);
    assert(!opengpu_tess_frontend_tokens(program, 4));
    assert(!opengpu_tess_frontend_tokens(program, 5));
    const auto *vs = static_cast<const tgsi_token *>(opengpu_tess_frontend_tokens(program, 0));
    const auto *gs = static_cast<const tgsi_token *>(opengpu_tess_frontend_tokens(program, 3));
    const auto *fs = static_cast<const tgsi_token *>(opengpu_tess_frontend_tokens(program, 1));
    assert(inspect(vs).outputs == 0xffff);
    const auto geometry_io = inspect(gs);
    assert(geometry_io.inputs == 0xffff && geometry_io.outputs == 0xffff);
    assert(geometry_io.emits == 3 && geometry_io.ends == 1);
    assert(inspect(fs).inputs == 0xffff);

    opengpu_native_gs_program native_gs = {};
    const unsigned gs_words = tgsi_num_tokens(gs);
    assert(!opengpu_native_gs_emit(gs, gs_words, &native_gs));
    check_geometry(gs, gs_words, native_gs);

    const opengpu_native_fs_config config = {7, 128, 16, 4, 1, 1};
    opengpu_native_fs_program native = {};
    assert(!opengpu_native_fs_emit(fs, tgsi_num_tokens(fs), &config, &native));
    assert(native.metadata.abi_version == 7 && native.metadata.input_arrays == 77);
    assert(native.metadata.requires_geometry_owner && native.metadata.requires_sample_owner);
    assert(native.metadata.output_color_mask == 15 && native.metadata.writes_sample_mask == 1);
    const unsigned inputs = OPENGPU_NATIVE_FS_SAMPLE_ID | OPENGPU_NATIVE_FS_SAMPLE_MASK_IN |
                            OPENGPU_NATIVE_FS_VARYING | OPENGPU_NATIVE_FS_GEOMETRY;
    assert(native.metadata.used_sysvals == inputs);
    assert(native.words && native.words <= 512);
    const opengpu_native_fs_config no_geometry = {6, 128, 16, 4, 1, 0};
    opengpu_native_fs_program rejected = {};
    assert(opengpu_native_fs_emit(fs, tgsi_num_tokens(fs), &no_geometry, &rejected));
    assert(!rejected.words);
    write_geometry(argv[2], native_gs);
    opengpu_tess_frontend_destroy(program);

    files[3] = nullptr;
    assert(!opengpu_tess_frontend_compile(files, nullptr));
    files[1] = vertex.c_str();
    files[3] = geometry.c_str();
    assert(!opengpu_tess_frontend_compile(files, nullptr));
    std::printf("Actual linked ESSL320 VS/GS/FS CPU PASS with 16 varying slots, %u GS words and %u ABI7 FS words\n",
                native_gs.words, native.words);
}
