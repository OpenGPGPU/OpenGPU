#!/usr/bin/env bash
# Install the pinned static x86-64 release without relying on Ubuntu mirrors.
set -euo pipefail
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "usage: $0 DESTINATION [DOWNLOADED_ARCHIVE]" >&2
  exit 2
fi
destination="$1"
version=4.14.2
archive_name="ccache-${version}-linux-x86_64-musl-static.tar.gz"
expected=d540ba01f160addbd3dd5e18eda9bd34005591b095c09ac8ae9d0a1b655e7ef5
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
archive="$tmp/$archive_name"
if [ "$#" -eq 2 ]; then
  cp "$2" "$archive"
else
  curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 10 --max-time 60 \
    "https://github.com/ccache/ccache/releases/download/v${version}/${archive_name}" -o "$archive"
fi
if command -v sha256sum >/dev/null; then
  actual=$(sha256sum "$archive")
else
  actual=$(shasum -a 256 "$archive")
fi
if [ "${actual%% *}" != "$expected" ]; then
  echo "ccache archive checksum mismatch" >&2
  exit 1
fi
tar -xOz -f "$archive" "ccache-${version}-linux-x86_64-musl-static/ccache" > "$tmp/ccache"
chmod +x "$tmp/ccache"
"$tmp/ccache" --version >/dev/null
mkdir -p "$destination"
cp "$tmp/ccache" "$destination/ccache"
