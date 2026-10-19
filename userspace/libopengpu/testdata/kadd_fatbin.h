/* SPDX-License-Identifier: MIT */
/* Synthetic fatbin for the clang-lowered kadd kernel.
 *
 * The device image is the machine code of
 *   __attribute__((device_kernel)) void kadd(int *p, int n) { *p = *p + n + 3; }
 * which the OpenGPU backend lowers to x1-relative kernarg accesses:
 *   lw a0, 0(ra); lw a1, 4(ra); add; addi 3; sw a0, 0(ra); opengpu.cease
 * Callers that need a file (the guest example) and the host tests share this
 * layout so the two cannot drift. */
#ifndef KADD_FATBIN_H
#define KADD_FATBIN_H

#include "opengpu_offload.h"

#include <stddef.h>
#include <stdint.h>
#include <string.h>

static const uint32_t kadd_words[] = {
    0x0000a503u, /* lw   a0, 0(ra)   */
    0x0040a583u, /* lw   a1, 4(ra)   */
    0x00a58533u, /* add  a0, a1, a0  */
    0x00350513u, /* addi a0, a0, 3   */
    0x00a0a023u, /* sw   a0, 0(ra)   */
    0x30500073u, /* opengpu.cease    */
};

#define KADD_WORD_COUNT (sizeof(kadd_words) / sizeof(kadd_words[0]))
#define KADD_TEXT_BYTES (KADD_WORD_COUNT * sizeof(uint32_t))

static void kadd_put_u16(uint8_t *p, uint16_t v)
{
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
}

static void kadd_put_u32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static void kadd_put_u64(uint8_t *p, uint64_t v)
{
    kadd_put_u32(p, (uint32_t)v);
    kadd_put_u32(p + 4, (uint32_t)(v >> 32));
}

static size_t kadd_align8(size_t n)
{
    return (n + 7u) & ~(size_t)7u;
}

/* Write a minimal ELF32 little-endian object whose .text is kadd_words.
 * \p elf must hold at least 256 bytes. Returns the object size, or 0. */
static size_t kadd_build_elf(uint8_t *elf, size_t elf_cap)
{
    size_t text_off = 52;
    size_t text_size = KADD_TEXT_BYTES;
    size_t shstr_off;
    const char shstr[] = "\0.text\0.shstrtab";
    size_t shstr_size = sizeof(shstr);
    size_t shoff;
    size_t total;

    if (elf == NULL || elf_cap < 256)
        return 0;
    shstr_off = text_off + text_size;
    shoff = kadd_align8(shstr_off + shstr_size);
    total = shoff + 3 * 40;
    if (total > elf_cap)
        return 0;

    memset(elf, 0, total);
    elf[0] = 0x7f;
    elf[1] = 'E';
    elf[2] = 'L';
    elf[3] = 'F';
    elf[4] = 1; /* ELFCLASS32 */
    elf[5] = 1; /* ELFDATA2LSB */
    elf[6] = 1; /* EV_CURRENT */
    kadd_put_u16(elf + 16, 1);   /* e_type = ET_REL */
    kadd_put_u16(elf + 18, 243); /* e_machine = EM_RISCV */
    kadd_put_u32(elf + 20, 1);   /* e_version */
    kadd_put_u32(elf + 32, (uint32_t)shoff);
    kadd_put_u16(elf + 40, 52);  /* e_ehsize */
    kadd_put_u16(elf + 46, 40);  /* e_shentsize */
    kadd_put_u16(elf + 48, 3);   /* e_shnum */
    kadd_put_u16(elf + 50, 2);   /* e_shstrndx */

    for (size_t i = 0; i < KADD_WORD_COUNT; i++)
        kadd_put_u32(elf + text_off + i * 4, kadd_words[i]);
    memcpy(elf + shstr_off, shstr, shstr_size);

    uint8_t *text = elf + shoff + 40;
    kadd_put_u32(text + 0, 1);                  /* sh_name -> ".text" */
    kadd_put_u32(text + 4, 1);                  /* SHT_PROGBITS */
    kadd_put_u32(text + 8, 6);                  /* SHF_ALLOC | SHF_EXECINSTR */
    kadd_put_u32(text + 16, (uint32_t)text_off);
    kadd_put_u32(text + 20, (uint32_t)text_size);
    kadd_put_u32(text + 32, 4);                 /* sh_addralign */

    uint8_t *shstrtab = elf + shoff + 80;
    kadd_put_u32(shstrtab + 0, 7);              /* sh_name -> ".shstrtab" */
    kadd_put_u32(shstrtab + 4, 3);              /* SHT_STRTAB */
    kadd_put_u32(shstrtab + 16, (uint32_t)shstr_off);
    kadd_put_u32(shstrtab + 20, (uint32_t)shstr_size);
    kadd_put_u32(shstrtab + 32, 1);
    return total;
}

