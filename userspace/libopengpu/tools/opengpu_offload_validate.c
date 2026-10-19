/* SPDX-License-Identifier: MIT */
/* Extract an OpenGPU device image from a clang-offload-packager container and
 * run it through the compute shader sandbox. This is the software half of the
 * M6 "load and dispatch on the opengpu triple" path: it exercises the same
 * reader and validator the runtime uses, without needing GPU hardware.
 *
 * Usage: opengpu_offload_validate <container> [triple]
 * Prints one line per entry and "VALID"/"REJECTED" for the requested triple. */
#include "opengpu_offload.h"
#include "opengpu_shader_validator.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv)
{
    if (argc < 2 || argc > 3) {
        fprintf(stderr, "usage: %s <container> [triple]\n", argv[0]);
        return 2;
    }
    const char *triple = argc == 3 ? argv[2] : "opengpu";

    FILE *file = fopen(argv[1], "rb");
    if (file == NULL) {
        perror(argv[1]);
        return 2;
    }
    if (fseek(file, 0, SEEK_END) != 0) {
        perror("fseek");
        fclose(file);
        return 2;
    }
    long length = ftell(file);
    if (length <= 0) {
        fprintf(stderr, "%s: empty container\n", argv[1]);
        fclose(file);
        return 2;
    }
    rewind(file);
    uint8_t *buffer = malloc((size_t)length);
    if (buffer == NULL) {
        perror("malloc");
        fclose(file);
        return 2;
    }
    if (fread(buffer, 1, (size_t)length, file) != (size_t)length) {
        perror("fread");
        free(buffer);
        fclose(file);
        return 2;
    }
    fclose(file);

    struct opengpu_offload offload;
    if (opengpu_offload_open(&offload, buffer, (size_t)length) != 0) {
        perror("opengpu_offload_open");
        free(buffer);
        return 1;
    }
    printf("entries: %llu\n",
           (unsigned long long)opengpu_offload_count(&offload));
    for (uint64_t i = 0; i < opengpu_offload_count(&offload); i++) {
        struct opengpu_offload_entry entry;
        if (opengpu_offload_entry(&offload, i, &entry) != 0) {
            perror("opengpu_offload_entry");
            free(buffer);
            return 1;
        }
        printf("  [%llu] triple=%s arch=%s kind=%u image=%zu bytes\n",
               (unsigned long long)i, entry.triple,
               entry.arch[0] ? entry.arch : "-", entry.image_kind,
               entry.image_size);
    }

    struct opengpu_offload_entry image;
    if (opengpu_offload_find(&offload, triple, &image) != 0) {
        fprintf(stderr, "no image for triple '%s'\n", triple);
        free(buffer);
        return 1;
    }
    const uint8_t *text = NULL;
    size_t text_size = 0;
    if (opengpu_offload_text(image.image, image.image_size, &text, &text_size) != 0) {
        perror("opengpu_offload_text");
        free(buffer);
        return 1;
    }
    if (text_size == 0 || text_size % 4 != 0 ||
        text_size / 4 > OPENGPU_SHADER_MAX_INSTRUCTIONS) {
        fprintf(stderr, "device code is not a valid instruction stream\n");
        free(buffer);
        return 1;
    }
    opengpu_shader_u32 words[OPENGPU_SHADER_MAX_INSTRUCTIONS];
    memcpy(words, text, text_size);
    bool valid = opengpu_compute_shader_validate_words(
        words, (opengpu_shader_u32)(text_size / 4), 8, 1);
    printf("device code: %zu bytes -> %s\n", text_size,
           valid ? "VALID" : "REJECTED");
    free(buffer);
    return valid ? 0 : 1;
}
