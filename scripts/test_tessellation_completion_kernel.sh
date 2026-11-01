#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 1 ] || [ ! -f "$1/Makefile" ]; then
  echo "usage: $0 /path/to/configured/linux-headers" >&2
  exit 2
fi
root=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/opengpu-gnz1-kernel.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/Makefile" <<'MAKE'
obj-m := gnz1.o
ccflags-y := -I$(OPENGPU_TEST_INCLUDE)
MAKE
cat > "$tmp/gnz1.c" <<'SOURCE'
#include <linux/module.h>
#include "opengpu_tessellation_completion.h"
static int __init gnz1_start(void)
{
    struct opengpu_tessellation_completion out;
    u32 words[12] = {0};
    return opengpu_tessellation_completion_decode(words, 12, &out);
}
static void __exit gnz1_end(void) {}
module_init(gnz1_start);
module_exit(gnz1_end);
MODULE_LICENSE("MIT");
SOURCE
make -s -C "$1" M="$tmp" OPENGPU_TEST_INCLUDE="$root/driver" modules
printf 'GNZ1 kernel module build: PASS\n'
