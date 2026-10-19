/* SPDX-License-Identifier: MIT */
#define _POSIX_C_SOURCE 200809L
#include "opengpu_offload.h"

#include <errno.h>
#include <string.h>

/* Offload binary version 3 layout, little-endian, 64-bit fields. All offsets
 * are absolute byte offsets from the start of the container. */
#define OPENGPU_OFFLOAD_VERSION 3u
#define OPENGPU_OFFLOAD_HEADER_SIZE 40u
#define OPENGPU_OFFLOAD_ENTRY_SIZE 40u
#define OPENGPU_OFFLOAD_STRING_ENTRY_SIZE 24u

static uint16_t load_u16(const uint8_t *p)
{
    return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}

static uint32_t load_u32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
           ((uint32_t)p[3] << 24);
}

static uint64_t load_u64(const uint8_t *p)
{
    return (uint64_t)load_u32(p) | ((uint64_t)load_u32(p + 4) << 32);
}

static const char *string_at(const struct opengpu_offload *offload, uint64_t offset)
{
    if (offset >= offload->size)
        return NULL;
    const char *value = (const char *)(offload->base + offset);
    if (memchr(value, '\0', offload->size - (size_t)offset) == NULL)
        return NULL;
    return value;
}

int opengpu_offload_open(struct opengpu_offload *offload, const void *buffer,
                         size_t size)
{
    if (offload == NULL || buffer == NULL) {
        errno = EINVAL;
        return -1;
    }
    const uint8_t *base = buffer;
    if (size < OPENGPU_OFFLOAD_HEADER_SIZE ||
        load_u32(base) != OPENGPU_OFFLOAD_MAGIC) {
        errno = EINVAL;
        return -1;
    }
    uint32_t version = load_u32(base + 4);
    if (version != OPENGPU_OFFLOAD_VERSION) {
        errno = ENOTSUP;
        return -1;
    }
    uint64_t total = load_u64(base + 8);
    uint64_t entries_offset = load_u64(base + 16);
    uint64_t entries_count = load_u64(base + 24);
    if (total != size || entries_offset != OPENGPU_OFFLOAD_HEADER_SIZE ||
        entries_count == 0) {
        errno = EINVAL;
        return -1;
    }
    /* Guard the entry table against overflow and truncation. */
    if (entries_count > (size - entries_offset) / OPENGPU_OFFLOAD_ENTRY_SIZE) {
        errno = EINVAL;
        return -1;
    }
    offload->base = base;
    offload->size = size;
    offload->version = version;
    offload->entries_offset = entries_offset;
    offload->entries_count = entries_count;
    return 0;
}

uint64_t opengpu_offload_count(const struct opengpu_offload *offload)
{
    return offload->entries_count;
}

int opengpu_offload_entry(const struct opengpu_offload *offload, uint64_t index,
                          struct opengpu_offload_entry *entry)
{
    if (offload == NULL || entry == NULL || index >= offload->entries_count) {
        errno = EINVAL;
        return -1;
    }
    const uint8_t *base = offload->base;
    const uint8_t *e =
        base + offload->entries_offset + index * OPENGPU_OFFLOAD_ENTRY_SIZE;
    uint64_t strings_offset = load_u64(e + 8);
    uint64_t string_count = load_u64(e + 16);
    uint64_t image_offset = load_u64(e + 24);
    uint64_t image_size = load_u64(e + 32);
    if (image_size > offload->size || image_offset > offload->size - image_size) {
        errno = EINVAL;
        return -1;
    }
    /* Check the offset before subtracting it from the container size. */
    if (strings_offset > offload->size ||
        string_count > (offload->size - (size_t)strings_offset) /
                           OPENGPU_OFFLOAD_STRING_ENTRY_SIZE) {
        errno = EINVAL;
        return -1;
    }
    entry->image_kind = load_u16(e + 0);
    entry->offload_kind = load_u16(e + 2);
    entry->flags = load_u32(e + 4);
    entry->triple = "";
    entry->arch = "";
    entry->image = base + image_offset;
    entry->image_size = (size_t)image_size;
    for (uint64_t i = 0; i < string_count; i++) {
        const uint8_t *s =
            base + strings_offset + i * OPENGPU_OFFLOAD_STRING_ENTRY_SIZE;
        const char *key = string_at(offload, load_u64(s + 0));
        const char *value = string_at(offload, load_u64(s + 8));
        if (key == NULL || value == NULL) {
            errno = EINVAL;
            return -1;
        }
        if (strcmp(key, "triple") == 0)
            entry->triple = value;
        else if (strcmp(key, "arch") == 0)
            entry->arch = value;
    }
    return 0;
}

