/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_OFFLOAD_H
#define OPENGPU_OFFLOAD_H

#include <stddef.h>
#include <stdint.h>

/* Reader for the LLVM "offload binary" container produced by
 * clang-offload-packager / llvm-offload-binary. The container wraps one or
 * more device images together with the triple that identifies the target
 * device, so a host program can embed a single "fat" blob and pick the
 * OpenGPU image at run time (llvm/include/llvm/Object/OffloadBinary.h).
 *
 * This reader is byte-order and pointer-size independent for the fields it
 * exposes: the on-disk layout is a little-endian 64-bit serialization, and we
 * decode it explicitly rather than aliasing the buffer. */
#define OPENGPU_OFFLOAD_MAGIC 0xAD10FF10u

/* ImageKind values mirror llvm::object::ImageKind. */
enum opengpu_offload_image_kind {
    OPENGPU_OFFLOAD_IMG_NONE = 0,
    OPENGPU_OFFLOAD_IMG_OBJECT = 1,
    OPENGPU_OFFLOAD_IMG_BITCODE = 2,
    OPENGPU_OFFLOAD_IMG_CUBIN = 3,
    OPENGPU_OFFLOAD_IMG_FATBINARY = 4,
    OPENGPU_OFFLOAD_IMG_PTX = 5,
    OPENGPU_OFFLOAD_IMG_SPIRV = 6,
};

struct opengpu_offload_entry {
    uint16_t image_kind;
    uint16_t offload_kind;
    uint32_t flags;
    /* NUL-terminated views into the container. triple is never NULL; arch is
     * an empty string when the entry carries no architecture metadata. */
    const char *triple;
    const char *arch;
    const uint8_t *image;
    size_t image_size;
};

struct opengpu_offload {
    const uint8_t *base;
    size_t size;
    uint32_t version;
    uint64_t entries_offset;
    uint64_t entries_count;
};

/* Validate the container header and prepare a reader. Returns 0 on success and
 * -1 with errno set on failure. The buffer must stay alive while the reader,
 * and any view obtained from it, is used. */
int opengpu_offload_open(struct opengpu_offload *offload, const void *buffer,
                         size_t size);

uint64_t opengpu_offload_count(const struct opengpu_offload *offload);

/* Decode entry \p index. Returns 0 on success, -1 with errno set on failure. */
int opengpu_offload_entry(const struct opengpu_offload *offload, uint64_t index,
                          struct opengpu_offload_entry *entry);

/* Find the first entry whose triple matches \p triple. The comparison accepts
 * a full triple (opengpu-unknown-none-elf) against an abbreviated request:
 * either side may omit trailing components. Returns 0 on success, -1 with
 * errno set (ENOENT when no entry matches). */
int opengpu_offload_find(const struct opengpu_offload *offload,
                         const char *triple,
                         struct opengpu_offload_entry *entry);

/* Extract executable code from a device image. When \p image is an ELF32
 * little-endian object the ".text" section is returned; any other image is
 * treated as a raw machine-code blob and returned whole. Returns 0 on success
 * and -1 with errno set on failure. The returned pointer aliases \p image. */
int opengpu_offload_text(const uint8_t *image, size_t image_size,
                         const uint8_t **text, size_t *text_size);

/* Largest instruction stream the compute shader binding accepts
 * (OPENGPU_SHADER_MAX_INSTRUCTIONS words). */
#define OPENGPU_OFFLOAD_MAX_SHADER_BYTES (512u * 4u)

/* Copy the instruction stream for \p triple into \p shader. The copy is the
 * exact .text (or raw blob) the compute launch places at the start of its
 * shader GEM. The DRM binding itself is a separate 64-byte-aligned resource;
 * this function does not pad. Returns 0 on success. errno is EINVAL when the
 * image is empty, misaligned or longer than the shader limit, EMSGSIZE when
 * it does not fit in \p capacity, and the find/text errors otherwise. */
int opengpu_offload_shader(const struct opengpu_offload *offload,
                           const char *triple, void *shader, size_t capacity,
                           size_t *shader_size);

#endif /* OPENGPU_OFFLOAD_H */
