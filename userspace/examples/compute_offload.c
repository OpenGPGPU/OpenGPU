/* SPDX-License-Identifier: MIT */
/* Load an OpenGPU fatbin and submit it through the compute ioctl.
 *
 * The reader copies the device .text into a shader GEM. Compute shader
 * bindings are 64-byte aligned, so the GEM is that size and the bytes after
 * cease stay zero. The packaged kernel is
 *   void kadd(int *p, int n) { *p = *p + n + 3; }
 * lowered so kernarg word 0 is the in/out value and word 1 is n.
 *
 * Usage: compute_offload [drm-node] [fatbin]
 * Defaults: /dev/dri/card0 and /opengpu_kadd.fatbin. */
#include "../opengpu.h"
#include "opengpu_offload.h"
#include "opengpu_shader_validator.h"

#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define SHADER_BYTES 64u
#define KERNARG_BYTES 64u

static uint8_t *read_file(const char *path, size_t *size)
{
    FILE *file = fopen(path, "rb");
    long length;
    uint8_t *buffer;

    if (file == NULL)
        return NULL;
    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        return NULL;
    }
    length = ftell(file);
    if (length <= 0) {
        fclose(file);
        errno = EINVAL;
        return NULL;
    }
    rewind(file);
    buffer = malloc((size_t)length);
    if (buffer == NULL) {
        fclose(file);
        return NULL;
    }
    if (fread(buffer, 1, (size_t)length, file) != (size_t)length) {
        free(buffer);
        fclose(file);
        return NULL;
    }
    fclose(file);
    *size = (size_t)length;
    return buffer;
}

int main(int argc, char **argv)
{
    const char *fatbin_path = argc > 2 ? argv[2] : "/opengpu_kadd.fatbin";
    struct opengpu_buffer shader = { 0 }, kernarg = { 0 };
    struct drm_opengpu_resource binding = { 0 };
    struct drm_opengpu_compute command = { 0 };
    struct opengpu_offload offload;
    uint32_t context = 0, fence = 0;
    uint64_t capabilities = 0;
    const char *step = "compute_offload";
    uint8_t *container = NULL;
    size_t container_size = 0, text_size = 0;
    int fd = -1, status = 1;
    uint32_t result;

    container = read_file(fatbin_path, &container_size);
    if (container == NULL) {
        perror(fatbin_path);
        return 1;
    }
    fd = opengpu_open(argc > 1 ? argv[1] : NULL);
    if (fd < 0)
        goto done;
    if (opengpu_capabilities(fd, &capabilities) ||
        opengpu_context_create(fd, &context) ||
        opengpu_buffer_create(fd, SHADER_BYTES, &shader) ||
        opengpu_buffer_create(fd, KERNARG_BYTES, &kernarg) ||
        opengpu_sync_create(fd, &fence))
        goto done;
    if (shader.size < SHADER_BYTES || kernarg.size < KERNARG_BYTES) {
        errno = EINVAL;
        goto done;
    }
    if (opengpu_offload_open(&offload, container, container_size) != 0)
        goto done;
    memset(shader.map, 0, SHADER_BYTES);
    if (opengpu_offload_shader(&offload, "opengpu", shader.map, SHADER_BYTES,
                               &text_size) != 0)
        goto done;
    if (text_size == 0 || text_size > SHADER_BYTES || (text_size & 3u) != 0) {
        errno = EINVAL;
        goto done;
    }
    /* The ioctl validates the whole 64-byte binding, which includes the
     * zero padding after cease. */
    if (!opengpu_compute_shader_validate_words_fpu(
            (const uint32_t *)shader.map, SHADER_BYTES / sizeof(uint32_t),
            KERNARG_BYTES, 1,
            (capabilities & OPENGPU_CAP_COMPUTE_SCALAR_FPU) != 0)) {
        fprintf(stderr, "device image rejected by the compute sandbox\n");
        errno = EINVAL;
        goto done;
    }

    ((uint32_t *)kernarg.map)[0] = 1;
    ((uint32_t *)kernarg.map)[1] = 2;
    binding.context_id = context;
    binding.slot = 1;
    binding.handle = shader.handle;
    binding.type = OPENGPU_RESOURCE_COMPUTE_SHADER;
    binding.size = SHADER_BYTES;
    if (opengpu_bind(fd, &binding))
        goto done;
    binding.slot = 2;
    binding.handle = kernarg.handle;
    binding.type = OPENGPU_RESOURCE_COMPUTE_KERNARG;
    binding.flags = OPENGPU_RESOURCE_UNCACHED;
    if (opengpu_bind(fd, &binding))
        goto done;
    command.context_id = context;
    command.shader_slot = 1;
    command.kernarg_slot = 2;
    command.grid[0] = command.grid[1] = command.grid[2] = 1;
    command.local[0] = command.local[1] = command.local[2] = 1;
    command.out_syncobj = fence;
    step = "compute submit";
    if (opengpu_compute(fd, &command))
        goto done;
    /* Verilator's vertex+fragment model does not finish a launch inside the
     * 30s budget used by the small fixed-function compute example. */
    step = "compute completion";
    if (opengpu_sync_wait_success(fd, fence, 300000))
        goto done;
    result = ((uint32_t *)kernarg.map)[0];
    if (result != 6) {
        fprintf(stderr, "kadd result %u, expected 6 (1 + 2 + 3)\n", result);
        errno = EIO;
        goto done;
    }
    puts("offload compute completed; kadd 1+2+3=6");
    status = 0;
done:
    if (status && errno)
        perror(step);
    if (fence)
        opengpu_sync_destroy(fd, fence);
    if (context)
        opengpu_context_destroy(fd, context);
    if (kernarg.handle)
        opengpu_buffer_destroy(fd, &kernarg);
    if (shader.handle)
        opengpu_buffer_destroy(fd, &shader);
    if (fd >= 0)
        close(fd);
    free(container);
    return status;
}
