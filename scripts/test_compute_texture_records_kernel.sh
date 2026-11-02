#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 1 ] || [ ! -f "$1/Makefile" ]; then
  echo "usage: $0 /path/to/configured/linux-build" >&2
  exit 2
fi
root=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/opengpu-compute-texture.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/Makefile" <<'MAKE'
obj-m := compute_texture_records_probe.o
ccflags-y := -I$(OPENGPU_TEST_INCLUDE)
MAKE
cat > "$tmp/compute_texture_records_probe.c" <<'SOURCE'
#include <linux/module.h>
#include "opengpu_compute_texture_records.h"
#include "opengpu_compute_texture_mips.h"
#include "opengpu_compute_texture_sampler_validation.h"
#include "opengpu_compute_texture_snapshot.h"
#include "opengpu_compute_texture_tables.h"
#include "opengpu_compute_texture_bound_view.h"
static unsigned char arena[128 + 2304];
static struct opengpu_compute_texture_table_entry table_entry;
static struct opengpu_compute_texture_level_config bound_level;
static int __init probe_start(void)
{
    u32 words[OPENGPU_COMPUTE_TEXTURE_ROOT_WORDS];
    struct opengpu_compute_texture_root_config root = {
        .count = 1, .bindings_limit = 64, .images_limit = 128, .samplers_limit = 64
    };
    struct opengpu_compute_texture_packet_config packet = { .x = 1, .y = 1 };
    struct opengpu_compute_texture_sampler_config sampler = { .max_lod_bits = 0x41700000u };
    struct opengpu_compute_texture_binding_config binding = {0};
    struct opengpu_compute_texture_image_config image = {
        .width = 2, .height = 2, .depth = 1, .layers = 1,
        .allocation_limit = 64, .levels_limit = 128,
    };
    struct opengpu_compute_texture_level_config level = {
        .width = 2, .height = 2, .depth = 1, .layers = 1,
        .allocation_limit = 64, .row_pitch = 2, .block_bytes = 1,
    };
    u32 level_address = 0;
    struct opengpu_compute_texture_snapshot snapshot = {
        .root = &root, .binding = &binding, .image = &image, .sampler = &sampler,
        .levels = &level, .level_addresses = &level_address, .level_count = 1,
        .operation = OPENGPU_COMPUTE_TEXTURE_CAPTURED_FETCH,
    };
    return opengpu_compute_texture_bound_view(&table_entry, &bound_level, 1, &image,
                                               &sampler, &level, 1, 0, 64) &&
           opengpu_build_compute_texture_tables(arena, sizeof(arena), 0x10000, 0, 0, 0,
                                                 &table_entry, 1) &&
           opengpu_validate_compute_texture_snapshot(&snapshot) &&
           opengpu_encode_compute_texture_root(words, &root) &&
           opengpu_encode_compute_texture_packet(words, &packet) &&
           opengpu_encode_compute_texture_sampler(words, &sampler) &&
           opengpu_encode_compute_texture_image(words, &image) &&
           opengpu_encode_compute_texture_level(words, &level) &&
           opengpu_validate_compute_texture_levels(&image, &level, 1, 0, false) &&
           opengpu_validate_compute_texture_sampler(&binding, &image, &sampler, true, false) ? 0 : -EINVAL;
}
static void __exit probe_end(void) {}
module_init(probe_start);
module_exit(probe_end);
MODULE_LICENSE("MIT");
MODULE_DESCRIPTION("Compute texture record encoder build probe");
SOURCE
"${MAKE:-make}" -s -C "$1" M="$tmp" OPENGPU_TEST_INCLUDE="$root/driver" modules
printf 'Compute texture records kernel module build: PASS\n'
