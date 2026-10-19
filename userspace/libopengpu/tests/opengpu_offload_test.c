/* SPDX-License-Identifier: MIT */
/* Host tests for the OpenGPU device-fatbin reader. These do not touch
 * hardware: they build synthetic containers matching the LLVM offload-binary
 * version 3 layout and check that the reader decodes them, then extract the
 * OpenGPU image into the 64-byte shader binding the compute ioctl validates. */
#include "opengpu_offload.h"
#include "opengpu_shader_validator.h"
#include "testdata/kadd_fatbin.h"

#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static void test_container_reader(void)
{
    uint8_t elf[256];
    uint8_t fatbin[1024];
    size_t elf_size = kadd_build_elf(elf, sizeof(elf));
    size_t fatbin_size = kadd_build_fatbin(fatbin, sizeof(fatbin));
    struct opengpu_offload offload;
    struct opengpu_offload_entry entry, abbrev, riscv;

    assert(elf_size != 0 && fatbin_size != 0);
    assert(opengpu_offload_open(&offload, fatbin, fatbin_size) == 0);
    assert(opengpu_offload_count(&offload) == 2);

    assert(opengpu_offload_find(&offload, "opengpu-unknown-none-elf",
                                &entry) == 0);
    assert(strcmp(entry.triple, "opengpu-unknown-none-elf") == 0);
    assert(strcmp(entry.arch, "opengpu") == 0);
    assert(entry.image_size == elf_size);

    assert(opengpu_offload_find(&offload, "opengpu", &abbrev) == 0);
    assert(abbrev.image == entry.image);

    assert(opengpu_offload_find(&offload, "riscv32-unknown-elf", &riscv) == 0);
    assert(riscv.image_size == 4);

    errno = 0;
    assert(opengpu_offload_find(&offload, "x86_64-unknown-linux-gnu",
                                &entry) == -1);
    assert(errno == ENOENT);
    printf("offload container reader: PASS\n");
}

static void test_elf_text_and_validation(void)
{
    uint8_t elf[256];
    size_t elf_size = kadd_build_elf(elf, sizeof(elf));
    const uint8_t *text = NULL;
    size_t text_size = 0;
    const uint8_t raw[8] = { 1, 2, 3, 4, 5, 6, 7, 8 };
    const uint8_t *raw_text = NULL;
    size_t raw_size = 0;
    opengpu_shader_u32 words[KADD_WORD_COUNT];

    assert(elf_size != 0);
    assert(opengpu_offload_text(elf, elf_size, &text, &text_size) == 0);
    assert(text_size == KADD_TEXT_BYTES);
    assert(memcmp(text, kadd_words, text_size) == 0);

    assert(opengpu_offload_text(raw, sizeof(raw), &raw_text, &raw_size) == 0);
    assert(raw_text == raw && raw_size == sizeof(raw));

    memcpy(words, text, text_size);
    assert(opengpu_compute_shader_validate_words(
        words, (opengpu_shader_u32)(text_size / 4), 8, 1));
    printf("ELF text extraction + sandbox: PASS\n");
}

static void test_submit_image(void)
{
    uint8_t fatbin[1024];
    uint32_t gem_words[16];
    uint8_t *gem = (uint8_t *)gem_words;
    uint8_t tiny[8];
    size_t fatbin_size = kadd_build_fatbin(fatbin, sizeof(fatbin));
    size_t text_size = 0;
    struct opengpu_offload offload;

    assert(fatbin_size != 0);
    assert(opengpu_offload_open(&offload, fatbin, fatbin_size) == 0);
    memset(gem, 0xa5, sizeof(gem_words));
    assert(opengpu_offload_shader(&offload, "opengpu", gem, sizeof(gem_words),
                                  &text_size) == 0);
    assert(text_size == KADD_TEXT_BYTES);
    assert(memcmp(gem, kadd_words, text_size) == 0);
    /* Bytes past the copied stream stay untouched. The launch zeroes them. */
    assert(gem[text_size] == 0xa5);

    memset(gem, 0, sizeof(gem_words));
    assert(opengpu_offload_shader(&offload, "opengpu", gem, sizeof(gem_words),
                                  &text_size) == 0);
    assert(opengpu_compute_shader_validate_words(
        gem_words, (opengpu_shader_u32)(sizeof(gem_words) / sizeof(gem_words[0])),
        64, 1));

    errno = 0;
    assert(opengpu_offload_shader(&offload, "opengpu", tiny, sizeof(tiny),
                                  &text_size) == -1);
    assert(errno == EMSGSIZE);

    /* A raw image that is not a whole number of instructions is rejected. */
    kadd_put_u64(fatbin + 40 + 32, 2);
    errno = 0;
    assert(opengpu_offload_shader(&offload, "riscv32", gem, sizeof(gem_words),
                                  &text_size) == -1);
    assert(errno == EINVAL);
    printf("shader image for compute submit: PASS\n");
}

static void test_rejects_malformed(void)
{
    struct opengpu_offload offload;
    struct opengpu_offload_entry entry;
    uint8_t junk[64];
    uint8_t fatbin[1024];
    size_t fatbin_size;

    memset(junk, 0, sizeof(junk));
    errno = 0;
    assert(opengpu_offload_open(&offload, junk, sizeof(junk)) == -1);
    assert(errno == EINVAL);

    kadd_put_u32(junk, OPENGPU_OFFLOAD_MAGIC);
    kadd_put_u32(junk + 4, 2);
    errno = 0;
    assert(opengpu_offload_open(&offload, junk, sizeof(junk)) == -1);
    assert(errno == ENOTSUP);

    fatbin_size = kadd_build_fatbin(fatbin, sizeof(fatbin));
    assert(fatbin_size != 0);
    /* Entry 1 string table sits past the container. The offset is checked
     * before it is subtracted from the container size. */
    kadd_put_u64(fatbin + 80 + 8, (uint64_t)fatbin_size + 16);
    assert(opengpu_offload_open(&offload, fatbin, fatbin_size) == 0);
    errno = 0;
    assert(opengpu_offload_entry(&offload, 1, &entry) == -1);
    assert(errno == EINVAL);
    errno = 0;
    assert(opengpu_offload_find(&offload, "opengpu", &entry) == -1);
    assert(errno == EINVAL);
    printf("malformed container rejection: PASS\n");
}

int main(void)
{
    test_container_reader();
    test_elf_text_and_validation();
    test_submit_image();
    test_rejects_malformed();
    printf("opengpu offload: all tests passed\n");
    return 0;
}
