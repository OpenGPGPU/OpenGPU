#!/usr/bin/env bash
# Build a private, small counter-enabled RTL/QEMU model and matching device tree.
set -euo pipefail
if [ "$#" -ne 2 ]; then
    echo "usage: $0 /path/to/qemu-build /path/to/arti-generated/embedded" >&2
    exit 2
fi
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
qemu_build="$(cd "$1" && pwd)"
wrapper="$(cd "$2" && pwd)"
arti_work="${ARTI_WORK:-$repo_dir/../arti-work}"
firmware="${QEMU_FW_DIR:-$arti_work/qemu-pc-bios}"
dtc="${DTC:-$arti_work/arti-linux-build/scripts/dtc/dtc}"
resources="${ARTI_COUNTER_RESOURCES:-0}"
[ "$resources" = "0" ] || [ "$resources" = "1" ] || {
    echo 'ARTI_COUNTER_RESOURCES must be 0 or 1' >&2; exit 2;
}
for file in "$qemu_build/build.ninja" "$wrapper/arti_rtl_model.cpp" \
            "$wrapper/arti_rtl_model.h" "$firmware/efi-virtio.rom" "$dtc"; do
    [ -f "$file" ] || { echo "missing prerequisite: $file" >&2; exit 1; }
done
for cmd in sbt verilator c++ ar ninja python3; do
    command -v "$cmd" >/dev/null || { echo "missing prerequisite: $cmd" >&2; exit 1; }
done
make_bin="${MAKE:-$(command -v gmake || command -v make)}"
artifact_root="${ARTI_COUNTER_ARTIFACTS:-$repo_dir/.artifacts/arti-counter-model}"
mkdir -p "$artifact_root"
artifact_root="$(cd "$artifact_root" && pwd)"
run_dir="$(mktemp -d "$artifact_root/counter.XXXXXX")"
printf 'Artifacts: %s\n' "$run_dir"
resource_option=""
[ "$resources" = "0" ] || resource_option="--compute-resources"
(cd "$repo_dir" && sbt -J-Xms512m -J-Xmx4G -J-XX:ActiveProcessorCount=2 \
    "runMain opengpu.elaboration.EmitGpuHostSystemAxi $run_dir/rtl --width 16 --height 16 --compute-units 1 --cu-lanes 4 --cu-warps 2 --memory-axi-data-bytes 8 --atomic-counters $resource_option")
(cd "$run_dir/rtl" && verilator --cc --no-timing --assert --threads 1 -Wno-fatal \
    --output-split 12000 --output-split-cfuncs 150 --Mdir "$run_dir/model" \
    --top-module GpuHostSystemAxi -f filelist.f)
"$make_bin" -C "$run_dir/model" -f VGpuHostSystemAxi.mk -j2 \
    CXX='c++ -std=gnu++17' OPT_FAST=-O1 OPT_SLOW=-O0 VGpuHostSystemAxi__ALL.a
verilator_root="$(verilator -V | sed -n 's/^[[:space:]]*VERILATOR_ROOT[[:space:]]*=[[:space:]]*//p' | head -1)"
[ -f "$verilator_root/include/verilated.cpp" ] || { echo 'Verilator headers not found' >&2; exit 1; }
cp "$wrapper/arti_rtl_model.cpp" "$wrapper/arti_rtl_model.h" "$run_dir/model/"
c++ -std=gnu++17 -O1 -fPIC -DARTI_MODEL_IDLE_GRACE=64 \
    -I"$run_dir/model" -I"$verilator_root/include" -I"$verilator_root/include/vltstd" \
    -c "$run_dir/model/arti_rtl_model.cpp" -o "$run_dir/model/arti_rtl_model.o"
for source in verilated verilated_threads; do
    c++ -std=gnu++17 -O1 -fPIC -I"$verilator_root/include" \
        -c "$verilator_root/include/$source.cpp" -o "$run_dir/model/$source.o"
done
cp "$run_dir/model/VGpuHostSystemAxi__ALL.a" "$run_dir/model/libarti_rtl_model.a"
ar rcs "$run_dir/model/libarti_rtl_model.a" "$run_dir/model/arti_rtl_model.o" \
    "$run_dir/model/verilated.o" "$run_dir/model/verilated_threads.o"
python3 "$repo_dir/scripts/link_arti_model.py" "$run_dir/model/libarti_rtl_model.a" \
    "$run_dir/qemu-system-aarch64" --qemu-build "$qemu_build"
"$run_dir/qemu-system-aarch64" -machine "virt,dumpdtb=$run_dir/original.dtb" \
    -accel tcg -cpu cortex-a53 -m 512M -L "$firmware" -display none -monitor none -serial none
python3 "$repo_dir/scripts/set_arti_guest_extent.py" "$run_dir/original.dtb" "$run_dir/guest.dtb" \
    --width 16 --height 16 --stride 64 --dtc "$dtc"
printf 'Isolated QEMU: %s/qemu-system-aarch64\nMatching DTB: %s/guest.dtb\n' "$run_dir" "$run_dir"