static int triple_matches(const char *entry, const char *request)
{
    size_t entry_len = strlen(entry);
    size_t request_len = strlen(request);
    size_t common = entry_len < request_len ? entry_len : request_len;
    if (memcmp(entry, request, common) != 0)
        return 0;
    /* Accept an exact match or an abbreviation at a component boundary. */
    if (entry_len == request_len)
        return 1;
    return entry[common] == '-' || request[common] == '-';
}

int opengpu_offload_find(const struct opengpu_offload *offload,
                         const char *triple,
                         struct opengpu_offload_entry *entry)
{
    if (offload == NULL || triple == NULL || entry == NULL) {
        errno = EINVAL;
        return -1;
    }
    for (uint64_t i = 0; i < offload->entries_count; i++) {
        struct opengpu_offload_entry candidate;
        if (opengpu_offload_entry(offload, i, &candidate) != 0)
            return -1;
        if (triple_matches(candidate.triple, triple)) {
            *entry = candidate;
            return 0;
        }
    }
    errno = ENOENT;
    return -1;
}

int opengpu_offload_text(const uint8_t *image, size_t image_size,
                         const uint8_t **text, size_t *text_size)
{
    if (image == NULL || text == NULL || text_size == NULL) {
        errno = EINVAL;
        return -1;
    }
    /* Non-ELF images are treated as pre-linked raw machine code. */
    if (image_size < 4 || image[0] != 0x7f || image[1] != 'E' ||
        image[2] != 'L' || image[3] != 'F') {
        *text = image;
        *text_size = image_size;
        return 0;
    }
    if (image_size < 52 || image[4] != 1 /* ELFCLASS32 */ ||
        image[5] != 1 /* ELFDATA2LSB */) {
        errno = ENOTSUP;
        return -1;
    }
    uint32_t shoff = load_u32(image + 32);
    uint16_t shentsize = load_u16(image + 46);
    uint16_t shnum = load_u16(image + 48);
    uint16_t shstrndx = load_u16(image + 50);
    if (shnum == 0 || shentsize < 40 || shstrndx >= shnum ||
        (uint64_t)shoff + (uint64_t)shnum * shentsize > image_size) {
        errno = EINVAL;
        return -1;
    }
    const uint8_t *strtab = NULL;
    uint32_t strtab_size = 0;
    const uint8_t *shstr = image + shoff + (uint64_t)shstrndx * shentsize;
    uint32_t strtab_off = load_u32(shstr + 16);
    uint32_t strtab_sz = load_u32(shstr + 20);
    if ((uint64_t)strtab_off + strtab_sz > image_size) {
        errno = EINVAL;
        return -1;
    }
    strtab = image + strtab_off;
    strtab_size = strtab_sz;
    for (uint16_t i = 0; i < shnum; i++) {
        const uint8_t *sh = image + shoff + (uint64_t)i * shentsize;
        uint32_t name = load_u32(sh + 0);
        if (name >= strtab_size)
            continue;
        const char *section_name = (const char *)strtab + name;
        if (memchr(section_name, '\0', strtab_size - name) == NULL)
            continue;
        if (strcmp(section_name, ".text") != 0)
            continue;
        uint32_t offset = load_u32(sh + 16);
        uint32_t size = load_u32(sh + 20);
        if ((uint64_t)offset + size > image_size) {
            errno = EINVAL;
            return -1;
        }
        *text = image + offset;
        *text_size = size;
        return 0;
    }
    errno = ENOENT;
    return -1;
}

int opengpu_offload_shader(const struct opengpu_offload *offload,
                           const char *triple, void *shader, size_t capacity,
                           size_t *shader_size)
{
    struct opengpu_offload_entry entry;
    const uint8_t *text = NULL;
    size_t text_size = 0;

    if (shader == NULL || shader_size == NULL) {
        errno = EINVAL;
        return -1;
    }
    if (opengpu_offload_find(offload, triple, &entry) != 0)
        return -1;
    if (opengpu_offload_text(entry.image, entry.image_size, &text,
                             &text_size) != 0)
        return -1;
    if (text_size == 0 || (text_size & 3u) != 0 ||
        text_size > OPENGPU_OFFLOAD_MAX_SHADER_BYTES) {
        errno = EINVAL;
        return -1;
    }
    if (text_size > capacity) {
        errno = EMSGSIZE;
        return -1;
    }
    memcpy(shader, text, text_size);
    *shader_size = text_size;
    return 0;
}
