/* SPDX-License-Identifier: MIT */
#include "pipe_opengpu.h"
#include <assert.h>
#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    const char *path = argc > 1 ? argv[1] : "/dev/dri/card0";
    struct pipe_opengpu_screen *a = pipe_opengpu_screen_create(path);
    struct pipe_opengpu_screen *b = pipe_opengpu_screen_create(path);
    struct pipe_opengpu_resource *original, *alias, *remote;
    uint32_t pitch;
    int fd, check_fd;

    assert(a && b);
    original = pipe_opengpu_resource_create_2d(a, 32, 24);
    assert(original);
    pitch = pipe_opengpu_resource_pitch(original);
    ((uint32_t *)pipe_opengpu_resource_map(original))[0] = 0x12345678;
    fd = pipe_opengpu_resource_export_fd(a, original);
    assert(fd >= 0 && (fcntl(fd, F_GETFD) & FD_CLOEXEC));
    alias = pipe_opengpu_resource_from_handle(a, 32, 24, pitch, fd, 1);
    remote = pipe_opengpu_resource_from_handle(b, 32, 24, pitch, fd, 1);
    assert(alias && remote);
    assert(!pipe_opengpu_resource_from_handle(b, 32, 24, 4, fd, 1));
    assert(!pipe_opengpu_resource_from_handle(b, 32, 4096, pitch, fd, 1));
    close(fd);
    assert(((uint32_t *)pipe_opengpu_resource_map(remote))[0] == 0x12345678);
    assert(pipe_opengpu_resource_handle(alias) == pipe_opengpu_resource_handle(original));
    pipe_opengpu_resource_destroy(a, original);
    check_fd = pipe_opengpu_resource_export_fd(a, alias);
    assert(check_fd >= 0);
    close(check_fd);
    ((uint32_t *)pipe_opengpu_resource_map(alias))[0] = 0x87654321;
    assert(((uint32_t *)pipe_opengpu_resource_map(remote))[0] == 0x87654321);
    pipe_opengpu_resource_destroy(a, alias);
    check_fd = pipe_opengpu_resource_export_fd(b, remote);
    assert(check_fd >= 0);
    close(check_fd);
    pipe_opengpu_resource_destroy(b, remote);
    pipe_opengpu_screen_destroy(b);
    pipe_opengpu_screen_destroy(a);
    puts("OPENGPU DMA-BUF PASS: sharing, bounds, and handle lifetime");
    return 0;
}
