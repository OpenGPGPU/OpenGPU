/* SPDX-License-Identifier: MIT */
/* Write the synthetic kadd fatbin used by examples/compute_offload.
 * Usage: emit_kadd_fatbin <path> */
#include "testdata/kadd_fatbin.h"

#include <stdio.h>

int main(int argc, char **argv)
{
    uint8_t fatbin[1024];
    size_t bytes;
    FILE *out;

    if (argc != 2) {
        fprintf(stderr, "usage: %s <fatbin>\n", argv[0]);
        return 2;
    }
    bytes = kadd_build_fatbin(fatbin, sizeof(fatbin));
    if (bytes == 0) {
        fprintf(stderr, "failed to build kadd fatbin\n");
        return 1;
    }
    out = fopen(argv[1], "wb");
    if (out == NULL) {
        perror(argv[1]);
        return 1;
    }
    if (fwrite(fatbin, 1, bytes, out) != bytes) {
        perror("fwrite");
        fclose(out);
        return 1;
    }
    if (fclose(out) != 0) {
        perror("fclose");
        return 1;
    }
    return 0;
}