/* Offload-binary v3 container:
 *   entry 0: triple riscv32-unknown-elf, raw 4-byte image
 *   entry 1: triple opengpu-unknown-none-elf, the ELF object above
 * \p fatbin must hold at least 1024 bytes. Returns the container size, or 0. */
static size_t kadd_build_fatbin(uint8_t *fatbin, size_t fatbin_cap)
{
    uint8_t elf[256];
    size_t elf_size = kadd_build_elf(elf, sizeof(elf));
    const uint8_t riscv_image[4] = { 0xef, 0xbe, 0xad, 0xde };
    const size_t entries_size = 2 * 40u;
    const size_t strings_size = 4 * 24u;
    size_t strtab_off = 40u + entries_size + strings_size;
    size_t cursor = 0;
    size_t key_off[4];
    size_t val_off[4];
    const char *keys[4] = { "triple", "arch", "triple", "arch" };
    const char *vals[4] = { "riscv32-unknown-elf", "riscv32",
                            "opengpu-unknown-none-elf", "opengpu" };
    size_t images_off, riscv_off, opengpu_off, total;

    if (fatbin == NULL || fatbin_cap < 1024 || elf_size == 0)
        return 0;
    for (size_t i = 0; i < 4; i++) {
        key_off[i] = cursor;
        cursor += strlen(keys[i]) + 1;
        val_off[i] = cursor;
        cursor += strlen(vals[i]) + 1;
    }
    images_off = kadd_align8(strtab_off + cursor);
    riscv_off = images_off;
    opengpu_off = images_off + sizeof(riscv_image);
    total = kadd_align8(opengpu_off + elf_size);
    if (total > fatbin_cap)
        return 0;

    memset(fatbin, 0, total);
    for (size_t i = 0; i < 4; i++) {
        memcpy(fatbin + strtab_off + key_off[i], keys[i], strlen(keys[i]) + 1);
        memcpy(fatbin + strtab_off + val_off[i], vals[i], strlen(vals[i]) + 1);
    }
    kadd_put_u32(fatbin + 0, OPENGPU_OFFLOAD_MAGIC);
    kadd_put_u32(fatbin + 4, 3);
    kadd_put_u64(fatbin + 8, total);
    kadd_put_u64(fatbin + 16, 40);
    kadd_put_u64(fatbin + 24, 2);
    kadd_put_u64(fatbin + 32, total);

    uint8_t *entry0 = fatbin + 40;
    uint8_t *entry1 = entry0 + 40;
    kadd_put_u16(entry0 + 0, 1);
    kadd_put_u64(entry0 + 8, 40 + entries_size);
    kadd_put_u64(entry0 + 16, 2);
    kadd_put_u64(entry0 + 24, riscv_off);
    kadd_put_u64(entry0 + 32, sizeof(riscv_image));
    kadd_put_u16(entry1 + 0, 1);
    kadd_put_u64(entry1 + 8, 40 + entries_size + 2 * 24);
    kadd_put_u64(entry1 + 16, 2);
    kadd_put_u64(entry1 + 24, opengpu_off);
    kadd_put_u64(entry1 + 32, elf_size);

    uint8_t *sentries = fatbin + 40 + entries_size;
    for (size_t i = 0; i < 4; i++) {
        kadd_put_u64(sentries + i * 24 + 0, strtab_off + key_off[i]);
        kadd_put_u64(sentries + i * 24 + 8, strtab_off + val_off[i]);
        kadd_put_u64(sentries + i * 24 + 16, strlen(vals[i]));
    }
    memcpy(fatbin + riscv_off, riscv_image, sizeof(riscv_image));
    memcpy(fatbin + opengpu_off, elf, elf_size);
    return total;
}

#endif /* KADD_FATBIN_H */
