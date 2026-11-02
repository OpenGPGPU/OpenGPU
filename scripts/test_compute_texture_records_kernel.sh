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
static int __init probe_start(void)
{
    u32 words[OPENGPU_COMPUTE_TEXTURE_ROOT_WORDS];
    struct opengpu_compute_texture_root_config root = {
        .count = 1, .bindings_limit = 64, .images_limit = 128, .samplers_limit = 64
    };
    return opengpu_encode_compute_texture_root(words, &root) ? 0 : -EINVAL;
}
static void __exit probe_end(void) {}
module_init(probe_start);
module_exit(probe_end);
MODULE_LICENSE("MIT");
MODULE_DESCRIPTION("Compute texture record encoder build probe");
SOURCE
"${MAKE:-make}" -s -C "$1" M="$tmp" OPENGPU_TEST_INCLUDE="$root/driver" modules
printf 'Compute texture records kernel module build: PASS\n'
