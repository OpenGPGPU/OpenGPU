/* Emit real TGSI shaders for the optional CU execution check. */
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
    int cancellation, separate, swizzle = -1, sequential, repeat, three_copies;
    FILE *out;

    if (argc != 3)
        return 2;
    sequential = !strcmp(argv[1], "sequential-swizzle");
    repeat = !strcmp(argv[1], "mad-swizzle-repeat") || !strcmp(argv[1], "fma-swizzle-repeat");
    three_copies = !strcmp(argv[1], "mad-swizzle-three") || !strcmp(argv[1], "fma-swizzle-three");
    if (repeat || three_copies) {
        swizzle = 0;
    } else if (!strncmp(argv[1], "mad-swizzle", 11) || !strncmp(argv[1], "fma-swizzle", 11)) {
        if (argv[1][11] < '0' || argv[1][11] > '2' || argv[1][12])
            return 2;
        swizzle = argv[1][11] - '0';
    } else if (sequential) {
        swizzle = 0;
    } else if (strcmp(argv[1], "mad") && strcmp(argv[1], "fma") &&
               strcmp(argv[1], "mad-cancel") && strcmp(argv[1], "fma-cancel") &&
               strcmp(argv[1], "separate-cancel")) {
        return 2;
    }
    cancellation = strstr(argv[1], "-cancel") != NULL;
    separate = !strcmp(argv[1], "separate-cancel");
    opcode = !strncmp(argv[1], "mad", 3) ? TGSI_OPCODE_MAD : TGSI_OPCODE_FMA;
    if (cancellation) {
        colour = op_src(TGSI_FILE_CONSTANT, 1, TGSI_SWIZZLE_Z, TGSI_SWIZZLE_Z,
                        TGSI_SWIZZLE_Z, TGSI_SWIZZLE_W, 0);
        sources[0] = op_src(TGSI_FILE_CONSTANT, 0, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X,
                            TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
        sources[1] = op_src(TGSI_FILE_CONSTANT, 0, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y,
                            TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0);
        sources[2] = op_src(TGSI_FILE_CONSTANT, 0, TGSI_SWIZZLE_Z, TGSI_SWIZZLE_Z,
                            TGSI_SWIZZLE_Z, TGSI_SWIZZLE_Z, 0);
    }
    begin_fragment(tokens, &header);
    add_decl(tokens, header, TGSI_FILE_INPUT, 0, 0, TGSI_SEMANTIC_POSITION);
    add_decl(tokens, header, TGSI_FILE_CONSTANT, 0, 1, -1);
    add_decl(tokens, header, TGSI_FILE_TEMPORARY, 0, 0, -1);
    add_decl(tokens, header, TGSI_FILE_OUTPUT, 0, 0, TGSI_SEMANTIC_COLOR);
    if (swizzle >= 0) {
        struct op_src init = op_src_id(TGSI_FILE_CONSTANT, 0);
        struct op_src result = op_src_id(TGSI_FILE_TEMPORARY, 0);
        struct op_src cross = three_copies ?
            op_src(TGSI_FILE_TEMPORARY, 0, TGSI_SWIZZLE_W, TGSI_SWIZZLE_X,
                   TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Z, 0) :
            op_src(TGSI_FILE_TEMPORARY, 0, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_X,
                   TGSI_SWIZZLE_Z, TGSI_SWIZZLE_W, 0);

        sources[0] = op_src(TGSI_FILE_CONSTANT, 1, TGSI_SWIZZLE_X, TGSI_SWIZZLE_X,
                            TGSI_SWIZZLE_X, TGSI_SWIZZLE_X, 0);
        sources[1] = sources[0];
        sources[2] = op_src(TGSI_FILE_CONSTANT, 1, TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y,
                            TGSI_SWIZZLE_Y, TGSI_SWIZZLE_Y, 0);
        if (swizzle == 2)
            sources[1] = sources[2];
        sources[swizzle] = cross;
        add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_TEMPORARY, 0,
               TGSI_WRITEMASK_XYZW, 1, &init);
        if (sequential) {
            add_op(tokens, header, opcode, TGSI_FILE_TEMPORARY, 0, TGSI_WRITEMASK_X, 3, sources);
            add_op(tokens, header, opcode, TGSI_FILE_TEMPORARY, 0, TGSI_WRITEMASK_Y, 3, sources);
        } else {
            add_op(tokens, header, opcode, TGSI_FILE_TEMPORARY, 0,
                   three_copies ? TGSI_WRITEMASK_XYZW : TGSI_WRITEMASK_X | TGSI_WRITEMASK_Y,
                   3, sources);
            if (repeat)
                add_op(tokens, header, opcode, TGSI_FILE_TEMPORARY, 0,
                       TGSI_WRITEMASK_X | TGSI_WRITEMASK_Y, 3, sources);
        }
        add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0,
               TGSI_WRITEMASK_XYZW, 1, &result);
    } else if (separate) {
        struct op_src addends[2] = { product[0], sources[2] };

        add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0, TGSI_WRITEMASK_XYZW, 1, &colour);
        add_op(tokens, header, TGSI_OPCODE_MUL, TGSI_FILE_TEMPORARY, 0,
               TGSI_WRITEMASK_Y, 2, sources);
        add_op(tokens, header, TGSI_OPCODE_ADD, TGSI_FILE_TEMPORARY, 0,
               TGSI_WRITEMASK_Y, 2, addends);
    } else {
        add_op(tokens, header, TGSI_OPCODE_MOV, TGSI_FILE_OUTPUT, 0, TGSI_WRITEMASK_XYZW, 1, &colour);
        add_op(tokens, header, opcode, TGSI_FILE_TEMPORARY, 0, TGSI_WRITEMASK_Y, 3, sources);
    }
    if (swizzle < 0)
        add_op(tokens, header, TGSI_OPCODE_MUL, TGSI_FILE_OUTPUT, 0, TGSI_WRITEMASK_X, 2, product);
    add_end(tokens, header);
    assert(!opengpu_fs_emit(tokens, &generated));
    assert(!opengpu_shader_lower_runtime_abi(generated.code, generated.words, false,
                                               code, OPENGPU_ABI_TARGET_WORDS, &count));
    assert(!!has_vfmacc(code, (unsigned)count) == !separate);
    out = fopen(argv[2], "wb"); assert(out);
    assert(fwrite(code, sizeof(*code), count, out) == count);
    assert(!fclose(out));
    printf("%s fragment fixture: %zu runtime words\n", argv[1], count);
    return 0;
}
