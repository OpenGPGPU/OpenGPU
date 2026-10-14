#!/bin/sh
set -eu
for tool in gcc pkg-config; do
    command -v "$tool" >/dev/null || {
        echo "GTK smoke build requires $tool; use the prepared GTK Debian image" >&2
        exit 2
    }
done
pkg-config --exists gtk+-3.0 epoxy || {
    echo 'GTK3/Epoxy development packages are required' >&2
    exit 2
}
gcc -O2 -Wall -Wextra -Werror -o /root/opengpu_gtk_smoke.bin \
    /root/gtk_opengpu_smoke.c $(pkg-config --cflags --libs gtk+-3.0 epoxy)
echo 'Built /root/opengpu_gtk_smoke.bin'
