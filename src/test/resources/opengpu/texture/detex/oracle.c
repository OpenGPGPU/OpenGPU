/* Test adapter for the unmodified detex decoder. */
#include "detex.h"
#include <stdio.h>
#include <string.h>

int main(void) {
    unsigned codec, sign;
    char hex[33];
    while (scanf("%u %u %32s", &codec, &sign, hex) == 3) {
        uint8_t input[16], output[128] = {0};
        for (unsigned n = 0; n < 16; n++) {
            unsigned byte;
            if (sscanf(hex + 2*n, "%2x", &byte) != 1) return 2;
            input[n] = byte;
        }
        /* GLES3 C.1.7 requires -128 to behave as -127. Upstream rejects -128. */
        if (sign && codec >= 4) {
            if (input[0] == 128) input[0] = 129;
            if (codec == 5 && input[8] == 128) input[8] = 129;
        }
        bool ok = false;
        unsigned mode = 0;
        if (codec == 1) { ok = detexDecompressBlockETC2(input, ~0u, 0, output); mode = detexGetModeETC2(input); }
        if (codec == 2) { ok = detexDecompressBlockETC2_PUNCHTHROUGH(input, ~0u, 0, output); mode = detexGetModeETC2_PUNCHTHROUGH(input); }
        if (codec == 3) { ok = detexDecompressBlockETC2_EAC(input, ~0u, 0, output); mode = detexGetModeETC2(input+8); }
        if (codec == 4) ok = sign ? detexDecompressBlockEAC_SIGNED_R11(input, ~0u, 0, output) : detexDecompressBlockEAC_R11(input, ~0u, 0, output);
        if (codec == 5) ok = sign ? detexDecompressBlockEAC_SIGNED_RG11(input, ~0u, 0, output) : detexDecompressBlockEAC_RG11(input, ~0u, 0, output);
        if (!ok) return 3;
        printf("%u", mode);
        for (unsigned pixel = 0; pixel < 16; pixel++) {
            for (unsigned c = 0; c < 4; c++) {
                int value = 0;
                if (codec < 4) value = output[pixel*4+c];
                else if (c < (codec == 4 ? 1u : 2u)) {
                    unsigned slot = pixel*(codec == 4 ? 1 : 2) + c;
                    if (sign) {
                        int v = ((int16_t *)output)[slot];
                        value = v >= 0 ? v >> 5 : -((-v) >> 5);
                    } else value = ((uint16_t *)output)[slot] >> 5;
                }
                printf(" %d", value);
            }
        }
        putchar('\n');
    }
    return ferror(stdin) ? 4 : 0;
}
