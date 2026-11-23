/* SPDX-License-Identifier: MIT */
#include "opengpu_native_fs_emit.h"
#include "opengpu_tess_frontend.h"
#include "tgsi/tgsi_parse.h"
#include <cassert>
#include <cstdio>
#include <string>

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
    opengpu_tess_frontend_destroy(program);

    files[3] = nullptr;
    assert(!opengpu_tess_frontend_compile(files, nullptr));
    files[1] = vertex.c_str();
    files[3] = geometry.c_str();
    assert(!opengpu_tess_frontend_compile(files, nullptr));
    std::printf("Actual linked ESSL320 VS/GS/FS CPU PASS with 16 varying slots and %u ABI7 FS words\n",
                native.words);
}
