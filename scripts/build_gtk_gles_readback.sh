#!/usr/bin/env bash
# Build an isolated GDK 3.24.38 with native BGRA GLES readback on Debian ARM64.
# The installed GTK and Mesa libraries are not replaced.
set -euo pipefail
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "usage: $0 NEW_OUTPUT_DIRECTORY [GTK_SOURCE_ARCHIVE]" >&2
    exit 2
fi
[ "$(uname -s)" = Linux ] && [ "$(uname -m)" = aarch64 ] || {
    echo 'Run this builder inside the Debian ARM64 guest.' >&2; exit 1;
}
for tool in meson ninja python3 pkg-config cc sha256sum tar; do
    command -v "$tool" >/dev/null || { echo "missing build dependency: $tool" >&2; exit 1; }
done
[ ! -e "$1" ] || { echo 'Choose a fresh output directory to preserve evidence.' >&2; exit 1; }
mkdir -p "$1"
output="$(cd "$1" && pwd)"
source_url=https://download.gnome.org/sources/gtk+/3.24/gtk+-3.24.38.tar.xz
source_sha=ce11decf018b25bdd8505544a4f87242854ec88be054d9ade5f3a20444dd8ee7
archive="$output/gtk+-3.24.38.tar.xz"
if [ "$#" = 2 ]; then cp "$2" "$archive"; else curl -fL --retry 2 "$source_url" -o "$archive"; fi
printf '%s  %s\n' "$source_sha" "$archive" | sha256sum --check
mkdir "$output/source"
tar -xf "$archive" -C "$output/source"
source_dir="$output/source/gtk+-3.24.38"
python3 - "$source_dir/gdk/gdkgl.c" "$output/gles-readback.patch" <<'PY_PATCH'
from pathlib import Path
import difflib, sys
path = Path(sys.argv[1])
before = path.read_text()
anchor = "      gboolean es_read_bgra = FALSE;"
assert before.count(anchor) == 1, "Unexpected GDK source"
after = before.replace(anchor, anchor + '\n' + """
      /* Native BGRA readback matches Cairo's little-endian byte order. */
      es_read_bgra = G_BYTE_ORDER == G_LITTLE_ENDIAN &&
        epoxy_has_gl_extension ("GL_EXT_read_format_bgra");""")
path.write_text(after)
Path(sys.argv[2]).write_text(''.join(difflib.unified_diff(
    before.splitlines(True), after.splitlines(True),
    fromfile='a/gdk/gdkgl.c', tofile='b/gdk/gdkgl.c')))
PY_PATCH
meson setup "$output/build" "$source_dir" --buildtype=release \
    -Dintrospection=false -Dgtk_doc=false -Dman=false -Ddemos=false \
    -Dexamples=false -Dtests=false -Dprint_backends=file \
    -Dx11_backend=true -Dwayland_backend=true -Dbroadway_backend=true
ninja -j"${GDK_BUILD_JOBS:-2}" -C "$output/build" gdk/libgdk-3.so.0.2406.32
cp "$output/build/gdk/libgdk-3.so.0.2406.32" "$output/libgdk-3.so.0"
LD_LIBRARY_PATH="$output${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" python3 - <<'PY_ABI'
import ctypes, os
# Debian GTK also references the X11 and Broadway GDK entry points.
ctypes.CDLL('libgtk-3.so.0', mode=os.RTLD_NOW)
PY_ABI
python3 - "$output" "$source_url" "$source_sha" <<'PY_MANIFEST'
from pathlib import Path
import hashlib, json, platform, subprocess, sys
root = Path(sys.argv[1])
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
report = dict(source_url=sys.argv[2], archive_sha256=sys.argv[3],
    library_sha256=sha(root/'libgdk-3.so.0'), patch_sha256=sha(root/'gles-readback.patch'),
    patched_source_sha256=sha(root/'source/gtk+-3.24.38/gdk/gdkgl.c'),
    architecture=platform.machine(),
    dependencies=subprocess.check_output(['pkg-config', '--modversion', 'glib-2.0',
        'epoxy', 'wayland-client', 'gtk+-3.0'], text=True).splitlines(),
    scope='GDK GLES fallback uses native BGRA readback when supported; no shader/channel workaround or CPU conversion added.',
    reference='https://github.com/GNOME/gtk/blob/gtk-3-24/gdk/gdkgl.c')
(root/'gtk-gles-readback.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(report, indent=2))
PY_MANIFEST
