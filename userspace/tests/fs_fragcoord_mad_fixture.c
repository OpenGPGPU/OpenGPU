/* Emit a real TGSI position-Y shader for the optional CU execution check. */
#define main opengpu_fs_alu_lower_test_main
#include "fs_alu_lower.c"
#undef main

int main(int argc, char **argv)
{
    struct tgsi_token tokens[MAX_TOKENS];
    struct tgsi_header *header;
    struct opengpu_fs_alu generated;
    uint32_t code[OPENGPU_ABI_TARGET_WORDS];
    size_t count = 0;
    struct op_src colour = op_src(TGSI_FILE_CONSTANT, 0, TGSI_SWIZZLE_Z,
                                   TGSI_SWIZZLE_Z, TGSI_SWIZZLE_Z, TGSI_SWIZZLE_W, 0);
    struct op_src sources[3] = {
        op_src(TGSI_FILE_INPUT, 0, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0),
        op_src(TGSI_FILE_CONSTANT, 0, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0),
        op_src(TGSI_FILE_CONSTANT, 0, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0),
    };
    struct op_src product[2] = {
        op_src(TGSI_FILE_TEMPORARY, 0, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0),
        op_src(TGSI_FILE_CONSTANT, 1, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0),
    };
    unsigned opcode;
    FILE *out;

    if (argc != 3 || (strcmp(argv[1], "mad") && strcmp(argv[1], "fma")))
        return 2;
    opcode = !strcmp(argv[1], "mad") ? TGSI_OPCODE_MAD : TGSI_OPCODE_FMA;
    begin_fragment(tokens, &header);
    add_decl(tokens, header, TGSI_FILE_INPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    add_decl(tokens, header, TGSI_FILE_CONSTANT, 0, 1, -1);
    add_decl(tokens, header, TGSI_FILE_TEMPORARY, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0, TGSI_WRITEMASK_XYZW, 1, &colour);
    add_op(tokens, header, opcode, TGSI_FILE_TEMPORARY, 0, TGSI_WRITEMASK_Y, 3, sources);
    add_op(tokens, header, TGSI_OPCODE_MUL, TGSI_FILE_OUTPUT, 0, TGSI_WRITEMASK_X, 2, product);
    add_end(tokens, header);
    assert(!opengpu_fs_emit(tokens, &generated));
    assert(!opengpu_shader_lower_runtime_abi(generated.code, generated.words, false,
                                               code, OPENGPU_ABI_TARGET_WORDS, &count));
    out = fopen(argv[2], "wb"); assert(out);
    assert(fwrite(code, sizeof(*code), count, out) == count);
    assert(!fclose(out));
    printf("%s fragment fixture: %zu runtime words\n", argv[1], count);
    return 0;
}
